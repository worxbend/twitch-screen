---
name: twitch-protocol-testing
description: Test the TSB/3 firmware and relay together using simulated Twitch events, native/socket suites and authorized device observations. Use for handshake, replay, timeout and reconnect behavior without a live Twitch account.
---

# TSB/3 protocol testing

Contract: `twitch-screen-firmware/docs/PROTOCOL.md`. Binary framed TCP on
8099; incompatible with retired NDJSON v2 and `demo-server/twitch_server.py`.
Read relevant contract sections before changing behavior. ACK is informational;
WELCOME supplies heartbeat timing. Replay is finite and best effort across
relay process restarts; do not claim durable or exactly-once delivery.

## Start the supported simulator

From the repository root:

```sh
export RELAY_HTTP_AUTH_API_TOKEN="$(openssl rand -hex 32)"
cd twitch-screen-relay
RELAY_TWITCH_MODE=simulated ./mill run
```

The simulator uses the real relay pipeline. Management endpoints require the
configured Basic credentials or Bearer token, even in simulation. Health,
aggregate stats, `/docs`, and exact Twitch callbacks remain public; callbacks
still enforce their own signature/state checks.

In a shell with the same token, inject a card:

```sh
curl --fail-with-body -X POST http://localhost:8080/api/v1/notifications \
  -H "Authorization: Bearer $RELAY_HTTP_AUTH_API_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"type":"alert","title":"Test","body":"TSB/3 notification"}'
```

Point the ignored firmware configuration at the relay's reachable LAN address
and TCP 8099. Use HTTPS for credential-bearing network requests; localhost
examples are for local development. Device TCP is intended for a trusted LAN.

## Automated checks

From `twitch-screen-relay/`:

```sh
./mill test
./mill test.testOnly twitchscreen.relay.device.DeviceLinkSuite
```

From the repository root: `python3 tools/check_protocol_vectors.py`.
Run firmware native, sanitizer and ESP32 build commands from the firmware
workflow skill or firmware README. Never run concurrent PlatformIO builds.

## Observable scenarios

- Fresh boot: `HELLO.last_seq=0`, WELCOME baseline, no historical replay.
- Same-process reconnect: retained later EVENTs replay in order; distinguish
  that from a full relay restart, where v3 lacks persisted sequence ownership.
- Burst beyond eight cards: bounded queue admission must preserve its replay
  mark; observe recovery/backpressure and keep UI progress responsive.
- Slow/nonreading TCP peer: independent deadline tears it down; later EVENTs
  must not cross an outbound EVENT gap on the same connection.
- Brief and prolonged WiFi outage: display keeps pumping; a disconnect edge
  invalidates the old session even if association quickly recovers.
- Duplicate device ID: replacement/backoff is deliberate; use distinct devices.
- Long/dark text and large counts: inspect both layouts on the round display.

Use an already-authorized serial session at 115200 baud for hardware evidence.
Record which checks were native, real sockets, simulated, or physical hardware.
Do not label physical recovery/rendering verified from a build alone.
