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

## K-042 + K-064: route WebSocket EventSub through one tested payload adapter

- K-042 (Medium-smell, relay-twitch): Duplicated transport-to-domain mapping (RLY-20 follow-through). Location at review time: `TwitchEventHandlers.scala:88-103`, `EventSubWebhookApi.scala:119-147`.
- K-064 (Low, relay-twitch): Chat/WS path doesn't null-normalize channel-update fields (RLY-21 residual). Location at review time: `TwitchEventHandlers.scala:99-101`.
- **Disposition: fixed.** Both gaps were `[partial]`. Production already normalised nulls and already shared `EventSubMapping`, but the RLY-21 tests exercised `TwitchEventHandlers.channelUpdated`, a test-only wrapper that no production code called. No test drove `registerEventSub`, and no test compared the two transports. Changing the registered handler to `Some(event.getTitle)` passed every test.

### Change

- `twitch-screen-relay/src/twitchscreen/relay/twitch/TwitchEventHandlers.scala`:
  - New `private[twitch]` adapters `followPayload(ChannelFollowEvent)`, `streamOnlinePayload(StreamOnlineEvent)` and `channelUpdatePayload(ChannelUpdateV2Event)`. Each wraps every nullable twitch4j getter in `Option(...)`. One scaladoc covers the block: these adapters are the WebSocket counterpart of the webhook's JSON decoding, `EventSubMapping` is the only place a payload becomes a `RelayEvent`, and `Option(...)` keeps nulls off the screen (RLY-21, K-064).
  - `registerEventSub` is now four one-line handlers, each `mapping.dispatch("<kind>", <adapter>(event))`. `stream.offline` has no fields, so it still passes `EventSubPayload()`, as the plan specifies. Same subscription kinds, same `BotFilter` publish path.
  - Deleted `TwitchEventHandlers.channelUpdated` and its stale scaladoc ("mirrors the webhook mapping in EventSubWebhookApi").
  - `EventSubMapping` and `EventSubWebhookApi` are unchanged.
- `test/.../twitch/TwitchEventSubFixtures.scala` (new, test-only): builds twitch4j events the way the WebSocket client does, with `TypeConvert.jsonToObject` over snake_case JSON. An absent field is written as an explicit JSON `null`. No reflection fallback was needed. It also provides `eventManager()`, an `EventManager` with `SimpleEventHandler` as the default handler, so `publish` is synchronous.
- `test/.../twitch/TwitchEventHandlersSuite.scala` (rewritten, 3 to 6 tests):
  - A fixture check that the JSON populates the getters, and that null JSON gives null getters.
  - RLY-21, all fields null: `channelUpdatePayload` gives `EventSubPayload()` with no `Some(null)`. `EventSubMapping.channelUpdated` gives `ChannelUpdated("somechannel", "", "")`, and its summary is `"somechannel updated:  ()"` with no "null".
  - RLY-21, all fields present: they pass through the adapter and the mapping.
  - RLY-21, category missing: the result is `ChannelUpdated("Streamer", "Soldering", "")`.
  - `followPayload` and `streamOnlinePayload` turn a null field into `None`.
  - K-064: registered-handler test. `registerEventSub` is wired to a real `EventManager`. Publishing a null-field `ChannelUpdateV2Event` puts `ChannelUpdated("somechannel", "", "")` on the bus. A null-user follow publishes nothing. A null-`startedAt` online publishes `StreamStarted(..., Some(clock now))`.
- `test/.../twitch/EventSubTransportParitySuite.scala` (new, 1 test, K-042). One scenario goes through both transports, each with a fresh bus, tracker and fixed clock:
  - Scenario: an all-null `channel.update`, a follow from `pixelpainter`, a follow from `Nightbot` (bot-filtered), `stream.online` with `started_at` 2026-09-25T09:02:20Z, then `stream.offline`.
  - WebSocket transport: twitch4j objects published on an `EventManager` wired by `registerEventSub`.
  - Webhook transport: signed JSON POSTed through `EventSubWebhookApi.create` on the Tapir stub, with distinct message ids. No health observe, so no `TwitchLinkUp` is on the bus.
  - Each bus is drained with `tryReceive`. The test asserts the webhook list equals `[ChannelUpdated("somechannel","",""), Followed("pixelpainter"), StreamStarted("somechannel","","",Some(start)), StreamEnded("somechannel", between(start, now))]`, and that the WebSocket list equals the webhook list.

### Red, then green

- Red: after the tests were written and before the production change, `./mill --no-daemon test.testOnly 'twitchscreen.relay.twitch.*'` failed at `test.compile` with 7 errors in `TwitchEventHandlersSuite.scala` (lines 31, 40, 51, 58, 59, 60, 62). The cause was that `channelUpdatePayload`, `followPayload` and `streamOnlinePayload` did not exist yet.
- Green: after the change, the same command passed. 11 suites, 113 tests, 0 failed. `TwitchEventHandlersSuite` has 6 tests and `EventSubTransportParitySuite` has 1.

### Mutation proof (reverted afterwards from a backup copy; `grep -c 'Some(event'` afterwards gives 0)

| Mutation | Result |
|---|---|
| `channelUpdatePayload`: `title = Some(event.getTitle)` | 3 failed: "RLY-21: an update with every field missing ..." (TwitchEventHandlersSuite:32), "K-064: the handlers registerEventSub wires publish null-normalised events" (:80), "K-042: the WebSocket and webhook transports publish identical events ..." (EventSubTransportParitySuite:102) |

### Validation (run from `twitch-screen-relay/`)

| Command | Result |
|---|---|
| `./mill --no-daemon test.testOnly 'twitchscreen.relay.twitch.*'` | SUCCESS. 11 suites, 113 tests, 0 failed. |
| `./mill --no-daemon compile` | SUCCESS (`-Werror`) |
| `./mill --no-daemon test` | SUCCESS. 40 suites, 457 tests, 0 failed. |
| `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` | SUCCESS (162 sources) |
| `grep -rn 'TwitchEventHandlers.channelUpdated\|mirrors the webhook mapping' twitch-screen-relay/` | no matches (exit 1) |
| `git diff --check` | clean |

## K-067: Sub-millisecond intervals admitted

**Disposition:** fixed. The test gap is closed with tests only; no production change was needed. `Config.scala` already enforced `toMillis >= 1` (and `toSeconds >= 1` for count windows) at every timer site. The regression test pinned only `SimulationConfig.interval` and `StatsConfig`.

### Change

`twitch-screen-relay/test/src/twitchscreen/relay/config/ConfigSuite.scala`, test "config rejects sub-millisecond timers and unbounded queue sizes" (edited in place, name kept):

