# Kimi review round 3: relay-core remediation evidence

Per-unit evidence for REVIEW_SUMMARY.kimi.md findings in the relay core.

## feat(relay): count and expose device-link over-limit refusals, test the log cadence

Findings: **K-056** (Low) Over-limit connections refused silently (no log/counter); **K-147** (Nit) Accept-failure logging goes silent at the cap.

### Change

- `DeviceHub` owns a `refused: AtomicLong`. `recordRefusedConnection()` increments it and returns the running total without going through the actor, so the accept loop never waits on the hub mailbox. `connectionsRefused` reads it for telemetry and stays safe after the actor's scope ends. `snapshot` overlays the live value on the actor's published snapshot.
- `HubSnapshot.connectionsRefused` sits after `connectionsAccepted`. The actor-side snapshot and the initial snapshot carry 0, and the facade overlays the live count.
- `DeviceLinkServer`: the local `var refused` is gone. The over-limit branch calls `hub.recordRefusedConnection()` and warns "Device connection limit (64) reached; refused N connections" when `shouldLogRefusal(N)` is true (the 1st refusal and every 100th). Accept failures log through `shouldLogFailure(n)` (the 1st and every 10th, as a `Long` that never saturates). The failure WARN now includes the consecutive count. Both predicates are pure and `private[device]`.
- `GET /status` gains `deviceLink.connectionsRefused` (`DeviceLink_OUT`, Schema derived).
- OTel gains an observable counter `relay.device.connections.refused`, registered with `useCloseableInScope` like the connected gauge.
- `docs/reference/http-api.md`: the `deviceLink` row documents `connectionsRefused` and the metric.

### Tests (written first; `test.compile` failed with 8 errors before the fix)

- New `DeviceLinkServerLoggingSuite`. Failure cadence is true for 1, 10, 30, 40 and 1000 and false for 2, 9, 11, 31 and 999. Refusal cadence is true for 1, 100 and 200 and false for 2, 50, 99, 101 and 199.
- New `DeviceBackpressureSuite` test "an over-limit refusal is counted in the snapshot and logged with the running total". It uses a logback `ListAppender` on the `DeviceLinkServer` logger (detached in `finally`) and opens 64+1 sockets. The extra socket reads -1, `snapshot.connectionsRefused == 1`, `hub.connectionsRefused == 1`, and exactly one WARN contains "refused 1 connections".
- `ApiSuite` "the status endpoint reports device connections refused at the session limit": `deviceLink.connectionsRefused == 0` on a fresh hub.
- `DiagnosticsSuite` "device gauge unregisters…": `relay.device.connections.refused` reports 0 while the scope is open and is absent after it ends.

### Revert check

- Replacing `hub.recordRefusedConnection()` with a constant makes the new backpressure test fail at line 74 (snapshot count). Suite result: 1 failed / 7.
- Disabling the refusal WARN makes the same test fail at line 77 (warning count). Suite result: 1 failed / 7.
- After restoring, the suite passes 7 / 7.

### Validation (from `twitch-screen-relay/`)

- `./mill --no-daemon test.testOnly 'twitchscreen.relay.device.*'`: PASS, 8 suites / 80 tests, 0 failed.
- `./mill --no-daemon test.testOnly 'twitchscreen.relay.observability.*' 'twitchscreen.relay.http.*'`: PASS, 6 suites / 50 tests, 0 failed.
- `./mill --no-daemon test`: PASS, 38 suites / 422 tests, 0 failed.
- `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll`: PASS.
- `git diff --check`: PASS.

## test(relay): session-level regressions for PONG write failure and established-session version refusal

Findings: **K-057** (Low) Failed PONG write discarded; **K-084** (Low) Version-mismatch Refusal duplicated. The fixes were already on main (`DeviceSession.handle` matches on `sink.write(Pong)` and returns `WriteFailed`, and both version checks share `versionRefusal`). This unit adds the missing session-level coverage. Production code does not change.

### Change (tests only)

- `TestDevice.scala`: `TestRelay.start` takes an optional `bus: EventBus`, which defaults to a new 64-slot bus as before. Existing call sites are unchanged. New `TestRelay.detachReason(events, budget)` waits inside `timeoutOption(budget)` for the first `RelayEvent.DeviceDisconnected`, skips every other event, and returns its reason string.
- `HubResilienceSuite` "K-057: a failed PONG write ends the session with WriteFailed promptly, not at the idle timeout". A `ServerSocket`/`implAccept` socket whose output stream fails `write(Int)`, `write(Array,Int,Int)` and `flush` once a flag is set. The config uses `idleTimeout = 60 s` and `pingInterval = 30 s`, so only the PONG path can end the session inside the budget. Steps: handshake, receive WELCOME and STATS, set the flag, send PING. Within 2 s the detach reason is `DisconnectReason.WriteFailed.describe` and the session fork joins. The device then drains nothing (no PONG, and the connection ends at EOF), and `hub.links` is empty.
- `DeviceLinkSuite` "§7/§6.7: a frame with another version byte on an established session gets BYE(1), detail 3, the 30 s floor, stamped with that version". Steps: a v3 handshake, then a well-framed PING sent with version byte 2. The relay answers with exactly one frame, stamped with version 2: `Bye(UnsupportedVersion, ByeDetail.of(3), 30 s, "relay speaks v3 only")`. A further `receive()` is `None` (EOF). The detach reason is `ProtocolViolation("frame carried version 2").describe`.

### Revert checks (production file restored after each, `git diff src/` empty)

