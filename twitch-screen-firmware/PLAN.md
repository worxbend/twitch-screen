# PLAN — Twitch Streamer Companion Display ("Twitch Orbit")

A desktop device for a Twitch streamer: a round LCD that shows live stream
telemetry while idle and pops animated, color-coded event cards (follows,
subs, raids, chat, …) the moment they happen — fed over a permanent,
self-healing TCP connection.

This document consolidates the original prompts and everything built so
far, and defines the desired final product.

## Hardware

- **Display:** Waveshare 1.28" round LCD, 240×240, GC9A01 driver, SPI
  (VCC/GND/DIN/CLK/CS/DC/RST/BL). Four mounting holes.
- **MCU:** ESP32 DevKit V1 Type-C (classic ESP32/WROOM-32, 30-pin).
  Four mounting holes. Silkscreen `D<n>` == `GPIO<n>`; GPIO16 is labeled
  `RX2`. D34/D35/VP/VN are input-only.
- **Mechanical (open):** display PCB and DevKit have different mounting
  footprints → needs a carrier plate / standoffs or a printed enclosure.
- **Wiring** (matches `platformio.ini` build flags):

  | LCD | Silkscreen | GPIO |
  |-----|-----------|------|
  | VCC | 3V3  | –  |
  | GND | GND  | –  |
  | DIN | D23  | 23 |
  | CLK | D18  | 18 |
  | CS  | D5   | 5  |
  | DC  | RX2  | 16 |
  | RST | D4   | 4  |
  | BL  | D15  | 15 |

## Desired final product

1. **Always-on streamer dashboard.** While the stream is live and nothing
   is happening, the device shows: pulsing LIVE pill + stream uptime,
   Twitch Glitch logo, large animated viewer count, chat-activity ring
   gauge on the panel edge, and follower/sub/chat-rate chips. When the
   stream is offline: dimmed Glitch + OFFLINE screen.
2. **Branded connecting state.** Whenever the link to the server is down
   (boot, WiFi loss, server crash, backoff), the device shows a dedicated
   animated widget: breathing Glitch logo, orbiting purple spinner arc,
   animated "CONNECTING…" — never a stale dashboard or blank screen.
3. **Instant event notifications.** New chat highlights and Twitch events
   (follow, sub, gift sub, raid, bits) slide in as animated full-screen
   cards with kind-specific colors/icons, hold ~3.5 s, slide out, queue
   back-to-back. Nothing is lost: missed events are replayed on reconnect.
4. **Beautiful, deliberate UI.** LVGL-based, Twitch palette
   (`#0E0E10` background, `#9146FF` purple, `#EB0400` live red), smooth
   ease-out animations, flicker-free partial rendering, layout designed
   for the round panel (edge ring, center stage, no wasted corners).
5. **Resilient connectivity.** One persistent TCP socket, server-push
   protocol; app-level heartbeat; exponential-backoff reconnect forever;
   WiFi auto-recovery. The link must never die due to the client.
6. **Real Twitch backend (future).** A service translating Twitch
   EventSub / IRC into the wire protocol — the firmware needs no changes.
7. **Enclosure (future).** Carrier/enclosure using the four mounting
   holes on each PCB.

## Architecture

```
Twitch EventSub/IRC ──> twitch-screen-relay ──TSB/3 binary push (TCP :8099)──> ESP32 link_client
                                   │                                              │
                          STATS frames (5 s)                              LVGL UI (240×240)
                          EVENT frames (instant)                          ├─ idle dashboard
                                                                          ├─ connecting widget
                                                                          └─ event overlay
```

- **`docs/PROTOCOL.md`** — wire protocol TSB/3 (supersedes NDJSON v2):
  binary frames over TCP :8099 with an 8-byte little-endian header (magic
  `a7 53`), types `HELLO`/`WELCOME`/`EVENT`/`STATS`/`PING`/`PONG`/`ACK`/`BYE`,
  heartbeat, replay of missed seqs, backoff policy.
- **Firmware** (`src/`): `lv_port` (TFT_eSPI + LVGL 9 glue), `link_client`
  (never-die TCP state machine), `ui_idle` (Twitch Orbit dashboard +
  connecting widget, three-state visibility), `ui_notify` (event overlay),
  `notification.h`/`stats.h` (models), `assets/twitch_glitch.h`
  (generated RGB565 logo).
- **Server**: `twitch-screen-relay` (Scala 3) is the only TSB/3 server.
  `demo-server/twitch_server.py` speaks v2 and is obsolete (see
  PROTOCOL.md §19).
- **Assets** (`assets/`, `tools/make_glitch.py`): Glitch SVG source +
  reproducible RGB565/PNG generator.
- **Secrets:** `src/credentials.h` (git-ignored), template in
  `credentials.example.h`.

## Status — done

- [x] PlatformIO project, GC9A01 bring-up (TFT_eSPI via build flags)
- [x] WiFi + protocol v1 (HTTP poll) → replaced by v2 (persistent TCP push)
- [x] Never-die TCP client: heartbeat, death detection, backoff 1s→30s,
      reconnect+replay verified by server-bounce test
- [x] LVGL 9 port (partial buffers, heap-backed, huge_app partition)
- [x] Twitch Orbit idle UI (live + offline) and Twitch event overlay
- [x] Animated connecting widget (spinner arc + breathing logo +
      "CONNECTING…"), shown on boot and on any link loss; verified by
      server-bounce test
- [x] Glitch logo asset pipeline (SVG → PNG + RGB565 C arrays)
- [x] Twitch simulator server
- Ops notes: ufw must allow TCP 8099 from the LAN; `pio` venv at
  `~/.platformio/penv` has broken twice from parallel modification.

## Roadmap — not yet done

- [ ] Real backend: Twitch EventSub (follows/subs/raids/bits) + IRC chat
      highlights → TSB/3 frames (twitch-screen-relay)
- [ ] NTP-synced clock; real stream uptime from API
- [ ] EventSub-driven `live` transitions (stream.online/.offline)
- [ ] NVS persistence of `last_seq` (survive power loss without re-baseline)
- [ ] Raid "takeover" animation; follower-goal mode for the edge ring
- [ ] Gradient/rainbow arc segments (canvas or multi-arc)
- [ ] PWM backlight dimming (day/night), optional
- [ ] systemd user service for the server
- [ ] Enclosure/carrier plate (4-hole mounts on both PCBs)
- [ ] Optional: touch/button input (this panel has none) for dismiss/goal

Regarding client-server communication, the server should expose a well-defined protocol over TCP. 
It should use a custom binary protocol for efficient communication between the server and connected devices, ensuring low latency and minimal overhead.

we need to send informations:
 - for the idle screen with number of messages in the chat, current viewers count and time since the start of the stream.
 - for the stream events:
    - stream start
    - stream end
    - raid, including the raider's information
    - follow, including the follower's information
    - subscribe, including the subscriber's information
    - donation, including the donor's information and amount
    - bits transaction, including the sender and amount
    - chat message, including the sender and content.

events from the bots should be ignored (both chat messages and other events, bots - streamelements, nightbot, moobot, etc.).
