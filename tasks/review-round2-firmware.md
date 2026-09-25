# Review round 2 — firmware

## FW-01 — prolonged WiFi outage in link session (test-only)

- Change: added `testProlongedOutage` to
  `twitch-screen-firmware/test/test_link_session/test_link_session.cpp`,
  registered in `main()` after `testWifiEdgeAndBackoff`. It streams, drops
  WiFi with `wifi=false` (no latched edge), and runs the fake clock for 120 s
  in 1 s steps with 3 loops each. It asserts that the link stays down, no
  `startConnect` happens, no bytes are written, and `linkLoop` keeps
  returning. It then latches a stale edge, restores WiFi and asserts that the
  edge is consumed without an attempt, that exactly one new attempt happens
  (no duplicate while connecting), that HELLO (`T_HELLO` at `outbound[3]`) is
  re-sent, and that `greet()` reaches streaming with welcomes+1 on the same
  attempt. No `src/` or `include/` changes.
- Red check: with the `return;` in the linkLoop wifi-down branch commented
  out, the suite failed with 7 failures (no-attempt, stale edge, exactly-one,
  HELLO, recovery). The source was reverted, and a byte compare against a
  backup confirmed the revert.
- Evidence:
  - `.venv-pio/bin/python -m platformio test -d twitch-screen-firmware -e native -e native-sanitized -f test_link_session -v`
    passes in both envs: 81 checks, 0 failures (previously 70).
  - `.venv-pio/bin/python -m platformio test -d twitch-screen-firmware -e native -e native-sanitized -v`:
    10/10 test cases pass. Per environment: codec 5,379, presentation 18,
    fuzz 3,000, wire 849, session 81 (9,327 total). No ASan/UBSan output.
- Still open: physical-device WiFi-outage acceptance (see `review-status.md`).

## FW-09: stable-interval backoff reset documented, REPLACED cap tested

- Change: `docs/PROTOCOL.md` §12 (intro, timer table) and §12.1 no longer say
  the backoff resets on `WELCOME`. They now describe the implemented
  device-local behavior: the ramp resets only after 60 s of continuous
  streaming (`STABLE_STREAM_MS`), and `BYE(1 UNSUPPORTED_VERSION)` and
  `BYE(9 REPLACED)` both jump to the 30 s cap (a non-zero `retry_after_s` is
  still an uncapped minimum). Nothing changes on the wire, and the vectors and
  frozen §6.7 table are unchanged. The `src/link_client.cpp` `scheduleRetry`
  comment now names REPLACED; this is a comment-only change.
- Test: `test_link_session.cpp` adds `testReplacedAndVersionByeJumpToCap()`,
  built on the helpers `byeCode()` and `byeJumpsToCap()`. After `greet()` it
  injects a BYE with code 9, then code 1, with retry_after 0. It asserts
  teardown, no attempt at t0+1000/+2000/+29999, exactly one attempt at t0+30000
  (the fake jitter is 0), and no duplicate after 5 more loops.
- Red check: with `|| b.code == tsb::BYE_REPLACED` removed from `handleBye`,
  the result was `FAIL: REPLACED waits for 30 s cap, not the 1 s ramp` (91
  checks, 1 failure). The UNSUPPORTED_VERSION case still passed. The source was
  restored from a backup and the byte compare (`cmp`) matched.
- Evidence:
  - `.venv-pio/bin/python -m platformio test -d twitch-screen-firmware -e native -e native-sanitized -f test_link_session -v`
    passes in both envs: 91 checks, 0 failures (previously 81).
  - `.venv-pio/bin/python -m platformio test -d twitch-screen-firmware -e native -e native-sanitized -v`:
    10/10 test cases pass. Per environment: codec 5,379, presentation 18,
    fuzz 3,000, wire 849, session 91. No ASan/UBSan output.
  - `grep -n "reset on .WELCOME\|resets after a successful" twitch-screen-firmware/docs/PROTOCOL.md`
    finds nothing.

## PROTO-09: PROTOCOL.md §19 recast as completed migration history

- Change: `docs/PROTOCOL.md` §19 was retitled "Migration from v2 (completed,
  non-normative)" and rewritten in the past tense as a historical note that
  sets no requirements. The stale phrases "does not exist today", "must stop
  describing" and "it is stale" were removed. The section number is unchanged,
  and §1–§18 (including the §18 V1–V20 vectors) are byte-identical: `cmp` of
  lines 1–1695 against the pre-edit copy matched, and the diff is one hunk at
  line 1696.
- Preconditions checked before writing each claim:
  - `grep -n 'TSB/3 binary over TCP :8099' twitch-screen-relay/README.md` matches line 46.
  - `grep -n 'TSB/3 binary push' twitch-screen-firmware/PLAN.md` matches line 64.
  - `grep -n 'protocol-version = 3\|max-frame-length = 256\|ignored-display-names' twitch-screen-relay/resources/application.conf`
    matches lines 28, 51 and 135.
  - `grep -n 'messagesTotal' .../protocol/Tsb3Encoder.scala` matches line 101
    (`STATS.msg_total`). §6.5 (line 528) is the STATS section.
  - `demo-server/twitch_server.py` is the retired stub. It exits and points
    to the relay's simulated mode.
- Evidence:
  - `grep -n 'does not exist today\|must stop describing\|it is stale' twitch-screen-firmware/docs/PROTOCOL.md`
    finds nothing (exit code 1). §19 contains no MUST, SHOULD, must or should.
  - `git diff --check` is clean.
  - As a no-op sanity check, `platformio test -d twitch-screen-firmware -e native` passed
    5/5, and `./mill test.testOnly twitchscreen.relay.protocol.Tsb3GoldenVectorSuite`
    passed 23 of 23 tests.
