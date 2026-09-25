# Review round 2 — relay remediation (branch swarm2/relay)

## RLY-03 — rebuild the Twitch client when the application token is rejected (401)

Change (twitch-screen-relay/src/twitchscreen/relay/twitch):
- `HelixPoller`: `start`/`poll`/`pollStream` take `appTokenRejected: () => Unit`. The streams attempt (app token) calls it on `isUnauthorized`. Followers/subscribers (user token) still call only `unauthorized` → `auth.rejectAccessToken()`. `poll`/`pollStream` are `private[twitch]` for tests, and `attempt` no longer defaults the callback.
- `EventSubWebhookApi.reconcileSubscriptions`: new `appTokenRejected` parameter, called before the unchanged `observe(...)` when list/delete/create fails unauthorized. Iteration continues on purpose, so every kind is observed as failed on that pass.
- `LiveTwitchSource`:
  - `appTokenRejected(health, restart)` sets the session restart flag. It warns once per session and observes `startup` with no token text.
  - `startIngestion` wires it into the poller (webhook and WebSocket) and into `maintainSubscriptions` → `reconcileSubscriptions`.
  - The rebuild loop is extracted to `superviseSessions` (build → scoped session → close → 1 s pause). A session that throws is caught, observed on `startup`, and paused `SessionFailurePause` = 30 s.
  - `resolveBroadcasterId` throws `ApplicationTokenRejected` (no cause) on 401 instead of retrying forever against the same client and token.
- Rebuild pacing: a restart request is consumed after `maintainSubscriptions`' 30 s sleep, plus the 1 s supervisor pause. A failed session waits 30 s. This keeps rebuilds to at most about one per 30 s under permanently invalid credentials.
- Health: component failures (`streams`, `eventsub-<kind>`, `startup`) stay Some(reason) → Degraded. There are no new states.

Tests (test/src/twitchscreen/relay/twitch/TwitchRecoverySuite.scala, 6 → 14). Each uses twitch4j's `TwitchHelixErrorDecoder` 401 thrown inside a Hystrix command:
- T1 create 401 → rebuild requested, kind observed failed, app-token (`null`) credential asserted.
- T2 list 401 → rebuild requested, no create calls, all kinds observed failed.
- T3 existing non-auth create failure → no rebuild.
- T4 `poll` with a streams 401 → rebuild requested, user grant not rejected, streams failing, Degraded.
- T4b user-token 401 → grant withheld, no rebuild.
- T5 non-auth streams failure → no rebuild, failure observed.
- T6 `appTokenRejected` sets the restart flag and reports why.
- T7 `superviseSessions` end-to-end with a fake client factory: a streams 401 ends session 1, both clients are closed, build #2 happens, pause = 1 s.
- T8 `resolveBroadcasterId` 401 → `ApplicationTokenRejected` (no cause) → session fails → 30 s pause → build #2.
- The test Hystrix commands disable the circuit breaker; before that the extra scripted failures could open the shared breaker and hide the 401 cause, depending on test order.
- Mutation check: disabling the streams and reconcile hooks fails 4 of the new tests.

Validation (run from twitch-screen-relay):
- `./mill --no-daemon test.testOnly twitchscreen.relay.twitch.TwitchRecoverySuite` → 14/14 pass (repeated 5× with the focused set, stable).
- `./mill --no-daemon test.testOnly twitchscreen.relay.twitch.TwitchRecoverySuite twitchscreen.relay.twitch.TwitchAuthSuite twitchscreen.relay.twitch.EventSubWebhookSuite twitchscreen.relay.twitch.ChannelStateTrackerSuite twitchscreen.relay.twitch.WebhookDeduplicationSuite` → 14 + 12 + 13 + 11 + 2 = 52, 0 failed.
- `./mill --no-daemon compile` (`-Werror`) → SUCCESS.
- `./mill --no-daemon test` → 27 suites, 324 tests (baseline 316), 0 failed, 0 ignored on two consecutive runs. One earlier full run hit a timing failure in `device.LifecycleOrderingSuite` (the device read got None). That code is outside the twitch package and passes 4/4 alone, so this is a pre-existing load-sensitive flake that this change did not touch.
- `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` → SUCCESS; `git diff --check` clean.

Residual:
- No live-Twitch acceptance: the proof uses scripted Helix adapters.
- A 401 on the chat join or on WebSocket `register` uses the user credential and keeps its existing handling (retry, or the subscription-failure rebuild).

## RLY-11 — extract and test WebSocket EventSub registration decision

