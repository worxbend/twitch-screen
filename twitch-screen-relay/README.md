# twitch-screen-relay

The backend half of the project: it watches a Twitch channel, and pushes what happens there to the ESP32 round
display over a persistent TCP link. It also serves an HTTP API for managing and monitoring itself.

Written in direct-style Scala 3 on the [VirtusLab Scala Stack](https://vss.virtuslab.com/) — Ox for structured
concurrency on virtual threads, Tapir on a synchronous Netty server, jsoniter for JSON, PureConfig for configuration,
MacWire for wiring. No effect system, no `Future`. Built with [Mill](https://mill-build.org); the `./mill` launcher
downloads the pinned Mill version and a Temurin 25 JDK on first use.

```sh
./mill test                     # unit, HTTP and real-socket regressions
export RELAY_HTTP_AUTH_API_TOKEN="$(openssl rand -hex 32)"
./mill run                      # http://localhost:8080/docs, device link on 8099
./mill assembly                 # one self-contained jar
```

## Try it without Twitch

`twitch.mode = simulated` generates a fixed, repeating script of follows, subs, gifts, raids, cheers and chat, so the
firmware and the enclosure can be worked on without Twitch credentials. This replaces what `demo-server/twitch_server.py`
used to do, speaking the same protocol through the same code paths as the real integration.

```sh
export RELAY_HTTP_AUTH_API_TOKEN="$(openssl rand -hex 32)"
RELAY_TWITCH_MODE=simulated ./mill run
# In another shell with the same management token:
curl -X POST localhost:8080/api/v1/notifications \
  -H "Authorization: Bearer $RELAY_HTTP_AUTH_API_TOKEN" \
  -H 'content-type: application/json' \
  -d '{"type":"alert","title":"Rack A","body":"78C"}'
```

Point the firmware's `SERVER_HOST`/`SERVER_PORT` at the machine running this and the screen lights up.

## How it fits together

```
      Twitch                                                   ESP32
  ┌────────────┐                                         ┌───────────────┐
  │ Helix poll │──┐                                  ┌──▶│ roundlcd-01   │
  │ chat (IRC) │──┼──▶ TwitchSource ──┐              │   └───────────────┘
  │ EventSub   │──┘                   │              │
  └────────────┘                      ▼              │
                                 ┌─────────┐   ┌───────────┐
  HTTP POST /notifications ─────▶│EventBus │──▶│ DeviceHub │──▶ TSB/3 binary over TCP :8099
                                 └─────────┘   └───────────┘
                                      │
                    ┌─────────────────┼──────────────────┬───────────────┐
                    ▼                 ▼                  ▼               ▼
              NotificationRouter  StatsAggregator   ActivityLog     AlertMonitor
              (which events           (the idle      (GET /activity) (GET /alerts)
               become a screen         dashboard)
               notification)
```

Producers publish to the bus; consumers subscribe. Neither knows the other exists, which is what lets the Twitch
source be swapped for a simulator — or removed entirely — without touching anything downstream.

Three pieces are worth knowing about:

**`DeviceHub` is an actor.** It assigns sequence numbers, keeps the 64-frame replay buffer and holds every attached
device. Running on a single thread is what makes sequence numbers monotonic *on the wire*: two events published at the
same instant cannot interleave their frames, so a device never sees a lower `seq` after a higher one and never
advances over a notification dropped from its outbound queue. The actor never blocks: an EVENT enqueue failure
closes that connection before a later EVENT can cross the gap; STATS may be dropped. Replay is bounded by retained
history and the current relay process. TSB/3 does not promise durable delivery across relay restarts.

**Each device connection is one virtual thread.** `DeviceLinkServer` accepts, forks a session, and the session's
reader runs in its own thread with the writer and heartbeat as forks beside it. Blocking socket I/O on a virtual
thread is interruptible, so ending the application scope ends every session; there is no shutdown flag anywhere in
the codebase.

**The event bus drops rather than blocks.** Each subscriber has a bounded queue and loses events once it is full,
reporting the loss through `GET /api/v1/status`. A relay that blocked its Twitch reader because the activity log was
busy would be dropping the events that matter to preserve the ones that do not.

## The device protocol

TSB/3, specified in [`../twitch-screen-firmware/docs/PROTOCOL.md`](../twitch-screen-firmware/docs/PROTOCOL.md): a
binary protocol over one long-lived TCP connection, server push, with `seq`-based replay on reconnect. Every frame is
an 8-byte header — magic `a7 53`, version, type, little-endian length, flags, header check — followed by a
fixed-offset, fixed-width payload of at most 248 bytes. It replaces the NDJSON v2 wire entirely; the two cannot
interoperate, and the cutover is a flag day.

Nothing is tolerant twice over: a malformed *frame* is skipped and counted, a malformed *stream* is resynchronised
one byte at a time until the budget runs out and then closed with a `BYE` saying why. The exact bytes of all twenty
golden vectors of §18 are pinned by `Tsb3GoldenVectorSuite`, in both directions; `FrameReaderSuite` and
`Tsb3DecoderSuite` cover framing and payload handling; and `DeviceLinkSuite` exercises the handshake, push, replay,
heartbeat, refusal and teardown over real sockets.

The relay is deliberately more patient than the firmware: the device gives up after 45 s of silence, the relay after
90 s, so the device decides when to reconnect rather than racing the server.

## HTTP API

Swagger UI at `/docs`. Resource-oriented and versioned, following AIP-136 — custom methods share the resource's path
segment after a colon (`POST /devices/7:disconnect`).

| Method | Path | What |
|---|---|---|
| `GET` | `/api/v1/health` | Liveness. Deliberately says nothing about Twitch, so a container health check never restarts a relay that is serving devices |
| `GET` | `/api/v1/status` | Readiness: Twitch, the device link, bus subscribers, alert and buffer counts |
| `GET` | `/api/v1/config` | The effective configuration after file, environment and defaults are merged, secrets masked |
| `GET` | `/api/v1/devices` | Attached devices and their traffic counters |
| `GET` | `/api/v1/devices/{connection}` | One device |
| `POST` | `/api/v1/devices/{connection}:disconnect` | Close a device's socket; it reconnects on its own backoff |
| `GET` | `/api/v1/notifications` | Recently published notifications, out of the replay buffer |
| `POST` | `/api/v1/notifications` | Push a notification to every attached device |
| `GET` | `/api/v1/stats` | The stream figures last broadcast to the devices |
| `GET` | `/api/v1/activity` | Recent relay activity, filterable by category |
| `POST` | `/api/v1/activity:export` | Download a plain-text activity report |
| `GET` | `/api/v1/alerts` | Raised alerts |
| `POST` | `/api/v1/alerts/{id}:acknowledge` | Acknowledge an active alert |
| `GET` | `/api/v1/alertRules` | The rules this relay was configured with |
| `GET` | `/api/v1/logs` | The relay's own recent log lines, for when it is running headless |
| `GET` | `/api/v1/twitch/authorize` | Open in a browser: redirects to Twitch's consent screen. `live` mode only |
| `GET` | `/api/v1/twitch/callback` | Twitch's OAuth redirect target; stores the token it is handed. `live` mode only |
| `GET` | `/api/v1/twitch/authorization` | Whose Twitch token the relay holds, its scopes and expiry — never the token. `live` mode only |
| `DELETE` | `/api/v1/twitch/authorization` | Revoke the token at Twitch and forget it. `live` mode only |
| `POST` | `/api/v1/twitch/eventsub` | Called by Twitch, not by you. Only mounted for the webhook transport |

Every error, including decode failures and unmatched routes, comes back as `{"error": "..."}`.

## Management authentication

At least one management credential must be configured, even in simulated mode.
Either valid Basic credentials or a valid Bearer token authorizes protected routes;
callers do not need both. Partial Basic configuration and malformed verifiers
fail startup. There is no default password, token or unauthenticated fallback.

- `RELAY_HTTP_AUTH_API_TOKEN`: random token of at least 32 UTF-8 bytes. Generate
  one with `openssl rand -hex 32` and send `Authorization: Bearer <token>`.
- `RELAY_HTTP_AUTH_BASIC_USERNAME` and `RELAY_HTTP_AUTH_BASIC_PASSWORD_HASH`:
  configure both for Basic Auth. Run `python3 tools/hash_management_password.py`
  to generate a salted verifier without storing the plaintext password.
  The format is `pbkdf2-sha256$600000$<base64 salt>$<base64 key>`; quote it with
  single quotes in shell assignments so dollar signs remain literal.

Health, aggregate stats and documentation remain public. The exact EventSub and
OAuth callbacks remain public with independent HMAC/timestamp/delivery-ID and
expiring single-use OAuth state checks. All other operations in the table above
require management authentication, including OAuth authorize/revoke and reads
of configuration, logs, history and device diagnostics. Browser Basic actions
also enforce cross-site request protection; CORS alone is insufficient.

Use HTTPS for credential-bearing network requests, directly or through a trusted
TLS proxy. Localhost commands are local development examples. Rotate credentials
by changing the environment and restarting. The verifier and token are masked in
configuration output. Never place credentials in URLs or commit `.env` files.

Notification POST returns HTTP 200 after publication; retries are **not
idempotent** and can create another card. A lost response does not prove the first
request failed. List `pageSize` values must be within 1–500. Device listings are
bounded by the listener's connection admission limit; the TCP listener trusts
its LAN and has no device authentication.

## Configuration

[`resources/application.conf`](resources/application.conf) is the reference, and every setting has an environment
variable override, so the container needs no config file. The most useful ones:

| Variable | Default | What |
|---|---|---|
| `RELAY_HTTP_AUTH_API_TOKEN` | — | Required unless complete Basic credentials are configured |
| `RELAY_HTTP_AUTH_BASIC_USERNAME` / `_PASSWORD_HASH` | — | Optional Basic alternative; both fields are required together |
| `RELAY_HTTP_PORT` | `8080` | Management API |
| `RELAY_DEVICE_PORT` | `8099` | Device link |
| `RELAY_TWITCH_MODE` | `disabled` | `disabled`, `simulated` or `live` |
| `RELAY_TWITCH_CHANNEL` | — | Channel login to watch |
| `RELAY_TWITCH_CLIENT_ID` / `_SECRET` | — | Twitch application credentials |
| `RELAY_TWITCH_REDIRECT_URL` | `http://localhost:8080/api/v1/twitch/callback` | OAuth redirect; must be registered on the Twitch application |
| `RELAY_TWITCH_TOKEN_FILE` | `data/twitch-token.json` | Where the granted user token is kept between restarts |
| `RELAY_TWITCH_SCOPES` | `moderator:read:followers,channel:read:subscriptions` | Scopes requested on the consent screen |
| `RELAY_TWITCH_EVENTSUB_TRANSPORT` | `websocket` | `websocket` or `webhook` |
| `RELAY_NOTIFICATION_TTL` | `8 seconds` | How long the firmware holds a card posted to `/api/v1/notifications` without its own `ttlMs`. Twitch events take their display time per kind from §6.4.1 and are not configurable |
| `RELAY_CHAT_NOTIFICATIONS` | `hide` | `show` puts every chat message on the screen |
| `RELAY_IGNORED_DISPLAY_NAMES` | `streamelements,nightbot,moobot,streamlabs,fossabot,sery_bot` | Comma-separated display names whose events are dropped at the source (§13.1); replaces the default list |
| `RELAY_STATS_INTERVAL` | `5 seconds` | Cadence of the idle dashboard push |

Misconfiguration fails at startup rather than at the first API call: `twitch.mode = live` without a client id is
rejected while the config is being read, and so is a ping interval longer than the idle timeout.

## Connecting to Twitch

Set `twitch.mode = live` and give it a client id and secret from
[dev.twitch.tv/console/apps](https://dev.twitch.tv/console/apps). Those two are the only Twitch credentials the relay
is configured with. The broadcaster's user token is obtained at runtime:

1. Register `http://localhost:8080/api/v1/twitch/callback` (or your `RELAY_TWITCH_REDIRECT_URL`) as an OAuth
   Redirect URL of the application.
2. Configure Basic management credentials for browser use (or request the authorize URL with a Bearer-capable
   client). Start the relay and open `http://localhost:8080/api/v1/twitch/authorize` in a browser, logged in to Twitch as the
   broadcaster.
3. Approve the consent screen. Twitch redirects back to the callback, and the relay exchanges the code for an access
   and refresh token, writes them to `data/twitch-token.json` (owner-only permissions) and starts EventSub and the
   follower/subscriber polls straight away. No restart is needed.

The relay refreshes the token before it expires and validates it hourly, so consent is a one-time step per deployment.
`GET /api/v1/twitch/authorization` shows whose token is held and which scopes are missing, and
`DELETE /api/v1/twitch/authorization` revokes it. The authorize request carries a single-use `state` that expires
after ten minutes, so a callback the relay did not start is refused.

Twitch accepts a plain-http redirect only for `localhost`. For a relay on another machine, either tunnel the port
(`ssh -L 8080:localhost:8080 pi`, then use the localhost URLs above) or serve the relay over HTTPS and set
`RELAY_TWITCH_REDIRECT_URL` to the public callback URL.

Each transport has one job, so no event arrives twice:

- **chat (IRC)** carries what chat sees: messages, subscriptions, gifted subs, cheers and raids. It connects
  anonymously, which reads any public channel but not subscriber-only chat.
- **EventSub** carries what only Twitch can push: individual follows, stream start and stop, channel updates.
- **the Helix poll** carries the totals nobody pushes: viewers, followers and subscribers.

Until someone authorizes, the relay still runs: viewer counts, chat and (on the webhook transport) stream start/stop
work on the application's credentials alone. Follows, follower and subscriber totals, and every EventSub WebSocket
subscription wait for the token. Each Helix call is attempted independently, so a missing scope costs one figure
rather than the poll.

`websocket` is the right EventSub transport for a Raspberry Pi: the relay dials out and needs no inbound
connectivity. `webhook` requires a publicly reachable HTTPS callback and a shared secret; the callback is
authenticated by its HMAC signature and timestamp, with a bounded delivery-ID cache suppressing duplicates.

## Docker

```sh
export RELAY_HTTP_AUTH_API_TOKEN="$(openssl rand -hex 32)"
docker compose up --build       # simulated mode, ports 8080 and 8099
```

The image is a Temurin 25 JRE plus one jar, running unprivileged, with a health check on `/api/v1/health`. The
granted Twitch token lives in `/home/relay/data`, which compose mounts as the `relay-data` volume so that consent
survives rebuilds. Base images and Mill launcher downloads are pinned by digest. Compose applies a 512 MiB
starting memory budget; Java uses up to 70% for heap and exits on heap exhaustion. The container smoke tests this
budget in simulated mode; size it from real stream/device load before production. Note that
the assembly is around 73 MB — twitch4j brings a large transitive stack (Jackson, OkHttp, Hystrix, Feign).

## Observability

OpenTelemetry is configured entirely from the standard `OTEL_*` environment variables and exports nothing until one
of them asks it to, so an unconfigured Pi pays nothing:

```sh
OTEL_TRACES_EXPORTER=otlp OTEL_METRICS_EXPORTER=otlp \
OTEL_EXPORTER_OTLP_ENDPOINT=http://collector:4317 ./mill run
```

Tapir records the standard HTTP server metrics; `RelayMetrics` adds relay-specific counters fed from the event bus,
so a new producer is instrumented by the fact that it publishes. Trace ids reach log lines through
`SetTraceIdInMDCInterceptor` and Ox's `InheritableMDC`, which is why `Main` installs
`PropagatingVirtualThreadFactory` — without it, forked work produces orphaned spans.

## Layout

```
src/twitchscreen/relay/
  Main.scala             OxApp entry point
  Dependencies.scala     the whole assembly, in dependency order
  Apis.scala             every group of endpoints, collected by MacWire's wireList
  config/                the typed configuration tree and its primitives
  protocol/              TSB/3: frame header, encoder, decoder, frame reader, domain types
  bus/                   RelayEvent and the fan-out
  device/                the TCP listener, sessions, the hub, and their HTTP API
  twitch/                the three event sources, twitch4j bridging, EventSub
  stats/                 the idle dashboard's fold
  activity/              the in-memory history
  alerts/                rules, evaluation and the store
  health/                liveness and readiness
  http/                  shared endpoint scaffolding and the server
  observability/         OpenTelemetry, metrics, the log buffer
test/src/…               MUnit suites; DeviceLinkSuite uses real sockets
```

One file is not Scala: [`twitch/EventSubFactory.java`](src/twitchscreen/relay/twitch/EventSubFactory.java). twitch4j
generates its EventSub conditions with Lombok's `@SuperBuilder`, whose recursive generics do not survive Scala's
wildcard capture — the setters return an unnameable `builder.B`. Small Java factories keep the rest of the
integration in Scala.

## Working on it

```sh
./mill compile                                   # must stay at zero warnings
./mill test
./mill test.testOnly twitchscreen.relay.http.ApiSuite
./mill mill.scalalib.scalafmt/                   # format sources
./mill mill.scalalib.scalafmt/checkFormatAll
RELAY_HTTP_PORT=8095 ./mill run                   # when 8080 is taken
```

Adding an endpoint: write it in its feature package using `.handle` / `.handleSuccess` (never `.serverLogic`), have
the class extend `ServerEndpoints`, and add it as a constructor parameter of `Apis` — `wireList` picks it up, so
the group is collected automatically. Declare its public, callback or sensitive access using the `Http` endpoint bases;
unclassified endpoints refuse startup. Protected operations advertise both authentication alternatives in OpenAPI.

The build enables `-Werror` with `-Wunused:all -Wvalue-discard -Wnonunit-statement`. Version numbers live in
`build.mill`; the relay's own version lives in `RelayVersion.scala`.

From the repository root, `python3 tools/audit_relay_dependencies.py` scans the resolved
runtime dependencies against OSV and fails on new advisories or incomplete scans. CI runs it too.
The two exact legacy exceptions expire on 2026-10-25; see the
[dependency assessment](../tasks/review-dependencies.md) for affected paths and follow-up.

## What has and has not been exercised

Verified here: the protocol bytes against the firmware's specification, the device link over real sockets, the
management API end to end, the aggregation and alert folds, the EventSub webhook's signature check, and the whole
relay running in simulated mode with a real TCP client attached and notifications arriving over both paths.

The image also builds and passes an isolated smoke under a 512 MiB limit: public and protected HTTP,
credential redaction, TSB/3 handshake, and a shutdown BYE followed by EOF on SIGTERM. Run it from the repository root:

```sh
docker build -t twitch-screen-relay:review twitch-screen-relay
python3 tools/smoke_container.py
```

The `live` Twitch path still needs validation with real account credentials. Scripted adapter tests cannot establish
provider acceptance or long-running recovery against the real service.