- Valid fixtures: `alerts` (every threshold enabled at 1 minute), `oauth`, `liveTwitchConfig("client")` and `deviceLinkConfig()`. Positive controls assert that each fixture accepts exactly 1 ms (`evaluationInterval`, `noDevicesConnectedFor`, `pollInterval`, `refreshBefore`). This proves each rejection below comes from the changed field and not from an invalid fixture.
- New rejection cases, each intercepted as `IllegalArgumentException`:
  - `SimulationConfig(1.second, 1.nanos)` (chatInterval)
  - `twitch.copy(pollInterval = 1.nanos)`
  - `oauth.copy(refreshBefore = 1.nanos)`
  - `alerts.copy(evaluationInterval = 1.nanos)`
  - `alerts.copy(evaluationInterval = 999.micros)`: boundary case showing the check is millisecond-granular, not just nonzero
  - `alerts.copy(noDevicesConnectedFor = Some(1.nanos))`, `twitchDisconnectedFor = Some(1.nanos)`, `streamOfflineFor = Some(1.nanos)`
  - `alerts.copy(errorRateWindow = 999.millis)`
  - `deviceLinkConfig(handshakeTimeout = 1.nanos)`
- The existing six cases are unchanged.

### Mutation proof (scratch edits of `Config.scala`, each restored from a backup copy; afterwards `git status` shows only `ConfigSuite.scala` modified)

Command for each: `./mill --no-daemon test.testOnly twitchscreen.relay.config.ConfigSuite`

| Site reverted to the old form | Result |
|---|---|
| `TwitchConfig`: `pollInterval.toMillis >= 1` -> `toNanos > 0` | 1 failed of 41. The K-067 test failed with "expected exception of type 'java.lang.IllegalArgumentException' but body evaluated successfully" (ConfigSuite.scala:191). |
| `TwitchOAuthConfig`: `refreshBefore.toMillis >= 1` -> `toNanos > 0` | 1 failed of 41 (same test, same message) |
| `AlertsConfig`: `evaluationInterval.toMillis >= 1` -> `toNanos > 0` | 1 failed of 41 (same test, same message) |
| `AlertsConfig` thresholds: `forall(_.toMillis >= 1)` -> `forall(_.toNanos > 0)` | 1 failed of 41 (same test, same message) |
| `AlertsConfig`: `errorRateWindow.toSeconds >= 1` -> `toNanos > 0` | 1 failed of 41 (same test, same message) |
| `DeviceLinkConfig`: `handshakeTimeout.toMillis >= 1` -> `toNanos > 0` | 1 failed of 41 (same test, same message) |

### Validation (run from `twitch-screen-relay/`)

| Command | Result |
|---|---|
| `./mill --no-daemon test.testOnly twitchscreen.relay.config.ConfigSuite` | SUCCESS. 41 tests, 0 failed. It passed on the first run because the production code was already fixed. |
| `./mill --no-daemon test.testOnly 'twitchscreen.relay.config.*'` | SUCCESS. 1 suite, 41 tests, 0 failed. |
| `./mill --no-daemon test` | SUCCESS. 41 suites, 459 tests, 0 failed. |
| `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` | SUCCESS (163 sources) |
| `git diff --stat -- twitch-screen-relay/src` | empty (no production change) |

## K-038: move remaining EventSub transport switches onto the transport strategy

- Finding: K-038, "maintainSubscriptions Long Method + transport Switch Statements". Severity: Medium-smell. Area: relay-twitch.
- **Disposition: fixed.** This closes both `[partial]` gaps. The Long Method part was already fixed. This unit removes the five transport switches that were left, at LiveTwitchSource :35, :57 and :122, SubscriptionPlan :22 and TwitchRuntimeHealth :45. It also replaces the substring "awaiting" detection with a typed outcome.

### Change

All paths are under `twitch-screen-relay/src/twitchscreen/relay/twitch/`.

- `EventSubOutcome.scala` (new): `private[twitch] enum EventSubOutcome { Healthy; Awaiting; Failed(reason) }`.
- `TwitchRuntimeHealth.scala`:
  - Added `record(component, outcome)`. It is the only mapping from outcome to health: Healthy goes to `observe(_, None)`, Awaiting to `awaiting(_)`, and Failed(r) to `observe(_, Some(r))`.
  - Added `recordSubscription(kind, outcome)`.
  - `resetSession` now takes the transport extras from `EventSubTransportPolicy.of(...).expectedHealth`, in place of the `Option.when` on the transport. The order and content of the expected list are unchanged.
- `EventSubTransportStrategy.scala`:
  - New `EventSubTransportPolicy`, a sealed trait with private `Webhook` and `WebSocket` objects. It has one resolver, `of(transport)`, whose match is the only place in the package that names `EventSubTransport.Webhook` or `EventSubTransport.WebSocket`.
  - Its members are `requiresGrant`, `enablesEventSocket`, `expectedHealth`, `webhookApi(...)` and `session(...)`. `webhookApi` is the single selection made before any session starts: Some for Webhook, None for WebSocket. `session(...)` replaces the old `EventSubTransportStrategy.create` match, which is deleted.
  - `SocketTransport` now takes `acceptingCallbacks`. Its constructor is the session-start hook: it calls `listenToSocket(client.getEventManager, ...)`, which is the former `LiveTwitchSource.observeSocket` moved here unchanged. It registers the ConnectionState, SubscriptionSuccess and SubscriptionFailure listeners, each gated by `acceptingCallbacks`. `WebhookTransport` does nothing at session start.
  - `subscriptionFailed` and `subscriptionSucceeded` moved to the `EventSubTransportStrategy` companion and now call `recordSubscription`.
  - Both strategies pass `health.recordSubscription` or `health.record` straight through. There is no string inspection left.
  - Both strategy classes are `private[twitch]` so the selection can be tested.
- `EventSubWebhookApi.reconcileSubscriptions`: `observe` is now `(String, EventSubOutcome) => Unit`.
  - An enabled subscription gives Healthy.
  - Both "awaiting callback verification" branches give Awaiting.
  - The catch branch gives `Failed(s"registration failed (...); retrying")`, with the text unchanged.
  - The doc comment is updated to match.
- `WebSocketRegistration.step`: `observe` is now `(HealthComponent, EventSubOutcome) => Unit`.
  - Before each register it emits Awaiting.
  - A rejected register emits `Failed("registration rejected; rebuilding connection")`.
  - A missing grant emits `Failed("awaiting broadcaster authorization")` on EventSubConnection. That report reached `health.observe` as a failure before this change, and the doc now says so.
- `LiveTwitchSource.scala`:
  - `create` resolves `val policy = EventSubTransportPolicy.of(config.eventSub.transport)` once.
  - The webhook API comes from `policy.webhookApi(...)`.
  - `build(config, policy)` calls `.withEnableEventSocket(policy.enablesEventSocket)`.
  - The session calls `val transport = policy.session(...)` where `observeSocket` used to be called: after the event handlers are registered and before `resolveBroadcasterId`. Nothing is reordered. `transport` is then passed into `maintainSubscriptions`.
  - `observeSocket`, `subscriptionFailed` and `subscriptionSucceeded` are deleted, along with the EventSocket event imports and the `EventSubTransport` import.
