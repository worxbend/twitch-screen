# Kimi review, round 3: relay-twitch remediation evidence

Review under remediation: `REVIEW_SUMMARY.kimi.md`. This file records per-unit evidence. The integration step owns `tasks/review-kimi-summary.md` and `tasks/todo.md`.

## K-011: Non-atomic compound read of TwitchAuth state (current, accessToken, scopes)

- Severity: Medium-bug. Area: relay-twitch.
- **Disposition: fixed.** It closes the `[partial]` gap: the two compound reads that were left.

### Change

- `twitch-screen-relay/src/twitchscreen/relay/twitch/TwitchAuth.scala`
  - Deleted the `handle` OAuth2Credential. Its getters each called `view` or `current` on their own, so `getAccessToken` and `getUserId`/`getScopes` could come from different grants.
  - Deleted the public `credential`, `current`, `accessToken` and no-arg `missingScopes`. Only tests used them.
  - Removed the imports that became unused: `OAuth2Credential`, `TwitchIdentityProvider` and `scala.jdk.CollectionConverters.*`.
  - Added the pure `missingScopesOf(granted: UserToken)`. `view` uses the same method to build its `missingScopes`, so the two cannot drift apart.
  - The warning in `completeAuthorization` now computes its missing scopes from `issued` instead of re-reading state.
  - Rewrote the class doc. Every read now goes through `view`, which builds one `AuthorizationView` from a single `state.get()`. Twitch4J gets an immutable `TwitchOAuthClient.credentialOf(plan.grant)` snapshot for each subscription plan. A refresh or rejection triggers a resubscribe and does not mutate a credential Twitch4J already holds.
- `twitch-screen-relay/src/twitchscreen/relay/twitch/TwitchAuthApi.scala`: `callback` now uses `auth.missingScopesOf(granted)`, so the caveat on the success page and the login it shows come from the same grant. `status()` already read a single `view` and is unchanged.
- `twitch-screen-relay/test/src/twitchscreen/relay/twitch/TwitchAuthSuite.scala`
  - `ScriptedTwitch.exchange("partial")` now issues a grant that lacks `moderator:read:followers`.
  - New tests:
    - "the callback page reports the scopes missing from the grant it just installed"
    - "missing scopes are computed from the issued grant, not re-read from state"
    - "Twitch4J receives an immutable credential snapshot"
  - Migrated calls: `auth.current` became `auth.view.held`. `auth.accessToken` became the suite helper `usableAccess(auth)`, which is `view.usable.map(_.accessToken.value)`. `auth.missingScopes` became `auth.view.missingScopes`. `auth.credential` became `auth.view.usable`. The `handle` assertions now assert on the usable grant. Every race and persistence assertion is kept.
  - Renamed tests:
    - "...and the handle twitch4j holds follows it" became "...and the usable grant follows it".
    - "sign-out racing a refresh cannot restore the credential handle or persisted grant" became "...cannot restore the usable grant or persisted grant".
    - "new consent racing refresh retains the new grant in memory handle and file" became "...retains the new grant as the usable grant and in the file".

### Red, then green

- Red: before the production change, `./mill --no-daemon test.testOnly 'twitchscreen.relay.twitch.TwitchAuthSuite'` failed to compile with `value missingScopesOf is not a member of twitchscreen.relay.twitch.TwitchAuth`.
- The callback-page test pins the behaviour the fix needs. It cannot reproduce the race deterministically through the stub interpreter. The race is proven by the "not re-read from state" test: after `signOut()`, `missingScopesOf(granted)` still reports `moderator:read:followers`, while `view.missingScopes` reports every configured scope.

### Validation (run from `twitch-screen-relay/`)

| Command | Result |
|---|---|
| `./mill --no-daemon test.testOnly 'twitchscreen.relay.twitch.*'` | SUCCESS. 10 suites, 105 tests, 0 failed. `TwitchAuthSuite` has 18 tests, up from 15. |
| `grep -rnE '\.(credential\|current\|accessToken)\b' src` | No call on a `TwitchAuth`. The remaining hits are `UserToken.accessToken` (TwitchOAuthClient:79, SubscriptionPlan:14, TokenFile:93, TwitchAuth:54 inside `rejectAccessToken`, AuthorizationView:7), `ThreadLocalRandom.current` (TwitchRetry:13, DeviceHub:132), `Span.current` (SetTraceIdInMDCInterceptor:19) and `RelayVersion.current` (RelayMetrics:16, StatusApi:63, HttpApi:33). |
| `grep -rn 'auth\.missingScopes\b' src` | no matches (exit 1) |
| `./mill --no-daemon compile` | SUCCESS, with `-Werror` |
| `./mill --no-daemon test` | SUCCESS. 37 suites, 421 tests, 0 failed, 0 ignored. |
| `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` | SUCCESS |