Change (twitch-screen-relay/src/twitchscreen/relay/twitch), behavior-preserving:
- New `WebSocketRegistration.step` (with `enum WebSocketStep`: `Await` / `Unchanged` / `Restart` / `Connected(grant)`) holds the WebSocket case of `LiveTwitchSource.maintainSubscriptions` unchanged.
  - The grant is the broadcaster-owned token while `moderator:read:followers` is granted.
  - A registered grant that changes or is lost → `Restart`, with no registration.
  - A new grant registers every subscription in order and never short-circuits (`map` then `forall`). Each one is observed as "awaiting subscription confirmation" before its `register` call, and as "registration rejected; rebuilding connection" when `register` returns false.
  - All accepted → `connect(grant)`, then `Connected`. Any rejection → `Restart`.
  - No grant → `eventsub-connection` is observed as "awaiting broadcaster authorization and follow scope", on the restart path too.
  - A missing credential → `Unchanged` (no-op).
- `register`, `connect` and `observe` are thin callbacks. The per-kind "awaiting" observation must precede that kind's `register` call, so it cannot be a pure-data return.
- `connect` receives the grant. The caller records `registeredGrant` before calling `client.getEventSocket.connect()`, which keeps the original order: a throwing `connect()` still leaves the grant recorded and is not re-registered on the next pass.
- `maintainSubscriptions` only maps `Restart` to `restartRequested.set(true)`. The webhook branch, the authorization/chat observations, the missing-scope computation and try/catch/sleep are untouched.
- The Scaladoc on `step` records the RLY-11 contract and why a rejection needs a full client rebuild (Twitch4J may discard permanently failed subscriptions).

Tests (new test/src/twitchscreen/relay/twitch/WebSocketRegistrationSuite.scala, 8 tests):
- No Ox, TwitchClient or Helix proxy.
- A single interleaved event log records observations, register calls and connect.
1. No broadcaster token → `Await`, no register/connect, only the awaiting eventsub-connection observation (AC1).
2. Missing `moderator:read:followers` → the same (AC1).
3. `channel.update` rejected (AC2):
   - All 4 kinds are registered in order, with each awaiting observation before its register.
   - The rejection is observed.
   - No connect. Returns `Restart`.
4. All accepted → connect once with grant "a", `Connected("a")`, no awaiting-grant observation (AC3).
5. Token b vs registered a → `Restart`, no register/connect/observation (AC4).
6. Follow scope lost / token lost after registration → `Restart` plus the awaiting observation (AC4).
7. Same grant after registration → `Unchanged`, empty log (AC4).
8. Credential None → `Unchanged`, empty log (AC5).

Red → green: the suite first failed to compile (no `WebSocketStep`), then passed 8/8.

Mutation check (each reverted; file verified byte-identical afterwards):
- `forall(identity)` → `forall(_ => true)`: test 3 fails.
- Restart-after-registration branch disabled (`if false`): tests 5 and 6 fail.
- Short-circuit (`subscriptions.view.map` so `forall` stops at the first false): test 3 fails (register count/log).

Validation (run from twitch-screen-relay):
- `./mill --no-daemon test.testOnly twitchscreen.relay.twitch.WebSocketRegistrationSuite` ×3 → 8/8 each time.
- `./mill --no-daemon test.testOnly twitchscreen.relay.twitch.WebSocketRegistrationSuite twitchscreen.relay.twitch.TwitchRecoverySuite twitchscreen.relay.twitch.TwitchAuthSuite twitchscreen.relay.twitch.EventSubWebhookSuite twitchscreen.relay.twitch.ChannelStateTrackerSuite twitchscreen.relay.twitch.WebhookDeduplicationSuite` → 8 + 14 + 12 + 13 + 11 + 2 = 60, 0 failed.
- `./mill --no-daemon compile` (`-Werror`) → SUCCESS.
- `./mill --no-daemon test` → 28 suites, 332 tests (baseline 27/324), 0 failed on two consecutive runs.
  - One earlier run hit the known load-sensitive `device.LifecycleOrderingSuite` flake. It passed alone (1/1).
- `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` → SUCCESS; `git diff --check` clean.

Residual:
- No live-Twitch proof: the decision is tested with fake callbacks.
- The asynchronous `EventSocketSubscriptionFailureEvent` handling (`subscriptionFailed`) is covered separately in TwitchRecoverySuite and is unchanged.
- User-request note: the "always commit and push to main" agent instruction already exists in AGENTS.md (lines 3-13, commit 0e69b89). It was not duplicated here.
- Not committed in this step, per the step instruction. Commit/rebase/push is left to integration.
