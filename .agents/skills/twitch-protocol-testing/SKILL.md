---
name: twitch-protocol-testing
description: End-to-end testing of the twitch-screen device against the demo server — run the Twitch simulator, trigger events, watch serial logs, and exercise reconnect/replay. Use when testing firmware behavior, the wire protocol v2, or server-client interaction without real Twitch credentials.
---

# Protocol v2 end-to-end testing

Contract: `twitch-screen-firmware/docs/PROTOCOL.md` — NDJSON over TCP, port
8099. Ops: client→server `hello`, `pong`; server→client `welcome`, `stats`,
`notify`, `ping`. Heartbeat: client ping every 15 s, link dead after 45 s of
silence; `hello.last_seq` drives replay of up to 64 buffered events.

## Demo server (Twitch simulator)

`demo-server/twitch_server.py` — stdlib-only Python, no deps:

```bash
python3 demo-server/twitch_server.py            # TCP :8099, trigger HTTP :8098
python3 demo-server/twitch_server.py --port 8099 --trigger-port 8098
```

Behavior: stats random-walk every 5 s; weighted random events every 6-18 s
(follow 28, chat 30, sub 14, gift 8, raid 9, bits 11); 64-event replay buffer.
Manual event for demos:

```bash
curl -X POST localhost:8098/trigger
```

Firmware must point at the host: `SERVER_HOST` in the git-ignored
`src/credentials.h` (LAN IP of the machine running the simulator, e.g.
192.168.1.x). Ops note from PLAN.md: `ufw` must allow TCP 8099 from the LAN.

## Serial observation

```bash
~/.platformio/penv/bin/pio device monitor   # 115200 baud
```

Log tags: `[wifi]`, `[link]` (connect/welcomed/down/retry), `[app]` (baseline,
new events). Server logs: `[accept]`, `[hello]` (shows replay count), `[gen]`,
`[trigger]`, `[drop]`.

## Test scenarios (proven procedures from PLAN.md)

- **Server bounce / reconnect-replay:** with the device running, kill the
  server, wait, restart it. Expect: device detects death (peer closed or 45 s
  heartbeat timeout) → connecting widget appears → backoff retries
  (1 s→30 s, jittered) → `hello` with old `last_seq` → server replays missed
  events → queued cards show back-to-back. Verified working 2026-09.
- **WiFi loss:** known open issue — blocking `wifiEnsureConnected()` freezes
  the connecting animation up to 10 s; don't report as new.
- **Event burst:** `curl -X POST localhost:8098/trigger` rapidly >8 times →
  8-slot queue drops oldest silently (known design limit).
- **Fresh boot baseline:** power-cycle the device → `last_seq=0` → no replay,
  adopts `latest_seq`.

## Expected steady-state timings

Stats frame → dashboard update within ~5 s; `notify` → card slides in
(350 ms), holds 3.5 s, slides out (280 ms); queued cards show back-to-back.

## Real backend

`twitch-screen-relay/` (Scala 3, Mill) is the future production server — as of
2026-09 it is a skeleton (health endpoint only), so all end-to-end testing
goes through the demo server. Run relay checks with `./mill test` from
`twitch-screen-relay/`.
