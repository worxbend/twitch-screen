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

## FW-08 / FW-24: deprecated LVGL aliases removed, stale pio paths fixed

- Changes:
  - FW-08: in `src/ui_idle.cpp:107` and `src/ui_notify.cpp:98,383`,
    `lv_anim_set_playback_duration` became `lv_anim_set_reverse_duration`. In
    `src/ui_notify.cpp:58,385`, `lv_anim_set_ready_cb` became `lv_anim_set_completed_cb`.
    The `animate()` parameter type at `src/ui_notify.cpp:50` changed from
    `lv_anim_ready_cb_t` to `lv_anim_completed_cb_t`. In LVGL 9.6.0 each old name is an
    api_map `#define` of the new one, so behavior is unchanged.
  - FW-08 guard: `[env:esp32dev]` build_flags in `platformio.ini` now include
    `-D LV_DISABLE_API_MAPPING=1`. Any deprecated LVGL name that comes back is now an
    undeclared-identifier error. The flag is not on the native envs, because they
    compile only `proto_codec.cpp` and `link_client.cpp` and never compile LVGL.
  - FW-24: the comments at `platformio.ini:68`, `test/test_custom_runner.py:15`,
    `test/test_link_wire/test_link_wire.cpp:23` and
    `test/test_proto_codec/test_proto_codec.cpp:27` now give
    `../.venv-pio/bin/pio test -e native`, run from twitch-screen-firmware, and point
    to README.md. `README.md:28` is unchanged on purpose: it names the old global pio
    as the case to avoid.
- Red check (the flag only, with the source unchanged):
  `platformio run -d twitch-screen-firmware -e esp32dev` exited with code 1:
  ```
  src/ui_idle.cpp:107:3: error: 'lv_anim_set_playback_duration' was not declared in this scope
  src/ui_notify.cpp:50:57: error: 'lv_anim_ready_cb_t' has not been declared
  src/ui_notify.cpp:58:14: error: 'lv_anim_set_ready_cb' was not declared in this scope
  src/ui_notify.cpp:98:3: error: 'lv_anim_set_playback_duration' was not declared in this scope
  src/ui_notify.cpp:383:3: error: 'lv_anim_set_playback_duration' was not declared in this scope
  src/ui_notify.cpp:385:3: error: 'lv_anim_set_ready_cb' was not declared in this scope
  ```
  No errors came from the LVGL or TFT_eSPI sources.
- Green check (after the renames), using the main checkout's `.venv-pio`, PlatformIO 6.1.18:
  - `platformio run -d twitch-screen-firmware -e esp32dev` reports SUCCESS with
    `-Wall -Wextra -Werror`. RAM is 67,256 B (20.5%) and flash is 1,152,845 B (36.6%),
    which is in line with earlier builds.
  - `platformio test -d twitch-screen-firmware -e native -e native-sanitized -v`: all
    10 test cases passed. Each env ran these checks with 0 failures: codec 5,379,
    presentation 18, fuzz 3,000, wire 849, session 91. There was no ASan or UBSan output.
  - `grep -rnE 'lv_anim_set_playback_|lv_anim_set_ready_cb|lv_anim_ready_cb_t' src include`
    found nothing (exit code 1).
  - `grep -n 'LV_DISABLE_API_MAPPING=1' platformio.ini` matches one line, line 49, in `[env:esp32dev]`.
  - `grep -rn 'penv/bin' twitch-screen-firmware --exclude-dir=.pio` matches only `README.md:28`.
  - `git diff --check` is clean. `src/credentials.h` is not staged.
