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

## K-017: No idle/read timeout on HTTP listener (slowloris)

- Severity: Medium-bug. Area: relay-twitch.
- **Disposition: fixed.** It closes the `[partial]` gap: the whole-request deadline. `RequestReadTimeout` sits after the codec, so every decoded body chunk reset it. A body trickled 1 byte (or one `1\r\nA\r\n` chunk) every 29 s could hold a connection for up to about 64 Ki x 30 s, and 128 such connections exhausted the listener. That included a body still being drained after an early 401.

### Change

- New `twitch-screen-relay/src/twitchscreen/relay/http/RequestDeadline.scala`: a per-connection `ChannelInboundHandlerAdapter`.
  - On an `HttpRequest` with no timer pending, it schedules a close on the channel's event loop after the deadline.
  - On a `LastHttpContent` it cancels the timer. The check is separate, so a `FullHttpRequest` starts and cancels at once.
  - A plain `HttpContent` never reschedules or extends the timer.
  - The timer is cancelled in `channelInactive` and `handlerRemoved`, so none leaks. The close is quiet, like `RequestReadTimeout`.
  - The state is a plain `var`, touched only on the event loop; a comment says so.
- `HttpApi.scala`:
  - Added the fixed limit `HttpApi.WholeRequestTimeout = 30.seconds`. It is not a config knob, like `MaxConnections`, `MaxBodyBytes` and `ReadTimeout`.
  - `startOnPort` takes `requestDeadline` (defaulting to that limit) so tests can use a small one.
  - Pipeline: codec, then `requestDeadline`, then `requestReadTimeout`, then `requestBodyLimit`. The deadline comes before the body limit, so it also covers chunks the body limit drops after a 413. The pipeline comment is updated.
- `docs/reference/relay-configuration.md`: the fixed-limits paragraph now also describes the 30-second whole-request deadline, from decoded headers to the final body chunk, which a trickled body cannot extend. It stays in the list of limits that are not deployment knobs.

### Tests (`ManagementAuthSuite.scala`, 2 new)

- `withServer` takes a `requestDeadline` and passes it to `startOnPort`. The new raw-socket helper `trickleUntilClosed` writes the head, then a forked writer sends a piece every interval. The main thread reads until EOF or reset, with a 5 s cap that turns a hang into a failure.
- "a slowly trickled body cannot extend the whole-request deadline": `readTimeout = 400ms`, `requestDeadline = 1s`, 100 ms trickle. The trickle is shorter than `readTimeout`, so the read deadline alone never fires. There are three cases:
  - authenticated `Content-Length: 1000`, one byte at a time;
  - authenticated `Transfer-Encoding: chunked`, `1\r\nA\r\n` at a time;
  - unauthenticated `Content-Length: 1000`, where Tapir may answer 401 early and the rest of the body is drained.

  Each case must close with elapsed time >= 800 ms and < 3 s, and `calls == 0`.
- "the whole-request deadline restarts for each keep-alive request and ends with the body": `requestDeadline = 500ms`. Two POSTs go over one keep-alive socket, with 900 ms idle between them, and both return 200. This proves the timer is cancelled on `LastHttpContent` and restarts per request.
- "silent connections and incomplete HTTP headers hit a read deadline" is unchanged and still green.

### Red, then green

- Red, compile: `startOnPort ... does not have a parameter requestDeadline`.
- Red, behaviour: I added the `requestDeadline` parameter to `startOnPort` temporarily unused, with no handler. `==> X ... a slowly trickled body cannot extend the whole-request deadline 5.043s` failed with `authenticated fixed-length: a trickled body held the connection open past the cap`. ManagementAuthSuite: 1 failed, 11 total. The keep-alive sanity test passed, as expected, since it guards against over-closing.
- Green: `./mill --no-daemon test.testOnly twitchscreen.relay.http.ManagementAuthSuite`: 11 tests, 0 failed.

### Validation (run from `twitch-screen-relay/`)

| Command | Result |
|---|---|
| `./mill --no-daemon test.testOnly twitchscreen.relay.http.ManagementAuthSuite` | SUCCESS. 11 tests (9 + 2), 0 failed. |
| `./mill --no-daemon compile` | SUCCESS, with `-Werror`, no warnings |
| `./mill --no-daemon test` | SUCCESS. 38 suites, 439 tests, 0 failed. |
| `./mill --no-daemon mill.scalalib.scalafmt/` then `mill.scalalib.scalafmt/checkFormatAll` | SUCCESS |
| `git diff --check` | clean |

