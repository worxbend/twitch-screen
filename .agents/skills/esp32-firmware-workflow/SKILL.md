---
name: esp32-firmware-workflow
description: Build, flash, and monitor the twitch-screen ESP32 firmware with PlatformIO. Use for firmware compilation, upload, serial debugging, platformio.ini, or firmware project layout changes.
---

# ESP32 firmware workflow

Project: `twitch-screen-firmware/`, ESP32 DevKit V1 (WROOM-32), Waveshare
GC9A01 240×240 LCD, Arduino framework, `huge_app` partition. The wire contract
is binary **TSB/3**, in `docs/PROTOCOL.md`; the retired NDJSON demo is incompatible.

## Toolchain and verification

Use a working `pio` on PATH or a dedicated virtual environment. The old
`~/.platformio/penv/bin/pio` may exist but fail with `ModuleNotFoundError` after
host Python updates. Check `pio --version` before relying on it. From the
repository root, an isolated setup is:

```sh
python3 -m venv .venv-pio
.venv-pio/bin/python -m pip install platformio==6.1.18
cd twitch-screen-firmware
../.venv-pio/bin/pio test -e native
../.venv-pio/bin/pio test -e native-sanitized
../.venv-pio/bin/pio run -e esp32dev
```

Never run concurrent PlatformIO installs/builds against the same package store.
Use the pinned platform/library versions in `platformio.ini`; changing a pin
requires recompilation and an explicit account of hardware validation limits.
Run `python3 tools/check_protocol_vectors.py` from the root for contract drift.

For an authorized hardware check, from the firmware directory:

```sh
../.venv-pio/bin/pio run -e esp32dev -t upload
../.venv-pio/bin/pio device monitor   # 115200 baud
```

A compile or native test does not establish correct display rendering, WiFi
outage recovery or watchdog behavior on a physical device.

## Files and ownership

- `src/main.cpp`: application queue, display dispatch, Arduino loop.
- `src/link_client.*`: connection lifecycle and transport integration.
- `src/proto_codec.*`: bounded binary codec and stream reader.
- `src/notify_queue.h`, `notification_wire.h`: queue admission, replay mark and
  wire-to-display conversion; overflow refuses newest, never drops oldest.
- `src/lv_port.*`, `ui_idle.*`, `ui_notify.*`: LVGL/TFT glue and presentation.
- `test/`: native codec, queue and session regressions.
- `include/lv_conf.h`: project LVGL configuration.

Keep LVGL and application callbacks on the Arduino loop. Network operations
must leave that loop responsive. Use wrap-safe unsigned `millis()` differences.
Do not change frozen wire fields or repurpose informational ACK as flow control.

## Wiring and credentials

TFT_eSPI configuration lives in `platformio.ini` flags, not `User_Setup.h`:
MOSI 23, SCLK 18, CS 5, DC 16 (RX2), RST 4, BL 15; SPI 40 MHz.

Copy `src/credentials.example.h` to ignored `src/credentials.h` if absent.
Preserve existing real credentials; never print or commit them. Set the WiFi
credentials and relay LAN host/port. See the root README and the
`twitch-protocol-testing` skill for relay simulation.