- `SubscriptionPlan.scala`: `needsGrant = EventSubTransportPolicy.of(config.eventSub.transport).requiresGrant || config.oauth.scopes.nonEmpty`. The signature of `build` is unchanged.
- `Config.scala:65` is validation and is not changed.

### Tests

- `EventSubTransportPolicySuite.scala` (new, 7 tests):
  - Pins WebSocket: requiresGrant, enablesEventSocket, and `expectedHealth == List(EventSubConnection)`.
  - Pins Webhook: no grant, no socket, and `expectedHealth == Nil`.
  - `webhookApi` is defined, with 1 endpoint, only for Webhook.
  - `policy.session` returns a `WebhookTransport` or a `SocketTransport`. This uses a real Helix-only `TwitchClient`, which makes no network calls, and closes it afterwards.
  - Driven through `listenToSocket` on a synchronous `EventManager` with real twitch4j `EventSocket*Event` objects:
    - A subscription failure sets restart and records `EventSubFollow` Failed("subscription rejected; rebuilding connection"), while an unrelated success stays healthy. This is the SocketTransport restart path.
    - The connection state maps to EventSubConnection health.
    - Callbacks are ignored once `acceptingCallbacks` is false: no restart and an identical status.
- `TwitchRecoverySuite.scala` (6 new, 4 migrated):
  - Migrated `reconcileSubscriptions` call sites: `isDefined`/`None` became `Failed(reason)` checks with the "registration failed" prefix kept, or `Healthy`.
  - The `subscriptionFailed` and `subscriptionSucceeded` calls now go through `EventSubTransportStrategy`.
  - New: a created subscription that is not yet enabled gives Awaiting.
  - New: an existing `webhook_callback_verification_pending` subscription at our callback gives Awaiting, with 0 creations.
  - New, through `record`:
    - Awaiting leaves the component non-failed and the link non-connected, and publishes no RelayFailure.
    - Failed(r) publishes `RelayFailure("twitch-streams", r)` and shows `streams: r` in the status.
    - Healthy after Failed logs "recovered" once and clears the failure.
    - `recordSubscription` with an unknown kind leaves the status unchanged and publishes nothing.
- `WebSocketRegistrationSuite.scala` (1 new, migrated):
  - The harness now records `(label, EventSubOutcome)` and logs Awaiting as "awaiting", Failed(r) as r, and Healthy as "ok".
  - `AwaitingGrant` is now `("eventsub-connection", Failed("awaiting broadcaster authorization"))`.
  - The rejected test also asserts the typed `Failed("registration rejected; rebuilding connection")`.
  - New: "a pre-register observation is typed awaiting and never failed".
- `SubscriptionPlanSuite` is unchanged and passes.

### Red, then green

- Red: before the production change, `./mill --no-daemon test.testOnly twitchscreen.relay.twitch.EventSubTransportPolicySuite` failed at `test.compile` with 49 errors, among them `Not found: EventSubTransportPolicy` (4 sites), `Not found: type EventSubOutcome` and `value listenToSocket is not a member of object twitchscreen.relay.twitch.EventSubTransportStrategy` (3 sites).
- Green: after the change, all 9 suites of the targeted command below pass.

### Mutation proof (restored from backup copies afterwards; `grep -c` confirms each original line is back)

| Mutation | Result |
|---|---|
| `TwitchRuntimeHealth.record`: `Awaiting => awaiting(component)` changed to `observe(component, Some("awaiting"))` | TwitchRecoverySuite: 1 failed of 30, "a typed awaiting outcome leaves the component neither failed nor connected..." |
| WebSocket policy: `expectedHealth = List(HealthComponent.EventSubConnection)` changed to `Nil` | EventSubTransportPolicySuite: 1 failed of 7, "the WebSocket policy needs a grant..." |

### Grep proofs (from the worktree root)

| Command | Output |
|---|---|
| `grep -rnE 'EventSubTransport\.(Webhook\|WebSocket)' twitch-screen-relay/src/twitchscreen/relay/twitch` | 2 lines, both in the resolver: `EventSubTransportStrategy.scala:51: case EventSubTransport.Webhook => Webhook` and `:52: case EventSubTransport.WebSocket => WebSocket` |
| `grep -rn 'eventSub.transport ==' twitch-screen-relay/src` | no matches (exit 1) |
| `grep -nw 'EventSubTransport' .../LiveTwitchSource.scala` | no matches (exit 1). The substring form without `-w` still matches the type names `EventSubTransportPolicy` (:29, :107) and `EventSubTransportStrategy` (:125), which the plan itself uses. The enum `EventSubTransport` is no longer referenced or imported. |
| `grep -rn 'contains("awaiting' twitch-screen-relay/src` | no matches (exit 1) |
| `grep -n 'observeSocket\|withEnableEventSocket(config' .../LiveTwitchSource.scala` | no matches (exit 1) |

### Validation (run from `twitch-screen-relay/`)

| Command | Result |
|---|---|
| `./mill --no-daemon test.testOnly twitchscreen.relay.twitch.WebSocketRegistrationSuite twitchscreen.relay.twitch.TwitchRecoverySuite twitchscreen.relay.twitch.TwitchAuthSuite twitchscreen.relay.twitch.EventSubWebhookSuite twitchscreen.relay.twitch.ChannelStateTrackerSuite twitchscreen.relay.twitch.WebhookDeduplicationSuite twitchscreen.relay.twitch.SubscriptionPlanSuite twitchscreen.relay.twitch.EventSubTransportPolicySuite twitchscreen.relay.twitch.EventSubTransportParitySuite` | SUCCESS. 9 suites, 0 failed: WebSocketRegistration 9, TwitchRecovery 30, TwitchAuth 18, EventSubWebhook 16, ChannelStateTracker 18, WebhookDeduplication 3, SubscriptionPlan 5, EventSubTransportPolicy 7, EventSubTransportParity 1. |
| `./mill --no-daemon test.testOnly 'twitchscreen.relay.twitch.*'` | SUCCESS. 12 suites, 127 tests, 0 failed. |
| `./mill --no-daemon compile` | SUCCESS, with `-Werror` (no unused imports) |
| `./mill --no-daemon test` | SUCCESS. 42 suites, 474 tests, 0 failed, 0 ignored. That is 14 more declared `test(` cases than HEAD `08cd1c1` (441 declared, now 455). |
| `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` | SUCCESS, after `reformatAll` |

## K-039: Scope-name literals scattered across five sites

- Severity: Medium-smell. Area: relay-twitch.
- **Disposition: fixed.** It closes the `[partial]` config drift gap. Runtime code already used `TwitchScopes`, but the default in `application.conf` was not tied to it.

### Change

