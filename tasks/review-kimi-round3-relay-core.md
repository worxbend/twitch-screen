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