## K-018: Cleartext management credentials with no warning (plain HTTP, host 0.0.0.0 default)

- Severity: Medium-bug. Area: relay-twitch.
- **Disposition: fixed.** It closes the `[partial]` test gap. The WARN already existed at `HttpApi.scala:52-55`, but no test checked it or the loopback classification.

### Change

- `twitch-screen-relay/src/twitchscreen/relay/http/HttpApi.scala`: a small extraction that keeps behavior the same.
  - `object HttpApi` gains `private val LoopbackHosts` (`localhost`, `127.0.0.1`, `::1`, `[::1]`), a pure `private[http] def isLoopback(host: Hostname)` that lowercases with `Locale.ROOT`, and `private[http] val PlaintextNonLoopbackWarning`. The message text is unchanged, so `docs/reference/relay-configuration.md:148` still describes it.
  - `startOnPort` now runs `if !HttpApi.isLoopback(config.host) then logger.warn(HttpApi.PlaintextNonLoopbackWarning)`. It still logs once per bind, from the same `twitchscreen.relay.http.HttpApi` logger.
- `twitch-screen-relay/test/src/twitchscreen/relay/http/ManagementAuthSuite.scala`:
  - `withServer` takes `host: String = "127.0.0.1"`. Existing callers keep the default.
  - New helper `plaintextWarnings(host)`. It attaches a logback `ListAppender` to the `HttpApi` logger, starts and stops the API on `host`, and returns the WARN messages that contain `plaintext HTTP on a non-loopback interface`. The appender is detached and stopped in `finally`. The pattern is copied from `DeviceBackpressureSuite`.

### Tests (`ManagementAuthSuite.scala`, 3 new)

- "a non-loopback bind warns once that management credentials travel in plaintext": binds `0.0.0.0` on an ephemeral port and expects exactly 1 warning.
- "loopback binds do not warn about plaintext management credentials": `127.0.0.1` and `LOCALHOST` each produce `Nil`. `LOCALHOST` bound fine on this host, so it did not need a fallback.
- "loopback classification is case-insensitive and covers IPv4, IPv6 and bracketed IPv6": `isLoopback` returns true for `localhost`, `LOCALHOST`, `127.0.0.1`, `::1`, `[::1]` and ` localhost ` (which `Hostname` trims). It returns false for `0.0.0.0`, `::`, `192.168.1.10` and `relay.example.com`.

### Red, then green

- Red, compile: `value isLoopback is not a member of object twitchscreen.relay.http.HttpApi`.
- Green: `./mill --no-daemon test.testOnly twitchscreen.relay.http.ManagementAuthSuite`: 14 tests (11 + 3), 0 failed.

### Mutation proof (each mutation was reverted afterwards)

| Mutation | Result |
|---|---|
| Delete the `logger.warn` line | 1 failed out of 14: "a non-loopback bind warns once ..." |
| Invert to `if HttpApi.isLoopback(config.host)` | 2 failed out of 14: "a non-loopback bind warns once ..." and "loopback binds do not warn ..." |
| Drop `.toLowerCase(Locale.ROOT)` | 2 failed out of 14: "loopback binds do not warn ..." and "loopback classification ..." (message `LOCALHOST`) |

### Validation (run from `twitch-screen-relay/`)

| Command | Result |
|---|---|
| `./mill --no-daemon test.testOnly twitchscreen.relay.http.ManagementAuthSuite` | SUCCESS. 14 tests, 0 failed. |
| `./mill --no-daemon test.testOnly 'twitchscreen.relay.http.*' 'twitchscreen.relay.observability.*'` | ApiSuite 27, TraceIdMdcSuite 2, ManagementRoutesSuite 4, DiagnosticsSuite 5, OtelLinkageSuite 3: 0 failed. ManagementAuthSuite failed 1 of 14 on the first run and then passed 14/14 in 3 of 3 reruns. See the note below. |
| `./mill --no-daemon test` | SUCCESS. 39 suites, 444 tests, 0 failed. |
| `./mill --no-daemon compile` | SUCCESS (`-Werror`) |
| `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` | SUCCESS |
| `git diff --check` | clean |

