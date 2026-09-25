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

## RLY-08 — push grace hysteresis and pushed-end started_at guard

Change (`twitch/ChannelStateTracker.scala`, `twitch/LiveTwitchSource.scala`; no protocol, config-schema or event-shape change):
- The grace is now `max(pushGrace (60 s), 3 × pollInterval)`. `LiveTwitchSource` passes `config.pollInterval`, so the default 30 s poll gives a 90 s grace. The factory default `pollInterval = Duration.Zero` keeps a 60 s grace for existing call sites.
- `observedOffline` while Live and inside the grace resets `absentPolls` to 0 and returns None. A poll-driven END needs 2 consecutive absences, both after the grace.
- `wentOffline` (EventSub push) records `pushedEndStartedAt` = the ended stream's start. After that, a poll-live (`observedLive`) whose `started_at` is the same or earlier is ignored, even after the grace. A strictly newer `started_at` announces START. A push (`wentLive`) is never filtered.
- The Scaladoc on `ChannelTrackerState` records the contract. Publication ordering is still owned by the actor and is unchanged.

Tests (`ChannelStateTrackerSuite`, 11 → 16; `Movable` clock hoisted to suite level; existing assertions unchanged):
- T1: poll 30 s → absences at +5/+35/+65 are None, +95 is None, +125 → `StreamEnded(125 s)`, then None (brief sequence a).
- T1b: poll 5 s → the grace stays 60 s: +30 None, +61 None, +66 → `StreamEnded(66 s)`.
- T2: `wentLive(S)`, `wentOffline`, then at +120 `observedLive(S)` → None and `observedLive(S − 10 s)` → None (brief sequence b).
- T3: same setup, `observedLive(t0 + 100)` at +120 → `StreamStarted(..., Some(t0 + 100))`.
- T4: an absence inside the grace (+80) and a poll-live (+85) do not count; END comes at +125 after absences at +95 and +125.
- Red → green: the suite first failed to compile (no `pollInterval` parameter), then passed 16/16.

Mutation check (source restored and verified byte-identical with `cmp` after each):
- Reverting (c) to the old accumulate-inside-grace `observedOffline`: T1 and T1b fail.
- Neutralising the started_at guard (`endedByPush` always false): T2 fails.

Validation (run from twitch-screen-relay):
- `./mill --no-daemon test.testOnly twitchscreen.relay.twitch.ChannelStateTrackerSuite` → 16/16.
- `./mill --no-daemon test.testOnly ...ChannelStateTrackerSuite ...EventSubWebhookSuite ...TwitchRecoverySuite ...WebSocketRegistrationSuite ...WebhookDeduplicationSuite` → 16 + 13 + 14 + 8 + 2 = 53, 0 failed.
- `./mill --no-daemon compile` (`-Werror`) → SUCCESS.
- `./mill --no-daemon test` → 28 suites, 337 tests (was 332), 0 failed on two consecutive runs.
  - Two earlier runs hit the known load-sensitive `device.LifecycleOrderingSuite` flake. It passed alone (1/1).
  - The flake also reproduced on the unmodified baseline (1 of 2 full runs). That suite does not use the tracker.
- `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` → SUCCESS; `git diff --check` clean.

Residual:
- A poll-live with no `started_at` is not filtered by the pushed-end guard. It is still suppressed only inside the grace.
- The optional 2-consecutive-live-polls rule after a pushed offline was not implemented.
- There is no live-Twitch acceptance: the behavior is proven with a movable clock only.

## RLY-43 — prove the EventSub webhook callback is reachable after bind

Change (behavior unchanged; one production refactor, the rest is tests):
- `Dependencies.serve()(using Ox): NettySyncServerBinding = httpApi.start(_ => twitch.startIngestion())`, with Scaladoc covering the RLY-43 ordering. `Main.run` now calls `dependencies.serve().discard` instead of inlining the same wiring.
- New shared test helper `test/src/twitchscreen/relay/twitch/EventSubSigning.scala` (`sign`, `headers`). It is now the only HMAC signing implementation in the test sources:
  - `EventSubWebhookSuite.sign` delegates to it. The unused `Mac`, `SecretKeySpec` and `UTF_8` imports were dropped.
  - `ManagementRoutesSuite` test 2 uses `EventSubSigning.headers(secret, "delivery", ...)` and keeps the forged-signature assertion.