- `twitch-screen-relay/src/twitchscreen/relay/twitch/TwitchScopes.scala`
  - Added `val Default: List[String] = List(Followers, Subscriptions)`. Its scaladoc says it is the default of `twitch.oauth.scopes` in `application.conf` and that ConfigSuite pins the two as equal.
  - The object doc now also calls it the reference for the shipped config default.
  - No production call site needs the whole list, because the default reaches runtime through config. No runtime use was forced.
- `twitch-screen-relay/resources/application.conf`: the literal list is kept. Its comment now says it must equal `TwitchScopes.Default` and that ConfigSuite pins them.
- `twitch-screen-relay/test/src/twitchscreen/relay/config/ConfigSuite.scala`
  - Imports `twitchscreen.relay.twitch.TwitchScopes`.
  - The shipped-default assertion is now `assertEquals(config.twitch.oauth.scopes, TwitchScopes.Default)`.
  - The duplicate-scope rejection case now uses `List(TwitchScopes.Subscriptions, TwitchScopes.Subscriptions)`.
- `twitch-screen-relay/test/src/twitchscreen/relay/twitch/TwitchAuthSuite.scala`
  - The fixture `scopes` is now `TwitchScopes.Default`.
  - The `partial` grant uses `filterNot(_ == TwitchScopes.Followers)`.
  - The callback-page and `missingScopesOf` assertions now use `TwitchScopes.Followers`.
  - **Kept as a literal:** `assertEquals(params("scope"), "moderator:read:followers channel:read:subscriptions")`. It has a comment saying it is literal on purpose, because it pins the exact wire format Twitch receives.
- `twitch-screen-relay/test/src/twitchscreen/relay/twitch/WebSocketRegistrationSuite.scala`
  - `Follow` is now `TwitchScopes.Followers`.
  - The missing-scope step uses `List(TwitchScopes.Subscriptions)`.

### Why the HOCON literal is kept

HOCON cannot reference a Scala constant, so `application.conf` has to hold the strings. The drift guard is ConfigSuite's "the configuration shipped in resources loads" test. It loads the real resource and asserts that it equals `TwitchScopes.Default`. If either side is renamed without the other, the test fails.

### Red, then green

- Red: ConfigSuite was changed first. `./mill --no-daemon test.testOnly 'twitchscreen.relay.config.ConfigSuite'` then failed to compile with `value Default is not a member of object twitchscreen.relay.twitch.TwitchScopes`.
- Green: after `Default` was added, the same command passed.
- Drift proof, run by hand and reverted: `TwitchScopes.Subscriptions` was temporarily changed to `"channel:read:subscriptionz"`. ConfigSuite then failed (exit 1, `1 tests failed`, with the diff showing `"channel:read:subscriptionz"`).

### Validation (run from `twitch-screen-relay/`)

| Command | Result |
|---|---|
| `./mill --no-daemon test.testOnly 'twitchscreen.relay.config.ConfigSuite'` | SUCCESS. 41 tests, 0 failed. |
| `./mill --no-daemon test.testOnly 'twitchscreen.relay.twitch.*'` | SUCCESS. 12 suites, 127 tests, 0 failed. |
| `./mill --no-daemon test` | SUCCESS. 44 suites, 482 tests, 0 failed, 0 ignored. No `[warn]` lines, with `-Werror`. |
| `./mill --no-daemon mill.scalalib.scalafmt/` then `mill.scalalib.scalafmt/checkFormatAll` | SUCCESS, clean |
| `git diff --stat` | Only the 5 intended files, plus this record. |

`grep -rn "moderator:read:followers\|channel:read:subscriptions" src test resources`:

```
src/twitchscreen/relay/twitch/TwitchScopes.scala:5:  val Followers: String = "moderator:read:followers"
src/twitchscreen/relay/twitch/TwitchScopes.scala:6:  val Subscriptions: String = "channel:read:subscriptions"
src/twitchscreen/relay/twitch/EventSubWebhookApi.scala:173:  /** Follows need `moderator:read:followers` granted ... (doc comment)
src/twitchscreen/relay/twitch/HelixPoller.scala:109:  /** ... a missing `moderator:read:followers` scope ... (doc comment)
test/src/twitchscreen/relay/twitch/TwitchAuthSuite.scala:93:    assertEquals(params("scope"), "moderator:read:followers channel:read:subscriptions")
resources/application.conf:78:    # moderator:read:followers: follow events ... channel:read:subscriptions: the subscriber total.
resources/application.conf:80:    scopes = ["moderator:read:followers", "channel:read:subscriptions"]
```

Every hit is one of these: the constants, the guarded HOCON default and its comment, the one wire-format assertion kept on purpose, or doc comments.

## K-043: HelixPoller 9-param signature + closures into TwitchAuth

- Severity: Medium-smell. Area: relay-twitch.
- **Disposition: fixed.** It closes the `[partial]` gap. The per-endpoint helpers no longer take the destructured context as a parameter list, and `TokenProvider.broadcaster` now has direct tests.

### Change

- `twitch-screen-relay/src/twitchscreen/relay/twitch/HelixPoller.scala`
  - `private[twitch] def pollStream(helix: TwitchHelix, context: PollingContext, appTokenRejected: () => Unit)` takes 3 parameters, down from 7. It reads `context.config.channel`, `context.tracker`, `context.bus.publish`, `context.clock` and `context.health`. The body is otherwise unchanged, including the `STREAM_START` comment. Scalafmt wrapped the `ViewersObserved` publish across lines because it gained the `context.` prefix.
  - `pollFollowers` and `pollSubscribers` are now `(helix, context: PollingContext, userToken, reject: () => Unit)`, with 4 parameters, down from 6. They read `context.broadcasterId`, `context.bus` and `context.health`.
  - `poll` no longer has `import context.*`. It calls `pollStream(helix, context, appTokenRejected)`, then the followers poll, then the subscribers poll, then `context.health.observe(Poll, None)`. The order is unchanged. Each user-token callback is still `() => tokens.reject(token)`.
  - `start`, `attempt`, `uptimeOf`, `intOr` and `isUnauthorized` are unchanged. The scaladoc on the two rejection callbacks still holds, and no parameter it names was renamed.
- `twitch-screen-relay/test/src/twitchscreen/relay/twitch/TwitchRecoverySuite.scala`
  - Added the private helper `contextOf(bus, health)(using Ox): PollingContext`. It needs `using Ox` because `ChannelStateTracker` forks.
  - All four `PollingContext` sites use the helper: the two `poll` tests and the two `pollStream` tests.
  - The two `pollStream` callers use the 3-argument form. Each test keeps its own bus and health, and the rebuild test still passes a fresh `EventBus`. Assertions are unchanged.
