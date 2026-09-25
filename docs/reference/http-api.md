# 🔌 Relay HTTP API

[Guidebook](../README.md) · [Relay setup](../guides/relay-setup.md) · [Configuration](relay-configuration.md)

Base URL for local development: `http://localhost:8080/api/v1`. Open **`http://localhost:8080/docs`** on the running relay for generated Swagger UI and schemas. The device itself uses binary TCP on port 8099; these HTTP endpoints manage the relay and publish cards.

## Authentication and shared behavior 🔑

Protected requests accept **one** `Authorization` header, using either:

```http
Authorization: Bearer <your-relay-management-token>
```

or HTTP Basic credentials. `curl --user admin` prompts for the plaintext management password. The relay stores a PBKDF2 verifier for Basic login. Twitch's Client Secret and broadcaster access token are separate credentials and cannot authenticate management requests.

Health, aggregate stats, Swagger documentation, and the exact OAuth/EventSub callbacks are public. Callbacks validate their own protocol credentials. Every other route below requires management authentication. Cross-site browser requests using Basic credentials can receive `403`; use the relay directly in the address bar and preserve the public Host header through an HTTPS proxy.

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
| `GET` | `/twitch/authorize` | Management; live mode | `302` redirect to Twitch consent |
| `GET` | `/twitch/callback` | OAuth state; live mode | HTML consent success/failure page |
| `GET` | `/twitch/authorization` | Management; live mode | Held grant's identity, scopes, and expiry |
| `DELETE` | `/twitch/authorization` | Management; live mode | Forget grant and attempt Twitch revocation |
| `POST` | `/twitch/eventsub` | Twitch HMAC; live webhook mode | EventSub challenge or delivery response |

Disabled/simulated modes do not mount the Twitch OAuth routes. The EventSub callback also requires webhook transport. A `404` for one of these routes can therefore be a mode mismatch.

## Send a card 💌

Use the same token as the running relay:

```sh
curl --fail --silent --show-error \
  -H "Authorization: Bearer $RELAY_HTTP_AUTH_API_TOKEN" \
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
| `ttlMs` | No | Integer 1–6553500; omitted uses `RELAY_NOTIFICATION_TTL` |

Useful kinds are `info`, `message`, `warning`, `alert`, `follow`, `sub`, `gift`, `raid`, `chat`, and `bits`. Text can be shortened when encoded for the 240 × 240 display. This endpoint posts a generic card; it does not expose all native Twitch event metadata such as a subscription tier or gift count.

> [!IMPORTANT]
> **Existing stream-kind compatibility issue:** the generated schema advertises `stream_start` and `stream_end`, but the current JSON codec accepts and emits `streamstart` and `streamend`. The underscored spellings return `400`. Prefer the ordinary kinds above for integrations; binary TSB/3 stream event codes are unaffected. This behavior was checked against the local assembly and the [JSON codec](../../twitch-screen-relay/src/twitchscreen/relay/http/ApiJson.scala).

Every accepted POST creates a **new** notification. Retrying after an interrupted response can create a duplicate; there is no idempotency key. The response confirms publication and retention in the current process, including when no device is attached. It does not confirm rendering on a screen.

HTTP `503` with `{"error": "TSB/3 sequence space exhausted (§10.1); …"}` means the relay has assigned the last `u32` sequence number, 4294967295. The relay never wraps the sequence, so it publishes nothing more until it is restarted. A restart begins a new sequence space, and devices re-baseline on it. The relay reports this once: an error log line and one `device-hub failed` relay failure, which appears in the activity log and the failure metrics. `GET /status` then reports `deviceLink.sequenceExhausted: true`. Retrying the POST does not help.

Read recent cards:

```sh
curl --fail --silent --show-error \
  -H "Authorization: Bearer $RELAY_HTTP_AUTH_API_TOKEN" \
  'http://localhost:8080/api/v1/notifications?pageSize=20'
```

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
| `deviceLink` | Connected devices, accepted connections, published notifications, latest sequence, replay count, and `sequenceExhausted` (true once the `u32` sequence space is spent; restart the relay) |
| `subscribers` | Internal event-bus subscriber statistics, including losses |
| `activityEntries`, `activeAlerts`, `bufferedLogRecords` | Current in-memory diagnostic counts |

Twitch health may be `Disabled`, `Connecting`, `Connected`, `Degraded`, or `Disconnected`. Read the detail with it; HTTP can be healthy while the broadcaster grant is missing. The status route returns a snapshot rather than converting every degraded condition into a failing HTTP status.

## Inspect and reconnect a device 📡

```sh
curl --fail --silent --show-error \
  -H "Authorization: Bearer $RELAY_HTTP_AUTH_API_TOKEN" \
  http://localhost:8080/api/v1/devices
```

The `devices` array includes numeric `connection`, firmware `device` identity, remote address, protocol version, connection/last-seen timestamps, baseline and acknowledged sequence, byte/frame counts, dropped/skipped frame counts, invalid-field counters, and resynchronization counts. The listener permits at most 64 sessions, including pending handshakes.

Use the current **connection number**, not the device name, in per-connection URLs. For example, when the list reports connection 7:

```sh
curl --fail --silent --show-error \
  -H "Authorization: Bearer $RELAY_HTTP_AUTH_API_TOKEN" \
  http://localhost:8080/api/v1/devices/7