- K-057: `DeviceSession.handle` with `sink.write(EncodedFrame(RelayMessage.Pong(token))).discard; None`. The new test fails at `HubResilienceSuite.scala:141` because no `WriteFailed` detach arrives within 2 s. Suite result: 1 failed / 6.
- K-084: the established-session `versionRefusal(...)` call replaced by a `Refusal` that has the same code and detail but no `VersionMismatchBackoff`. The new test fails at `DeviceLinkSuite.scala:386` (`retryAfter = 0 seconds`, expected 30 seconds). Suite result: 1 failed / 42.

### Validation (from `twitch-screen-relay/`)

- Both suites run 3 times: PASS each time, HubResilienceSuite 6 / 6 and DeviceLinkSuite 42 / 42.
- `./mill --no-daemon test.testOnly 'twitchscreen.relay.device.*'`: PASS, 8 suites / 82 tests, 0 failed.
- `./mill --no-daemon test`: PASS, 38 suites / 427 tests, 0 failed.
- `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll`: PASS.
- `git diff --check`: PASS.

## test(relay): pin empty-rule AlertMonitor skip and fix stale rule doc link

Findings: **K-072** (Low) Alert monitor runs with zero rules; **K-094** (Low) Rule knowledge shotgun (AlertRule + MonitorState). The K-072 fix was already on main: `AlertMonitor.start` logs "No alert rules are enabled" and starts no `foldTimed` subscriber when `rules.isEmpty`. The rule logic had already moved into `AlertRule.check`. This unit adds the missing regression test and fixes the dangling scaladoc link. Production behaviour does not change.

### Change

- `alerts/AlertMonitor.scala` (scaladoc only): `([[MonitorState.check]])` becomes `([[AlertRule.check]])`. scalafmt rewrapped the paragraph.
- New `test/src/twitchscreen/relay/alerts/AlertMonitorSuite.scala` (2 tests). The fixtures are a fixed clock, `AlertsConfig` with `evaluationInterval = 1.hour` (so the timer never fires), `EventBus(clock, 16)` and a `DeviceHub` from the `ApiSuite`-style `DeviceLinkConfig`. Each test runs inside `supervised`:
  - "no configured rules starts no alerts subscriber": `AlertMonitor.start(config, Nil, …)`. `bus.subscriberStats` has no `alerts` entry and `store.activeCount == 0`.
  - "a configured rule subscribes the monitor to the bus": `List(AlertRule.NoDevicesConnected(1.minute))`. The `alerts` subscriber is present and `activeCount == 0`.

### Tests written first, with the revert check

- With the `if rules.isEmpty` guard disabled (`if false`), the Nil test fails at `AlertMonitorSuite.scala:46`: obtained `(true, 0)`, expected `(false, 0)`. The log shows "Evaluating 0 alert rules". Suite result: 1 failed / 2.
- After restoring, `git diff src/` shows only the scaladoc line and the suite passes 2 / 2.

### Validation (from `twitch-screen-relay/`)

- `grep -rn 'MonitorState.check' .`: no output (exit 1).
- `./mill --no-daemon test.testOnly 'twitchscreen.relay.alerts.*'`: PASS, 4 suites / 22 tests (AlertMonitorSuite 2, AlertRuleSuite 3, MonitorStateSuite 8, AlertStoreSuite 9), 0 failed. Repeated 3 times, and it passed each time.
- `./mill --no-daemon test`: PASS, 39 suites / 429 tests, 0 failed, in 4 of 4 consecutive runs, after the K-057 test fix below. Before that fix, 2 of 4 runs failed the K-057 test.
- `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll`: PASS after `reformatAll` rewrapped the scaladoc.
- `git diff --check`: PASS.

### Fix after judge round 1: K-057 test flake (HubResilienceSuite)

The first full-suite runs were flaky: 2 of 4 failed the K-057 test from a43a815. The detach reason was `read failed: Socket closed` instead of `WriteFailed`.

- **Root cause (test fixture, not production):** `FrameSink.write` calls `frame.writeTo(target)` and then `target.flush()`. The device can read STATS as soon as `writeTo` returns, before the writer fork's `flush()` runs. The test then set `failing`, and its guarded `flush()` threw inside the *STATS* write. The writer fork closed the socket before the PING reached the reader, so the reader ended with `ReadFailed` and the PONG path never ran.
- **Fix:** In `HubResilienceSuite`, the failing socket's `flush()` is no longer guarded. The underlying socket stream's flush is a no-op, and it now has a comment explaining why. `write(Int)` and `write(Array,Int,Int)` still fail once the flag is set, so the PONG write still fails. Production code does not change.
- **Deterministic reproduction:** I made two temporary changes, both reverted afterwards: `Thread.sleep(100)` between `writeTo` and `flush` in `FrameSink.write`, and `Thread.sleep(300)` after `failing.set(true)` in the test. With those in place, the previous test fails every time (`obtained "read failed: Socket closed"`) and the fixed test passes 6 / 6. After the revert, `git diff src/` shows only the AlertMonitor scaladoc line.
- **Red check still holds:** I replaced the PONG handling in `DeviceSession.handle` with `sink.write(...).discard; None`. The fixed test fails at `HubResilienceSuite.scala:142` (no `WriteFailed` detach within 2 s), and the suite result is 1 failed / 6. The production file was restored afterwards.
- `./mill --no-daemon test.testOnly twitchscreen.relay.device.HubResilienceSuite` ×5: PASS each time, 6 / 6.
- `./mill --no-daemon test.testOnly 'twitchscreen.relay.alerts.*'`: PASS, 4 suites / 22 tests.
- `./mill --no-daemon test` ×4 consecutive: PASS each time, exit 0, 39 suites / 429 tests, 0 failed.
- `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll`: PASS. `grep -rn 'MonitorState.check' .`: no output. `git diff --check`: PASS.

## fix(relay): make EventBus subscriber delivered count handler completions; test overflow WARN