- `twitch-screen-relay/test/src/twitchscreen/relay/twitch/TwitchAuthSuite.scala`: five new tests go through the production adapter `TokenProvider.broadcaster(auth, channel)`.
  - "the broadcaster token provider supplies a scoped token only for the configured channel's own grant" returns `access-1` for both scopes on `somechannel`, and also for `SomeChannel` because the owner match ignores case. It returns `None` for both scopes on `otherchannel`, a grant owned by another login.
  - "the broadcaster token provider withholds a scope the grant lacks" uses the `partial` grant: followers gives `None` and subscriptions gives `access-1`.
  - "the broadcaster token provider withholds an expired grant" advances the clock to expiry without `maintain`. Both scopes give `None` and `view.held` is still defined.
  - "rejecting a token through the broadcaster provider withholds only that grant": `reject("access-unrelated")` leaves `access-1` usable. `reject("access-1")` withholds both scopes. After `maintain()`, the token is `access-2`.
  - "a stale token's rejection leaves a newer grant usable": after a refresh to `access-2`, a late `reject("access-1")` still leaves `access-2`, and `view.usable` is still defined.

### Red, then green

- The refactor keeps behaviour unchanged, so it has no production red. The existing `TwitchRecoverySuite` poll and `pollStream` tests pass with the same assertions through the new signatures.
- The new `TokenProvider.broadcaster` tests pin behaviour that already exists, so they passed against the current code: `TwitchAuthSuite` has 23 tests, up from 18. To check that they detect regressions, I made two temporary mutations to `TokenProvider.scala` and reverted each one afterwards (`git diff` shows no change to that file):
  1. Dropping the owner/channel filter (`auth.view.usable.filter(_.scopes.contains(scope))`) failed "the broadcaster token provider supplies a scoped token only for the configured channel's own grant".
  2. Making `reject` ignore which token it got (`auth.rejectAccessToken()`) failed "rejecting a token through the broadcaster provider withholds only that grant" and "a stale token's rejection leaves a newer grant usable".

### Validation (run from `twitch-screen-relay/`)

| Command | Result |
|---|---|
| `./mill --no-daemon test.testOnly 'twitchscreen.relay.twitch.TwitchAuthSuite'` | SUCCESS. 23 tests, 0 failed, up from 18. |
| `./mill --no-daemon test.testOnly 'twitchscreen.relay.twitch.*'` | SUCCESS. 12 suites, 132 tests, 0 failed. `TwitchRecoverySuite` has 30 tests. |
| `./mill --no-daemon compile` | SUCCESS, with `-Werror` |
| `./mill --no-daemon test` | SUCCESS. 44 suites, 487 tests, 0 failed, 0 ignored. |
| `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` | SUCCESS |
| `grep -n 'import context\|pollStream(' src/twitchscreen/relay/twitch/HelixPoller.scala test/src/twitchscreen/relay/twitch/TwitchRecoverySuite.scala` | No `import context`. The only hits are the new 3-parameter definition (HelixPoller:56), the call in `poll` (HelixPoller:46) and the two 3-argument test calls (TwitchRecoverySuite:254, :285). |

## K-045: Config reader boilerplate duplicated 9x

- Severity: Medium-smell. Area: relay-twitch.
- **Disposition: fixed.** It closes the `[partial]` gap. `HttpAuthConfig` now validates in its constructor, and its reader is the shared `ValidatedConfigReader.derivedValidated[HttpAuthConfig]`. No site uses the hand-built `.map(_.tap(_.validate()))` form any more.

### Change

- `twitch-screen-relay/src/twitchscreen/relay/config/HttpAuthConfig.scala`
  - The case-class body now calls `validate()` first, and `validate` is `private`. This is the `DeviceLinkConfig` pattern. The default parameter values and the `http.auth...` require messages are unchanged.
  - The reader is `given ConfigReader[HttpAuthConfig] = ValidatedConfigReader.derivedValidated[HttpAuthConfig]`. It is still strict (unknown keys are rejected and JDK system properties are tolerated), and the constructor's `IllegalArgumentException` becomes a `CannotConvert` at the cursor path.
  - `import ox.{computeIntensive, tap}` is now `import ox.computeIntensive`.
  - Scaladoc: construction rejects an invalid or method-less credential set, so every instance is usable, and the reader reports the rejection as a `CannotConvert` at `http.auth`. The old wording, "required at server startup", is gone.
- `twitch-screen-relay/src/twitchscreen/relay/config/Config.scala`: `HttpConfig` no longer has the `auth.validate()` body line. It is now `final case class HttpConfig(host: Hostname, port: Port, auth: HttpAuthConfig)`.
- `twitch-screen-relay/src/twitchscreen/relay/http/ManagementAuth.scala`: `config.validate()` is removed. The doc comment never claimed that the class validates its config, so it needed no change.
- `twitch-screen-relay/test/src/twitchscreen/relay/config/ConfigSuite.scala`
  - Added a shared `ValidToken` constant.
  - "HTTP config cannot construct a readiness bypass with absent authentication" becomes "K-045: HTTP auth config cannot be constructed without a complete credential method". `HttpAuthConfig()` throws with "requires Basic credentials or an API token". `HttpAuthConfig("operator", apiToken = valid)` throws with "requires both Basic username and password hash".
  - New positive control, "K-045: a complete credential method constructs", builds from a token only. ConfigSuite has no password hash, so the Basic triple is covered by ManagementAuthSuite's `auth` and by TraceIdMdcSuite.
  - New "K-045: invalid http.auth in HOCON is a CannotConvert scoped to http.auth, returned rather than thrown". It loads each case over `ConfigFactory.load()` and pattern-matches `ConvertFailure(CannotConvert(_, _, msg), _, "http.auth")`. The cases are an empty api-token ("requires Basic credentials or an API token"), a 16-byte token ("at least 32 bytes"), and `basic-username = "operator"` with the token cleared ("requires both Basic username and password hash").
  - "configured credentials reject whitespace usernames and non-header-safe bearer tokens" no longer calls `.validate()`, so the constructor now throws.
- `twitch-screen-relay/test/src/twitchscreen/relay/http/ManagementAuthSuite.scala`: "startup rejects absent or incomplete credentials..." becomes "absent or incomplete credentials cannot be constructed and password verification rejects a wrong password". It asserts `intercept[IllegalArgumentException](HttpAuthConfig(...))` directly, without `ManagementAuth(...)`. The `PasswordVerifier` assertions are unchanged.

### Red, then green