curl --fail --silent --show-error --request POST \
  -H "Authorization: Bearer $RELAY_HTTP_AUTH_API_TOKEN" \
  http://localhost:8080/api/v1/devices/7:disconnect
```

Disconnect closes the socket and returns its snapshot. It does not permanently block a device; firmware reconnects on its backoff schedule. A stale connection number returns `404`. This is useful for checking reconnect behavior while retained replay remains available.

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
  -H "Authorization: Bearer $RELAY_HTTP_AUTH_API_TOKEN" \
  'http://localhost:8080/api/v1/logs?minLevel=Warn&pageSize=50'

curl --fail --silent --show-error \
  -H "Authorization: Bearer $RELAY_HTTP_AUTH_API_TOKEN" \
  'http://localhost:8080/api/v1/alerts?activeOnly=true'

curl --fail --silent --show-error --request POST \
  -H "Authorization: Bearer $RELAY_HTTP_AUTH_API_TOKEN" \
  --output relay-activity.txt \
  'http://localhost:8080/api/v1/activity:export?category=Device&pageSize=100'
```

The report returns plain text with download filename `relay-activity.txt`. Review its contents before sharing; activity and logs can contain channel or device details.

`GET /alertRules` returns rule names, severity, and descriptions. Acknowledging an active alert uses its numeric ID:

```sh
curl --fail --silent --show-error --request POST \
  -H "Authorization: Bearer $RELAY_HTTP_AUTH_API_TOKEN" \
  http://localhost:8080/api/v1/alerts/3:acknowledge
```

Replace 3 with an active alert ID from your list. An unknown ID returns `404`; an alert that is no longer active returns `409`. Acknowledgement stops it counting as active; it does not fix its cause. States are `Active`, `Acknowledged`, and `Resolved`. Rules are configured at startup, not edited through HTTP.

## Configuration and Twitch consent 🎮

`GET /config` returns a `settings` map using dotted HOCON keys and string values. Management tokens/password verifiers, the Twitch application secret, and EventSub secret are masked. There is no configuration-write endpoint.

The OAuth endpoints exist only in live mode:

- **`GET /twitch/authorize`** returns `302` with a Twitch `Location` URL and `Cache-Control: no-store`. It starts a single-use, ten-minute state. Do not follow it with a client that would forward management credentials to Twitch.
- **`GET /twitch/callback`** receives Twitch's `code` and `state`, or its error parameters. It returns HTML with `200` or `400`. Start at `/authorize`, not a manually constructed callback.
- **`GET /twitch/authorization`** returns `authorized`, optional `login`/`userId`/`expiresAt`, `scopes`, `missingScopes`, and `authorizeUrl`. `authorized` reports that a grant is held; readiness is separate. Tokens are never returned.
- **`DELETE /twitch/authorization`** clears the in-memory grant and attempts saved-file deletion and provider revocation. It returns `204`, or `404` when none is held. File-deletion and provider failures are logged; confirm that the saved token file is gone before relying on sign-out across a restart.

Follow [Twitch setup](../guides/twitch-app-setup.md) for app registration, login identity, and callback routing.

The webhook route is provider-facing, not a manual event injection API. It checks `Twitch-Eventsub-Message-Id`, `Twitch-Eventsub-Message-Timestamp`, `Twitch-Eventsub-Message-Signature`, and `Twitch-Eventsub-Message-Type` headers against the original body. Use `/notifications` for operator-generated cards.

## Error responses 🛠️

Typical error shape:

```json
{"error":"Invalid management credentials"}
```

| HTTP status | Meaning / next step |
|---|---|
| `400` | Invalid input, enum, range, or failed OAuth callback; inspect `error` or callback HTML |
| `401` | Missing/incorrect management credentials, duplicate Authorization headers, or invalid provider callback authentication |
| `403` | A valid Basic request was rejected as cross-site |
| `404` | Route/resource absent, stale connection/alert ID, or Twitch route not mounted |
| `409` | Alert acknowledgement conflicts with current state |
| `413` | Request body exceeds 65536 bytes |
| `503` | Basic password verification is busy; retry with a modest delay. On `POST /notifications` it can instead mean the sequence space is exhausted; the `error` text says so, and only a relay restart clears it |
| `500` | Internal server error; inspect redacted logs |

Basic password verification permits two concurrent derivations; additional checks receive `503`. Prefer Bearer authentication for frequent automated polling. Retry GET requests as appropriate; notification POST retries can publish another card.

Source trail: [API assembly](../../twitch-screen-relay/src/twitchscreen/relay/Apis.scala), [HTTP transport](../../twitch-screen-relay/src/twitchscreen/relay/http/HttpApi.scala), [notification contract](../../twitch-screen-relay/src/twitchscreen/relay/device/NotificationApi.scala), and [authentication](../../twitch-screen-relay/src/twitchscreen/relay/http/ManagementAuth.scala).
