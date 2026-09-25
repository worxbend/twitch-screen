# ⚡ Configure, build and flash the firmware

[← Guidebook](../README.md) · [Build the device](device-build.md) · [Use the device →](device-use.md)

This guide installs the firmware on the classic ESP32 DevKit V1 with the Waveshare GC9A01 LCD. **Flashing** writes the program to the ESP32. Configuration is compiled into that program, so changing Wi-Fi or the relay address requires rebuilding and uploading.

The commands below use a Linux/macOS-style shell from the repository root unless a step changes directory. The native sanitizer target is configured for Linux. Windows command-path differences are listed below; a Windows hardware run is not established by these instructions.

## 1. Check the prerequisites

- [Wire the LCD](device-build.md#2-wire-the-display) and connect the ESP32 with a USB **data** cable.
- Install Git, OpenSSL and Python 3 with virtual-environment support. Debian/Ubuntu may need `python3-venv`; PlatformIO's [Python installation notes](https://docs.platformio.org/en/latest/faq/install-python.html) cover OS differences.
- For native tests, install a host C++ compiler/toolchain. The cross compiler for the ESP32 is downloaded by PlatformIO.
- Have a 2.4 GHz Wi-Fi network and a computer reachable from it to run the relay. The classic WROOM-32 uses the 2.4 GHz band; see [Espressif's module datasheet](https://documentation.espressif.com/esp32-wroom-32_datasheet_en.html).
- The [simulated-stream tutorial](first-simulated-stream.md) is a useful introduction, but its loopback-only listener cannot accept a physical ESP32. A Twitch application is not required for this first device check.

### Start a relay the physical device can reach

Finish and stop any earlier tutorial instance first, using its shutdown step, so ports 8080 and 8099 are free. In a **separate terminal starting at the repository root**, launch this simulated relay:

```sh
cd twitch-screen-relay
export RELAY_HTTP_AUTH_API_TOKEN="$(openssl rand -hex 32)"
RELAY_HTTP_HOST=127.0.0.1 RELAY_HTTP_PORT=8080 \
  RELAY_DEVICE_HOST=0.0.0.0 RELAY_DEVICE_PORT=8099 \
  RELAY_TWITCH_MODE=simulated ./mill run
```

This keeps management HTTP on the relay computer while accepting device TCP connections on its network interfaces. Use a trusted LAN and allow TCP 8099 through the host firewall. The `0.0.0.0` listener address is **not** the address to put in the firmware: `SERVER_HOST` must be this computer's actual LAN address.

Wait for `Device link listening on 0.0.0.0:8099` and `Management API on http://127.0.0.1:8080/docs` in the startup log. **Leave this terminal running through the upload and first-boot check.** Continue the remaining commands in another terminal at the repository root. Stop the relay with Ctrl+C when finished. For an existing configured backend, use its reachable device listener instead; [relay setup](relay-setup.md) explains persistent deployment.

## 2. Install the project's PlatformIO version

From the repository root:

```sh
python3 -m venv .venv-pio
.venv-pio/bin/python -m pip install platformio==6.1.18
.venv-pio/bin/pio --version
```

The final command should identify PlatformIO Core 6.1.18. Keep the explicit virtual-environment paths in subsequent commands; shell activation is unnecessary.

The project uses an isolated, pinned environment for reproducibility. PlatformIO's [installation guide](https://docs.platformio.org/en/latest/core/installation/) describes its general installer options. Avoid installing the project toolchain into your system Python, and run PlatformIO commands **sequentially** because they share a package store.

The first build downloads the pinned ESP32 platform, framework, toolchain and libraries. Allow network access and time for that initial setup.

### Windows path equivalents

In PowerShell, use `py -3 -m venv .venv-pio`, then `.\.venv-pio\Scripts\python.exe` and `.\.venv-pio\Scripts\pio.exe`. From the firmware directory, use `..\.venv-pio\Scripts\pio.exe`. A serial port might be `COM3` instead of `/dev/ttyUSB0`. Use a native Windows USB driver appropriate to the board's actual USB-to-serial chip; do not assume every Type-C clone uses the same one.

## 3. Create the private configuration

Enter the firmware directory and copy the template **only when the private file does not already exist**:

```sh
cd twitch-screen-firmware
if [ ! -e src/credentials.h ]; then
  cp src/credentials.example.h src/credentials.h
fi
```

Open `src/credentials.h` in your editor and replace its placeholders:

```cpp
#pragma once

#define WIFI_SSID "your-2.4ghz-network"
#define WIFI_PASSWORD "your-wifi-password"
#define SERVER_HOST "192.168.1.100"
#define SERVER_PORT 8099
#define DEVICE_ID ""
```

| Setting | What to enter |
| --- | --- |
| `WIFI_SSID` | Exact network name, including case |
| `WIFI_PASSWORD` | Wi-Fi password; escape quotes/backslashes correctly in a C++ string |
| `SERVER_HOST` | Relay computer's LAN IPv4 address or a hostname resolvable by the ESP32; no `http://`, path or port suffix |
| `SERVER_PORT` | Relay device TCP port, normally `8099` |
| `DEVICE_ID` | Leave empty for the automatic identity, or choose a unique readable name |

`127.0.0.1` and `localhost` mean **the ESP32 itself** when used by its firmware. `0.0.0.0` is a server bind address, not the address a device should connect to. Use the relay computer's actual LAN address and reserve it in your router if possible.

Port **8099 is the device link**. Port **8080 is the HTTP management API**. They speak different protocols and cannot be interchanged. Allow the device TCP connection through the host firewall on your trusted LAN; guest Wi-Fi client isolation can prevent it even when both machines have Internet access.

The TCP device connection has no application authentication or encryption. Keep it on a trusted network. Twitch client secrets and management API tokens belong in the relay configuration, not in this header.

### Device identity

An empty `DEVICE_ID` produces `lcd-<chip MAC>` automatically, making it unique per physical ESP32. A custom identity must be **1–31 printable ASCII bytes**, nonblank and unique among connected devices. Use a short value such as `desk-lcd-01`. Longer configured strings are truncated by the firmware and can collide.

Two devices claiming the same identity replace one another at the relay and log `BYE code=9` (`REPLACED`). Leaving the default empty value avoids this common setup trap.

`src/credentials.h` is ignored by Git. Keep it private and preserve it during updates. The compiled firmware also contains these values: treat the resulting firmware binary as private configuration material.

## 4. Verify and compile

Run these from `twitch-screen-firmware/`, one at a time:

```sh
../.venv-pio/bin/pio test -e native
../.venv-pio/bin/pio test -e native-sanitized
../.venv-pio/bin/pio run -e esp32dev
```

The `native` target tests codec, session, queue and presentation behavior on the computer. `native-sanitized` repeats the host suites with AddressSanitizer and UndefinedBehaviorSanitizer on Linux. `esp32dev` compiles the actual firmware. Successful commands end with passing test/build results; a firmware build writes `.pio/build/esp32dev/firmware.bin`.

From the repository root, also check the shared protocol fixtures:

```sh
python3 tools/check_protocol_vectors.py
```

These checks establish software behavior and buildability. Physical rendering, wire integrity and recovery during a real Wi-Fi outage need observation on the device.

### What is pinned

The authoritative settings live in [platformio.ini](../../twitch-screen-firmware/platformio.ini):

| Item | Project setting |
| --- | --- |
| Board / framework | `esp32dev` / Arduino |
| ESP32 platform | `espressif32 @ 7.1.2` |
| Arduino framework package | `4.20017.260907+sha.dcc1105b` |
| TFT_eSPI | `2.5.43` |
| LVGL | `9.6.0` |
| Partition layout | `huge_app.csv` |
| LCD SPI clock | 40 MHz |
| Upload / monitor speeds | 460800 / 115200 baud |

The display driver and GPIO mapping are configured by build flags. There is no library `User_Setup.h` to edit. USB upload is the supported update path; this firmware does not implement OTA provisioning or updates.

## 5. Select the serial port

With the board connected, from `twitch-screen-firmware/`:

```sh
../.venv-pio/bin/pio device list
```

Identify the port that belongs to the ESP32. Linux often uses `/dev/ttyUSB0` or `/dev/ttyACM0`, macOS often uses `/dev/cu.*`, and Windows uses `COM` names. Substitute your real port in the commands below.

On Linux, a permission error is usually a host-device access problem. Follow PlatformIO's [udev/group-membership instructions](https://docs.platformio.org/en/stable/core/installation/udev-rules.html), reconnect the board and, when group membership changes, log out and back in. Run your normal build as your own user.

Close any existing serial monitor before uploading so it releases the port.

## 6. Upload

This command writes to the connected ESP32:

```sh
../.venv-pio/bin/pio run -e esp32dev -t upload --upload-port /dev/ttyUSB0
```

When only one board is attached, PlatformIO can usually autodetect the port and the `--upload-port` argument can be omitted. Keep it explicit when several boards are connected.

If upload remains at `Connecting…`, hold the board's **BOOT** button during the upload connection attempt. If necessary, hold BOOT, briefly press EN/reset, and release BOOT once the tool starts writing. Espressif documents this GPIO0-at-reset behavior in [boot mode selection](https://docs.espressif.com/projects/esptool/en/latest/esp32/advanced-topics/boot-mode-selection.html). After a successful write, reset normally without holding BOOT.

Do not erase flash as a routine fix: normal upload already replaces the program. Check cable, port, access permissions and boot mode first.

## 7. Watch the first boot

```sh
../.venv-pio/bin/pio device monitor --port /dev/ttyUSB0 --baud 115200
```

Press EN/reset after opening the monitor to see startup. Look for these source-defined log fragments; numbers and ordering around network events vary:

```text
[app] reset reason=...
[display] synchronous RGB565 buffer=19200 B
[link] connecting...
[link] connected
[link] hello sent, last_seq=0 caps=0x07
[link] welcomed: ...
```

The display first renders **CONNECTING**, then shows the relay's current live/offline state. Simulated mode produces cards without Twitch credentials. A failed connection logs a reason and retries automatically.

Use `Ctrl+C` to leave the monitor. Follow [troubleshooting](troubleshooting.md) if there is no display, no serial port or no `welcomed` message.

## 8. Update an existing device

1. Preserve your ignored `src/credentials.h` and note the currently working firmware commit.
2. Update the repository and read any protocol/configuration changes.
3. Repeat the host checks and ESP32 build.
4. Close the serial monitor, upload over USB and observe startup again.
5. Check a notification, display colors, text fit and reconnection behavior on the physical device.

Firmware and relay must agree on **TSB/3**. The retired NDJSON demo cannot talk to this build. Power cycling clears the firmware's in-memory replay position; it is not a way to recover old notifications.

Continue with [daily use](device-use.md), [relay configuration](relay-setup.md) and [Twitch app registration](twitch-app-setup.md).
