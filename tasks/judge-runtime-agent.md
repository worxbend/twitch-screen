# Independent judge: firmware, repository and CAD

Reviewer: runtime agent, 2026-09-25. This report reviews other owners' changes;
it is not the approval gate for this agent's own relay implementation.

## Scope and verdict

Reviewed root commits `d7ddeb9`, `751878b`, `2d11663`, `a8cf652`, the current
`tools/smoke_container.py` including real SIGTERM, and firmware commits from
`e0fd7b4` through `03b1f0c` (integrated by root through `dca5b72`).

**Approve the reviewed source changes.** No unresolved Critical or Required
behavioral findings were found. Final integration validation remains the
orchestrator's gate; this does not certify physical hardware or remote CI.
One minor trailing blank line in `link_client.h` was reported for integration
cleanup and has no behavioral effect.

## Review evidence

- Firmware: traced DNS callback release/acquire publication and stale request
  handling, WiFi disconnect latch, nonblocking connect/read/write, bounded
  output queue/deadlines, and single-descriptor close-worker ownership. The
  worker performs only descriptor disposal; session/UI state stays on loop.
  No new socket is admitted while the previous descriptor is closing.
- Session behavior: checked full-queue EVENT retention and high-water mark,
  wrong-version rejection before pause, actual-refusal teardown, periodic
  output while input is paused, ACK capability handling, stable-session retry
  reset, wrap-safe deadlines, and generic cross-version BYE teardown. Native
  tests exercise the real session state machine through a fake clock/transport,
  including 30-event bursts, partial/stalled writes and a 64-second queue pause.
- Presentation/codec: inspected RGB565 buffer size/alignment and synchronous
  flush use, title height and bounded text, full-u32 count formatting, dark-name
  color fallback, display-control conversion, shared header validation and
  resynchronization units. Deterministic chunk-invariance fuzzing is additional
  evidence, not a proof over every input or a physical rendering test.
- Protocol: frozen frame layouts remain intact. Close-on-EVENT-gap is explicit;
  replay across restart, age policy, unknown initial stats and retry minimum
  limits are documented rather than claimed solved. Sequence exhaustion wording
  requires refusal and an operator restart without wrap.
- CI/deployment: inspected pinned action permissions, sequential firmware
  checks, relay warnings/format/test gates, protocol comparison, container
  builder dependencies, OCI/launcher digest checks, nonroot token directory,
  memory settings, auth examples and real-socket SIGTERM smoke. The smoke
  validates BYE8 followed by EOF and uses an isolated generated test credential.
- Repository/skills: onboarding consistently uses TSB/3 relay simulation;
  obsolete Python server only exits with migration guidance. Skill symlinks
  resolve, firmware commands preserve credentials by policy, and hardware
  claims are separated from native/build evidence.
- CAD: compared extracted cavity and vent formulas against original operations,
  checked explicit hardware membership and parameter restoration in finally,
  and verified that independent smaller base probes/count checks remain. One-mm
  extraction sampling is correctly described as discrete rather than continuous
  clearance proof. Shared preview dimensions preserve the previous values.

## Checks and limits

Reviewer ran `python3 tools/check_protocol_vectors.py`: all 20 vectors and 213
prose assertions agree. Python syntax checks for CAD/firmware/tools and launcher
shell syntax passed. The reviewer inspected the reported FreeCAD geometry,
assembly and wall evidence; did not rerun physical or rendering work.

The firmware owner and then root independently report all five native plus all
five sanitized suites passing (9,316 assertions per environment), and a clean
ESP32 build with application warnings fatal. Root owns exclusive PlatformIO
execution; this reviewer did not run another overlapping build. Static RAM is
67,256 bytes; the worker stack is dynamic and is not part of that static total.

Remaining acceptance limits are recorded in the owner matrices: no flash,
physical LCD/WiFi/watchdog test, live Twitch credential test, physical CAD fit,
or remote GitHub Actions success is inferred from these host checks. The
512 MiB container setting is a starting simulated-smoke budget, not a load
capacity guarantee. Optional deferred design/policy items are not represented
as implemented.
