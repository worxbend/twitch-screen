# End-to-end testing: safe preflight and pending acceptance

[Implementation status](../implementation-status.md) · [Migration](migration.md)

From the repository root:

```sh
python3 tools/e2e_device.py
```

The command reads git identity/status and checks local tool/header presence. It never reads credentials, accesses the desktop bus, connects to a device/provider, installs services, starts Docker or controls OBS/Twitch. Exit 2 means acceptance is blocked, not a runner crash. To retain private JSON, pass `--output` with a new file in an existing private directory. Overwrite/symlink targets are refused. Test with `python3 -m unittest discover -s tools/tests -v`.

This is a preflight scaffold, not an implemented physical/live scenario executor. Named scenarios remain blocked until software foundations exist and real observations are collected. Current local container smoke proves real classic TCP WELCOME/STATS/EVENT/BYE and credential-free HTTP only. Mock HTTP, synthetic buses, controller tests and device reports do not prove LCD pixels, desktop popup compatibility or provider events.

## Required software before H

Clock/display/weather/CPU HTTP ingress is mounted, as are runtime monitoring APIs. Durable desired display/monitoring intent survives restart. The default relay now negotiates DESK with capable native applications; default ESP32 advertisement remains disabled. Installed companion uploads and same-port assembled-relay recovery are software gates. The internal-gated actual hub/session additionally exercises eight retained pending cards, zero credit, minimum outbound capacity and original telemetry expiry. These native memory-display observations do not prove LCD pixels.

Explicit hub epoch reset and stable application-scoped OAuth ownership are implemented and exercised; OAuth diagnostics reliably enter current hub authority. Scheduled clock-discontinuity recovery now owns bounded source cancellation/join/recreation and bounded continuing retry after construction or teardown failure. Stable webhook dispatch validates resolved broadcaster/subscription identity and preserves deduplication across replacement; source-tagged alerts check current hub authority. These installed owners do not complete real provider/suspend and full failure acceptance. Integration wave 12 independently exercised all four baseline/current peer combinations over actual TCP against the exact extracted Compose assembly; this is artifact compatibility, not default ESP32 or LCD acceptance. The baseline firmware row uses its original LinkSession/NotifyQueue with a socket adapter, not a physical board. Actual native continuous-control, partial socket writes, blocked kernel writer and original-identity lifecycle checks are separate finite software gates, not a universal transport guarantee. Runtime monitoring is mounted; admin snapshot/SSE/panel are mounted. Complete race/reset/producer-to-panel, browser reconnect/error/zoom/contrast and resource acceptance remains pending. Verify the complete joint profile before enabling default ESP32 advertisement. Verify production SSE beyond 60 seconds, admission/slow-reader/cleanup and real-browser panel workflows on exact final artifacts. Internal pairing, fixture traffic and HTTP acceptance are not substitutes for these production gates.

## Physical and live prerequisites

- Dynamically identify the connected board and serial path; verify pin map/firmware identity and exclusive ownership before upload. The current mission authorizes scoped PLAN upload/testing after these prerequisites; it does not authorize guessed Wi-Fi settings or unrelated board changes. Never overwrite credentials.
- Provide LCD photo/video or explicit human observation. Measure actual text fit, RGB565/animation seams, loop/render percentiles, heap/largest block/stack and the required 60-minute watchdog soak.
- Supply the exact private env path through operator-owned tooling; verify key presence only. Preserve token storage. Public authorization metadata must establish usable worxbend identity/scopes; channel text alone is not a grant.
- Verify authenticated OBS WebSocket v5, exact `OBS AI` placeholder scene, destination and baseline output ownership. Never reveal/change keys, service/profile or unrelated scene content. Do not start/stop a broadcast in preflight.
- Establish Ubuntu 24.04/GNOME 46 Wayland monitor permission and normal desktop delivery with synthetic-only filters. The current mission permits scoped service tests after ownership and supported-session checks. Preserve existing services and capture no unrelated private content; no root/bus-policy workaround.
- Establish trusted private HTTPS and exact registered callback for real phone/tablet consent. Emulated touch is not real mobile OAuth.

## Evidence and cleanup

For every scenario record identity, timestamps, expected/observed result, software layer, blocked reason and evidence paths. ACK is enqueue; DEVICE_REPORT observes firmware lifecycle; only visual evidence verifies pixels. Simulated Twitch events are fixtures, not live paid/rare provider coverage. Never spend money or mutate another channel to manufacture events.

Track owned containers/processes/output before execution. On every exit restore only test-modified settings and stop only test-started output/processes. Preserve grants and unrelated services. Never prune Docker or delete token volumes. Keep raw artifacts owner-only; sanitize secrets/personal chat/desktop content before sharing. No public publication or whole-product completion follows from a preflight or software-only pass.
