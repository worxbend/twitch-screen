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