- Red first: with the tests changed and production untouched, 4 tests failed. Three were in ConfigSuite: the K-045 constructor test and the whitespace/token test ("body evaluated successfully"), plus the HOCON test (no `CannotConvert` at `http.auth`). The fourth was ManagementAuthSuite's construction test.
- The HOCON red was a real defect, not just test scaffolding. A probe of the pre-change code showed that the old reader, `ValidatedConfigReader.strict(ConfigReader.derived[HttpAuthConfig].map(_.tap(_.validate())))`, reported `ConvertFailure@http.auth:ExceptionThrown`. PureConfig's `ConfigReader.map` catches the exception itself and wraps it as `ExceptionThrown`, so `ValidatedConfigReader.apply` never saw it and it never became `CannotConvert`. `derivedValidated` fixes this because the constructor throws inside `apply`'s try.
- Green after the production change: ConfigSuite 43 tests, ManagementAuthSuite 14 tests, 0 failed.
- Mutation check, then reverted: I replaced the body call with `if sys.props.contains("k045.mutant.never.set") then validate()`. Deleting the call outright trips `-Werror` because the private method becomes unused. The mutant failed 5 tests. Four were in ConfigSuite: the K-045 constructor test, "missing management credentials fail configuration loading with an auth path", the K-045 HOCON test, and the whitespace/token test. The fifth was ManagementAuthSuite's construction test. I restored the file from a scratch copy, and `git diff` shows only the intended change.

### Validation (run from `twitch-screen-relay/`)

| Command | Result |
|---|---|
| `./mill --no-daemon test.testOnly twitchscreen.relay.config.ConfigSuite twitchscreen.relay.http.ManagementAuthSuite` | SUCCESS. ConfigSuite 43 tests (up from 41), ManagementAuthSuite 14 tests, 0 failed. |
| `./mill --no-daemon compile` | SUCCESS, with `-Werror` |
| `./mill --no-daemon test` | SUCCESS. 45 suites, 497 tests, 0 failed. This change adds +2. The rest of the difference from the 487 recorded under K-043 comes from other lane commits already on this branch. |
| `./mill --no-daemon mill.scalalib.scalafmt/` then `mill.scalalib.scalafmt/checkFormatAll` | SUCCESS |
| `grep -rn "validate()" src/.../HttpAuthConfig.scala src/.../Config.scala src/.../ManagementAuth.scala test/src` | Only the private constructor-body calls and definitions: HttpAuthConfig:20/22 and Config:27/29, 119/122 (DeviceLinkConfig and TwitchConfig). There are no hits in HttpConfig, ManagementAuth or tests. `grep -rn "tap(_.validate" src` finds nothing. |
| K-016 tests (unknown keys under `http.auth` rejected, `http.auth.preference` system property tolerated) | Green in the ConfigSuite run above. |

## K-100 (Low, relay-twitch): Sensitive.Empty sentinel instead of Option

Retention in `tasks/review-kimi-root.md:44` ("would change HOCON default semantics") was wrong: a reader that maps only the exact `""` to `None` keeps `application.conf`'s `= ""` / `${?ENV}` defaults working unchanged. `application.conf` is untouched.

### Change

- `config/Sensitive.scala`: deleted `val Empty`. Added `Sensitive.optionalReader: ConfigReader[Option[Sensitive]]` = `ConfigReader[String].map(raw => Option.when(raw.nonEmpty)(Sensitive(raw)))`. Only the exact empty string is absent, so a whitespace-only value reaches validation and is rejected. `given ConfigReader[Sensitive]` and `isSet` stay. `twitch.client-secret` and `eventSub.secret` stay plain `Sensitive`, and `isSet` still validates them.
- `config/HttpAuthConfig.scala`: `basicPasswordHash: Option[Sensitive] = None`, `apiToken: Option[Sensitive] = None`. `object HttpAuthConfig` declares `private given ConfigReader[Option[Sensitive]] = Sensitive.optionalReader` before the `derivedValidated` given, so derivation picks it instead of pureconfig's generic option reader. The second mutation below proves this. `validate()` now uses `forall`/`isEmpty`/`isDefined`, and every message text is unchanged. A programmatic `Some(Sensitive(""))` or `Some(Sensitive("  "))` is rejected as "cannot be whitespace". Scaladoc updated.
- `http/ManagementAuth.scala`: Bearer: `config.apiToken.exists(expected => equal(token, expected.value))`. `checkBasic`: `config.basicPasswordHash match { case Some(hash) if encoded.length <= 2048 => <tryAcquire / verify / finally release, as before>; case _ => unauthorized }`. The order is unchanged: an absent hash or an oversized header is rejected before the semaphore is touched.
- ConfigApi masking is path-based, so it is unaffected. `HttpAuthConfig.toString` renders `Some(***)`, and a test pins this.

### Tests

- ConfigSuite: call sites now wrap values in `Some`. `liveTwitchConfig` uses `Sensitive("")`. Added a well-formed `ValidHash` constant. New tests:
  - "K-100: an empty HOCON auth secret reads as None and a whitespace-only one is rejected": `basic-password-hash = ""` becomes `None`. `api-token = "   "` is a CannotConvert at `http.auth` naming api-token. A whitespace hash with a username gives "basic-password-hash cannot be whitespace".
  - "K-100: whitespace-only secrets and a Basic password hash without a username cannot be constructed": a blank token, a blank hash, an empty `Some(Sensitive(""))` hash, and a valid hash with no username ("requires both Basic username and password hash").
  - "K-100: an optional auth secret stays masked when the config is rendered".
  - The K-045 HOCON test (`api-token = ""` gives "requires Basic credentials or an API token") is unchanged and green.
- ManagementAuthSuite: `withServer` takes `credentials: HttpAuthConfig = auth`. New "K-100: a token-only config rejects Basic and a Basic-only config rejects Bearer": each config gets a 401 for the wrong method and a 200 for its own, and the handler runs once each.
- TraceIdMdcSuite and TwitchAuthSuite updated (wrapped in `Some` / `Sensitive("")`).

### Red, then green

