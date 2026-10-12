# Twitch Screen

A small display for your whole desk.

DIY ESP32 firmware, a Scala relay and a printable round-display enclosure. The existing production path supports Twitch dashboards, simulated streams and generic notification cards. The multi-purpose desk update is in progress, not product-complete.

[Guidebook](docs/README.md) · [Implementation status](docs/implementation-status.md) · [Build a device](docs/guides/device-build.md) · [Static product site](site/README.md) · [Contribute](CONTRIBUTING.md)

## What works, and what remains

| Capability | Current scope |
|---|---|
| Generic HTTP notification publication and classic TCP delivery | Existing production path; acceptance is not proof of LCD pixels |
| Twitch live/simulated/disabled ingestion, dashboard and event cards | Existing production path; this integration does not certify live providers |
| Credential-free private HTTP | Implemented HTTP-only boundary; obsolete auth settings fail startup |
| DESK/1 contract, controller and delivery owner | Actual internal-gated hub/session and default relay/native application negotiation are installed; default ESP32 enables DESK after real widget/lifecycle initialization and valid boot identity; LCD appearance remains a separate check |
| Clock/display/weather/CPU HTTP APIs | Mounted calibrated ingress and durable desired display intent; no DESK/LCD application guarantee |
| Scheduled clock-discontinuity recovery and source ownership | Installed bounded cancellation/join/retry and source-fenced webhook/alerts; complete provider/suspend/writer acceptance remains pending |
| Runtime channel switching | Canonical monitoring API and owned asynchronous source application installed; real-provider and complete failure acceptance remain pending |
| Reported admin queues, private admin panel/feed | Mounted canonical snapshot/SSE and bundled panel; complete race/browser/physical acceptance remains pending |
| Linux companion | Installed measured-CPU/card HTTP pairing and private synthetic-bus recovery pass; supported desktop/service/LCD acceptance pending |
| Built-in backend weather | Optional Open-Meteo current conditions, one relay-owned fetcher and TCP weather widget delivery; [configuration and provider terms](docs/guides/weather.md) |
| Connected/disconnected Orbital Breathing and telemetry widgets | Actual LVGL adapters and production selection are software-tested; physical LCD appearance remains a separate check |
| Static product site | Local build/browser tested; publication not authorized or claimed |
| CAD enclosure | Existing nominal design; actual parts and print fit require physical validation |

## First success without Twitch

Requires Docker Engine with Compose v2 and Python 3. From this checkout:

```sh
cd twitch-screen-relay
python3 tools/compose.py --env-file .env.example up --build -d
curl --fail http://127.0.0.1:8080/api/v1/health
curl --fail -H 'Content-Type: application/json' \
  -d '{"type":"info","title":"Desk check","body":"Generic notification","ttlMs":8000}' \
  http://127.0.0.1:8080/api/v1/notifications
```

Defaults are disabled Twitch ingestion, no channel, hidden chat cards and loopback-only port publication. No management token is required. Successful POST means relay publication, not device presentation. Stop only this stack with `python3 tools/compose.py stop`; preserve its token volume. This command does not flash or connect hardware.

Remove old nonempty `RELAY_HTTP_AUTH_*` settings before startup. Authentication was removed deliberately. Anyone who reaches the service can read/control it. Host/Origin defenses do not authenticate LAN peers. Use only a trusted LAN/VPN/firewall; never expose management or plain device TCP publicly. Explicit LAN setup is in the [relay guide](docs/guides/relay-setup.md). The dedicated Caddy topology passes local certificate-verified HTTPS tests. Real mobile trust/consent remains pending; use desktop loopback consent, not plain HTTP LAN OAuth.

For a native simulated run, with JDK 25 available:

```sh
cd twitch-screen-relay
RELAY_TWITCH_MODE=simulated ./mill --no-server run
```

The [simulated tutorial](docs/guides/first-simulated-stream.md) explains the classic flow. Native defaults bind both listeners to loopback.

## Workbenches and checks

- [Relay](twitch-screen-relay/README.md): Scala 3/Mill/JDK 25. `./mill --no-server -j 1 test`.
- [Firmware](twitch-screen-firmware/README.md): PlatformIO/Arduino/LVGL. `pio test -e native` and `pio test -e native-sanitized`.
- [Companion](twitch-screen-client/README.md): Python 3.12+, opt-in desktop forwarding and aggregate CPU/memory; installed software pairing is exercised, supported desktop/service/LCD acceptance remains blocked.
- [CAD](twitch-screen-cad-design/README.md): FreeCAD/Blender, unchanged geometry and provisional physical fit.
- [Site](site/README.md): static build/preview, no private relay connection or tracking.
- Root: `python3 tools/check_protocol_vectors.py`, `python3 tools/check_desk_contract.py`, `python3 -m unittest discover -s tools/tests -v`.

The CI workflow prepares automated checks; local test evidence does not prove hosted Actions execution. [End-to-end preflight](docs/guides/end-to-end-testing.md) records physical/live prerequisites without starting a stream, capturing private content or enabling services.

## Licensing and limits

[LICENSE.md](LICENSE.md) maps original software to MIT, original documentation/illustrations to CC BY 4.0 and original hardware designs to CERN-OHL-P-2.0. [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) lists unresolved artwork, photo, CAD-reference and dependency rights. No whole-repository cleared-rights assertion is made. Existing photos and logos are excluded from this landing page and the site artifact; their presence elsewhere still blocks relevant release clearance.

Independent project, not affiliated with Twitch. Software source is available; physical parts, printing, hosting and provider usage may cost money. No commit, release, public deployment, physical rendering or scoped live acceptance is implied by this update.
