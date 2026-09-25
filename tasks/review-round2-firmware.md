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
