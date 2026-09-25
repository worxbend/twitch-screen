# ⚙️ Relay configuration reference

[Guidebook](../README.md) · [Relay setup](../guides/relay-setup.md) · [Twitch setup](../guides/twitch-app-setup.md) · [HTTP API](http-api.md)

The source of truth is [resources/application.conf](../../twitch-screen-relay/resources/application.conf), with startup validation in [Config.scala](../../twitch-screen-relay/src/twitchscreen/relay/config/Config.scala) and [HttpAuthConfig.scala](../../twitch-screen-relay/src/twitchscreen/relay/config/HttpAuthConfig.scala). Settings are read at startup. The HTTP configuration endpoint is read-only.

The tables below list **every explicit `RELAY_*` environment override** in the shipped configuration. Advanced settings further down have HOCON keys only; an invented environment name has no effect.

## Management HTTP and authentication 🔑

| Environment variable | HOCON key | Default | Meaning / constraint |
|---|---|---|---|
| `RELAY_HTTP_HOST` | `http.host` | `0.0.0.0` | HTTP bind address |
| `RELAY_HTTP_PORT` | `http.port` | `8080` | HTTP port, 1–65535 |
| `RELAY_HTTP_AUTH_API_TOKEN` | `http.auth.api-token` | Empty | Bearer credential; at least 32 UTF-8 bytes in the accepted ASCII alphabet |
| `RELAY_HTTP_AUTH_BASIC_USERNAME` | `http.auth.basic-username` | Empty | Basic username; no colon, control characters, or surrounding whitespace |
| `RELAY_HTTP_AUTH_BASIC_PASSWORD_HASH` | `http.auth.basic-password-hash` | Empty | Salted PBKDF2-SHA256 verifier; requires a Basic username |

At least one complete authentication method is mandatory **in every mode**. If both are configured, either can authorize a request. Half-configured Basic credentials fail startup even with a valid Bearer token.

The Bearer alphabet is `A–Z`, `a–z`, `0–9`, `.`, `_`, `~`, `+`, `/`, `-`, with optional trailing `=`. `openssl rand -hex 32` generates an accepted 64-character random value. An ordinary sentence, token with spaces, or short default is rejected.

The exact Basic verifier format is `pbkdf2-sha256$600000$<base64-salt>$<base64-key>`, with a 16–64-byte salt and 32-byte derived key. Generate it with `python3 tools/hash_management_password.py` from `twitch-screen-relay/`. Keep literal dollar signs by single-quoting a saved verifier in shell or Compose environment files.