Findings: **K-073** (Low) EventBus subscriber overflow is silent; **K-153** (Nit) SubscriberStats.delivered overstates delivery. The overflow WARN (logged at power-of-two drop counts) was already on main but no test covered it. `delivered` still counted queue acceptance, and only the Scaladoc and docs had been changed to say so. This unit uses option 2 from the review: `delivered` keeps its name but now counts finished handler calls, and a new `enqueued` counter takes over the old meaning.

### Change

- `bus/EventBus.scala`: `SubscriberStats(name, enqueued, delivered, dropped)`. Each counter has a Tapir `@description`, so its meaning appears in the OpenAPI schema at `/docs`. The Scaladoc states the invariants: `enqueued + dropped` = offered, `delivered <= enqueued`, and `enqueued - delivered` = backlog.
- `Subscription`: `deliveredCount` is renamed to `enqueuedCount` and is still incremented on a successful `trySendOrClosed`. The new `deliveredCount` is incremented only by `private[bus] markDelivered()`. The overflow WARN and its power-of-two gate are unchanged.
- `consume` calls `markDelivered()` after the handler's try/catch, so a failed call is counted once it has finished. `foldTimed` calls it after each step only when `input.isDefined`, so timer ticks are not counted. Both now use `register(name)` directly. A raw `subscribe` channel keeps `delivered = 0`. Every production subscriber (activity, metrics, device-notifications, stats, alerts) goes through `consume` or `foldTimed`.
- `docs/reference/http-api.md`: the `subscribers` row lists `name`, `enqueued`, `delivered` and `dropped`. The paragraph defines all three counters and the backlog. A grep of `docs/` and README found no other subscriber `delivered` wording.
- `BotFilterSuite` helper (raw `subscribe`): sums `enqueued + dropped` instead of `delivered + dropped`, so its meaning is unchanged.

### Tests (written first; `./mill --no-daemon test.compile` failed with "value enqueued is not a member of SubscriberStats" before the fix)

- `EventBusSuite` "a subscriber that stops reading…": now asserts `(enqueued, delivered, dropped) == (2, 0, 3)` and `enqueued + dropped == 5`.
- `EventBusSuite` "K-153: delivered counts finished handler calls, not queue acceptance". Setup: a capacity-2 bus and a `consume` handler that blocks on a release channel. Publish 1 event and wait until the handler has entered, then publish 4 more. The counters are `(3, 0, 2)`. Release 3 times and poll within 3 s until delivered = 3. The counters are then `(3, 3, 2)`.
- `EventBusSuite` "foldTimed counts a delivery per finished event step, not per tick, including a failed step". Setup: 1 h tick, events "first", "bad" (the step throws) and "last". Result: `(3, 3, 0)`.
- `EventBusSuite` "K-073: queue overflow is logged at WARN when the dropped count reaches 1, 2 and 4, not 3". A logback `ListAppender` is attached to `twitchscreen.relay.bus.Subscription` and detached and stopped in `finally`. After 5 events into a stalled capacity-2 subscriber, the WARNs are exactly the messages for 1 and 2 dropped. A 6th event adds "4 events dropped".
- `EventBusSuite` "SubscriberStats counters carry OpenAPI descriptions": checks the Tapir `SProduct` field descriptions.
- `ManagementRoutesSuite` "K-153: the OpenAPI document describes what each bus subscriber counter means": the served `/docs/docs.yaml` contains all three descriptions.

### Revert checks (production file restored after each; `cmp` against a backup confirmed it identical, and `git diff src/` shows only the intended change)

1. `delivered` incremented in `offer` and `markDelivered()` made a no-op: 2 failed / 11 in EventBusSuite, at `EventBusSuite.scala:39` (the stalled test expects delivered 0) and `:53` (the K-153 test expects `(3, 0, 2)`).
2. `logger.warn` replaced by `logger.debug` (a bare `then ()` does not compile under the strict warning flags): the K-073 test fails at `EventBusSuite.scala:97`. Result: 1 failed / 11.
3. Power-of-two gate replaced by `if true`: the K-073 test fails at `EventBusSuite.scala:97` (an extra WARN at 3). Result: 1 failed / 11.
4. The `delivered` `@description` removed: the schema test fails at `EventBusSuite.scala:109` and the docs.yaml test fails at `ManagementRoutesSuite.scala:200` ("docs.yaml lacks 'events whose handler call has finished'").

### Validation (from `twitch-screen-relay/`)

- `./mill --no-daemon test.testOnly 'twitchscreen.relay.bus.*'` ×3: PASS each time, EventBusSuite 11 / 11.
- `./mill --no-daemon test.testOnly 'twitchscreen.relay.twitch.*' 'twitchscreen.relay.http.*' 'twitchscreen.relay.alerts.*'`: PASS, 18 suites / 170 tests.
- `./mill --no-daemon test`: 39 suites / 444 tests. 11 of 12 runs passed. One run had an intermittent failure in `SequenceExhaustionSuite` "a stream lifecycle transition on an exhausted hub still delivers STATS…" (`:134`, the Offline STATS frame did not arrive within the device read budget under full-suite load). That suite passed 5 / 5 when run alone. An export of the untouched HEAD passed 6 / 6 full runs. The test does not read any subscriber counter, and this change only adds one atomic increment per handled event. It is recorded here as an existing timing-sensitive test and was not changed in this unit.
- `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll`: PASS.
- `git diff --check`: PASS.

## test(relay): pin shared encoded frames across sessions

Findings: **K-138** (Low) Identical frames re-encoded per session. The code fix was already on main. `Outbound` (DeviceHub.scala:22-28) caches one `EncodedFrame` per `TextPolicy` in `private lazy val verbatim`/`folded`, and `broadcast` (DeviceHub.scala:371-373) builds one `Outbound` and sends that same instance to every attached device. No test covered either half. This unit adds tests only. Production code does not change.

