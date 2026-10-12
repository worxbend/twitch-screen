# 🔌 Relay HTTP API

> Desk-update migration notice: management Basic/Bearer instructions in this historical guide are obsolete for this checkout. Nonempty `RELAY_HTTP_AUTH_*` settings fail startup; Authorization confers no privilege. Use the [current root quickstart](../../README.md#first-success-without-twitch), [migration guide](../guides/migration.md) and [implementation status](../implementation-status.md). Trusted-network isolation is mandatory. Clock/display/weather/CPU/monitoring ingress and durable desired display/monitoring intent are mounted. The default relay supports DESK negotiation and mounted admin snapshot/SSE/panel; default ESP32 and complete provider/physical acceptance remain pending.

[Guidebook](../README.md) · [Relay setup](../guides/relay-setup.md) · [Configuration](relay-configuration.md)

Base URL for local development: `http://localhost:8080/api/v1`. Open **`http://localhost:8080/docs`** on the running relay for generated Swagger UI and schemas. The device itself uses binary TCP on port 8099; these HTTP endpoints manage the relay and publish cards.

## Private-network boundary and shared behavior

Management needs no login, password or token. Anyone reaching the listener can inspect and control the relay. Isolate HTTP and TCP on a trusted LAN/VPN/firewall. Nonempty obsolete `RELAY_HTTP_AUTH_*` or old HOCON auth settings fail startup; Authorization headers confer no privilege.

Host, Origin and Fetch Metadata are checked before buffering request bodies. Non-callback POST/PUT/PATCH requests need exactly one JSON Content-Type header, including bodyless management actions. Use `-H 'Content-Type: application/json'` for those actions. Provider callbacks retain OAuth state or EventSub signature checks. Direct HTTP and exact immediate-proxy HTTPS origins are supported. Certificate-verified container HTTPS and notification readback pass; real mobile consent and complete production admin/SSE acceptance remain pending. See [migration](../guides/migration.md). “Management” in the route map denotes purpose, not authentication.

| Contract | Behavior |
|---|---|
| Error body | JSON object with an `error` string, including ordinary route/decode errors |
| Diagnostic list query | Notifications, activity, logs, and alerts accept `pageSize` from **1 to 500**, newest first. Devices and alert rules are unpaged. |
| List envelopes | Named arrays such as `devices`, `notifications`, `entries`, `alerts`, or `records` |
| Enum casing | Most management enums use names such as `Live`, `Offline`, `Warning`, `Info`; notification kinds use lowercase JSON names |
| HTTP body limit | **65536 bytes**, including chunked requests |
| Persistence | Diagnostic lists and replay history are in memory and reset on restart |
| Delivery | A successful publication does not prove a device displayed the card |

## Route map 🗺️

All paths in this table are relative to `/api/v1`.

| Method | Path | Access | Result |
|---|---|---|---|
| `GET` | `/health` | Public | HTTP liveness |
| `GET` | `/stats` | Public | Latest aggregate stream stats sent to devices |
| `GET` | `/status` | Management | Integration readiness, device/bus/buffer counts |
| `GET` | `/config` | Management | Effective configuration with secrets masked |
| `GET` | `/devices` | Management | All current device connections |
| `GET` | `/devices/{connection}` | Management | One current connection |
| `POST` | `/devices/{connection}:disconnect` | Management | Close one connection; return its snapshot |
| `GET` | `/notifications` | Management | Retained notification records |
| `POST` | `/notifications` | Management | Publish one card to all attached devices |
| `GET` | `/activity` | Management | Recent activity records |
| `POST` | `/activity:export` | Management | Plain-text activity report download |
| `GET` | `/alerts` | Management | Raised alert records |
| `GET` | `/alertRules` | Management | Configured rules |
| `POST` | `/alerts/{id}:acknowledge` | Management | Acknowledge an active alert |
| `GET` | `/logs` | Management | Buffered application log records |
| `GET` | `/twitch/authorize` | Management; OAuth configured | `302` redirect to Twitch consent |
| `GET` | `/twitch/callback` | OAuth state; OAuth configured | HTML consent success/failure page |
| `GET` | `/twitch/authorization` | Management; OAuth configured | Held grant's identity, scopes, and expiry |
| `DELETE` | `/twitch/authorization` | Management; OAuth configured | Forget grant and attempt Twitch revocation |
| `POST` | `/twitch/eventsub` | Twitch HMAC; live webhook mode | EventSub challenge or delivery response |

The four OAuth routes are mounted when valid OAuth application configuration is available, independent of disabled/simulated/live ingestion. Missing or invalid OAuth configuration leaves those routes absent without blocking generic operation. The EventSub callback still requires live webhook transport.

## Send a card 💌

Publish over the isolated listener without management credentials:

```sh
curl --fail --silent --show-error \
  -H 'Content-Type: application/json' \
  --data '{"type":"info","title":"Desk check","body":"The tiny screen has entered the chat.","ttlMs":8000}' \
  http://localhost:8080/api/v1/notifications
```

HTTP `200` returns the assigned replay record. Example shape, with illustrative sequence/time:

```json
{
  "seq": 1,
  "id": "ntf-0001",
  "type": "info",
  "title": "Desk check",
  "body": "The tiny screen has entered the chat.",
  "at": "2026-09-25T12:00:00Z",
  "ttlMs": 8000
}
```

| Input field | Required? | Contract |
|---|---|---|
| `type` | Yes | Notification JSON kind; see the compatibility note below |
| `title` | Yes | Nonblank after trimming; at most 4096 characters |
| `body` | Yes | May be empty; at most 4096 characters |
| `ttlMs` | No | Exact integral JSON number 100–30000 ms, rounded up to 100 ms; omitted uses 8000 ms |
| `expiresInMs` | No | Exact integral JSON number 1–600000 ms; omitted uses 60000 ms |
| `deadline` | No | Calibrated `{relayEpoch, monotonicMs}`; cannot extend waiting lifetime |

Useful kinds are `info`, `message`, `warning`, `alert`, `follow`, `sub`, `gift`, `raid`, `chat`, and `bits`. Text can be shortened when encoded for the 240 × 240 display. This endpoint posts a generic card; it does not expose all native Twitch event metadata such as a subscription tier or gift count.

> [!IMPORTANT]
> Canonical lifecycle notification names are `stream_start` and `stream_end`. Unseparated `streamstart`/`streamend` aliases now return `400`. Binary TSB/3 event codes remain unchanged. Treat this validation correction and the hold-limit change as breaking HTTP migration, not a classic wire layout change.

Every accepted POST creates a **new** notification. Retrying after an interrupted response can create a duplicate; there is no idempotency key. The response confirms publication and retention in the current process, including when no device is attached. It does not confirm rendering on a screen.

HTTP `503` with `{"error": "TSB/3 sequence space exhausted (§10.1); …"}` means the relay has assigned the last `u32` sequence number, 4294967295. The relay never wraps the sequence, so it publishes nothing more until it is restarted. A restart begins a new sequence space, and devices re-baseline on it. The relay reports this once: an error log line and one `device-hub failed` relay failure, which appears in the activity log and the failure metrics. `GET /status` then reports `deviceLink.sequenceExhausted: true`. Retrying the POST does not help.

Read recent cards:

```sh
curl --fail --silent --show-error \
  'http://localhost:8080/api/v1/notifications?pageSize=20'
```

Notifications with no known source timestamp omit `at`; they no longer claim the Unix epoch. Locally published cards have a timestamp.

The default `pageSize` is 20. The response combines the two replay rings: up to 64 non-chat notifications by default and a separate fixed 16 chat notifications. Requesting 500 does not create a longer history.

## Health, readiness, and stats 🩺

`GET /health` returns:

```json
{"status":"Up"}
```

It only establishes that the HTTP server can respond. Docker's health check uses it so a Twitch outage does not restart a relay that can still serve devices.

`GET /stats` returns `state`, `viewers`, `followers`, `subscribers`, `uptimeSeconds`, and `chatRate`. `state` is `Live` or `Offline`; `chatRate` is messages per minute. This HTTP DTO is a subset of the full TSB/3 STATS payload; it does not expose `messagesTotal` or `streamStartedAt`.

`GET /status` is the operator's readiness snapshot:

| Field | What to inspect |
|---|---|
| `version`, `startedAt`, `uptimeSeconds` | Running version and process lifetime |
| `twitch` | `mode`, `health`, `channel`, `detail` |
| `deviceLink` | Connected devices, accepted connections, `connectionsRefused` (connections closed at the 64-session limit, pending handshakes included; also exported as the `relay.device.connections.refused` metric; a rising value means too many devices, or a connection leak or flood), published notifications, latest sequence, replay count, and `sequenceExhausted` (true once the `u32` sequence space is spent; restart the relay) |
| `subscribers` | Internal event-bus subscriber statistics: per subscriber `name`, `enqueued`, `delivered` and `dropped` |
| `activityEntries`, `activeAlerts`, `bufferedLogRecords` | Current in-memory diagnostic counts |

Twitch health may be `Disabled`, `Connecting`, `Connected`, `Degraded`, or `Disconnected`. Read the detail with it; HTTP can be healthy while the broadcaster grant is missing. The status route reads a nonblocking hub snapshot and does not convert every degraded condition into a failing HTTP status. For each subscriber, `enqueued` counts events accepted into its queue, `delivered` counts handler calls that have finished (a failed call counts too), and `dropped` counts events lost to a full queue. `enqueued - delivered` is the current backlog, including an event whose handler is still running. Repeated failures update an open alert’s message without reopening or unacknowledging it.

## Inspect and reconnect a device 📡

```sh
curl --fail --silent --show-error \
  http://localhost:8080/api/v1/devices
```

The `devices` array includes numeric `connection`, firmware `device` identity, `remoteAddress`, `protocolVersion`, `connectedAt`, `baselineSeq`, and a nested `traffic` object. `traffic` contains last-seen timestamps, acknowledged sequence, byte/frame counts, dropped/skipped frame counts, invalid-field counters, and resynchronization counts. Clients using formerly top-level traffic fields must now read them under `traffic`. The listener permits at most 64 sessions, including pending handshakes.

Use the current **connection number**, not the device name, in per-connection URLs. For example, when the list reports connection 7:

```sh
curl --fail --silent --show-error \
  http://localhost:8080/api/v1/devices/7

curl --fail --silent --show-error --request POST \
  -H 'Content-Type: application/json' \
  http://localhost:8080/api/v1/devices/7:disconnect
```

Disconnect drains accepted frames, sends a final operator-disconnect BYE, closes the socket, and returns its snapshot. It does not permanently block a device; firmware reconnects on its backoff schedule. A stale connection number returns `404`. This is useful for checking reconnect behavior while retained replay remains available.

## Activity, logs, and alerts 🧾

| Endpoint | Query parameters | Defaults |
|---|---|---|
| `GET /activity` | `pageSize`, optional `category` | 100, all categories |
| `POST /activity:export` | `pageSize`, optional `category` | 100, all categories |
| `GET /logs` | `pageSize`, `minLevel` | 100, `Info` |
| `GET /alerts` | `pageSize`, optional `severity`, `activeOnly` | 50, all severities, `false` |

Activity categories are `Channel`, `Audience`, `Twitch`, `Device`, `Notification`, and `Failure`. Log levels are `Trace`, `Debug`, `Info`, `Warn`, and `Error`. Alert severities are `Warning` and `Critical`. Query values are case-sensitive enum names.

```sh
curl --fail --silent --show-error \
  'http://localhost:8080/api/v1/logs?minLevel=Warn&pageSize=50'

curl --fail --silent --show-error \
  'http://localhost:8080/api/v1/alerts?activeOnly=true'

curl --fail --silent --show-error --request POST \
  -H 'Content-Type: application/json' \
  --output relay-activity.txt \
  'http://localhost:8080/api/v1/activity:export?category=Device&pageSize=100'
```

The report returns plain text with download filename `relay-activity.txt`. Review its contents before sharing; activity and logs can contain channel or device details.

`GET /alertRules` returns rule names, severity, and descriptions. Acknowledging an active alert uses its numeric ID:

```sh
curl --fail --silent --show-error --request POST \
  -H 'Content-Type: application/json' \
  http://localhost:8080/api/v1/alerts/3:acknowledge
```

Replace 3 with an active alert ID from your list. An unknown ID returns `404`; an alert that is no longer active returns `409`. Acknowledgement stops it counting as active; it does not fix its cause. States are `Active`, `Acknowledged`, and `Resolved`. Rules are configured at startup, not edited through HTTP.

## Configuration and Twitch consent 🎮

`GET /config` returns a `settings` map using dotted HOCON keys and string values. Provider secrets are masked. There is no configuration-write endpoint.

The application-scoped owner mounts OAuth endpoints with valid OAuth application configuration; ingestion mode does not control their lifetime:

- **`GET /twitch/authorize`** returns `302` with a Twitch `Location` URL and `Cache-Control: no-store`. It starts a single-use, ten-minute state. Do not follow it with a client that would forward management credentials to Twitch.
- **`GET /twitch/callback`** receives Twitch's `code` and `state`, or its error parameters. It returns HTML with `200` or `400`. Start at `/authorize`, not a manually constructed callback.
- **`GET /twitch/authorization`** returns `authorized`, optional `login`/`userId`/`expiresAt`, `scopes`, `missingScopes`, and `authorizeUrl`. `authorized` is true only while the retained grant is usable. Expired or rejected grants may retain identity and scopes for diagnosis while reporting false; readiness remains separate. Tokens are never returned.
- **`DELETE /twitch/authorization`** clears the in-memory grant and attempts saved-file deletion and provider revocation. It returns `204`, or `404` when none is held. File-deletion and provider failures are logged; confirm that the saved token file is gone before relying on sign-out across a restart.

Follow [Twitch setup](../guides/twitch-app-setup.md) for app registration, login identity, and callback routing.

The webhook route is provider-facing, not a manual event injection API. It checks `Twitch-Eventsub-Message-Id`, `Twitch-Eventsub-Message-Timestamp`, `Twitch-Eventsub-Message-Signature`, and `Twitch-Eventsub-Message-Type` headers against the original body. Use `/notifications` for operator-generated cards.

## Error responses 🛠️

Typical boundary rejection:

```json
{"error":"Private HTTP boundary rejected request","code":"browser_boundary_rejected"}
```

| HTTP status | Meaning / next step |
|---|---|
| `400` | Invalid input, enum, range, or failed OAuth callback |
| `401` | Provider authentication failure, not missing management credentials |
| `403` | Host, Origin, Fetch Metadata or request-target boundary rejection |
| `404` | Route/resource absent, stale connection/alert ID, or Twitch route not mounted |
| `409` | Alert acknowledgement conflicts with current state |
| `413` | Request body exceeds 65536 bytes |
| `415` | Management POST/PUT/PATCH lacks the required single JSON Content-Type |
| `503` | Publication unavailable, including exhausted sequence space; inspect error text |
| `500` | Internal server error; inspect redacted logs |

Retry GET requests as appropriate. Notification POST retries can publish duplicates. No Basic password verification or Bearer privilege exists.

## Mounted desk ingress

`GET /clock` returns a no-store relay epoch and monotonic millisecond sample. Notification POST and CPU PUT return matching `X-Relay-Epoch` and `X-Relay-Monotonic-Ms` exchange samples. Wrong deadline epoch returns 409, an expired deadline 410, and rate-limit rejection 429 with `Retry-After: 1`. Explicit null, duplicate or unknown request fields are rejected.

`GET /display` returns desired override/preference and source readiness. `PATCH /display` accepts `twitchDisplayEnabled` and/or `preferredDashboard` (`generic`, `weather`, `cpu`), preserves omitted fields and persists accepted intent before success. Equivalent writes do not advance revision. Desired intent is not device application. The default relay and initialized ESP32 support DESK negotiation; older classic-only firmware remains notification-compatible but cannot apply dashboard selection. Reports describe firmware observations, never physical pixels.

`GET /cpu` reads the latest global sample; `PUT /cpu` requires `sourceId`, `host` and `cpuPercent`. Optional fields are `memoryPercent`, `temperatureCelsius`, `observedAt`, `freshnessMs` and calibrated `deadline`. Percentages are finite 0–100; temperature is −20–150 °C; host is valid Unicode up to 128 scalar values. Freshness is 1000–60000 ms, default 10000. CPU history is not persisted or replayed. Follow the [canonical contract](desk-contract.md) for exact schemas.

`GET /weather` reads the latest relay-owned snapshot, whether fetched by the optional built-in Open-Meteo source or uploaded externally; `PUT /weather` admits the canonical source/location/temperature/condition payload with optional humidity and calibrated deadline. Omitted unit defaults to celsius; explicit null is rejected. Invalid, expired or wrong-epoch writes preserve accepted revision/data. Diagnostic labels preserve valid Unicode, including embedded controls, within 128 scalar values; wire text sanitization is separate. Reads never renew freshness; failed provider refreshes preserve the last successful value's original deadline. See [weather setup](../guides/weather.md) and [canonical schemas](desk-contract.md).

`GET /twitch/monitoring` and `PATCH /twitch/monitoring` are mounted. PATCH accepts a nonempty partial `mode`/`channel` update; modes are `disabled`, `simulated` and `live`. Channel input is format-validated and normalized to lowercase. Live resolution runs before acceptance under a three-second budget; missing application configuration rejects live selection without mutation. Accepted desired state persists separately from effective asynchronous application, and display intent remains unchanged. GET exposes desired/effective mode/channel, transition, generation, resolved identity and numeric-ID/scope grant compatibility. Stored unrelated grants are not revoked. See the [canonical schemas](desk-contract.md).

`GET /admin/snapshot` and `GET /admin/events` are mounted, together with the bundled panel at `/`. Snapshot responses join authoritative desired display/monitoring, current CPU/weather, bounded publication previews, separate transport pressure and firmware-reported queues. An unknown FIFO is null; a calibrated known-empty FIFO is an empty array. Stale/historical observations retain explicit labels and age, never become current from an ACK or receipt alone.

The same-origin SSE feed sends complete `snapshot`/`reset` events with `<relayEpoch>:<revision>` IDs, one latest pending snapshot, no-store/no-buffering headers and heartbeat comments. Limits are eight feeds globally, two per normalized remote IP, four snapshots/s and 512 KiB encoded snapshots. Chart history uses 180 five-second buckets and at most sixteen device identities; publication previews retain at most eighty records. A reset means epoch change or coalesced revision gap, not notification replay. There is no application polling fallback.

Companion calibration/upload/readback works against current ingress. Exact final HTTP/TCP/SSE/browser gates remain required independently of mounting. Default ESP32/physical LCD, complete provider/suspend, broader race/reset/producer-to-panel and physical mobile consent remain unverified.

Source trail: [API assembly](../../twitch-screen-relay/src/twitchscreen/relay/Apis.scala), [HTTP transport](../../twitch-screen-relay/src/twitchscreen/relay/http/HttpApi.scala), [notification contract](../../twitch-screen-relay/src/twitchscreen/relay/device/NotificationApi.scala), and [browser boundary](../../twitch-screen-relay/src/twitchscreen/relay/http/BrowserBoundary.scala).