Neither listener terminates TLS. See the [network guide](../guides/relay-setup.md#network-boundaries-and-https-) before exposing management credentials beyond localhost.

## Device listener 📡

| Environment variable | HOCON key | Default | Meaning / constraint |
|---|---|---|---|
| `RELAY_DEVICE_HOST` | `device-link.host` | `0.0.0.0` | TCP bind address |
| `RELAY_DEVICE_PORT` | `device-link.port` | `8099` | TCP port, 1–65535; match firmware `SERVER_PORT` |
| `RELAY_DEVICE_IDLE_TIMEOUT` | `device-link.idle-timeout` | `90 seconds` | Accepted duration must have 1–65535 whole seconds; longer than ping interval |

The listener accepts **TSB/3** only. The firmware connects to the relay's reachable address, not the listener's `0.0.0.0` wildcard. There is no device authentication or encryption; use a trusted device network.

## Twitch mode and application 🎮

| Environment variable | HOCON key | Default | Meaning / constraint |
|---|---|---|---|
| `RELAY_TWITCH_MODE` | `twitch.mode` | `disabled` | `disabled`, `simulated`, or `live`; case-insensitive |
| `RELAY_TWITCH_CHANNEL` | `twitch.channel` | Empty | Channel **login**, without URL or `#`; mandatory in live mode |
| `RELAY_TWITCH_CLIENT_ID` | `twitch.client-id` | Empty | Twitch application Client ID; mandatory in live mode |
| `RELAY_TWITCH_CLIENT_SECRET` | `twitch.client-secret` | Empty | Twitch application secret; mandatory in live mode |
| `RELAY_TWITCH_POLL_INTERVAL` | `twitch.poll-interval` | `30 seconds` | Positive Helix polling interval |
| `RELAY_SIMULATION_INTERVAL` | `twitch.simulation.interval` | `12 seconds` | Positive interval between scripted synthetic events |

| Mode | Source behavior | Needs Twitch application credentials? |
|---|---|---|
| `disabled` | No Twitch connection; manual notification API still works | No |
| `simulated` | Repeating synthetic stream activity and chat | No |
| `live` | Helix polling, anonymous IRC, and EventSub | Yes, plus broadcaster consent for scoped data |

The Dockerfile defaults to `disabled`; **Compose overrides this to `simulated`**. The numeric broadcaster ID is resolved from the login. The selected mode determines which Twitch HTTP routes exist.

## Broadcaster OAuth 🔐

| Environment variable | HOCON key | Default | Meaning / constraint |
|---|---|---|---|
| `RELAY_TWITCH_REDIRECT_URL` | `twitch.oauth.redirect-url` | `http://localhost:8080/api/v1/twitch/callback` | Exact registered callback; path must be `/api/v1/twitch/callback` |
| `RELAY_TWITCH_SCOPES` | `twitch.oauth.scopes` | `moderator:read:followers,channel:read:subscriptions` | Comma-separated requested scopes; replaces defaults |
| `RELAY_TWITCH_TOKEN_FILE` | `twitch.oauth.token-file` | `data/twitch-token.json` | Nonempty storage path in live mode; relative to process working directory |

Live callback validation accepts HTTPS or HTTP on the recognized loopback hosts `localhost`, `127.0.0.1`, and IPv6 loopback. It rejects a query string, fragment, user-info, or any different path. Register the exact chosen URL with Twitch; the default localhost spelling is the documented setup path.

The backend receives access and refresh tokens through consent; there is **no user-access-token environment setting**. The grant's login must match `RELAY_TWITCH_CHANNEL`. A missing grant keeps relevant integrations awaiting authorization; it is not a reason to paste a Twitch token into firmware.

Changing scope configuration requires a new consent round to obtain new permissions. The default follow and subscriber scopes are also expected by integration readiness checks; reducing the list can leave the source degraded.

## EventSub transport 📬

| Environment variable | HOCON key | Default | Meaning / constraint |
|---|---|---|---|
| `RELAY_TWITCH_EVENTSUB_TRANSPORT` | `twitch.event-sub.transport` | `websocket` | `websocket` or `webhook`; case-insensitive |
| `RELAY_TWITCH_EVENTSUB_CALLBACK_URL` | `twitch.event-sub.callback-url` | Empty | Required for live webhook mode; HTTPS on port 443, path `/api/v1/twitch/eventsub` |
| `RELAY_TWITCH_EVENTSUB_SECRET` | `twitch.event-sub.secret` | Empty | Required for live webhook mode; 10–100 ASCII characters |

WebSocket transport opens an outbound connection. Webhook transport requires Twitch to reach your HTTPS callback. Its URL cannot include user-info, a query, or a fragment. See [Twitch setup](../guides/twitch-app-setup.md#optional-use-eventsub-webhooks-) for callback routing and secret-rotation limitations.

## Notifications and display cadence ✨

| Environment variable | HOCON key | Default | Meaning / constraint |
|---|---|---|---|
| `RELAY_NOTIFICATION_TTL` | `notifications.default-ttl` | `8 seconds` | Default for manual HTTP notifications only; 1–6553500 milliseconds |
| `RELAY_CHAT_NOTIFICATIONS` | `notifications.chat` | `hide` | `show` or `hide`; case-insensitive |
| `RELAY_IGNORED_DISPLAY_NAMES` | `notifications.ignored-display-names` | `streamelements,nightbot,moobot,streamlabs,fossabot,sery_bot` | Comma-separated display names; replaces the default list |
| `RELAY_STATS_INTERVAL` | `stats.broadcast-interval` | `5 seconds` | Positive interval between device stats broadcasts |

Chat `hide` suppresses individual chat **cards**; accepted chat still feeds counters. Ignored display names are filtered **at source**, so their authored events also do not enter the bus, replay, or chat counters. Matching uses a trimmed, lowercase **display name**, not a stable user ID or login. A renamed bot can evade this list, and a person choosing a matching display name can be suppressed.

Customizing the ignored list replaces it wholesale. Include the defaults in your value when you want to add one more bot. List parsing accepts either a HOCON list or a comma-separated environment value.

Live and simulated Twitch event cards use protocol-defined TTLs: follows/chat 6 seconds; subs/gifts/Bits and generic kinds 8 seconds; raids and stream transitions 10 seconds. `RELAY_NOTIFICATION_TTL` affects only `POST /api/v1/notifications` when `ttlMs` is omitted. Wire TTL is encoded in deciseconds; see the [protocol](../../twitch-screen-firmware/docs/PROTOCOL.md) for rounding and payload limits.

## Activity and alerts 🚨

| Environment variable | HOCON key | Default | Meaning / constraint |
|---|---|---|---|
| `RELAY_ACTIVITY_BUFFER_SIZE` | `activity.buffer-size` | `500` | Positive number of retained activity entries |
| `RELAY_ALERT_NO_DEVICES_FOR` | `alerts.no-devices-connected-for` | `2 minutes` | Positive duration before the no-device rule triggers |
| `RELAY_ALERT_TWITCH_DISCONNECTED_FOR` | `alerts.twitch-disconnected-for` | `1 minute` | Positive duration for the Twitch-disconnected rule |
| `RELAY_ALERT_STREAM_OFFLINE_FOR` | `alerts.stream-offline-for` | Unset | Positive duration to enable the offline-stream rule |

Alert evaluation runs every 15 seconds by default. Leaving `RELAY_ALERT_STREAM_OFFLINE_FOR` unset retains the normal behavior: an offline stream alone does not trigger that optional rule. For rules enabled by an application default, merely unsetting the environment variable restores the default; use a HOCON `null` override to disable an optional duration. Zero or a negative duration is invalid.

## Advanced HOCON settings 🧩

These settings have **no explicit environment override** in the shipped file:

| HOCON key | Default | Constraint / purpose |
|---|---|---|
| `device-link.protocol-version` | `3` | Must be exactly 3 |
| `device-link.accept-backlog` | `16` | Positive TCP accept backlog |
| `device-link.handshake-timeout` | `5 seconds` | Positive time to receive the device greeting |
| `device-link.ping-interval` | `20 seconds` | 1–65535 whole seconds; shorter than idle timeout |
| `device-link.outbound-queue-capacity` | `128` | Must hold at least `replay-buffer-size + 18` greeting frames |
| `device-link.replay-buffer-size` | `64` | 1–65535 retained non-chat notifications; chat has a separate fixed 16-record ring |
| `device-link.max-frame-length` | `256` | Must be exactly 256 bytes, including header |
| `twitch.oauth.refresh-before` | `15 minutes` | Positive lead time for token refresh |
| `twitch.simulation.chat-interval` | `2 seconds` | Positive synthetic-chat interval |
| `bus.subscriber-queue-capacity` | `1024` | Positive per-consumer event queue capacity |
| `stats.chat-rate-window` | `1 minute` | Positive rolling rate window |
| `alerts.evaluation-interval` | `15 seconds` | Positive rule evaluation cadence |
| `alerts.buffer-size` | `100` | Positive alert retention count |
| `alerts.error-rate-threshold` | `10` | Nonnegative threshold within the error-rate window |
| `alerts.error-rate-window` | `5 minutes` | Positive error-rate window |
| `observability.log-buffer-size` | `500` | Positive in-memory log retention count |

For a standalone JAR, an external HOCON file can include application defaults and override selected keys. For example, save this as `data/relay-local.conf` beneath `twitch-screen-relay/`:

```hocon
include classpath("application.conf")

alerts.no-devices-connected-for = null
observability.log-buffer-size = 1000
```

From that directory, with your authentication environment configured and the assembly built:

```sh
java -Dconfig.file=data/relay-local.conf -jar out/assembly.dest/out.jar
```

This disables the no-device duration rule and retains 1000 log records. Explicit values in the external file override the included values; the environment substitutions still apply to settings you leave inherited. Keep secret values in the configured environment rather than this sample file.

Additional fixed limits in code include 64 simultaneous TCP sessions, 128 HTTP connections, 65536-byte HTTP request bodies, and list `pageSize` values of 1–500. They are not deployment knobs in this version.

## OpenTelemetry and JVM options 📊

OpenTelemetry uses standard `OTEL_*` settings independently of the `RELAY_*` tree. Traces, metrics, and logs default to exporter `none`; service name defaults to `twitch-screen-relay`.

| Variable | Typical value | Use |
|---|---|---|
| `OTEL_SERVICE_NAME` | `twitch-screen-relay` | Service identity in your telemetry backend |
| `OTEL_TRACES_EXPORTER` | `otlp` | Enable trace export |
| `OTEL_METRICS_EXPORTER` | `otlp` | Enable metrics export |
| `OTEL_LOGS_EXPORTER` | `otlp` | Enable log export |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://collector:4317` | Reachable collector endpoint |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError` | Consumed by the Docker entrypoint; not automatically applied by a direct `java -jar` invocation |

The base Compose file forwards only its listed environment keys. Add additional `RELAY_*` or `OTEL_*` values to an override file to pass them into the container. An arbitrary variable in your host shell or Compose `.env` does not become a container variable by itself.

## Verify the effective configuration ✅

```sh
curl --fail --silent --show-error \
  -H "Authorization: Bearer $RELAY_HTTP_AUTH_API_TOKEN" \
  http://localhost:8080/api/v1/config
```

The response is `{"settings":{...}}`, using dotted HOCON keys and string values. The Basic verifier, API token, Twitch client secret, and EventSub secret are replaced by `***`. The broadcaster token is not part of this configuration endpoint. `/config` is still protected because settings reveal deployment details.

Values such as `"30 seconds"` require shell quoting when assigned on a command line. Durations must be finite; use readable units rather than guessing milliseconds. For malformed settings, the relay fails startup with validation context instead of silently running a partially configured integration.