### Change

- NEW `test/src/twitchscreen/relay/device/OutboundSuite.scala` (package `twitchscreen.relay.device`, so the `private[device]` types are visible).

### Tests

- "K-138: an Outbound encodes once per text policy and returns the same bytes on every call". For `Outbound(Pong(Token.fromWire(1)))`, `encoded(Verbatim) eq encoded(Verbatim)`, `encoded(AsciiFolded) eq encoded(AsciiFolded)`, and `encoded(Verbatim) ne encoded(AsciiFolded)`. The last assertion pins the per-policy dispatch.
- "K-138: a broadcast hands every same-policy device the same Outbound and the same encoded bytes". Setup: `DeviceHub.start` with no listener. Two devices, "alpha" and "beta", attach directly through `hub.attach(AttachRequest(...))`, each with `TestDevice.FullCaps` and its own `Channel.buffered[Outbound](16)`. Each greeting (WELCOME, STATS) is drained and its types asserted; greeting frames are per device and are not expected to be shared. Then `hub.broadcastStats(StreamStats.Unknown)` and `hub.publish(EventRequest.of(Info, "shäred", ""))` (Right) each deliver one frame to both devices. For each broadcast the test asserts the Outbound instances are `eq`, and that `encoded(Verbatim)` and `encoded(AsciiFolded)` are `eq` across the two devices. `encoded` runs on the test thread rather than a writer fork, which is equivalent because `EncodedFrame` is immutable.

### Revert checks (DeviceHub.scala restored with `git checkout` after each; `git diff --stat src/` empty afterwards; the suite passed 2 / 2 again)

1. `private lazy val verbatim`/`folded` changed to `private def`: 2 failed / 2. Test 1 failed at `OutboundSuite.scala:20` (Verbatim `eq`). Test 2 got past the Outbound `eq` and failed at `OutboundSuite.scala:57` (`encoded(Verbatim)` `eq` across devices).
2. `broadcast` changed to `attached.values.foreach(d => send(d, Outbound(message)))`, with the now-unused `val frame` removed. When that val was left in place, the strict warning flags stopped compilation. Result: 1 failed / 2. Test 2 failed at `OutboundSuite.scala:56` ("each device received its own Outbound") on the first STATS broadcast. Test 1 was unaffected, as expected.

### Validation (from `twitch-screen-relay/`)

- `./mill --no-daemon test.testOnly twitchscreen.relay.device.OutboundSuite` ×3: PASS each time, 2 / 2.
- `./mill --no-daemon test.testOnly 'twitchscreen.relay.device.*'`: PASS, 9 suites / 84 tests, 0 failed.
- `./mill --no-daemon test`: PASS, exit 0, 40 suites / 451 tests, 0 failed.
- `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll`: PASS on the first run, so no reformat was needed.
- `git diff --check`: PASS. `git status` lists only the new suite and this record. No production source changed.

## test(relay): pin uptime clamp on backward clock step

Findings: **K-160** (Nit) Negative uptime on backward clock step. The fix was already on main. `StatusApi.status()` (health/StatusApi.scala:65) computes `uptimeSeconds = math.max(0L, JDuration.between(startedAt, clock.instant()).toSeconds)`. This clamp is accepted in place of a monotonic clock. Before this unit, the only test was ManagementRoutesSuite (`startedAt = now - 300s`, which asserts `"uptimeSeconds":300`), and it passes with or without the clamp. This unit adds tests only. Production code does not change.

### Change

- `test/src/twitchscreen/relay/http/ApiSuite.scala`: the helper that was `statusDeviceLink(initial)` is now `statusOf(initial: SeqNo = SeqNo.Zero, startedAt: Instant = clock.instant()): Either[Unit, Status_OUT]`. It passes `startedAt` to `StatusApi(...)` and returns the whole `Status_OUT`. `statusDeviceLink(initial)` is kept as `statusOf(initial).map(_.deviceLink)`, so the existing callers read the same as before. `Status_OUT` was added to the `health` import.

### Tests

- "K-160: the status endpoint clamps uptime to 0 when the wall clock has stepped back before startedAt". Setup: `startedAt = clock.instant().plusSeconds(60)`, where the fixed clock sits 60 s behind startedAt. The test asserts `uptimeSeconds == Right(0L)` and `startedAt == Right(startedAt)`, so the future startedAt is reported unchanged. As a companion it asserts `startedAt = now - 90s` gives `uptimeSeconds == Right(90L)`, which shows the clamp does not flatten forward uptime. The body is decoded with the jsoniter `Status_OUT` codec, so the decoded `0L` is the same value as the wire `"uptimeSeconds":0`.

### Revert check

- Line 65 of StatusApi.scala was changed to `uptimeSeconds = JDuration.between(startedAt, clock.instant()).toSeconds`. `./mill --no-daemon test.testOnly twitchscreen.relay.http.ApiSuite` then gave FAIL. The K-160 test failed at `ApiSuite.scala:111` with "values are not the same ... value = -60". The file was restored with `git checkout -- src`, after which `git diff --stat src` was empty and the suite passed 28 / 28.

### Validation (from `twitch-screen-relay/`)

- `./mill --no-daemon test.testOnly twitchscreen.relay.http.ApiSuite` ×3: PASS each time, 28 / 28, 0 failed.
- `./mill --no-daemon test.testOnly 'twitchscreen.relay.http.*'`: PASS, 4 suites / 49 tests, 0 failed. This includes ManagementRoutesSuite at 5 / 5 (`"uptimeSeconds":300`).
- `./mill --no-daemon test`: PASS (SUCCESS), 40 suites / 456 tests, 0 failed.
- `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll`: PASS on the first run, so no reformat was needed.
- `git diff --check`: PASS. `git status` lists only ApiSuite.scala and this record. No production source changed.

