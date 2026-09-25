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
