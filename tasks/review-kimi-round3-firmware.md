# Kimi round 3 firmware remediation (2026-09-25)

Scope: firmware units from the third remediation pass over
`REVIEW_SUMMARY.kimi.md`. Integration owns `tasks/review-kimi-summary.md` and
`tasks/todo.md`, so this file records only the per-unit evidence.

## K-025: per-STATS label updates are unconditional, including the hidden group

**Status: fixed, and a regression test now pins it.** The behaviour was
already fixed in round 1 (`renderVisibleStats`/`setLabelIfChanged`), but no
test caught it if the old writes returned. This unit closes that gap by
moving the decision into a pure, testable plan (Option 1 from the review).

### Change

- New `twitch-screen-firmware/src/stats_render_plan.h`: header-only C++11. It
  depends only on `stdint.h`, `string.h`, `stats.h` and `presentation.h`, with
  no Arduino or LVGL. It provides:
  - `visibleStatsGroup(linkUp, covered, live)`, the single rule that says
    which stat group is on screen (`None` / `Live` / `Offline`);
  - `StatsShown`, a mirror of the panel: live and offline chip texts, the
    viewers count-up origin, and the arc value;
  - `planStatsRender()`, which decides the chip, viewers and arc writes for
    the visible group only, as compare-and-skip against the mirror. The arc
    is clamped to 100;
  - `commitStatsPlan()` and `forgetLiveViewers()`.
