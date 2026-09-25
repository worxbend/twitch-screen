# Firmware and protocol review execution

Baseline: `e0fd7b4` (review orchestration commit, application baseline `a66a0529`).
Grooming checked current source; the original K/D/C reports are absent. Historical
reproduction claims in REVIEW_CONSOLIDATED.md are not new runtime evidence.

Ownership: firmware agent implements firmware source, host tests, PlatformIO,
asset tooling and this protocol document. Runtime agent owns relay changes.
Root owns onboarding, project skills, CI, CAD, integration and push.

## Accepted TSB/3 compatibility decisions

- Keep frozen frame layouts, sequence fields and capability bits.
- On relay EVENT enqueue failure, close that device before a later EVENT can
  cross the gap. STATS remains replaceable by its next observation.
- Device retains a complete EVENT while its queue is full; UI continues, socket
  input applies backpressure, output deadlines still run. Actual app refusal
  tears down for replay without admitting a higher sequence.
- Replay is bounded and best effort. Relay process restarts reset sequence space;
  HELLO has no session echo. Durable replay across restarts requires a later
  persistence/epoch design, not a guessed comparison of session IDs.
- Ignore REPLAY on non-EVENT frames and ACK without effective CAP_ACK; these are
  normal complete frames for liveness/counters. BYE 4/6 are reserved/not sent in
  v3. Unknown BYE always tears down. Sequence exhaustion refuses publication
  until an operator restarts; it never wraps.
- No new replay-age cutoff or retry-floor ceiling. Both need product policy.

## Finding disposition

`Resolved` below means source, host tests and ESP32 build closure. Hardware
acceptance remains explicitly listed; it was not replaced by a compile claim.
Other owners' rows refer to their separate execution reports.

| ID | Groomed status | Scope / acceptance |
|---|---|---|
| FW-01 | Resolved | Async WiFi/DNS/connect, atomic disconnect latch; native short/prolonged outage sessions. |
| FW-02 | Resolved | Watch loop after eliminating network waits; log reset reason; physical stall test remains manual. |
| FW-03 | Resolved | Font-height title in default/chat layout; physical long-name visual check remains manual. |
| FW-04 | Resolved | Pump first frame before networking; connected means WELCOME. |
| FW-05 | Resolved | Nonblocking bounded partial output and connect deadlines; native tests. |
| FW-06 | Resolved | Explicit aligned RGB565 byte buffer. |
| FW-07 | Resolved | Single buffer for synchronous flush; ESP32 size/build evidence. |
| FW-08 | Resolved | Pin installed versions, application warnings, remove used deprecated LVGL APIs. |
| FW-09 | Resolved | Stable interval before ramp reset, REPLACED maximum ramp, MAC-derived empty ID. |
| FW-10 | Deferred policy | Frozen BYE minimum must remain a minimum; no unilateral cap. |
| FW-11 | Resolved | Rate-limit frame skip diagnostics, nonblocking serial transport logs. |
| FW-12 | Resolved | Deterministic parser/chunking fuzz plus native-sanitized target. |
| FW-13 | Resolved | Fake clock/transport exercises real session; CI owned by root. |
| FW-14 | Resolved | Body ellipsis for fixed-height text. |
| FW-15 | Resolved | Minimum readable chatter luminance with white fallback; pure boundary test. |
| FW-16 | Resolved | Bounded K/M/B numeric formatting; boundary tests. |
| FW-17 | Resolved | Exception decoder, source commit in firmware ID and reset reason. |
| FW-18 | Deferred optional | OTA requires partition/authentication/rollback/service requirements. |
| FW-19 | Resolved optional | Strip ASCII controls at display conversion, avoid untrusted BYE log text. |
| FW-20 | Resolved optional | Static actor/text width assertions. |
| FW-21 | Resolved optional | Shared header validation predicate. |
| FW-22 | Resolved | Current LVGL configuration and connectivity contract. |
| FW-23 | Resolved | Script-relative asset outputs; keep original rendering geometry. |
| FW-24 | Resolved via supported isolated environment | Broken global pio reproduced; isolated 6.1.18 venv and reproducible command. |
| FW-25 | Resolved | Main logs session-change accessor. |
| FW-26 | Resolved optional | Named presentation flags at wire conversion boundary. |
| FW-27 | Resolved | Remove ring-border dead store before clearDecor. |
| FW-28 | Resolved | Document frame counter at framing/consume stage, including decoder skips. |
| PROTO-01 | Resolved | Pause full queue before consuming EVENT; native >capacity burst stays connected. |
| PROTO-02 | Deferred design | Document bounded restart limitations and correct retained 67–130 arithmetic. |
| PROTO-03 | Runtime track | Close on relay EVENT enqueue failure before later seq. |
| PROTO-04 | Firmware resolved; runtime track | Correct session ID and periodic ping comments. |
| PROTO-05 | Runtime track | Lifecycle and corresponding state delivered coherently. |
| PROTO-06 | Runtime track | Saturate chat_rate at u16 maximum. |
| PROTO-07 | Runtime track | Audience-only actor fallback. |
| PROTO-08 | Root track | Mechanical spec/relay/firmware golden comparison in CI. |
| PROTO-09 | Resolved | Remove obsolete implementation/migration status from normative rules. |
| PROTO-10 | Deferred design | Unknown initial stats needs negotiated representation or suppression; current wire zeros ambiguous. |
| PROTO-11 | Deferred policy | Document no age cutoff and possible stale lifecycle cards; don't invent default TTL. |
| PROTO-12 | Resolved | Log negotiated timers each connection. |
| PROTO-13 | Firmware resolved; runtime track | Count discarded octets consistently, native conformance assertion. |
| PROTO-14 | Resolved | Reserve BYE 4/6 as never sent in TSB/3. |
| PROTO-15 | Runtime track | Ignore ACK without negotiated CAP_ACK; test normal liveness. |
| PROTO-16 | Firmware resolved; runtime track | Ignore REPLAY on non-EVENT; counter/receiver tests. |
| PROTO-17 | Resolved | Generic unknown BYE teardown wording outside table. |
| PROTO-18 | Runtime track | Refuse publish at 0xffffffff until restart; never wrap. |

