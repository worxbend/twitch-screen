# Migration and rollback: desk update in progress

[Current status](../implementation-status.md) · [Credential-free quickstart](../../README.md#first-success-without-twitch)

This checkout removes management Basic/Bearer authentication. Anyone reaching HTTP can read/control it. Restrict HTTP and device TCP with a trusted LAN/VPN/firewall before upgrade. Native listeners default to loopback; Compose publishes on loopback by default. Nonempty obsolete `RELAY_HTTP_AUTH_*` keys and nonempty old HOCON auth settings fail startup, including whitespace values. Remove them deliberately; ignored Authorization headers confer no privilege. Provider Twitch OAuth/callback signatures remain required.

Use `python3 tools/compose.py` from `twitch-screen-relay/` for interpolation-aware migration checks. It checks arbitrary obsolete-prefix inputs; direct Compose only forwards its explicitly listed deprecated keys. Do not print expanded config/environment. Mode/channel/chat are interpolated, with disabled/empty/hide defaults. Set a specific `RELAY_BIND_IP` and matching allowed Host/Origin settings for LAN access, never wildcard public publishing.

## Compatibility today

Classic TSB/3 layouts and all 20 vectors remain unchanged. The default production relay supports DESK/1 negotiation; actual internal-gated and default-relay native application tests cover synchronization, conserved credit and reconnect. The default ESP32 enables DESK only after actual widgets and notification lifecycle are built and a valid immutable boot identity is installed; coherent synchronized session readiness is still required before applying controls. Native application reports and memory-display views do not certify physical pixels. Classic ACK means enqueue, not pixels. Old devices do not gain waiting-expiry/control/report guarantees. Companion 0.1.0 calibrates and uploads measured CPU and synthetic cards to the mounted clock/CPU/notification APIs. Supported desktop/service and physical CPU-to-LCD acceptance remain pending.

The 30-second notification hold maximum, calibrated waiting expiry, runtime-state persistence, startup precedence and clock/display/weather/CPU/monitoring ingress are implemented. Runtime monitoring owns asynchronous source application separately from display intent. The private admin projection/feed/panel are mounted; complete default firmware/provider/physical acceptance remains pending. HTTP POST hold is now 100–30000 ms, rounded upward to 100 ms, default 8000; `RELAY_NOTIFICATION_TTL` no longer changes that default. Auth removal and hold/validation changes require major-release migration. No numeric product release tag has been selected.

## Backup and rollback

Before replacing a deployment, stop only the owned process/stack and back up operator configuration and the existing Twitch token directory privately. Preserve project/volume identity. Never remove grants with `down -v`, Docker prune or token deletion. Back up `RELAY_RUNTIME_STATE_FILE` (default `data/runtime-state.json`) privately with the token directory. Desired display/monitoring intent persists; CPU samples and RAM notification history do not.

Rollback to an authenticated revision also requires restoring compatible management configuration privately. Do not expose the credential-free revision while assuming old auth settings protect it. Preserve tokens, verify loopback health and a labeled generic card after restart, and reconcile observed provider/device state separately. Firmware rollback to classic-only code loses dashboard-control, freshness and device-report support. No rollback command may flash hardware or affect a live broadcast without scoped target authorization.

Missing historical `tasks/` reports stay missing. Security exception expiry remains unchanged; reassess from resolved-artifact evidence before release. See [rights blockers](../../THIRD_PARTY_NOTICES.md). Full documentation/source consistency and clean-checkout rollback acceptance remain pending.