## K-016: Unknown or misspelled secret keys render unmasked in /config

- Severity: Medium-bug. Area: relay-twitch.
- **Disposition: fixed.** This closes both `[partial]` gaps. A misspelled key now fails startup and the error names it. `/config` also masks every path the schema does not know.

### Change

- `twitch-screen-relay/src/twitchscreen/relay/config/ConfigKeys.scala` (new, `private[config]`): `ConfigKeys.of[A]` returns a case class's HOCON keys. It reads the field labels through `Mirror.ProductOf` and maps them with `ConfigFieldMapping(CamelCase, KebabCase)`, the same mapping `ConfigReader.derived` uses.
- `ValidatedConfigReader.scala`:
  - `derivedValidated` now needs `Mirror.ProductOf` (every caller is a case class). It goes through the new `strict(reader)`.
  - `strict` rejects any key in the section that is not in `ConfigKeys.of[A]`. Each one fails with `UnknownKey` at `atKeyOrUndefined(key)`, which gives the failure the full path and origin. All unknown keys are collected, not just the first.
  - The IllegalArgumentException-to-CannotConvert wrapping is kept.
  - PureConfig-core 0.17.10 on Scala 3 has no `ProductHint`, so the check lives in the reader.
- `HttpAuthConfig.scala`: the reader is now `ValidatedConfigReader.strict(ConfigReader.derived[HttpAuthConfig].map(_.tap(_.validate())))`, so `http.auth.api-tokn` is rejected. `validate()` and the path-qualified errors are unchanged.
- `Config.scala`: added `Config.SchemaPaths`, every leaf path the typed config reads, built from `ConfigKeys.of` for each section, with the nested sections `auth`, `oauth`, `event-sub` and `simulation` expanded. The root `Config` stays lenient because `ConfigFactory.load()` also holds system properties and other libraries' keys. Every section class (Http, HttpAuth, DeviceLink, Twitch, TwitchOAuth, EventSub, Simulation, Notifications, Bus, Stats, Activity, Alerts, Observability) is strict.
- `ConfigApi.scala`: `secret(path)` is also true for any path outside `Config.SchemaPaths`, so such a path renders as `***`. The suffix rule stays as defence in depth. The scaladoc is updated.
- `application.conf` and the test fixtures needed no change. The full suite loads all of them.

### Tests (`ConfigSuite.scala`, 7 new)

- "a misspelled secret key fails startup and names the key...": `twitch.client-secert`
- "a misspelled management token key fails startup and names the key": `http.auth.api-tokn`
- "unknown keys are rejected in nested and leaf sections alike": `twitch.oauth.redirct-url`, `stats.broadcast-intervl`, `device-link.idle-timout`
- "every unknown key is reported, not just the first"
- "an optional setting may still be omitted, and one that is set is not mistaken for an unknown key"
- "/config masks every key the schema does not know, whatever its spelling": `twitch.client-secert`, `twitch.apiKey` and `http.auth.api-tokn` each render `***`, and no value equals `leak`.
- "the schema lists every shipped key, and only the known secrets are masked": the shipped keys minus `SchemaPaths` is empty, and exactly the four known secret paths are masked.

The existing masking tests ("the rendered configuration masks every secret" and "unknown secret keys are masked independent of spelling convention") still pass.

**Out of scope, not changed.** The planner said the defaulted `notifications.ignored-display-names` "stays allowed when absent". That is not true today, and it was not true before this change: the Scala 3 derived reader does not apply case-class defaults. Leaving the key out failed with `Key not found: 'ignored-display-names'` before any production change. `application.conf` always sets it. The omission test therefore covers the `Option` field `alerts.no-devices-connected-for` instead.

### Red, then green