## Verification log

- Global `~/.platformio/penv/bin/pio --version` reproduced
  `ModuleNotFoundError: No module named platformio`.
- Isolated `.venv-pio` created with `python3 -m venv` and
  `python -m pip install platformio==6.1.18`; use its absolute Python path with
  `-m platformio` from firmware directory. No global venv was modified.
- Initial native session regression target could not build old Arduino-bound
  session on host. Added transport seam, then ran real session with fake I/O.
- `pio test -e native`: three suites pass, session has 51 assertions (initial slice).
- Hardware port `/dev/ttyUSB0` exists but has not been identified, flashed or
  exercised. Visual LCD, real WiFi outage and watchdog reset remain physical
  acceptance checks; native tests and compilation cannot establish those.

## Final slice evidence and limitations

- `python -m platformio test -d twitch-screen-firmware -e native -e native-sanitized -v`
  passes all five suites in both environments: wire/queue 849 assertions, codec
  5,379, session 70, presentation 18, parser fuzz 3,000 (9,316 per environment).
  ASan/UBSan reported no faults. The fuzz seed is fixed and chunk invariance is
  compared across arbitrary bytes, mutated vectors and intact vectors.
- A regression assertion reproduced resync unit mismatch (5 rejected windows
  versus 21 discarded octets), then passed after aligning the counter.
- Root's review reproduced delayed version rejection with a full queue. The
  version guard now precedes pause. A 64-second paused queue retains its mark,
  still emits four periodic pings, then accepts the pending EVENT as soon as
  display capacity returns. These are deterministic fake-clock session tests.
- The pinned lwIP implementation can block close even on nonblocking sockets
  when FIN allocation fails. Its socket API has SO_LINGER disabled in this SDK.
  A single persistent worker owns one descriptor being closed; a new connection
  cannot allocate another until that slot is released. No task-per-retry growth.
  Source: [Espressif lwIP close implementation](https://github.com/espressif/esp-lwip/blob/2.1.3-esp/src/api/api_msg.c),
  [Espressif socket API](https://docs.espressif.com/projects/esp-idf/en/v4.4/esp32/api-guides/lwip.html).
- ESP32 build with pinned platform/framework/LVGL/TFT_eSPI and application
  `-Wall -Wextra -Werror` passes. Static RAM falls from 105,720 to 67,256 bytes;
  final flash is 1,152,845 bytes. The aligned
  synchronous RGB565 draw buffer is 19,200 bytes; the former two arrays totalled
  57,600. Worker stack is dynamically allocated and is not included in static RAM.
- `make_glitch.py` executed from `/tmp`; generated tracked header/PNG assets had
  no diff. Script launch directory no longer chooses output location.
- The broken global PlatformIO venv was not repaired or reused. PlatformIO 6.1.18
  works from a newly created isolated venv; root independently recreated the documented setup
  in the integration worktree and passed all ten host-suite runs and the ESP32 build.
  Integration static RAM is 67,248 bytes and flash is 1,151,877 bytes with the local
  configuration; the workstream measurements above used its example configuration. Firmware pins are build-verified installed
  versions, not a claim of new hardware qualification.
- Deferred policy/design items: FW-10 (frozen retry floor cap), FW-18 (OTA),
  PROTO-02 (restart sequence ownership), PROTO-10 (unknown initial stats),
  PROTO-11 (replay-age cutoff). The current limitations are documented honestly.
- No flash, physical display observation, deliberate watchdog stall, or real
  WiFi outage was performed. Those hardware checks remain outstanding.
