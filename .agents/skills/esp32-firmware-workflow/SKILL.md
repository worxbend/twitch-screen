---
name: esp32-firmware-workflow
description: Build, flash, and monitor the twitch-screen ESP32 firmware (twitch-screen-firmware/, PlatformIO). Use when compiling, uploading, or serial-debugging the device, or when touching platformio.ini, build flags, or the project layout.
---

# ESP32 firmware workflow (twitch-screen-firmware)

PlatformIO project: ESP32 DevKit V1 (WROOM-32) + Waveshare 1.28" round LCD
(GC9A01, 240×240, SPI). Arduino framework, `huge_app` partition.

## Toolchain — pio is NOT on PATH

Use the venv binary directly:

```bash
~/.platformio/penv/bin/pio run                # build (~8-12 s incremental)
~/.platformio/penv/bin/pio run -t upload      # flash (~33 s, auto-detects port)
~/.platformio/penv/bin/pio device monitor     # serial monitor, 115200 baud
~/.platformio/penv/bin/pio run -t upload && ~/.platformio/penv/bin/pio device monitor
```

Known ops issue (from firmware PLAN.md): the `~/.platformio/penv` venv has
broken twice from parallel modification — never run two pio installs/builds
concurrently; if pio breaks, rebuild the venv rather than repairing in place.

Build runs from `twitch-screen-firmware/`. Budget after a full build:
RAM ~32 %, Flash ~39 % — plenty of headroom for new UI features.

## Project layout

- `src/main.cpp` — app: 8-slot notification queue, seq dedup/re-baseline, loop
- `src/link_client.{h,cpp}` — never-die TCP client (protocol v2), WiFi helper
- `src/lv_port.{h,cpp}` — TFT_eSPI + LVGL 9 glue, flush callback
- `src/ui_idle.{h,cpp}` — live dashboard / offline screen / connecting widget
- `src/ui_notify.{h,cpp}` — event overlay cards
- `src/notification.h`, `src/stats.h` — models, `NotifyKind` enum + labels
- `src/assets/twitch_glitch.h` — generated RGB565 logo (regenerate with `tools/make_glitch.py`)
- `include/lv_conf.h` — LVGL config (fonts 14/20/28/48 enabled)
- `docs/PROTOCOL.md` — wire protocol v2 contract (shared with server projects)
- `PLAN.md` — requirements, done-list, roadmap; keep it updated when shipping features

## TFT_eSPI config lives in platformio.ini build flags

There is NO `User_Setup.h`. Pin map, driver (`GC9A01_DRIVER`), and SPI speed
are `-D` flags in `platformio.ini`. Changing wiring = edit build flags:

MOSI 23, SCLK 18, CS 5, DC 16 (silkscreen "RX2"), RST 4, BL 15.
`SPI_FREQUENCY=40000000` is proven; 80 MHz is untested (flag comment says
raise only if the panel stays clean).

## Credentials

`src/credentials.h` is git-ignored; template is `src/credentials.example.h`
(WIFI_SSID, WIFI_PASSWORD, SERVER_HOST, SERVER_PORT, DEVICE_ID). Never commit
the real file; never read it out to the user.

## Conventions in this codebase

- Single-threaded: everything (link callbacks, UI updates, LVGL) runs on the
  Arduino loop — no LVGL locking needed, keep it that way.
- `millis()` arithmetic is wrap-safe unsigned subtraction everywhere; match it.
- UI text buffers: `Notification.title[48]`, `body[96]`, always `strlcpy`.
- Loop ends with `delay(5)`; `lvPortPump()` must run every iteration — do not
  add long blocking calls to `loop()` (blocking WiFi reconnect freezing the
  connecting animation is a known open issue, see PLAN.md review notes).
