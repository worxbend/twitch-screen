---
name: gc9a01-display-debugging
description: Diagnose rendering problems (wrong colors, garbled image, flicker, artifacts) on the twitch-screen GC9A01 round LCD driven via TFT_eSPI + LVGL 9. Use when the user reports poor image quality, wrong colors, or screen glitches on the device.
---

# GC9A01 / TFT_eSPI / LVGL rendering pipeline debugging

Pipeline: LVGL renders into dual static draw buffers (240×40 RGB565, partial
mode) → `flushCb` in `src/lv_port.cpp` → `tft.pushPixels` → SPI @ 40 MHz →
GC9A01 panel.

## Diagnosis method: verify with color math, not guessing

When the user shows a photo with wrong colors, compute the expected vs.
observed RGB565 16-bit values — each failure mode has a deterministic color
signature. Known-good palette (from `src/ui_idle.cpp`): bg `#0E0E10`, purple
`#9146FF`, live red `#EB0400`, text `#EFEFF1`.

## Failure mode 1: byte order (endianness) — FIXED 2026-09, regression-watch

Symptom: every color wrong in a systematic way; white stays white. Observed:
near-black bg renders tan/olive, red LIVE pill renders dark blue, purple logo
renders green-cyan. Cause: LVGL renders RGB565 little-endian; GC9A01 expects
MSB-first over SPI.

Verify: swap the two bytes of the expected RGB565 value and check it decodes
to the observed color, e.g. bg `#0E0E10` → `0x0862` → swapped `0x6208` ≈
rgb(99,130,66) tan/olive; `#EB0400` → `0xE820` → `0x20E8` → dark blue.

Fix (applied in `src/lv_port.cpp`, keep it):
`lv_display_set_color_format(disp, LV_COLOR_FORMAT_RGB565_SWAPPED);`
LVGL 9.2+ has a dedicated swapped-blend path (`LV_DRAW_SW_SUPPORT_RGB565_SWAPPED`,
default on), so pixels leave the renderer MSB-first and `pushPixels` keeps its
fast block-SPI path. Do NOT also call `tft.setSwapBytes(true)` — that swaps
twice and falls back to a slow per-pixel write path in TFT_eSPI.

## Failure mode 2: display inversion (GC9A01 IPS panels)

Symptom: image looks like a photo negative — dark UI renders bright white,
colors complementary. Fix: TFT_eSPI GC9A01 init normally handles INVON; if
needed, call `tft.writecommand(TFT_INVON)` (0x21) after `tft.init()`.

## Failure mode 3: BGR vs RGB (MADCTL bit 3)

Symptom: red and blue channels swapped only — green tones stay correct
(unlike byte swap, which distorts greens too). Purple renders orange-ish,
red pill renders blue but background stays dark. Fix: `tft.setSwapBytes` is
unrelated; toggle MADCTL via `tft.writecommand(0x36)` data byte bit 3, or in
TFT_eSPI setup flags.

## Failure mode 4: SPI signal integrity

Symptom: random pixel sparkle, shifted rows, tearing that changes when wires
move; worse at higher clock. Fix: shorten/reseat jumper wires, keep
`SPI_FREQUENCY=40000000` (proven); only try 80 MHz if the panel stays clean.

## Not bugs — don't chase these

- Diagonal screen-door lines in photos: camera moiré.
- Slight color fringing at the panel edge: the panel itself.
- Uptime seconds jumping in 5 s steps: stats frames arrive every 5 s
  (NTP clock is on the PLAN.md roadmap).

## Verifying a fix on hardware

1. `~/.platformio/penv/bin/pio run -t upload` (see esp32-firmware-workflow skill).
2. Ask the user for a photo; check: background near-black, LIVE pill red,
   Glitch logo/ring Twitch purple, text white.
3. Only if colors are still wrong, move to the next failure mode above.
