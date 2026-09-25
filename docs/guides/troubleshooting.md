# 🩺 When the tiny circle has opinions

[← Guidebook](../README.md) · [Device use](device-use.md) · [Relay setup](relay-setup.md)

Work from the first layer that fails: **power → display → Wi-Fi → relay TCP → Twitch**. One successful layer narrows the next check. A simulated notification is especially useful because it exercises the display path without needing a live Twitch account.

## Start with the symptom

| Symptom | Start here |
| --- | --- |
| No USB serial port | [USB and upload](#usb-and-upload) |
| Build cannot find PlatformIO or credentials | [Build environment](#build-environment) |
| Black, white or incorrect-color screen | [Display and wiring](#display-and-wiring) |
| CONNECTING never ends | [Connection and networking](#connection-and-networking) |
| Device disconnects repeatedly | [Connection log decoder](#connection-log-decoder) |
| OFFLINE while the channel is live | [Twitch and telemetry](#twitch-and-telemetry) |
| API returns 401 or startup refuses to run | [Management authentication](#management-authentication) |
| Missing, delayed or shortened notifications | [Cards and replay](#cards-and-replay) |
| Enclosure or USB cable does not fit | [Printed parts and assembly](#printed-parts-and-assembly) |

## Build environment

### `ModuleNotFoundError: No module named 'platformio'`

An old PlatformIO virtual environment can break after a host Python upgrade. Use the isolated environment in [firmware setup](firmware-setup.md#2-install-the-projects-platformio-version) and confirm its `pio --version` works before compiling. Do not mix that environment with an old `~/.platformio/penv` executable.

### `credentials.h: No such file or directory`

Create the ignored file from `src/credentials.example.h`, fill in your Wi-Fi and relay settings, then rebuild. The template itself is not used as the active configuration. Preserve an existing private header instead of copying over it.

### Platform downloads or builds fail unexpectedly

Run one PlatformIO install/build/test command at a time. Check Internet access and the **first actual error** before retrying. Use the pinned platform and library versions in `platformio.ini`; a dependency upgrade is a separate development change, not a routine setup step.

`native-sanitized` is the Linux host gate. A missing host compiler or incompatible sanitizer toolchain is different from an ESP32 compilation failure. The command `pio run -e esp32dev` builds the hardware target; it does not run the host tests.

## USB and upload

### The board powers up, but no port appears

Power does not prove the cable carries data. Try a known data cable and a direct USB port, then run `pio device list`. Check which USB-to-serial chip your particular board uses before installing a driver. Match the actual device, not only its connector shape.

### Permission denied or port busy

Close serial monitors and other applications using the port. On Linux, apply [PlatformIO's device-access instructions](https://docs.platformio.org/en/stable/core/installation/udev-rules.html). Group changes require a new login session; reconnect the board after installing udev rules.

### Upload stays at `Connecting…`

Verify the selected port. Hold BOOT during the connection attempt; if needed, hold BOOT and briefly press EN/reset. Release BOOT after writing begins. For the electrical boot-mode explanation, use [Espressif's bootloader guide](https://docs.espressif.com/projects/esptool/en/latest/esp32/advanced-topics/boot-mode-selection.html).

After upload, reset with BOOT released. A board left in download mode will not run the display application.

### Serial text is unreadable or startup is missing

Use **115200 baud**. Open the monitor, then press EN/reset to capture the boot messages. Look for `[app] reset reason=` and the display initialization line. Serial messages can be omitted when the output buffer is full, so a missing individual line is not definitive evidence of a missing action.

## Display and wiring

Disconnect power before reseating wires. Compare each connection with the [wiring table](device-build.md#2-wire-the-display).

| Observation | Likely area | First check |
| --- | --- | --- |
| No backlight | Power, ground or BL | LCD VCC to 3V3, common ground, BL to GPIO 15 |
| Backlight but no UI | SPI/control wiring or wrong module | DIN 23, CLK 18, CS 5, DC **16/RX2**, RST 4; GC9A01 non-touch module |
| All colors systematically wrong | RGB565 byte order | Confirm current `lv_port.cpp` uses `LV_COLOR_FORMAT_RGB565_SWAPPED` |
| Red and blue swap but dark background stays dark | RGB/BGR panel ordering | Confirm module/controller and driver configuration before changing it |
| Image looks like a photographic negative | Panel inversion | Confirm the GC9A01 initialization path |
| Random sparkles, shifted rows, changes when wires move | Connection/signal integrity | Reseat and shorten wires; keep the configured 40 MHz SPI clock |

The expected palette is near-black `#0E0E10`, purple `#9146FF`, red LIVE `#EB0400`, and near-white `#EFEFF1`. A tan/olive background together with a blue LIVE pill suggests byte order; it is different from merely swapping red and blue channels.

The current renderer already uses swapped RGB565. Adding `tft.setSwapBytes(true)` as a second fix can swap the bytes twice. Confirm the checked-out implementation before applying advice from an old experiment or another TFT_eSPI project.

The current port uses one aligned **19,200-byte** draw buffer and synchronous partial flushes. Older project notes describe other buffer layouts. Use [lv_port.cpp](../../twitch-screen-firmware/src/lv_port.cpp) as the current implementation reference.

Fine diagonal lines visible only in a camera photo can be camera/display moiré. Compare with what your eyes see before treating the photo pattern as corrupted pixels.

## Connection and networking

### The screen stays on CONNECTING

1. Confirm the SSID/password in your private firmware configuration and the availability of a **2.4 GHz** network. Changes require a rebuild and upload.
2. Confirm the relay is actually running. On its computer, request `http://127.0.0.1:8080/api/v1/health`.
3. Confirm `SERVER_HOST` is that computer's reachable LAN address. It must not be `localhost`, `127.0.0.1`, `0.0.0.0`, an HTTP URL or a stale DHCP address.
4. Confirm `SERVER_PORT` matches the relay's **device TCP** listener, normally 8099. HTTP 8080 is a separate service.
5. Check the host firewall, container port publishing and router/client-isolation rules. The ESP32 must be able to reach the relay across the LAN.
6. Inspect serial messages at 115200 baud and find the first connection failure below.

A working HTTP health request on the relay computer does not prove TCP 8099 is reachable from Wi-Fi. Likewise, both devices having Internet access does not prove a guest network lets them communicate.

The firmware's connection messages start after Wi-Fi is connected. A persistent connecting animation without link-attempt messages points first toward Wi-Fi or missing serial output; the current application does not print a full Wi-Fi credential/IP diagnostic on every attempt.

### One device works, another keeps reconnecting

Check `DEVICE_ID`. Two physical boards using the same custom name replace each other. Leave it empty for automatic chip-derived identities or assign distinct names no longer than 31 printable ASCII bytes, then rebuild and flash the changed devices.

### Reconnect is slower after several failures

This is expected backoff: the retry base grows to 30 seconds, adds jitter and honors a relay-requested minimum. It resets after 60 seconds of stable streaming. Do not interpret a pause between retries as a permanent stop.

## Connection log decoder

| Serial fragment | Meaning | Action |
| --- | --- | --- |
| `connect failed` / `connect timeout` | DNS or TCP connection did not complete | Verify host, port, relay listener and network access |
| `welcome timeout` | TCP connected but no valid greeting arrived in time | Check that this is the TSB/3 relay and device port |
| `bad handshake` | First inbound frame was not an acceptable greeting | Match firmware/relay versions and inspect the service on that port |
| `wifi lost` | Wi-Fi is down | Check signal and router; the firmware retries automatically |
| `heartbeat timeout` | No usable inbound activity within the device's silence deadline | Check relay health, network interruption and logs |
| `peer closed/read failed` | Socket closed or a read failed | Correlate with relay restart/disconnect logs |
| `write timeout` | Pending output could not complete within its deadline | Check a stalled connection or relay |
| `BYE code=1` | Unsupported protocol version | Update the matching relay/firmware pair; TSB/3 and old NDJSON cannot interoperate |
| `BYE code=3` | Invalid device ID | Use a short nonblank printable ASCII name or the automatic default |
| `BYE code=8` | Relay shutting down | Wait for relay restart |
| `BYE code=9` | Another connection claimed the same device ID | Fix duplicate names |
| `BYE code=10` | Rate limit | Inspect the relay load and honor its retry delay |
| `queue full, refused` | Defensive queue admission refused a new card | Let the queue drain; reconnect/replay is bounded by retained history |
| `close worker allocation failed` | Required firmware transport task was not created | Record build and boot logs; investigate memory/task creation before assuming a network fault |

The complete refusal-code contract is in [TSB/3 §6.7](../../twitch-screen-firmware/docs/PROTOCOL.md). Repeated malformed-frame counters are useful evidence; copy the counter line along with the surrounding failure, not only the last retry message.

## Management authentication

### Relay refuses to start without credentials

Every mode, including `simulated`, needs a management Bearer token or complete Basic credentials. Follow [relay setup](relay-setup.md). A partial Basic configuration or malformed password verifier can reject startup even when other settings look correct.

### HTTP 401 from `/status`, `/devices` or notification injection

Use the same credential configured in the running relay. A new terminal does not automatically inherit an export from another one. Generating a new token only in the client terminal will not match the already running server.

For a Bearer installation, from a shell containing the existing token:

```sh
curl --fail-with-body http://127.0.0.1:8080/api/v1/status \
  -H "Authorization: Bearer $RELAY_HTTP_AUTH_API_TOKEN"
```

Use HTTPS or a trusted local connection for administration; the display's TCP connection uses a different protocol and does not accept this HTTP header.

## Twitch and telemetry

### OFFLINE persists during a real stream

First confirm simulated events work on the display. Then check the relay mode, channel login and `/api/v1/status`. `disabled` mode does not ingest Twitch; `simulated` mode describes a demo stream. Use `live` mode and complete [Twitch app authorization](twitch-app-setup.md) for a real channel.

### Viewer count works, follower or subscriber count does not

Different upstream calls need different grants and channel roles. A missing metric does not establish that the whole relay is disconnected. Inspect `/api/v1/twitch/authorization` and status using your management credential, then compare the granted scopes/account with the [Twitch setup guide](twitch-app-setup.md).

### OAuth redirect fails

Check that the registered Twitch redirect URL exactly matches the relay configuration, including scheme, host, port and path. A `localhost` callback refers to the computer running the browser. Headless/remote hosting needs the browser and callback routing described in [Twitch app setup](twitch-app-setup.md).

### Uptime, totals or ring look surprising

The uptime label ticks every second even though telemetry normally arrives every five seconds. `MSG` is a running observed-message count, while the edge ring is recent messages per minute capped visually at 100. A relay restart resets in-memory aggregation; it cannot reconstruct chat from before it started.

## Cards and replay

- **Cards arrive late during a burst:** they display sequentially, with per-kind hold times. Eight waiting slots and animation time can create a visible backlog.
- **A reset did not recover missed events:** fresh firmware boots have no retained replay position. Replay is bounded and in memory, not a persistent inbox.
- **A reconnect shows an old event:** retained replay has no age cutoff. A card can belong to an earlier stream.
- **Long names or bodies end in dots:** text is deliberately clipped with an ellipsis to fit the circle.
- **Emoji or non-Latin names change:** the firmware advertises ASCII-only text support, so the relay folds unsupported characters.
- **Some bot events never appear:** the relay filters its configured ignored display names upstream. Review the configured list rather than changing the firmware queue.
- **A donation event never appears:** native cash-donation ingestion is not implemented. The protocol reserves that kind; use supported custom-notification integration where appropriate.

See [device use](device-use.md) for the exact display behavior and [the protocol](../../twitch-screen-firmware/docs/PROTOCOL.md) for replay limits.

## Printed parts and assembly

### ESP32 holes, USB port or LCD retainer do not line up

Compare your parts to the [measurement checklist](../../twitch-screen-cad-design/docs/measurement_checklist.md). The model uses provisional dimensions for the clone board, display stack and connectors. Change the parameters and regenerate the exports; scaling the whole enclosure also changes screw sizes, LCD opening and wall thickness.

### The USB plug fits the socket but not the enclosure

Measure the cable's plastic overmould, not only the metal connector. The generated opening depends on `UsbPlugWidth`, `UsbPlugHeight` and `UsbPlugCornerRadius` in the CAD parameters.

### The shell rocks or the base needs force

Check supports, the locating lip, wire routing and post clearance. The base should not pinch the loom. Validate the base and pilot holes with small test prints before tightening the complete assembly.

### A mesh contains boards and a cable-shaped object

You opened an `assembly_view_only` export. Use `print_plate.3mf` or the four individual printable parts from the [build guide](device-build.md#5-choose-and-slice-the-printable-files).

## Collect a useful issue report

Include the repository commit, board/module model, OS, failing command, first relevant error, serial reset/connection messages and relay status. For display faults, include a photo and say whether the fault is visible to the eye. For mechanical faults, include measured dimensions and the exact export used.

Remove passwords, tokens, private credential files and personal notification contents before sharing logs. State which checks actually ran: a successful compile or nominal CAD report is not a physical device test.
