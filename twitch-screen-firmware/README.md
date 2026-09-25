# twitch-screen-firmware

ESP32 firmware for the Waveshare GC9A01 round LCD, using Arduino, TFT_eSPI and
LVGL 9. It speaks binary [TSB/3](docs/PROTOCOL.md) to the Scala relay on TCP 8099.

From the repository root, create an isolated, reproducible PlatformIO setup:

```sh
python3 -m venv .venv-pio
.venv-pio/bin/python -m pip install platformio==6.1.18
cd twitch-screen-firmware
cp src/credentials.example.h src/credentials.h  # only if the local file is absent
```

Edit the ignored `src/credentials.h` with WiFi credentials and the relay's LAN
address. Preserve an existing local configuration. Wiring is in
[PLAN.md](PLAN.md) and the build flags in `platformio.ini`.

```sh
../.venv-pio/bin/pio test -e native
../.venv-pio/bin/pio test -e native-sanitized
../.venv-pio/bin/pio run -e esp32dev
../.venv-pio/bin/pio run -e esp32dev -t upload  # connected device
../.venv-pio/bin/pio device monitor            # 115200 baud
```

Run PlatformIO commands sequentially; they share a package store. If an old
`~/.platformio/penv/bin/pio` fails to import `platformio`, use the isolated setup
above. The pinned platform and library versions are in `platformio.ini`.

The native suites cover codec bytes, queues and session behavior. From the
repository root, `python3 tools/check_protocol_vectors.py` verifies both
implementations against the normative vectors. Native success and compilation
do not establish physical LCD rendering or WiFi/watchdog behavior.

For simulated events, follow the [root quick start](../README.md#how-the-pieces-talk)
using the relay's `RELAY_TWITCH_MODE=simulated`. Management requests require Basic
or Bearer credentials. The old Python demo is retired and incompatible.

The display and application callbacks stay on the Arduino loop. Queue overflow
refuses newest; it never silently discards an older accepted card. Recovery and
the finite replay guarantees are described in the protocol. USB updates remain
the supported deployment method; OTA requires a separate partition/recovery design.
