# Kimi firmware remediation — 2026-09-25

Scope: every firmware-specific item in `REVIEW_SUMMARY.kimi.md`, including its
Medium, Low, Nit and cross-cutting selections. Firmware changes live in the
`twitch-screen-firmware/` subtree. The root orchestrator owns protocol prose,
vector-generation anchoring and cross-project integration.

## Behavior and structural findings

| Review finding | Disposition and evidence |
| --- | --- |
| Duplicate WELCOME rewinds the application baseline | Fixed: streaming WELCOME tears down before `onWelcome`; session regression asserts only one greeting and unchanged high-water mark. |
| DNS Pending can wedge forever | Fixed: connect teardown cancels `DnsLookup`; generation tokens reject stale callbacks and permit a fresh request even when the prior callback is lost. Production helper is host-tested for cancellation, replacement, stale success and stale failure. The existing 3-second connection deadline covers DNS too. |
| Full-queue pause suppresses silence forever | Fixed: each continuous pause ends after twice the reported relay idle timeout (default 180 seconds), reconnecting without consuming or acknowledging the blocked EVENT. Session tests exercise the timeout and resumed queue delivery. |
| Watchdog init silently keeps 5 seconds | Claim disproved for the pinned SDK: `esp_task_wdt.h` explicitly says repeated initialization updates timeout/panic settings. Added checked/logged `esp_task_wdt_init(10, true)` and `esp_task_wdt_status(nullptr)` after the void Arduino `enableLoopWDT()` API. Hardware expiry behavior remains unmeasured. |
| App Serial.printf bypasses nonblocking link logging | Fixed: bounded `appLog` routes every application/display diagnostic through the transport's `availableForWrite()`-gated sink. Full UART drops diagnostics instead of delaying the loop. |
| CONNECTING timer/label heap churn while hidden | Fixed: named fixed strings use static label text; dots timer, spinner and logo animation stop whenever connecting is hidden or covered. |
| Unconditional per-STATS label updates | Fixed: cache the latest stats, update only the visible group, compare text/value before assigning; reveal reapplies the cached state. |
| EncodeResult negative sentinel / unsafe size conversion | Fixed: POD `{uint16_t size; EncodeError err;}` plus explicit `ok()`; all callers/test expectations migrated. |
| link_client singleton globals and manual resets | Fixed: one static `LinkSession` owns all mutable session fields, reset through value initialization. No heap allocation introduced. |
| deliverFrame long method | Fixed: extracted version, queue-pause, handshake and skipped-frame boundaries. |
| Parallel NotifyKind switches / raw hex known-kind list | Fixed: one sparse wire-ordinal `KindPresentation` table supplies known-kind membership, label, icon, color and default hold time; unknown wire codes retain INFO fallback. Host checks cover all 256 byte values. |
| Duplicate clearDecor / panel constants | Fixed: shared `ui_common.h`, `PANEL`, label helpers and decoration helper; used by both UI files and LVGL port. |
| uiNotifyShow long method | Fixed: extracted kind presentation, text binding and entrance handling. |
| Backoff burned while close-worker/DNS unavailable | Fixed: `readyForConnect()` defers local resource waits without increasing the failure count. DNS is cancelled on timeout instead of remaining unavailable. Session regression verifies the first actual failure still uses initial backoff. |
| WELCOME trusts unadvertised capability bits | Fixed: intersect with `DEVICE_CAPS` before storing and forwarding capabilities; regression checks the all-capabilities golden WELCOME. |
| Backoff reset contradicts protocol prose | Already aligned on baseline: firmware and protocol §12/§12.1 require 60 seconds of stable streaming. Existing host regression retained. |
| Null close-task notification | Fixed: descriptor acquisition requires a created worker; the notification is additionally guarded. Impossible fallback retains descriptor ownership and emits a diagnostic instead of notifying null. |
| Replay cards still animate | Fixed: replay immediately displays at y=0 with full opacity and no entrance, attention flash or ring pulse; also dismisses immediately after its hold. |
| Asset descriptor trusts generated size | Fixed: static assertions bind 48/84-pixel RGB565 arrays to their dimensions. |
| Unmeasured 8 KB loop task stack headroom | Instrumented: one-minute minimum-free-stack diagnostics use the SDK's byte-valued `uxTaskGetStackHighWaterMark`. No physical measurement fabricated; hardware replay/render/outage workload remains a validation limit. |
| LVGL boot allocations unchecked / panic boot loop | Fixed: explicit display-null check and LVGL 9.6 custom allocation/assertion handler. Failure halts rendering with repeating nonblocking diagnostics and services subscribed watchdog/idle tasks instead of rebooting in a loop. Uses current `LV_ASSERT_USE_CUSTOM_INCLUDE` API, not deprecated compatibility macros. |
| Overlapping Wi-Fi reconnect policies | Fixed: disable Arduino auto-reconnect; the transport owns its named 15-second retry interval. |
| Header extraction duplicated | Fixed: FrameReader uses `decodeHeader()` for validation/extraction. |
| Encode/check/write repeated four times | Fixed: `sendEncoded` is the typed result/write boundary. |
| BYE floor/max temporary fields | Fixed: teardown receives retry advice directly; advice cannot persist across a later connection. |
| static const header constants | Fixed: protocol constants and generated asset declarations use constexpr; generator matches generated source. |
| Magic operational limits | Fixed: named retry exponent, close-worker stack size, Wi-Fi retry and watchdog/stack-report constants; named session-test deadline constants. Layout geometry remains intentional literal placement. |
| Uptime/viewer label reallocations every tick | Fixed: persistent bounded buffers, compare-and-skip, static label text. |
| Every live card runs two full-panel effects | Fixed: routine cards use a slide; attention flash reserved for WARNING/ALERT; removed overlapping full-overlay opacity animation. |
| Infinite animations on hidden groups | Fixed: visibility/notification coverage owns pause/resume of dashboard animations/timers. |
| Unused LV_USE_FLOAT | Fixed: disabled; pinned ESP32 build passes. |
| TX FIFO memmove on every partial write | Fixed: advance a read offset; compact only when appending needs tail space. Exact fragmented HELLO and ACK regressions pass. |
| Wi-Fi PSK in unencrypted flash | Explicitly documented in firmware README. Enabling encryption/secure boot is a device provisioning/recovery migration and is not silently enabled. |
| HELLO bypasses sender string rules | Fixed: `buildHello` replaces control/non-ASCII identity/version bytes with `_`; preserves bounded NUL padding/truncation; host regression verifies sanitization. |
| nullptr hygiene / unused notification and UI includes | Fixed. Numeric zero comparisons remain numeric. |
| Chained CONNECTING dot ternary | Fixed: static string table. |
| Unseeded random jitter | Fixed: use ESP32 hardware RNG `esp_random()`. |
| Stale protocol-v2 comment in local credentials.h | Not present in tracked example (already TSB/3). Private ignored credentials are not changed or published. |