- Red, compile: `value SchemaPaths is not a member of object twitchscreen.relay.config.Config`.
- Red, behaviour: I ran the suite with the `SchemaPaths` reference temporarily neutralized in the test. 6 of the new tests failed. `load[Config]` returned `Right(...)` for `client-secert`, `api-tokn`, the nested and leaf typos, and the two-typo case. flatten rendered `twitch.client-secert` unmasked. The sixth failure was the defaults issue described above; that test was then narrowed.
- Green: `./mill --no-daemon test.testOnly 'twitchscreen.relay.config.*'`: ConfigSuite 38 tests, 0 failed.
- Real startup (`Config.load()` against the runClasspath) with a misspelled key in a `-Dconfig.file` source fails with `at 'twitch.client-secert': - (<file>: 3) Unknown key.`

### Validation (run from `twitch-screen-relay/`)

| Command | Result |
|---|---|
| `./mill --no-daemon test.testOnly 'twitchscreen.relay.config.*'` | SUCCESS. ConfigSuite 38 tests (31 + 7), 0 failed. |
| `./mill --no-daemon compile` | SUCCESS, with `-Werror`, no warnings |
| `./mill --no-daemon test` | SUCCESS. 37 suites, 428 tests, 0 failed. |
| `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` | SUCCESS |
| `git diff --check` | clean |

### Judge round 1 fix: JDK system properties

- **Blocking issue.** `ConfigFactory.load()` layers every JVM system property over `application.conf`. The JDK's networking properties (`http.proxyHost`, `http.proxyPort`, `http.nonProxyHosts`, `http.agent`, `http.auth.preference`, `http.auth.digest.*`, `http.auth.ntlm.domain`) share the relay's `http` and `http.auth` namespaces. The strict readers rejected them, so `-Dhttp.proxyHost=proxy.local` stopped the relay from starting.
- **Change.** In `ValidatedConfigReader.scala`, an unknown key is exempt when its value's origin is the system-property overlay. The origin description is read from `ConfigFactory.systemProperties().origin()`, not hard-coded. A key that also appears in a relay-owned source gets a `merge of ...` origin and is still rejected. `/config` still shows such a property as `***`, because it is outside `Config.SchemaPaths`. The scaladoc records the trade-off: a misspelled relay key passed only as `-D` loads and is masked, while the same typo in `application.conf` or in a `config.file` source fails.
- **Tests (`ConfigSuite.scala`, 3 new, 10 for K-016 in total).**
  - "the JDK's http.* and http.auth.* system properties still load, and /config masks them": sets the real `http.nonProxyHosts` and `http.auth.preference` system properties, calls `invalidateCaches`, loads, and restores them afterwards. `http.proxyHost` is left out of the real properties because it would route the test JVM's HTTP clients through a proxy.
  - "a system-property overlay carrying proxy settings loads, as ConfigFactory.load() layers it": `http.proxyHost`, `http.proxyPort`, `http.nonProxyHosts`, `http.auth.preference` and `http.auth.digest.validateServer`, parsed with the system-properties origin.
  - "a misspelled relay key in a config.file source still fails, even next to system properties": a temp `.conf` file with `twitch.client-secert` and `http.auth.api-tokn`. Both are named in the failure.
- **Red, then green.** With the exemption disabled, the first two new tests failed with `(system properties) Unknown key.`. With it enabled, all 41 ConfigSuite tests pass.
- **Real startup** (`Config.load()` on the runClasspath, a Java probe in the scratchpad):
  - Each of `-Dhttp.proxyHost=proxy.local`, `-Dhttp.nonProxyHosts=localhost|*.local` and `-Dhttp.auth.preference=basic` on its own gets past the key check and stops at the expected `http.auth requires Basic credentials or an API token`.
  - Those flags together with `http.proxyPort`, `http.auth.digest.validateServer=true` and a valid `-Dhttp.auth.api-token` print `LOADED`.
  - `-Dconfig.file=typo.conf -Dhttp.proxyHost=proxy.local` fails with `at 'twitch.client-secert': (typo.conf: 3) Unknown key.`

| Command (from `twitch-screen-relay/`) | Result |
|---|---|
| `./mill --no-daemon test.testOnly 'twitchscreen.relay.config.*'` | SUCCESS. ConfigSuite 41 tests, 0 failed. |
| `./mill --no-daemon compile` | SUCCESS, with `-Werror` |
| `./mill --no-daemon test` | SUCCESS. 37 suites, 431 tests, 0 failed. |
| `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` | SUCCESS |
| `git diff --check` | clean |