## refactor(relay): add NotificationKind.isLifecycle and pin per-kind policy through the encoder and hub

Findings: **K-034** (Medium-smell) Per-kind policy scattered across four matches. Most of the fix was already on main. `defaultTtl`, `foldsToPlaceholder`, `isGeneric` and `isDurable` are fields of the `NotificationKind` enum, and the encoder, `DisplayTtl`, `ReplayBuffers` and `DeviceHub` read them. Two gaps were left. (1) One per-kind equality check remained in `DeviceHub.sequenced`: `record.kind == StreamStart || record.kind == StreamEnd` decides whether a STATS snapshot follows the EVENT. (2) No test withheld a generic kind from a device without CAP_GENERIC, and no test checked `foldsToPlaceholder` per kind through the encoder.

### Change

- `src/twitchscreen/relay/protocol/NotificationKind.scala`: added the enum parameter `val isLifecycle: Boolean = false` as the last parameter. `StreamStart` and `StreamEnd` now pass `isLifecycle = true` by name. The scaladoc explains that lifecycle kinds are followed by a STATS snapshot when the stats state is known.
- `src/twitchscreen/relay/device/DeviceHub.scala:285`: `if record.kind.isLifecycle && (transition.isDefined || latestObservedStats != StreamStats.Unknown)`. The import stays as it was, because DeviceHub uses the wildcard `twitchscreen.relay.protocol.*`. Behaviour does not change, and neither do the wire bytes.

### Tests (written first)

- NEW `test/src/twitchscreen/relay/protocol/NotificationKindPolicySuite.scala`, 3 tests:
  - "K-034: every NotificationKind has an expected policy row…": the literal table's `keySet == values.toSet`.
  - "K-034: isGeneric, isDurable, foldsToPlaceholder and isLifecycle are pinned for every kind": Info/Message/Warning/Alert are (generic, durable, no placeholder, not lifecycle). Follow/Sub/Gift/Raid/Bits are (not generic, durable, placeholder, not lifecycle). Chat is (not generic, not durable, placeholder, not lifecycle). StreamStart/StreamEnd are (not generic, durable, no placeholder, lifecycle).
  - "K-034: an emoji-only actor folds to the placeholder…": for every kind, it encodes an EVENT with actor `"🎉🎉🎉"` using `Tsb3Encoder.toDevice(..., text = TextPolicy.AsciiFolded)` and decodes it with `Tsb3Decoder.fromRelay(WireBytes.frameOf(...))`. The expected actor comes from a literal `PlaceholderKinds` set rather than from `kind.foldsToPlaceholder`: `WireStrings.FoldedPlaceholder` for Follow/Sub/Gift/Raid/Chat/Bits, and `""` for Info/Message/Warning/Alert/StreamStart/StreamEnd.
- `test/src/twitchscreen/relay/device/TestDevice.scala`: added `NoGenericCaps = Ack | Chat`.
- `test/src/twitchscreen/relay/device/DeviceLinkSuite.scala`: added "§6.1: the generic kinds are withheld from a device without CAP_GENERIC". The device sends HELLO with `NoGenericCaps` and drains WELCOME and STATS. The test publishes an Info card, then a Follow, and asserts the next EVENT is `(Follow, seq 2)`: Info took seq 1 and was withheld.
- Red step: before the production change, `./mill --no-daemon test.compile` failed at `NotificationKindPolicySuite.scala:42` with "value isLifecycle is not a member of twitchscreen.relay.protocol.NotificationKind".

### Revert checks (each file restored from a copy afterwards; `git diff src/` then showed only the intended two-file change)

1. `isLifecycle = true` removed from `StreamEnd`. `test.testOnly 'twitchscreen.relay.device.*' 'twitchscreen.relay.protocol.*'`: FAIL, 2 failed / 197 in 16 suites. The table test failed at `NotificationKindPolicySuite.scala:43`, and LifecycleOrderingSuite "each lifecycle EVENT is followed by its post-transition STATS in the running pipeline" failed at `LifecycleOrderingSuite.scala:43` (called from `:26`), 1 failed / 1. The DeviceLinkSuite lifecycle tests still passed, so they do not cover the StreamEnd STATS follow-up on their own. LifecycleOrderingSuite does.
2. `DeviceHub.scala:409` changed to `case RelayMessage.Event(record) if record.kind.isGeneric => true`. `test.testOnly 'twitchscreen.relay.device.*'`: FAIL, 1 failed / 85 in 9 suites. The new DeviceLinkSuite test failed at `DeviceLinkSuite.scala:219`, 1 failed / 43.
3. `foldsToPlaceholder = true` removed from `Follow`. `test.testOnly 'twitchscreen.relay.protocol.*'`: FAIL, 3 failed / 112 in 7 suites. The encoder test failed at `NotificationKindPolicySuite.scala:54` ("folded actor of Follow"), and the table test failed at `:43`. The existing ProtocolBoundarySuite "ASCII fallback applies only to audience display names" also failed, at `ProtocolBoundarySuite.scala:39`.

### Validation (from `twitch-screen-relay/`)

- `./mill --no-daemon test.testOnly 'twitchscreen.relay.device.*' 'twitchscreen.relay.protocol.*'`: PASS, 16 suites / 197 tests, 0 failed. This includes NotificationKindPolicySuite 3 / 3, DeviceLinkSuite 43 / 43 and LifecycleOrderingSuite 1 / 1.
- `./mill --no-daemon test`: PASS (SUCCESS), 42 suites / 464 tests, 0 failed. Tsb3GoldenVectorSuite passed 23 / 23, so the wire bytes did not change.
- `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll`: PASS on the first run, so no reformat was needed.
- `git diff --check`: PASS.
- `grep -rnE 'NotificationKind\.(StreamStart|StreamEnd)' src`: the only hits are `device/NotificationRouter.scala:96` and `:104`, where the router builds those events. DeviceHub no longer appears.