- `twitch-screen-firmware/src/ui_idle.cpp`:
  - `renderVisibleStats()` builds the plan, commits it, then writes only the
    flagged chips (`lv_label_set_text_static` on the mirror's storage), the
    uptime tick (`setStaticLabel` still compare-skips it), viewers, and arc.
  - `applyVisibility()` hides groups using `visibleStatsGroup()` and calls
    `forgetLiveViewers()` when leaving live.
  - `shownViewers` is replaced by the mirror.
  - `setViewers()` takes the previous value as its count-up origin, so the
    animation is unchanged.
- `twitch-screen-firmware/src/ui_common.h`: removed `setLabelIfChanged`,
  which is now dead (ui_idle.cpp was its only caller).
- `twitch-screen-firmware/test/test_presentation/test_presentation.cpp`: 31
  new checks through the `nextStats()` and `apply()` helpers. They cover:
  - the first live STATS writes live chips, viewers and arc only;
  - identical live STATS and identical offline STATS cause zero writes;
  - a single changed field sets exactly one flag;
  - the arc clamps at 250, and a later 300 causes no write;
  - offline STATS never touch viewers, arc or uptime, and leave the live
    mirror alone;
  - covered and connecting cause zero writes and no commit;
  - re-showing a group redraws the cached totals against that group's own
    mirror;
  - `forgetLiveViewers` forces the viewers write again;
  - counts in the same rounding bucket (1049 then 1099) cause no write;
  - `visibleStatsGroup` gives the right group for all 8 combinations.
- `platformio.ini` is unchanged, because the header-only code needs no
  `build_src_filter` entry.

### Evidence (in `twitch-screen-firmware/`, pio = `.venv-pio/bin/pio`)

- Red: with only the new tests added, `pio test -e native -f test_presentation`
  failed to compile (`fatal error: stats_render_plan.h: No such file or directory`).
- Green: `test_presentation` passes with `316 checks, 0 failures` (285 checks
  before this unit, 31 new).
- Mutation 1: `planStatsRender` was changed to set every chip, viewers and arc
  flag whenever the group is not `None`. 8 checks failed: identical live and
  offline STATS, the single-field change, the arc clamp and re-clamp, the
  live chips after an offline spell, the forgotten viewers, and the
  rounding bucket. The change was reverted.
- Mutation 2: `visibleStatsGroup` was changed to ignore covered and
  link-down. 11 checks failed: covered and connecting zero writes, uptime not
  ticking while connecting, the mirror not committed while hidden, and 6 of
  the 8 visibility combinations. The change was reverted.
- `pio test -e native -e native-sanitized`: 10 test cases, 10 succeeded
  (5 suites x 2 environments, with ASan and UBSan in the sanitized
  environment, and `-Wall -Wextra -Werror` under gnu++11).
- `pio run -e esp32dev`: SUCCESS. RAM 67,276 B (baseline 67,228 B, +48 B for
  the mirror). Flash 1,154,969 B (baseline 1,154,753 B, +216 B).
- `git -C .. diff --check`: clean.
- `grep -rn "setLabelIfChanged\|shownViewers" src test`: no matches.

Behaviour is preserved:

- the same text and values end up on screen;
- the viewers count-up still starts from the previously shown value, and
  from the new value after leaving live;
- the rules for which group is visible are the same.

The mirror starts from what `buildLive`/`makeChip` draw ("0" chips, arc 0),
so the first frame writes exactly what the old `lv_label_get_text` or
`lv_arc_get_value` comparisons wrote. None of this has been observed on
hardware.

## K-107: replayed cards still play the slide-in

Finding K-107 (Low, firmware), "Replayed cards still play slide-in despite
comment/§6.4", at `ui_notify.cpp:364-370`.

### Status

The behaviour was already fixed before this round: `playEntrance` showed a
replayed card at once, and `hideStart` skipped the slide-out for it. No test
covered it, because `ui_notify.cpp` is not built in `env:native`. This unit
moves the entrance decision into a pure function and pins it with a regression
test. On-device behaviour is unchanged. `docs/PROTOCOL.md:469` and `:1477`
say "A receiver SHOULD render a replayed card without the entrance animation".

### Change

- `src/presentation.h`: includes `notification.h` (pure, no cycle) and adds
  `enum class Entrance : uint8_t { None, Slide, FlashThenSlide }` and
  `entranceFor(bool replay, NotifyKind kind)`. Replay gives `None` for every
  kind. Otherwise Warning and Alert give `FlashThenSlide`, and every other kind
  gives `Slide`.
- `src/ui_notify.cpp`: `bool replayCard` is now `Entrance cardEntrance`, set once
  in `uiNotifyShow` from `entranceFor(n.replay, n.kind)`. `hideStart` skips the
  slide-out when it is `None`. `playEntrance` switches on it (None: immediate
  show; Slide: `slideIn()`; FlashThenSlide: the unchanged flash block). The
  inline `n.replay` and Warning/Alert checks are gone. The timings, call order
  and flash colour (still `n.kind`) are the same.
  `grep -n "n.replay\|replayCard" src/ui_notify.cpp` finds only the
  `entranceFor(n.replay, n.kind)` call.
- `test/test_presentation/test_presentation.cpp`: 528 new checks:
  - all 256 codes with replay give `None`;
  - all 256 codes without replay give `FlashThenSlide` exactly for
    Warning/Alert and `Slide` otherwise;
  - explicit checks that a replayed Alert or Warning gives `None` and a live
    one gives `FlashThenSlide`;
  - the 10 routine kinds, plus unknown codes `0x18` and `0xff`, give `Slide`
    when live.
- `platformio.ini` is unchanged, because the code is header-only.

### Evidence (in `twitch-screen-firmware/`)

`pio` is `/home/worxbend/.platformio/penv/bin/pio`. The system Python moved to
3.14, so that entry point now fails with `ModuleNotFoundError: No module named
'platformio'`. The runs used a scratchpad wrapper that runs `/usr/bin/python3 -m
platformio` with `PYTHONPATH=~/.platformio/penv/lib/python3.13/site-packages`
(PlatformIO Core 6.2.0). The penv itself was not modified.

- Baseline on 938019a: `pio run -e esp32dev` SUCCESS, RAM 67,276 B, Flash
  1,154,969 B.
- Red: with only the tests added, `pio test -e native -f test_presentation`
  failed to compile (`'entranceFor' was not declared in this scope`,
  `'Entrance' has not been declared`).
- Green: `844 checks, 0 failures` (316 before, 528 new).
- Mutation A: the `if (replay) return Entrance::None;` line was dropped from
  `entranceFor`. 258 checks failed: 256 "replay never animates, any kind" and
  "replayed alert/warning skips flash". The change was reverted.
- Mutation B: Alert was made to give `Slide` (the condition became Warning
  only). 2 checks failed: "live alert flashes" and "live cards flash only for
  warning and alert, any code". The change was reverted.
- `pio test -e native -e native-sanitized`: 10 test cases, 10 succeeded
  (5 suites x 2 environments).
- `pio run -e esp32dev`: SUCCESS. RAM 67,276 B (+0). Flash 1,154,973 B (+4 B).
- `git -C .. diff --check`: clean.

None of this has been observed on hardware.

## K-118: routine-card ring pulse repaints the full panel

Finding K-118 (Low, firmware), "Every live card pays two full-screen
animations", at `ui_notify.cpp:370-386`. Remaining gap: `slideIn()` started an
infinite 600 ms opacity pulse on the 232x232 accent `ring` for every live card.
Object-wide style opa below COVER forces layered rendering, so a routine card
repainted nearly the whole 240x240 panel on every frame for its whole hold.

### Status

Fixed in code with option (a): the ring is static on routine cards, and only
live WARNING/ALERT cards keep the pulse. Not verified on hardware (see "Owed to
owner").

### Change

- `src/presentation.h`: adds `inline bool ringPulses(Entrance e)`, which is true
  only for `Entrance::FlashThenSlide`. It is keyed off `Entrance`, so severity is
  defined once (in `entranceFor`) and a replayed card (`None`) can never pulse.
- `src/ui_notify.cpp`: `slideIn()` starts the ring `lv_anim` only when
  `ringPulses(cardEntrance)`. The overlay slide is unchanged. The comments on
  `slideIn`, `hideStart` and the `Entrance::Slide` case now describe the static
  routine ring. `hideStart` still deletes the ring animation and sets the ring to
  `LV_OPA_COVER`. Both are no-ops after a routine card, and after a severe card
  they reset the ring so the next routine card starts at COVER.
- `test/test_presentation/test_presentation.cpp`: a K-118 block. `None` and
  `Slide` do not pulse and `FlashThenSlide` does. For all 256 codes, a live card
  pulses exactly when it is Warning/Alert, and a replayed card never pulses. The
  10 routine kinds plus unknown codes `0x18` and `0xff` do not pulse when live.
- Preserved: all timings (slide in/out, 600 ms pulse with reverse, hold), the
  WARNING/ALERT flash and its colour, the severe pulse and its stop/reset in
  `hideStart`, and replay (shown at once, static, leaves at once).

### Evidence (in `twitch-screen-firmware/`)

`pio` is the same scratchpad wrapper as for K-107 (`/usr/bin/python3 -m
platformio` with `PYTHONPATH=~/.platformio/penv/lib/python3.13/site-packages`,
PlatformIO Core 6.2.0).

- Red: with only the tests added, `pio test -e native -f test_presentation`
  failed to compile (`'ringPulses' was not declared in this scope`).
- Green: `1371 checks, 0 failures` (844 before, 527 new: 3 + 512 + 12).
- Mutation A: `ringPulses` returned `e != Entrance::None` (the old behaviour,
  where every live card pulses). 267 checks failed: 254 "only live warning/alert
  pulse the ring, any code", 12 "routine live ring stays static" and 1 "routine
  ring is static". The change was reverted.
- Mutation B: `ringPulses` returned `false`. A bare `return false;` did not
  compile (unused parameter under the native warning flags), so the mutant was
  `(void)e; return false;`. 3 checks failed: "severe ring pulses" and 2 "only
  live warning/alert pulse the ring, any code" (Warning and Alert). The change
  was reverted.
- `pio test -e native -e native-sanitized`: 10 test cases, 10 succeeded
  (5 suites x 2 environments).
- `pio run -e esp32dev`: SUCCESS. RAM 67,276 B (+0). Flash 1,154,981 B (+8 B
  against 1,154,973 B).
- `git -C .. diff --check`: clean.
- `grep -n ringPulses src/ui_notify.cpp src/presentation.h
  test/test_presentation/test_presentation.cpp`: one definition, one use in
  `slideIn`, and the tests.

### Owed to owner

- An on-device check that a routine card's hold no longer invalidates the full
  panel. Use the LVGL perf monitor (`LV_USE_PERF_MONITOR`) or a flush-area log in
  `lv_port.cpp`. Also check that a WARNING/ALERT card still pulses and that the
  next routine card shows a solid ring. None of this has been observed on
  hardware.

## K-116: magic numbers (viewer animation threshold, retry-counter saturation)

Finding K-116 (Low, firmware), "Magic numbers (3072, 15000, shift caps 6/5,
watchdog 10)". Status: fixed. Most of the literals the finding names already had
constants before this change: 3072 is `CLOSE_STACK_BYTES`, 15000 is
`WIFI_RETRY_MS` (`src/link_transport_esp32.cpp`), the shift caps are
`BACKOFF_MAX_SHIFT` (+1) (`src/link_client.cpp`), and watchdog 10 is
`WATCHDOG_TIMEOUT_S` (`src/main.cpp`). This change names the two behavioural
literals that were still bare.

### Change

- `src/ui_idle.cpp`: added `constexpr uint32_t VIEWER_ANIM_MAX = 10000;` next to
  `COUNT_ANIM_MS`, with a comment. Counts at or above it skip the count-up and are
  drawn directly. `formatCount` (`src/presentation.h`) shows them as "10K" and
  up. `setViewers` uses the constant at both sites that repeated `10000`. The
  `from` comparison is written `from >= (int64_t)VIEWER_ANIM_MAX`, so it stays a
  signed 64-bit comparison exactly as it was with the `int` literal.
- `src/link_client.cpp`: added `constexpr uint8_t FAILURES_SATURATE = 31;` below
  `BACKOFF_MAX_SHIFT`. Its comment says the bound stops the `uint8_t` counter
  wrapping to 0 (which would reset the ramp to 1 s) and keeps any
  `(failures - 1)` shift below the 32-bit width. It is guarded by
  `static_assert(FAILURES_SATURATE > BACKOFF_MAX_SHIFT + 1 && FAILURES_SATURATE < 32, ...)`.
  `scheduleRetry` now reads `if (failures < FAILURES_SATURATE) ++failures;`.
- The backoff math, log text, the reset on a stable stream, and the tests are
  unchanged. No includes were added.

### Evidence (in `twitch-screen-firmware/`)

`pio` is the same scratchpad wrapper as for K-107 (PlatformIO Core 6.2.0).

- Naming is not testable at runtime, so no runtime test was added. The existing
  `test_link_session` backoff cases pass unchanged, which shows the behaviour is
  preserved.
- static_assert mutation: setting `FAILURES_SATURATE` to 6 and then to 32 each
  failed `pio test -e native -f test_link_session` to compile (`static assertion
  failed: retry counter bound must cover the backoff ramp ...`). Both were
  reverted to 31, and the suite passed again (1/1).
- Baseline before the edit: `pio run -e esp32dev` SUCCESS, RAM 67,276 B, Flash
  1,154,981 B.
- After: `pio run -e esp32dev` SUCCESS, RAM 67,276 B (+0), Flash 1,154,981 B (+0).
- `pio test -e native -e native-sanitized`: 10 test cases, 10 succeeded
  (5 suites x 2 environments).
- `git -C .. diff --check`: clean.
- `grep -n "10000" src/ui_idle.cpp` returns only line 23, the
  `VIEWER_ANIM_MAX` definition. `grep -n "< 31" src/link_client.cpp` returns
  nothing.

## K-128: magic timing literals in link session tests

Finding K-128 (Low, firmware), "Magic timing literals in session tests". Status:
fixed. An earlier change named the handshake, ping, silence, stable and pause
timings (`SECOND_MS` through `DEFAULT_PAUSE_LIMIT_MS`). This change names the
retry and BYE timings that were still bare. The change is test-only, and every
value stays numerically identical.

### Change

- `test/test_link_session/test_link_session.cpp`: added three constants after
  `DEFAULT_PAUSE_LIMIT_MS`, under a comment saying they mirror
  `src/link_client.cpp` and must change with it:
  `RETRY_BASE_MS = 1 * SECOND_MS` (`BACKOFF_BASE_MS`),
  `RETRY_CAP_MS = 30 * SECOND_MS` (`BACKOFF_MAX_MS`), and
  `BYE_FLOOR_MS = 60 * SECOND_MS` (the `retry_after_s` carried by the
  unknown-code BYE frame). Two `static_assert`s check that the BYE floor is
  above the ramp cap and fits the `retry_after_s` low byte.
- The literals were rewritten as follows:
  - Connect-deadline retry `+= 1000` becomes `RETRY_BASE_MS`.
  - Backoff ramp `998`/`1001` become `RETRY_BASE_MS - 2` and `RETRY_BASE_MS + 1`.
    The `+= 2` stays, because it is the step that crosses the deadline.
  - The outage loop's 1 s wall-clock step becomes `SECOND_MS`.
  - The busy-worker retry becomes `RETRY_BASE_MS`.
  - `goodbye[12] = 60` becomes `(uint8_t)(BYE_FLOOR_MS / SECOND_MS)`, and
    `59990` becomes `BYE_FLOOR_MS - 10`. The `+= 20` stays, because it is the
    step that crosses the floor.
  - REPLACED/UNSUPPORTED_VERSION `t0 + 1000/2000/29999/30000` become
    `RETRY_BASE_MS`, `2 * RETRY_BASE_MS`, `RETRY_CAP_MS - 1` and `RETRY_CAP_MS`.
  - The stable split `30000`/`30001` becomes `STABLE_INTERVAL_MS / 2` and
    `STABLE_INTERVAL_MS / 2 + 1`. The two `+= 1000` retries become
    `RETRY_BASE_MS`.
  - Wrap `999` becomes `RETRY_BASE_MS - 1`, with a comment that the pump ticks
    supply the last millisecond.
- Byte, sequence, address and loop-count literals are unchanged (`0xfffffff0u`,
  `0x12345678`, `256`, `120`, `100`, and the `999` BYE code comment), and so is
  `ACK_COALESCE_MS = 200`. `src/` is unchanged.

### Evidence (in `twitch-screen-firmware/`)

`pio` is the same scratchpad wrapper as for K-107 (PlatformIO Core 6.2.0).

- Renaming is not testable at runtime, so no runtime test was added. The
  existing assertions prove the rename preserves behaviour.
- An accidental red during editing: a first draft put the wrap comment before
  `pump(wrap);`, which commented the pump out. `pio test -e native -f
  test_link_session` then failed "retry deadline is wrap-safe" (104 checks,
  1 failure). This shows the wrap check depends on that pump. The comment was
  moved after the call.
- Green: `pio test -e native -f test_link_session` and `pio test -e
  native-sanitized -f test_link_session`: 104 checks, 0 failures, 1/1 succeeded
  in each.
- Mutation A: setting `RETRY_BASE_MS = 2 * SECOND_MS` failed 2 checks out of 104
  ("backoff waits for deadline", "short streaming session preserves exponential
  ramp"). It was reverted.
- Mutation B: setting `RETRY_CAP_MS = 31 * SECOND_MS` failed 2 checks out of 104
  ("REPLACED waits for 30 s cap, not the 1 s ramp", "UNSUPPORTED_VERSION with
  retry_after 0 still jumps to 30 s cap"). It was reverted.
- Mutation C: setting `BYE_FLOOR_MS = 61 * SECOND_MS` passed (0 failures). This
  is expected, because the frame's `retry_after_s` and the wait are now both
  derived from the one constant. The test pins the relationship "the device
  honours the relay-supplied floor", not the value 60 s. It was reverted.
- After reverting, the suite was green again: 104 checks, 0 failures.
- `pio test -e native -e native-sanitized`: 10 test cases, 10 succeeded
  (5 suites x 2 environments).
- `pio run -e esp32dev`: SUCCESS, RAM 67,276 B (+0), Flash 1,154,981 B (+0)
  (test-only change).
- `git -C .. diff --check`: clean.
- `grep -nE '(time|t0)[^;]*[0-9]{3,}' test/test_link_session/test_link_session.cpp`:
  no matches.
- `grep -nE '[+ ]=?[ ]*[0-9]{3,}' test/test_link_session/test_link_session.cpp`:
  11 lines. They are the `SECOND_MS`, `ACK_COALESCE_MS` and
  `DEFAULT_PAUSE_LIMIT_MS` definitions, `writeChunk = 256`, the `& 255` byte
  masks (2 lines), the loop counts `120` (comment and loop) and `100` (2 lines),
  and the `unknown code 999` comment. None is a protocol timing.