## Tests/tooling findings

| Review finding | Disposition and evidence |
| --- | --- |
| HELLO assertion only checked >=56 bytes | Fixed: assert exactly 68 bytes and byte-for-byte equality against independently built expected HELLO. |
| Differential-only parser fuzz | Fixed: retained 3000 chunk-invariance cases; added 1000 golden-frame/noise construction-oracle cases (exact hash, frame count, payload bytes, resync count) and 4095/4096/4097-byte resync-budget boundary oracles. A parser dropping every frame cannot pass. |
| Copied relay greeting predicate pretends to prove parity | Fixed: replace with explicit specification examples including zero/equal/ahead/behind/u32-max. Golden replay and cross-project socket checks provide integration evidence. |
| Native builds lack Werror | Fixed: `-Werror` inherited by both native environments. |
| ttl 6000/6001 boundary untested | Fixed: 5999, 6000 and 6001 exact decoder expectations. |
| Real timing literals in session tests | Named primary handshake/connect/ping/silence/ACK/stability/pause intervals; all tests use a fake clock and no sleeping. |
| Crash/no summary not represented by custom runner | Pinned PlatformIO base already records thrown process failures; strengthened custom runner to add a named missing-summary error and fail zero-check summaries. Five Python contract tests cover crash, missing, valid, zero and skipped cases. |
| formatCount rounding boundaries untested | Added 1049/1099/1100, 9949/9950/9999 and equivalent M/B boundaries. Policy truncates; tests document that it does not round upward. |
| gen_vectors zero prose assertions | Root-owned fix, deliberately not duplicated here. |

## Verification

- `pio test -e native -e native-sanitized`: all 10 suite runs pass, with project/test warnings treated as errors and ASan/UBSan enabled for the sanitized runs.
- PlatformIO Python `tools/test_runner_contract.py`: 5 tests pass.
- `pio run -e esp32dev`: passes with pinned Arduino-ESP32, TFT_eSPI and LVGL; final pre-documentation build used 67,228 bytes RAM and 1,154,753 bytes flash.
- `git diff --check`: passes.
- Pinned library source checked for watchdog reconfiguration, void loop-WDT API, FreeRTOS stack units, LVGL assertion hook and glyph bytes.
- No device upload, serial hardware observation, display rendering measurement, or watchdog-expiry experiment performed. UI rendering, physical outage recovery and minimum render-stack headroom remain hardware validation limits.

## Preserved explicit constraints

The root documents the trusted-home-LAN threat model (H2): plaintext TCP and
self-declared identity are not authentication. A trusted relay's BYE retry floor
remains uncapped as required by the current wire contract (FW-10); changing it
unilaterally would break that contract. No OTA updater was added (FW-18); USB
remains the documented update path. These architectural/physical items are not
misrepresented as implemented security or measured hardware guarantees.