## test(relay): pin nested traffic object in device JSON

Findings: **K-037** (Medium-smell) One new counter = five edits in two files. The production fix was already on main. `Device_OUT` (`src/twitchscreen/relay/device/DeviceApi.scala:13-35`) embeds `traffic: LinkTraffic` whole (Preserve Whole Object), and `Device_OUT.from` copies `link.traffic`, so a new counter touches only `LinkCounters.scala`. One gap was left: no test checked the HTTP/JSON shape, which KIMI-D23 (`tasks/review-kimi-device.md`) records as an intentional management API change. ApiSuite covered only the empty list and 404s.

### Change

Test only. No production source changed (`git diff src/` is empty).

### Tests

- NEW `test/src/twitchscreen/relay/device/DeviceApiJsonSuite.scala`, 4 tests. It sits in the `device` package because `ConnectionId.apply` is `private[device]`. It serializes with `writeToString` through the same `given JsonValueCodec[Device_OUT]` / `[Devices_OUT]` that `DeviceApi`'s `jsonBody` outputs use, so it pins the wire shape. The fixture `LinkTraffic` has a distinct non-zero value in every Long field (11..23) and `lastSeenAt = Instant.ofEpochSecond(1790309000L)`.
  - "K-037: a device's counters are rendered under a nested traffic object": decodes the JSON with test-private probe DTOs. `traffic == Some(TrafficProbe(11, 12, 23, 21, 22, 20, instant))`, and the top-level `framesSent`, `ackedSeq`, `bytesSent` and `lastSeenAt` are all `None`.
  - "K-037: no LinkTraffic field is flattened onto the device object": cuts out the `"traffic":{...}` slice. For every name in `LinkTraffic.productElementNames`, the key is inside the slice and absent from the rest, so future counters are covered automatically. The six device-level keys are present in the rest.
  - "K-037: the device list nests traffic per device too": `Devices_OUT` JSON starts with `{"devices":[{`, contains `"traffic":{"framesSent":11,`, and has exactly one `"framesSent":` key.
  - "K-037: the OpenAPI schema nests traffic as well": the `Schema[Device_OUT]` `SProduct` field names include `traffic` and exclude `framesSent`.

### Revert checks (the files were restored from copies after each check; `git diff src/` was then empty)

1. R1: added a flattened `framesSent: Long` to `Device_OUT`, filled from `link.traffic.framesSent` in `from` and in the test fixture. `test.testOnly twitchscreen.relay.device.DeviceApiJsonSuite`: FAIL, 4 failed / 4. The failures were at `probe.framesSent` (`:63` in the committed file), "framesSent flattened into {...,"baselineSeq":5,"framesSent":11,}" (`:73`), the single-`framesSent` count (`:81`) and the schema `!fields.contains("framesSent")` (`:88`). The reported lines were one higher because of the extra fixture line.
2. R2: renamed `traffic` to `counters` in `Device_OUT` and `from`. Same command: FAIL, 4 failed / 4. The failures were at `probe.traffic == Some(...)` (`:62`), the missing `"traffic":{` slice (`:54`, from test (b)), the missing `"traffic":{"framesSent":11,` (`:80`) and the schema `fields.contains("traffic")` (`:87`).

### Validation (from `twitch-screen-relay/`)