- Red: tests changed, production untouched. `./mill --no-daemon test.compile` FAILED with 22 compile errors (type mismatches between `Option[Sensitive]` test values and the old `Sensitive` fields).
- Green: `test.testOnly ConfigSuite ManagementAuthSuite TraceIdMdcSuite TwitchAuthSuite`: 46 + 15 + 2 + 23 tests, 0 failed (ConfigSuite +3, ManagementAuthSuite +1).
- Mutation 1 (reader maps `raw.trim.nonEmpty` to Some): 1 ConfigSuite failure, the K-100 HOCON whitespace test ("got requires Basic credentials or an API token"). Reverted from a scratch copy.
- Mutation 2 (deleted the `private given` so pureconfig's default option reader is used): 10+ ConfigSuite failures, including the K-045 HOCON test (`""` became `Some`, giving the "ASCII Bearer token alphabet" error), the K-100 HOCON test, and the shipped-config load tests. Reverted from a scratch copy. `grep` confirms both originals are back.

### Validation (from `twitch-screen-relay/`)

| Command | Result |
|---|---|
| `./mill --no-daemon test.testOnly twitchscreen.relay.config.ConfigSuite twitchscreen.relay.http.ManagementAuthSuite twitchscreen.relay.http.TraceIdMdcSuite twitchscreen.relay.twitch.TwitchAuthSuite` | SUCCESS: 46/15/2/23 tests, 0 failed |
| `./mill --no-daemon compile` | SUCCESS (`-Werror`) |
| `./mill --no-daemon test` | SUCCESS: 45 suites, 502 tests, 0 failed (up from 497, +5), on two consecutive runs. An earlier run on the same tree reported 1 failure that I did not capture and that did not recur; it is presumably an existing timing-sensitive test, not this change. |
| `./mill --no-daemon mill.scalalib.scalafmt/` then `mill.scalalib.scalafmt/checkFormatAll` | SUCCESS |
| `grep -rn 'Sensitive.Empty' src test` | no output |
| `grep -rn 'basicPasswordHash.isSet\|apiToken.isSet\|apiToken.value.isEmpty\|basicPasswordHash.value.isEmpty' src` | no output |
| `git status --short` | 7 Scala files; `application.conf` unchanged |

## K-101 (Low, relay-twitch): Config section registries restated 4x (ConfigApi Shotgun Surgery)

- **Disposition: fixed.** This closes both `[partial]` gaps. `Config.Sections` is now derived from the `Config` case class, and the startup log is built from `Sections.zip(config.productIterator)`. No hand-written list of section names is left in `Config.scala` or `ConfigSuite.scala`.

### Change

- `twitch-screen-relay/src/twitchscreen/relay/config/Config.scala` (`object Config`)
  - `val Sections: List[String] = ConfigKeys.of[Config]`. This is compile-time (inline `Mirror.ProductOf` with `constValueTuple` of `MirroredElemLabels`), not reflection. It goes through the same `ConfigFieldMapping(CamelCase, KebabCase)` that pureconfig uses to read the keys. Declaration order is unchanged: http, device-link, twitch, notifications, bus, stats, activity, alerts, observability. The new scaladoc says that adding a field adds a section to `/config` and to the startup log.
  - New pure `private[config] def render(config: Config): String`. It checks `require(Sections.sizeIs == config.productArity, ...)`, then writes the header `Relay configuration:` and one line per section: two spaces, then `<section>:` padded to a common width (the longest name plus 2, which is column 15, the same value column as before), then `value.toString`. `def log(config: Config): Unit = logger.info(render(config))`, and its signature is unchanged for `Main` and `ManagementRoutesSuite`.
  - **Log text change (intentional):** labels are now the kebab-case HOCON section names (`http:`, `device-link:`, ...) instead of the prose labels (`HTTP:`, `Device link:`, ...). This is the only way to derive them without a second registry, and it matches both `/config` and `application.conf`. The rendered values (each section's `toString`, with `Sensitive` masked) and the order are byte-for-byte unchanged. `grep -rn "Device link:"` found only the old `Config.scala`, so nothing depends on the old labels.
- `ConfigApi.scala` is unchanged. `flatten` already consumes `Config.Sections`, so `GET /config` output is identical.
- `SchemaPaths` (from K-016) is left as is. It lists leaf keys per nested section, not a section registry, and the existing K-016 test "the schema lists every shipped key" already guards it (see the proof experiment below).

### Tests (`ConfigSuite.scala`, 3 new, 1 rewritten)

- Rewritten: "the rendered configuration is limited to the relay's own sections, so system properties cannot leak". The literal `Set(...)` is replaced with `assertEquals(sections, Config.Sections.toSet)`, plus an assertion that no JDK property root (`java`, `user`, `os`, `file`, `line`) appears.
- "K-101: Config.Sections is exactly Config's fields, kebab-cased the way pureconfig reads them". This is an independent oracle: runtime `productElementNames` on a loaded instance, mapped through `ConfigFieldMapping(CamelCase, KebabCase)`, compared with the compile-time Mirror list. It also checks that there are no duplicates.
- "K-101: every Config section appears in GET /config". Every entry of `Config.Sections` has at least one `section.` leaf in `ConfigApi.flatten(ConfigFactory.load())`. All nine sections have leaves in the shipped `application.conf`.
- "K-101: the startup log renders every section, in declaration order, with secrets masked". This loads the config with `twitch.client-secret`, `twitch.event-sub.secret` and the api-token set. It asserts the header, one line per section in order, that each line starts with `  <section>:` and ends with that section's `toString`, that no raw secret appears, and that `***` does.

### Red, then green

- Red: with the tests written and production untouched, `./mill --no-daemon test.compile` FAILED with `ConfigSuite.scala:244:24 value render is not a member of object twitchscreen.relay.config.Config`. T1 and T2 pass against the old literal because it happened to agree with the fields today. The red proof for those is the dummy-field experiment below.
- Green after the production change: `test.testOnly 'twitchscreen.relay.config.*'` passes ConfigSuite 49 tests, 0 failed (46 before, plus 3).

### Proof experiment (not committed; `Config.scala` and `application.conf` restored from scratch copies, and afterwards `grep -c extra` gives 0 in both and `git status --short` shows only `Config.scala` and `ConfigSuite.scala`)

1. I added a dummy field `extra: ObservabilityConfig` to `Config` and `extra { log-buffer-size = 500 }` to `application.conf`, with derived `Sections`.
   - With `SchemaPaths` also extended by `leaves("extra", ...)`, ConfigSuite passed 49/49. `Sections`, `/config` (the "every section appears" test) and the startup log (the render test: one line per section, ending in its `toString`) all picked up `extra` with no other edit, and T1 still held.
   - With `SchemaPaths` not extended, the only failure was the existing K-016 test "the schema lists every shipped key, and only the known secrets are masked". So the leaf schema cannot drift silently either. `extra.*` still appears in `/config`, masked as an unknown path.
2. With the same dummy field, I reverted `Sections` to the old hand-written literal. ConfigSuite FAILED with 2 of 49: "K-101: Config.Sections is exactly Config's fields..." (ComparisonFailException at ConfigSuite.scala:229) and "K-101: the startup log renders every section..." (`requirement failed: Config.Sections List(http, ..., observability) does not match Config's 10 fields`). Before this change, a tenth section would have compiled, loaded, passed every test, and been missing from `GET /config`.

### Validation (run from `twitch-screen-relay/`)

| Command | Result |
|---|---|
| `./mill --no-daemon test.testOnly 'twitchscreen.relay.config.*'` | SUCCESS. ConfigSuite 49 tests, 0 failed |
| `./mill --no-daemon test.testOnly 'twitchscreen.relay.http.*'` | SUCCESS. ApiSuite 28, TraceIdMdcSuite 2, ManagementRoutesSuite 5 (it calls `Config.log`), ManagementAuthSuite 15, 0 failed |
| `./mill --no-daemon compile` | SUCCESS, with `-Werror` and no warnings |
| `./mill --no-daemon test` | SUCCESS. 45 suites, 505 tests, 0 failed (502 before, plus 3) |
| `./mill --no-daemon mill.scalalib.scalafmt/` then `mill.scalalib.scalafmt/checkFormatAll` | SUCCESS |
| `grep -rn '"device-link"' twitch-screen-relay/src twitch-screen-relay/test` (from the worktree root) | One hit, `Config.scala:226 leaves("device-link", ConfigKeys.of[DeviceLinkConfig])`. This is the K-016 `SchemaPaths` leaf prefix, not a section registry. |
| K-016 masking tests and "the rendered configuration masks every secret" | Unmodified and green in the ConfigSuite run above |

## K-156 (Nit, relay-twitch): WWW-Authenticate sent on 403/503

- **Disposition: fixed (test only).** This closes the `[partial]` gap. Production already sent the challenge only on 401: in `http/ManagementAuth.scala` the 403 (cross-site) and 503 (password verification busy) rejections pass `challenge = None`, and only `ManagementRejection.unauthorized` carries `Basic realm="relay", Bearer realm="relay"`. 401 (header present) and 403 (header absent) were already tested. 503 had no test until now.

### Change

- `twitch-screen-relay/test/src/twitchscreen/relay/http/ManagementAuthSuite.scala`, test "password verification has a shared nonblocking two-request limit": `assertEquals(send().code.code, 503)` becomes `val busy = send()`, and the following are asserted on it:
  - `busy.code.code == 503` (unchanged)
  - `busy.header("WWW-Authenticate").isEmpty` (new; sttp's `header` is case-insensitive)
  - the body starts with `{"error":` (new; the same JSON error body check as the 401/403 tests)
  - The forks, the latch release and the two 200 joins are unchanged.
- No production change. `docs/reference/http-api.md:17` ("Only 401 includes WWW-Authenticate") already matches.

### Proof experiment (not committed; `ManagementAuth.scala` restored from a scratch copy)

- I changed the 503 rejection in `checkBasic` from `None` to `Some("Basic realm=\"relay\", Bearer realm=\"relay\"")`, the same value as `ManagementRejection.unauthorized`.
- `./mill --no-daemon test.testOnly twitchscreen.relay.http.ManagementAuthSuite` FAILED, 1 of 15: "password verification has a shared nonblocking two-request limit", `munit.FailException` at `ManagementAuthSuite.scala:255` (the new header assertion).
- After I restored the file, `git status --short` showed only `ManagementAuthSuite.scala` (plus this tasks file).

### Validation (run from `twitch-screen-relay/`)

| Command | Result |
|---|---|
| `./mill --no-daemon test.testOnly twitchscreen.relay.http.ManagementAuthSuite` | SUCCESS: 15 tests, 0 failed. RED (1 failed, at line 255) during the proof experiment |
| `./mill --no-daemon test.testOnly 'twitchscreen.relay.http.*'` | SUCCESS: ApiSuite 28, TraceIdMdcSuite 2, ManagementRoutesSuite 5, ManagementAuthSuite 15, 0 failed |
| `./mill --no-daemon compile` | SUCCESS (`-Werror`) |
| `./mill --no-daemon mill.scalalib.scalafmt/` then `mill.scalalib.scalafmt/checkFormatAll` | SUCCESS |
| `./mill --no-daemon test` | SUCCESS: 45 suites, 505 tests, 0 failed (count unchanged; this change adds assertions to an existing test) |
| `git status --short` | only `ManagementAuthSuite.scala` and `tasks/review-kimi-round3-relay-twitch.md` |

## K-154 (Nit, relay-core): Actor mailboxes implicitly bounded at 16, an undocumented load-bearing invariant

- **Disposition: fixed (documentation only).** This closes both `[partial]` gaps. The invariant is now stated once in `docs/reference/architecture.md` (bounded-resources table row plus an "Actor mailboxes" note with anchor `#actor-mailboxes`), and each of the 4 `Actor.create` sites has a comment pointing to it.

### Verified facts

- Ox 1.0.8 (`~/.cache/coursier/.../com/softwaremill/ox/core_3/1.0.8/core_3-1.0.8.jar`): `javap -c 'ox.channels.BufferCapacity$package$BufferCapacity$'` shows `default()` initialised with `bipush 16`. `javap -c 'ox.channels.Actor$'` shows `create(T, Option, Ox, int)` calling `BufferCapacity.newChannel(I)`, so the mailbox is a bounded channel of 16 and `ask` (send into it) blocks the caller when it is full.
- `grep -rn "Actor.create" twitch-screen-relay/src`: exactly 4 sites. No `BufferCapacity` override anywhere in the relay.
- `CredentialTransitions` (TwitchAuth) persists inside the actor: `install`, `refreshIfCurrent` and `remove` call `file.save` or `file.delete`. Network calls (`client.exchange`, `client.refresh`, `client.isValid`, `client.revoke`) run outside `ask`. The doc says this honestly: operations are short, and credential persistence is the one local file write inside an actor.
- `DeviceHub.connectedCount` reads an `AtomicInteger`, so it bypasses the mailbox (stated in the doc).

### Change

- `docs/reference/architecture.md`, "Concurrency and bounded memory":
  - New "Actor mailboxes" bullet after the DeviceHub bullet. It covers the 16-slot default, the absence of a `BufferCapacity` override, the 4 actors, and how `ask` blocks callers (EventSub/Twitch callback, Helix poller, token maintenance, HTTP and device-session threads). It says this is backpressure, not dropping, and that operations are short and non-blocking with network I/O outside. It names the token-file exception and the `connectedCount` bypass.
  - New table row: "Relay actor mailbox ... | 16 pending operations (Ox default) | `ask` blocks the calling thread until a slot frees; nothing is dropped."
- Comments only, no code change: `device/DeviceHub.scala:67-68` (existing comment extended; the ask/observe sentence kept), `twitch/TwitchAuth.scala:132-133`, `twitch/ChannelStateTracker.scala:132-133`, `twitch/TwitchRuntimeHealth.scala:31-32`.
- No test: the change has no behaviour change, and the finding says documentation only.

### Grep evidence

- `grep -rn -i mailbox` over the 4 files: hits at DeviceHub.scala:67-68, TwitchAuth.scala:132-133, ChannelStateTracker.scala:132-133 and TwitchRuntimeHealth.scala:31-32, each directly above its `Actor.create`. DeviceHub also has older hits at lines 110 and 380.
- `grep -rn -B3 "Actor.create" twitch-screen-relay/src | grep -ci mailbox`: 6, so every one of the 4 sites has a mailbox comment within 3 lines.
- `grep -rn "Actor.create" twitch-screen-relay/src | wc -l`: 4, unchanged.
- `grep -n -i mailbox docs/reference/architecture.md`: line 105 (the note, which contains "16", "`ask`" and "blocks") and line 116 (the table row).

### Validation (run from `twitch-screen-relay/`)

| Command | Result |
|---|---|
| `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` | SUCCESS (168 sources) |
| `./mill --no-daemon compile` | SUCCESS (`-Werror`; 4 sources recompiled) |
| `./mill --no-daemon test` | SUCCESS: 45 suites, 505 tests, 0 failed (count unchanged) |
| `git diff --check` | clean |
| `git diff --stat` | the 5 files above plus this tasks file |