Note on the flake: the first parallel package run failed "a slowly trickled body cannot extend the whole-request deadline" (K-017) at `assertEquals(calls.get(), 0)`. So under parallel load, a protected handler ran once during a trickle case. That test and its code path are not touched by K-018: the new tests never call `/protected`, and each `withServer` has its own `calls` counter. It did not reproduce in the next 3 package runs or in the full `test` run. I am recording it as a timing flake that was already in K-017, for integration to follow up.

## K-061: Spurious 'recovered' on first health observation

- Severity: Low. Area: relay-twitch. Location: `TwitchRuntimeHealth.scala:28` at review time.
- **Disposition: fixed (regression added; the code fix was already on main).** It closes the `[partial]` test gap. `TwitchHealthState.observe` already logs `Twitch <label> recovered` only when `previous.exists { case Failed(_) => true; case _ => false }`, and `resetSession()` seeds `Awaiting`, not `Failed`. The line is log-only (`logger.info`) and never reaches the bus, so before this change no test failed if the guard was removed.

### Change

- Test-only. No production code changed.
- `twitch-screen-relay/test/src/twitchscreen/relay/twitch/TwitchRecoverySuite.scala`:
  - New helper `recoveredLines(body)`. It attaches a logback `ListAppender` to the `TwitchHealthState` logger (`classOf[TwitchHealthState]`, same package) and forces the level to INFO, so a `RELAY_LOG_LEVEL=WARN` environment cannot make the negative cases pass vacuously. It runs `body` and returns the INFO messages that end with `recovered`. In `finally`, it detaches and stops the appender and restores the saved level. It needs no sleeps: `observe` is an actor `ask`, so each log call has finished before `ask` returns. The pattern is copied from `ManagementAuthSuite.plaintextWarnings`.
  - 4 new tests. Each one uses a fresh `TwitchRuntimeHealth(config, EventBus(Clock.systemUTC(), 64))`:
    - "health does not log 'recovered' for a component's first observation": `observe(Startup, None)` produces `Nil`.
    - "health does not log 'recovered' for observations after a session reset": `observe(Startup, None)`, `resetSession()`, `observe(Streams, None)`, `observe(Startup, None)` produces `Nil`.
    - "health logs 'recovered' once when a failed component becomes healthy": `observe(Streams, Some("x"))`, then `observe(Streams, None)` twice, produces exactly `List(s"Twitch ${HealthComponent.Streams.label} recovered")`. The repeated Healthy does not log a second time.
    - "health does not log 'recovered' when a failed component went back to awaiting first": Failed, then `awaiting(Streams)`, then Healthy produces `Nil`. This held on current main, so it pins that "recovered" means a direct failed-to-healthy transition.
  - Imports added: logback `Level`, `Logger as LogbackLogger`, `ILoggingEvent`, `ListAppender`, `org.slf4j.LoggerFactory`, `scala.jdk.CollectionConverters.*`. `discard` was already imported from ox.

### Red, then green

- Green on current main: `./mill --no-daemon test.testOnly twitchscreen.relay.twitch.TwitchRecoverySuite`: 24 tests (20 + 4), 0 failed.
- The code fix was already on main, so the red step is shown by the mutations below.

### Mutation proof (each mutation was reverted with `git checkout -- twitch-screen-relay/src/twitchscreen/relay/twitch/TwitchRuntimeHealth.scala`)

| Mutation | Result |
|---|---|
| Guard removed: `case Healthy =>` | 3 failed out of 24, each a `munit.ComparisonFailException: values are not the same`: "first observation" (line 420), "after a session reset" (line 430), "went back to awaiting first" (line 448) |
| Guard disabled: `case Healthy if false =>` | 1 failed out of 24: "logs 'recovered' once when a failed component becomes healthy" (line 439) |

After the revert, `git diff --stat` showed only `TwitchRecoverySuite.scala` changed (58 insertions), plus this evidence file.

### Validation (run from `twitch-screen-relay/`)

| Command | Result |
|---|---|
| `./mill --no-daemon test.testOnly twitchscreen.relay.twitch.TwitchRecoverySuite` | SUCCESS. 24 tests, 0 failed. |
| `./mill --no-daemon test.testOnly 'twitchscreen.relay.twitch.*'` | SUCCESS. 10 suites, 109 tests, 0 failed. |
| `./mill --no-daemon compile` | SUCCESS (`-Werror`) |
| `./mill --no-daemon test` | SUCCESS. 39 suites, 448 tests (444 + 4), 0 failed. |
| `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` | SUCCESS (160 sources) |
| `git diff --check` | clean |