- `ManagementRoutesSuite.withServer` afterBind hook, after the /health and OAuth-callback asserts:
  - POSTs an unauthenticated, signed `webhook_callback_verification` (message id `after-bind`, challenge `after-bind-challenge`) to `/api/v1/twitch/eventsub`.
  - Asserts 200 and the echoed challenge, with the clue "EventSub webhook callback verifies when ingestion is allowed to start".
  - An `AtomicBoolean afterBindRan` is asserted right after `startOnPort` returns, so the hook cannot be skipped silently.
  - Each `withServer` call builds a fresh live source, and the ids differ ("after-bind" vs test 2's "delivery"), so deduplication cannot clash.
- New `test/src/twitchscreen/relay/StartupOrderSuite.scala` (1 test):
  - The HTTP port comes from a closed `ServerSocket(0)`, because `Port` must be 1..65535.
  - A recording `TwitchSource` (no endpoints) opens a TCP connection to the port inside `startIngestion`, then GETs `/api/v1/health`.
  - `Dependencies(HttpApi(List(HealthApi(), recorder), ...), hub, recorder).serve()` is run inside `supervised`.
  - Asserts `calls == 1` and `Observation(connected = true, healthStatus = Some(200))`.

Red → green: StartupOrderSuite first failed to compile ("value serve is not a member of twitchscreen.relay.Dependencies"). After `serve` was added, it passed 1/1.

Mutation checks (sources restored and verified with `cmp` after each):
- Hook expects `"wrong-challenge"` → ManagementRoutesSuite 4/4 fail at the new body assertion with the RLY-43 clue.
- `twitch` removed from `apis` (replaced by a second `HealthApi()`) → 4/4 fail inside the afterBind hook. The first hook assertion to trip is the OAuth-callback check, before the EventSub POST.
- `serve()` rewritten to `twitch.startIngestion(); httpApi.start()` → StartupOrderSuite fails with `healthStatus = None` (connection refused, so no HTTP). This is the negative control.

Validation (run from twitch-screen-relay):
- `./mill --no-daemon test.testOnly twitchscreen.relay.StartupOrderSuite twitchscreen.relay.http.ManagementRoutesSuite twitchscreen.relay.twitch.EventSubWebhookSuite` ×4 → 1 + 4 + 13, 0 failed each time.
- `./mill --no-daemon compile` (`-Werror`) → SUCCESS. The first attempt caught an unused `UTF_8` import in EventSubWebhookSuite, which was fixed.
- `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` → SUCCESS; `git diff --check` clean.
- `./mill --no-daemon test` → 29 suites, 338 tests (was 28/337), 0 failed on runs 1, 3 and 4 (runs 3 and 4 consecutive).
  - Run 0 hit the known `device.LifecycleOrderingSuite` flake, which passed alone (1/1).
  - Run 2 had one `device.DeviceLinkSuite` failure, which passed alone 3/3 (37/37). It is load-sensitive, uses its own port-0 relay and does not touch the changed code.

Residual:
- No live Twitch: the proof is a local signed verification plus a recording source.
- `StartupOrderSuite` picks the port with a close-then-bind step, so there is a small TOCTOU window. A collision would fail this suite at bind, not pass it silently.
- Async token maintenance started in `LiveTwitchSource.create` may still run before binding, as review-security.md already notes. Only `startIngestion` is ordered after bind.

## PROTO-03 — STATS overflow is lossy-but-connected

Change (test only, no production code): `DeviceBackpressureSuite` gets a new test, "STATS overflow is counted and dropped without closing the connection or skipping a seq". It sits right after the unchanged EVENT-overflow test, so EVENT drops and STATS drops are now covered separately.
- Setup mirrors the EVENT-overflow case. `Channel.buffered[Outbound](2)` is filled by the undrained greeting (WELCOME + STATS), then `hub.broadcastStats(StreamStats.Unknown)` is called.
- Checks after the drop:
  - AC1: `counters.traffic.framesDropped == 1`.
  - AC2: `hub.links.size == 1`, the transport is not closed, and `connectedCount == 1`. `links` is an actor ask, so it is ordered after the tell. The link is never detached, so no `DeviceDisconnected` is published.
- AC3: after WELCOME is drained, `hub.publish(Follow)` returns seq 1. The queue delivers the greeting STATS, then `RelayMessage.Event` with `seq == 1`, so there is no gap. At the end the transport is still open, one link remains and `framesDropped` is still 1.

Mutation check (not committed; the source was restored and `git status` shows only the test changed): the `case _ => ()` branch in `DeviceHubState.send` was changed to detach and close as the Event case does. The new test then fails at `hub.links.size` (DeviceBackpressureSuite.scala:115), and the other 5 still pass.

Validation (run from twitch-screen-relay):
- `./mill --no-daemon test.testOnly twitchscreen.relay.device.DeviceBackpressureSuite` ×4 → 6/6, 0 failed each time.
- `./mill --no-daemon test.testOnly twitchscreen.relay.device.DeviceBackpressureSuite twitchscreen.relay.device.DeviceLinkSuite` → 6 + 37, 0 failed.
- `./mill --no-daemon compile` (`-Werror`) → SUCCESS.
- `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` → SUCCESS; `git diff --check` clean.
- `./mill --no-daemon test` → 29 suites, 339 tests (was 338), 0 failed on runs 2 and 3 (consecutive).
  - Run 1 hit the known load-sensitive `LifecycleOrderingSuite` (1) and `DeviceLinkSuite` (1) flakes. Re-run alone, they passed: 1/1 and 37/37.

Residual:
- STATS is still lossy by design. A dropped snapshot is only replaced by the next STATS broadcast.
- RLY-16 (greeting capacity) is separate and unchanged here.

## PROTO-14 / PROTO-16 — no BYE 4/6; REPLAY ignored on device frames

Changes (PROTO-14):
- `protocol/ProtocolError.scala`:
  - The unused `InvalidSequence` variant is removed, along with its `describe` and `byeAdvice` cases. A repo-wide grep found no other user.
  - `byeAdvice` is now an exhaustive match with no wildcard. The seven non-fatal and closed-stream variants each have an explicit `None` arm, so under `-Werror` a new variant will not compile until someone chooses its BYE.
  - The Scaladoc says codes 4 and 6 are reserved in §6.7 and no variant maps to them.
- `protocol/Tsb3Message.scala`: `ByeCode.InvalidSequence` (4) and `ByeCode.FrameTooLarge` (6) are kept, with their `value`, `defined` and `fromWire` entries, for decoding and logging. The Scaladoc now marks them "MUST NOT be sent (§6.7); decoded for logging only".
- `device/DeviceSession.scala` `reasonFor`: the two outbound reason strings are gone. One non-sending arm, `case ByeCode.InvalidSequence | ByeCode.FrameTooLarge => s"reserved code ${code.value}"`, keeps the match exhaustive, still with no wildcard.

Tests:
- `ProtocolBoundarySuite`, "§6.7: no ProtocolError variant advises BYE 4 (INVALID_SEQUENCE) or 6 (FRAME_TOO_LARGE)":
  - Builds one sample for each of the 14 remaining variants.
  - Completeness guard: a `Mirror.SumOf` + `constValue[Tuple.Size[...]]` case count is checked against the samples' ordinals. It compiled, so no fallback match was needed.
  - Asserts that no `byeAdvice` gives code 4 or 6.
  - Asserts that `ByeCode.fromWire(4|6)` still decodes to the named codes and `.value` gives 4 and 6.
- `DeviceLinkSuite`, "§3.2: REPLAY on a device PING or ACK is ignored — answered, recorded, no BYE, nothing skipped":
  - After the handshake and seq 1, it sends a PING and an ACK(1) with flags=0x01 via `sendBytes` + `WireBytes.withHeader` (hchk recomputed).
  - Asserts: PONG(77) is returned, and a plain PING gets PONG(78), which orders the checks and shows the link is up. One link remains, `ackedSeq == 1` and `framesReceived == 4`.
  - Asserts that all skip counters (skipped, unknown type, wrong direction, short payload, invalid field, oversize) and `resyncEvents` are 0.
  - No production change for PROTO-16: neither `DeviceSession.handle` nor `Tsb3Decoder.fromDevice` reads the flags. This is a conformance/characterisation test.

Red/green and mutation checks (sources restored from a backup after each; `git diff --stat` shows only the intended changes):
- Red: the new boundary test with an `InvalidSequence("x")` sample, run before the deletion, failed with "invalid sequence number: x advises reserved BYE Some(4)". Green after the deletion (5/5).
- `byeAdvice` changed so `FrameTimeout(_)` returns `Some((ByeCode.FrameTooLarge, ByeDetail.Zero))` → the boundary test fails with "no complete frame within 1 second advises reserved BYE Some(6)".
- `DeviceSession.handle` given `else if frame.header.flags.isReplay then duplicateHello(sink, frame)` → the REPLAY test fails at DeviceLinkSuite.scala:264 (no PONG; the other 37 still pass).

Validation (run from twitch-screen-relay):
- `grep -rn "InvalidSequence" src/twitchscreen/relay/protocol/ProtocolError.scala` → no match.
- `./mill --no-daemon test.testOnly twitchscreen.relay.protocol.ProtocolBoundarySuite twitchscreen.relay.protocol.FrameReaderSuite twitchscreen.relay.protocol.Tsb3DecoderSuite twitchscreen.relay.protocol.Tsb3GoldenVectorSuite twitchscreen.relay.device.DeviceLinkSuite` → 5 + 27 + 30 + 23 + 38, 0 failed.
- `./mill --no-daemon test.testOnly twitchscreen.relay.device.DeviceLinkSuite` ×3 → 38/38, 0 failed each time.
- `./mill --no-daemon compile` (`-Werror`) → SUCCESS.
- `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` → SUCCESS; `git diff --check` clean.
- `./mill --no-daemon test` → 29 suites, 341 tests (was 339), 0 failed on the first run, with no flake.

Residual:
- Firmware `proto_codec.h` still names codes 4 and 6. That file only receives and logs them, and it is outside the relay unit.
- The relay has no separate protocol-violation counter. For PROTO-16, the proof of "no violation" is the link staying up with no BYE and zero skip/resync counters.

## PROTO-18 — contain sequence exhaustion

Changes (all under `twitch-screen-relay/src/twitchscreen/relay/`; no wire change and no PROTOCOL.md change, since §10.1 already specifies the behaviour):
- `device/DeviceHub.scala`:
  - New typed refusal `private[relay] final case class SequenceExhausted(latest: SeqNo)`.
  - `publish(EventRequest)`, `publishTransition` and `publish(NotificationRequest)` now return `Either[SequenceExhausted, Notification]`.
  - `DeviceHubState.publish` no longer throws inside the actor. When `latestSequence.next` is `None`, it returns `Left` and mutates nothing: no seq, no replay-ring entry, no `notificationsPublished` increment, no `latestObservedStats` change, no frame.
  - The first refusal logs one error line and publishes `RelayFailure("device-hub", "sequence space exhausted at 4294967295; restart the relay")`. A `private var exhaustionReported` latches that report, and later refusals log at debug level only. The latch also stops a loop: the router turns the RelayFailure into an Alert card, which the hub refuses without reporting again.
  - Test seam: `private[relay] def startingAt(..., initialSequence: SeqNo)`. `start` delegates to it with `SeqNo.Zero`. It is `private[relay]` because `DeviceHub` is already `private[relay]` and `ApiSuite`, in the http package, uses it.
- `device/HubSnapshot.scala`: new field `sequenceExhausted: Boolean`, derived as `latestSequence.next.isEmpty`.
- `stats/StatsAggregator.scala`: `publishTransition(...) match { case Right(_) => (); case Left(_) => hub.broadcastStats(stats) }`. §6.5's STATS still lands and the supervised fork keeps running. There is no try/catch.
- `device/NotificationApi.scala`: a refusal maps to `Fail.Unavailable("TSB/3 sequence space exhausted (§10.1); restart the relay to begin a new sequence space")`, which is HTTP 503 with a `{"error": …}` body. The endpoint description documents the 503.
- `device/NotificationRouter.scala`: a comment only (the Either is discarded; the hub reports refusals itself).
- `health/StatusApi.scala`: `DeviceLink_OUT.sequenceExhausted` is a new, additive JSON field.
- `docs/reference/http-api.md`: POST /notifications documents the 503. The `deviceLink` row lists `sequenceExhausted`, and the 503 row in the status table mentions exhaustion.
- `test/.../DeviceBackpressureSuite.scala`: adapted to the Either (`.fold(refused => fail(...), identity)`).

Tests:
- New `device/SequenceExhaustionSuite` (5 tests; the hub is seeded through `DeviceHub.startingAt`):
  1. "the last u32 sequence is assigned and delivered, the next publish is refused without an EVENT or a wrap" (AC1, AC2). Seeded at 0xfffffffe:
     - The next publish returns `Right` with seq `Max`, and a TestDevice receives EVENT seq 4294967295.
     - The following publish returns `Left(SequenceExhausted(Max))`.
     - After `broadcastStats`, the next frame is STATS.
     - `latestSeq == Max`, `notificationsPublished` is unchanged, `sequenceExhausted` is true, and the newest recent notification is seq `Max`.
  2. "an exhausted hub keeps serving attach, replay, snapshot and STATS" (AC3):
     - A new device gets a WELCOME with `latestSeq == Max`, then STATS.
     - A device reconnecting with `last_seq = Max-1` gets WELCOME, then exactly the seq-Max EVENT, then STATS.
     - `links`, `snapshot` and `broadcastStats` still serve both devices.
  3. "exhaustion is reported to the operator exactly once" (AC4):
     - Setup: NotificationRouter is running, and a bus subscription is taken before the refusals.
     - Three refusals (EventRequest, NotificationRequest, publishTransition), plus one routed follow.
     - Asserts exactly one `RelayFailure("device-hub", _)` in a bounded drain, and `notificationsPublished == 1`.
  4. "a stream lifecycle transition on an exhausted hub still delivers STATS and does not end the relay scope" (AC5). Seeded at Max, with NotificationRouter and StatsAggregator running:
     - StreamStarted, then StreamEnded, produce only STATS frames: Live, then Offline. No EVENT is sent.
     - `latestStats` is Offline, and the test body completes, so the scope is intact.
  5. "POST /notifications on an exhausted hub is a documented 503" (AC6):
     - Uses TapirSyncStubInterpreter.
     - Asserts 503, a body containing `"error"` and `exhausted`, and nothing published.
- `http/ApiSuite`, "the status endpoint reports whether the device link's sequence space is exhausted" (AC7): through the real StatusApi, the flag is `false` for `SeqNo.Zero` and `true` for `SeqNo.Max`.
- `ProtocolBoundarySuite` (`SeqNo.Max.next == None`) is kept unchanged.

Red to green:
- Before the seam and the Either existed, the new suite did not compile (7 errors).
- A red build was then made with the new signatures but with the exhaustion arm still throwing. All 5 tests failed:
  - Test 4 ended the supervised scope with `IllegalStateException` out of the StatsAggregator fork. This is the crash the brief describes.
  - Test 5 got 500 instead of 503.
- With the fix, all 5 pass.
- Observation while making test 4 deterministic: the StatsAggregator's first `Flow.tick` `Publish` can be processed just after start-up. With a 1 h interval, that tick emitted an extra STATS Live right after the StreamStarted input. Test 4 therefore reads frames until the expected STATS state (bounded to 8 frames) and asserts that every frame read is STATS.
  - This is probably the source of the known `LifecycleOrderingSuite` flake. There, an extra STATS between the lifecycle EVENT/STATS pairs breaks the strict next-frame asserts.
  - That suite is not changed here; this is a note for the integration step.

Mutation checks (each applied with sed, run, then restored from a backup; `git diff --stat` confirmed the source was back each time):
- (a) `StatsAggregator` `case Left(_) => ()` (fallback removed): test 4 fails, because no STATS Offline arrives (ComparisonFail after about 5 s).
- (b) `if !exhaustionReported || true then` (latch defeated): test 3 fails because the failure count is greater than 1. The router's Alert card is refused and reported again.
- (c) The exhaustion arm wraps: `latestSequence = SeqNo.Zero; Right(sequenced(...))`. All 5 tests fail, including 1 and 2.

Validation (run from twitch-screen-relay):
- `./mill --no-daemon test.testOnly twitchscreen.relay.device.SequenceExhaustionSuite` ×3 → 5/5, 0 failed, each time.
- `./mill --no-daemon test.testOnly` over SequenceExhaustion, LifecycleOrdering, DeviceLink, DeviceBackpressure, NotificationRouter, ApiSuite, ManagementRoutes and ProtocolBoundary → 5 + 1 + 38 + 6 + 16 + 20 + 4 + 5, 0 failed.
- `./mill --no-daemon compile` (`-Werror`) → SUCCESS.
- `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` → SUCCESS; `git diff --check` clean.
- `./mill --no-daemon test` → 30 suites, 347 tests (was 29/341), 0 failed on two consecutive runs.
  - One earlier run hit the known `LifecycleOrderingSuite` flake. It passed alone (1/1).

Residual:
- Live exhaustion needs about 4.29e9 EVENTs, so it is proven only through the `startingAt` seam, not on a live relay.
- Recovery is by restart only, as §10.1 specifies. No persisted counter exists or was added.

## RLY-25 / RLY-16 — wire-unit config validation

Change:
- `config/Config.scala`, `DeviceLinkConfig.validate()`:
  - `idle-timeout` and `ping-interval` must be whole seconds (`toNanos % 1e9 == 0`), because WELCOME carries both as u16 seconds (§6.2). The 1..65535 s range checks stay.
  - Ping is checked before idle, so 1500ms/1900ms names `ping-interval`.
  - `ping < idle` is now compared in wire seconds (`toSeconds`). The comment cites §12, "MUST enforce at startup".
  - `handshake-timeout` only has to be positive. It is not a wire value, so sub-second values stay allowed.
- The literal `18` is gone. `DeviceLinkConfig.ChatReplaySize = 16` is now the single source of truth. `GreetingFrames = ChatReplaySize + 2` covers WELCOME, the chat ring and STATS (§11.1). The capacity message names the breakdown.
- `device/DeviceHub.scala`: `DeviceHubState.ChatReplaySize = DeviceLinkConfig.ChatReplaySize`.
- `NotificationsConfig`: `default-ttl` must be a multiple of 100 ms within 100..6553500 ms (u16 deciseconds, where 0 means the device default).
- `resources/application.conf` is unchanged (5s / 90s / 20s / 128 >= 64+18 / 8s) and still loads.

Tests:
- `ConfigSuite` gains 7 tests:
  - ping values 0, -1s and 500ms are rejected with `ping-interval`.
  - 1500ms/1900ms is rejected with `ping-interval`, 2s/2500ms with `idle-timeout`, and an idle of 0 or -1s with `idle-timeout`.
  - a handshake of 0 or -1s is rejected, and 300ms is accepted.
  - default-ttl values 50ms, 0, 150ms and 6553600ms are rejected; 100ms, 8s and 6553500ms are accepted.
  - capacity replay+17 is rejected with `outbound-queue-capacity`, replay+18 is accepted, and a guard asserts `GreetingFrames == 18 == ChatReplaySize + 2`.
  - zero or negative simulation, bus, stats, alerts and activity values are rejected.
  - through the reader, 1500ms/1900ms gives a `Left` that mentions ping-interval.
- `DeviceLinkSuite` gains "RLY-16: a greeting at the minimum accepted outbound capacity arrives whole":
  - Setup: replay 4, capacity 4+GreetingFrames, a baseline follow plus 5 follows and 20 chats, and last_seq=1.
  - It receives 22 frames: WELCOME, 20 REPLAY EVENTs with seqs 3..6 then 11..26 in strictly ascending order, and a trailing STATS.
- The existing fixtures are unchanged (TestRelay 5/4, ApiSuite 5/4, Backpressure 2/1, handshake 300/600ms, `NotificationsConfig(30.seconds)`). The only edit to them is the "eighteen frames" comment, which now reads GreetingFrames.

Red to green:
- The new tests first failed to compile because `GreetingFrames` did not exist.
- On the first implementation, 2 failed: the idle check ran before the ping check, so the message named the wrong key. Reordering the checks made all 26 ConfigSuite tests pass.

Mutation check:
- `GreetingFrames = ChatReplaySize + 1`: the new DeviceLinkSuite test fails at line 150, because the trailing STATS is dropped at capacity 21 and a PING arrives after about 4 s. The other 38 tests pass.
- The file was restored from a backup, and `sha256sum -c` confirmed it byte-identical.

Validation (run from twitch-screen-relay):
- `./mill --no-daemon test.testOnly twitchscreen.relay.config.ConfigSuite` → 26/26, 0 failed.
- `./mill --no-daemon test.testOnly twitchscreen.relay.device.DeviceLinkSuite twitchscreen.relay.device.DeviceBackpressureSuite` → 39 + 6, 0 failed.
- `./mill --no-daemon compile` (`-Werror`) → SUCCESS.
- `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` → SUCCESS; `git diff --check` clean.
- `./mill --no-daemon test` → 30 suites, 355 tests (was 347), 0 failed. The LifecycleOrderingSuite flake did not appear.

Residuals:
- idle > 15 s (§12) is not enforced. It is a device-side warning under §6.2, and the test fixtures rely on short timers.
- handshake-timeout can still be sub-second by design.
- NotificationApi's private `MaxTtlMillis` is not yet shared with `NotificationsConfig` (optional).