- `./mill --no-daemon test.testOnly twitchscreen.relay.device.DeviceApiJsonSuite`: PASS, 4 / 4, 0 failed.
- `./mill --no-daemon test.testOnly 'twitchscreen.relay.device.*'`: PASS, 10 suites / 89 tests, 0 failed.
- `./mill --no-daemon test.testOnly twitchscreen.relay.http.ApiSuite`: PASS, 28 / 28, 0 failed.
- `./mill --no-daemon test`: PASS (SUCCESS), 43 suites / 468 tests, 0 failed.
- `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll`: the first run found 1 misformatted file (the new suite's scaladoc wrap). After `./mill --no-daemon mill.scalalib.scalafmt/`, it PASSED. Only the comment wrap changed, and no assertion line numbers moved.
- `git diff --check`: PASS. `git status` lists only the new suite and this record.

## refactor(relay): move offer, overflow and drain-then-close behavior onto AttachedDevice

Findings: **K-081** (Low) AttachedDevice Data Class + Feature Envy (`DeviceHub.scala:343-352` in the review). An earlier round (KIMI-D05) had already moved `link`, `wants(message, chat)` and `queueFinalBye` onto `AttachedDevice`. Two envious call sites were left. `DeviceHubState.send` reached into `device.outbound.trySendOrClosed`, `device.counters.recordDropped`, `device.outbound.doneOrClosed` and `device.connection.close`. The facade's `shutdown()` and `disconnect()` each repeated their own `timeoutOption` / `drained.receiveOrClosed` / `connection.close` block. `outbound`, `counters`, `connection` and `drained` were public vals.

### Change (`src/twitchscreen/relay/device/DeviceHub.scala` only)

- New `private[device] enum Offer { Accepted, DroppedReplaceable, Overflowed, Closed }`.
- `AttachedDevice.offer(frame): Offer` holds the logic that used to be inline in `send`, with the original comments. A queued frame returns `Accepted`. On a full queue, the drop is recorded on the device's own counters. An EVENT then marks the queue done, closes the transport and returns `Overflowed`. Any other frame returns `DroppedReplaceable`. A `ChannelClosed` queue returns `Closed` and records nothing.
- `AttachedDevice.drainThenClose(limit)` delegates to the companion's `AttachedDevice.drainThenClose(devices, limit)`. The companion uses one `timeoutOption` deadline shared by all the devices, then closes every transport in `finally`, swallowing `IOException` through `private closeTransport()`. These are the old shutdown semantics: one shared 2 s deadline, not 2 s per device.
- `outbound`, `counters`, `connection` and `drained` are now `private val`. `id`, `device`, `connectedAt` and `caps` stay public because they are immutable identity values, and `greet` needs `caps` for WELCOME.
- `DeviceHubState.send` is now an exhaustive match with no wildcard: `Overflowed` leads to `detach(OutboundOverflow)` and the warn log, and `Accepted | DroppedReplaceable | Closed` do nothing. Adding an `Offer` case later breaks the build under `-Werror`.
- The facade calls `AttachedDevice.drainThenClose(devices, DeviceHub.DrainLimit)` in `shutdown()` and `device.drainThenClose(DeviceHub.DrainLimit)` in `disconnect()`. `DrainLimit = 2.seconds` is named once, in the `DeviceHub` companion. Neither method contains `timeoutOption`, `receiveOrClosed` or `close` any more.
- Ordering note: `doneOrClosed` and the transport close now run just before `detach` rather than just after, still inside the same actor operation. The session's own detach, triggered by the socket close, arrives through the actor mailbox after this operation, and `DeviceHubState.detach` is a no-op for an id it no longer holds. The `OutboundOverflow` reason and the `DeviceDisconnected` event are therefore unchanged, and the unmodified DeviceBackpressureSuite pins both.
- The top-level `queueFinalBye(outbound, …)` also stays. Its `retry` parameter now uses the imported `FiniteDuration` instead of the fully qualified name.

### Tests (test first)

NEW `test/src/twitchscreen/relay/device/AttachedDeviceSuite.scala`, 8 tests. They drive `AttachedDevice` directly, with no actor and no socket, through a fixture of a buffered queue, `LinkCounters`, an `AtomicBoolean` transport and a drained channel:
(a) offer Accepted; (b) replaceable frame on a full queue is DroppedReplaceable, counted, link kept, and the next offer after a receive is Accepted; (c) EVENT on a full queue is Overflowed, counted, transport closed, the queued frame is still receivable, and then the queue reports ChannelClosed; (d) offer on a closed queue is Closed and records nothing; (e) drainThenClose returns in under 1 s once drained is signalled, and closes; (f) drainThenClose closes after a 50 ms deadline with no drain; (g) an `IOException` from the transport close is swallowed; (h) three undrained devices with a 200 ms shared deadline finish in under 500 ms, and all three are closed.

- Red: before the production change, `./mill --no-daemon test.testOnly 'twitchscreen.relay.device.AttachedDeviceSuite'` failed to compile with 18 errors, all of them `value offer is not a member of AttachedDevice`, `Not found: Offer` or a missing `drainThenClose`. Two test-side typing slips were fixed first: a `Closeable` ascription and the `DurationLong` import.
- Green: the same command passed, 8 / 8.

### Revert checks (the production file was restored from a copy after each check; `cmp` confirmed it matched)

1. In `offer`, EVENT overflow returned `DroppedReplaceable` without `doneOrClosed` or the close. Running `test.testOnly AttachedDeviceSuite DeviceBackpressureSuite` gave FAIL. AttachedDeviceSuite was 1 failed / 8, with (c) failing at `AttachedDeviceSuite.scala:76`. DeviceBackpressureSuite was 1 failed / 7, with "EVENT overflow closes and removes the connection before any later event can cross the gap" failing at `DeviceBackpressureSuite.scala:107`.
2. In `drainThenClose`, `finally ()` replaced the `closeTransport` loop. Running `test.testOnly AttachedDeviceSuite DeviceLinkSuite` gave FAIL for AttachedDeviceSuite, 3 failed / 8: (e) at `:94`, (f) at `:100` and (h) at `:110`. DeviceLinkSuite stayed 43 / 43. Its operator-disconnect test still sees EOF, because the session's writer closes the socket itself when the queue reaches `Done`. The facade's close is only the backstop for a writer that is stuck, so the new unit tests (e) and (f) are the only thing that pins it.
3. Per-device deadline: `devices.foreach(d => timeoutOption(limit)(…))`. Running `test.testOnly AttachedDeviceSuite` gave FAIL, 1 failed / 8. Test (h) failed at `:109` with "three undrained devices took 601717322 nanoseconds; the deadline is per device".

### Validation (from `twitch-screen-relay/`)

- `./mill --no-daemon test.testOnly 'twitchscreen.relay.device.AttachedDeviceSuite'`: PASS, 8 / 8.
- `./mill --no-daemon test.testOnly 'twitchscreen.relay.device.DeviceBackpressureSuite' 'twitchscreen.relay.device.DeviceLinkSuite' 'twitchscreen.relay.device.ApplicationShutdownSuite'`: run 3 times, PASS every time: 7 / 7, 43 / 43 and 2 / 2. No test was modified.
- `./mill --no-daemon test.testOnly 'twitchscreen.relay.device.*'`: PASS, 11 suites / 97 tests, 0 failed.
- `./mill --no-daemon test`: PASS (SUCCESS), 44 suites / 476 tests, 0 failed. The previous record was 43 / 468, so this is +1 suite and +8 tests.
- `./mill --no-daemon mill.scalalib.scalafmt/reformatAll`, then `checkFormatAll`: PASS. The reformat was a no-op; `cmp` against the pre-format copy matched.
- `grep -nE '\.outbound|\.counters|\.connection\.close|\.drained' src/twitchscreen/relay/device/DeviceHub.scala`: 6 hits. `:220` and `:222` are `request.outbound` in the pre-attach `queueFinalBye` refusals, which take an `AttachRequest`, not an `AttachedDevice`. `:409`, `:410` and `:412` are the private val initialisers inside `AttachedDevice`, and `:449` is `_.drained` inside the `AttachedDevice` companion. No `device.outbound`, `device.counters`, `device.connection` or `device.drained` remains in the facade or in `DeviceHubState`.
- `git diff --check`: PASS.

## refactor(relay): separate queue drain from write failure in DeviceSession writeLoop

Findings: **K-086** (Low) Boolean blindness in DeviceSession write loop (`DeviceSession.scala:181-207` in the review). An earlier round replaced the Boolean with `enum WriteResult { Written, Failed }`. But `writeLoop` still mapped normal queue completion (`ChannelClosed.Done`) to `WriteResult.Failed`, so a drained queue (final BYE sent, or detached) and a real write or encoding failure collapsed into one value named `Failed`.

### Change (`src/twitchscreen/relay/device/DeviceSession.scala` only)

- `writeLoop` now matches `receiveOrClosed()` directly. A frame goes through `writeFrame`: `Written` recurses and `Failed` closes the socket. `ChannelClosed.Done` calls `closeQuietly(io.socket)` itself, and `ChannelClosed.Error` rethrows. The recursive call stays in tail position, and `@tailrec` still compiles. The scaladoc names the three outcomes: continue, write failure and drained.
- The oversized-frame decision is now a pure function, `private[device] def oversizedFramePolicy(message: RelayMessage): WriteResult`, on the `DeviceSession` object. An EVENT gives `Failed` and anything else gives `Written`. `writeFrame` calls it after it records the drop and logs the warning, so the behavior is unchanged.
- `enum WriteResult` has a scaladoc saying that `Failed` means only a write or encoding failure (or an EVENT that could not be sent), never queue completion. With this, the KIMI-D09 wording in `tasks/review-kimi-device.md` ("separates queue completion ... and socket failure") is accurate, and that file is not edited.
- `grep -n "ChannelClosed.Done" src/twitchscreen/relay/device/DeviceSession.scala`: 1 hit, `:186 case ChannelClosed.Done => closeQuietly(io.socket)`.
- `grep -n "WriteResult.Failed" src/twitchscreen/relay/device/DeviceSession.scala`: `:185` is the writeLoop failure branch, `:205` the oversize policy, `:254` the PONG write result consumed in the read loop (`WriteFailed`), and `:369` and `:381` the `FrameSink.write` failure paths (`writeFinal` delegates to `write`). None of these is queue completion.

### Tests (test first)

NEW test in `test/src/twitchscreen/relay/device/HubResilienceSuite.scala`: "K-086: an oversized EVENT stops the writer; an oversized replaceable frame is skipped". An EVENT (built through `EventRequest.of(...).record(...)`, as AttachedDeviceSuite does) gives `Failed`. A PING and a STATS each give `Written`. The end-to-end path cannot be reached, because `Config.scala` requires the frame limit to be exactly 256.

- Red: before the production change, `./mill --no-daemon test.testOnly 'twitchscreen.relay.device.HubResilienceSuite'` failed to compile with 3 errors: `value oversizedFramePolicy is not a member of object twitchscreen.relay.device.DeviceSession`.
- Green: the same command passed, 7 / 7.

### Revert checks (the production file was restored from a copy after each check; `cmp` confirmed it matched)

1. `case ChannelClosed.Done => ()`, so the writer no longer closes on drain. Running `test.testOnly DeviceLinkSuite ApplicationShutdownSuite` still PASSED, 43 / 43 and 2 / 2. DeviceLinkSuite took 6.9 s instead of about 2 s. The operator-disconnect test (EVENT, BYE, EOF) still sees EOF because the `AttachedDevice.drainThenClose` backstop closes the transport after its 2 s deadline (see K-081 revert note 2 above). To be honest about coverage: the Done-branch close is pinned only by timing, as the first closer. No test fails without it.
2. `oversizedFramePolicy` returned `Written` for Event. Running `test.testOnly HubResilienceSuite` gave FAIL, 1 failed / 7: the K-086 test at `HubResilienceSuite.scala:149`.

### Validation (from `twitch-screen-relay/`)

- `./mill --no-daemon test.testOnly 'twitchscreen.relay.device.HubResilienceSuite'`: PASS, 7 / 7.
- `./mill --no-daemon test.testOnly 'twitchscreen.relay.device.DeviceLinkSuite' 'twitchscreen.relay.device.ApplicationShutdownSuite' 'twitchscreen.relay.device.DeviceBackpressureSuite' 'twitchscreen.relay.device.AttachedDeviceSuite'`: PASS, 43 / 43, 2 / 2, 7 / 7 and 8 / 8. No existing test was modified. This includes the final-BYE drain test (EVENT, BYE, EOF), the SERVER_SHUTDOWN BYE drain, EVENT overflow and the stalled-write deadline.
- `./mill --no-daemon test.testOnly 'twitchscreen.relay.device.*'`: PASS, 11 suites / 98 tests, 0 failed. The previous record was 97, so this is +1.
- `./mill --no-daemon test`: PASS (SUCCESS), 45 suites / 491 tests, 0 failed, with no `[warn]` or `[error]` lines under `-Werror`. The baseline on this lane branch is 45 / 490, which includes the relay-twitch commits `234eb5e` and `2bcca20` merged since the K-081 record, so this is +1 test.
- `./mill --no-daemon mill.scalalib.scalafmt/reformatAll`, then `checkFormatAll`: PASS. The reformat was a no-op; `cmp` against the pre-format copies matched.
- `git diff --check`: PASS. `git status` lists only `DeviceSession.scala`, `HubResilienceSuite.scala` and this record.
