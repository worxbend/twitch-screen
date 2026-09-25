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
