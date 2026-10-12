# Plan: multi-purpose desk display

Status: implementation handoff; product and engineering decisions selected under the user's delegated authority on 2026-10-11.
Section 14 locks the implementation contract. A1 transcribes and validates it in normative docs/codecs/fixtures; it is not another product-approval round.
Optional roadmap ideas stay outside the first release.
Baseline: `main` at `b30b91f`.
Scope: major repository-wide product update: relay + firmware + protocol + tests + deployment + all documentation + product microsite + relay admin panel + runtime Twitch channel control + queue observability + Linux desktop companion daemon + release evidence.
No implementation yet. This update plans execution; it does not start Docker, flash hardware or start an OBS broadcast.

### Instructions for the implementing agent

Read sections 1–5 and 14 before editing runtime code, then work through section 6's dependency order.
The user asked the planning agent to make the remaining decisions autonomously. Use these decisions without asking the same preference questions again.
Technical corrections supported by source inspection or failing tests may be made autonomously: record the reason and update the contract, both peers and fixtures together.
Do not silently reduce required scope or reinterpret an unverified gate as passed. Discover local hardware/secrets only when the relevant execution slice needs them.
This file records planned work, not completed implementation. Keep unchecked implementation boxes unchecked until their acceptance evidence exists.
Existing `PROTOCOL.md` remains authoritative for legacy peers; section 14 defines the new negotiated profile to add alongside it.

## 1. Goal

Keep existing Twitch dashboard, cards, integration and authorization.
Extend the device into a multi-purpose display without coupling other features to Twitch.
Ship the complete product, not just new runtime code: consistent documentation, reproducible deployment,
a free/open-source product landing page, a modern private-network relay admin panel,
a companion daemon forwarding desktop notifications and CPU/memory snapshots,
and verified real-device operation on the `worxbend` Twitch channel.

### Approved control model

- `twitchDisplayEnabled`: management-controlled dashboard override. Not OAuth status or ingestion configuration.
- `preferredDashboard`: `generic`, `weather` or `cpu`; remembered while Twitch overrides it.
- Generic notifications: independent queued overlays, not an exclusive display mode.
- Relay connectivity and data freshness: independent inputs to screen selection.

Enabling Twitch display means the operator wants the streaming dashboard now.
It does not authorize an account, start a broadcast, or change Twitch ingestion.
Admin UI controls this display intent separately from which Twitch channel the relay monitors.

### Approved behavior

- Twitch display enabled: existing Twitch dashboard and cards; hide weather/CPU dashboards.
- Generic notifications work with Twitch enabled, offline, unavailable or disabled.
- Active generic notification always finishes, including when Twitch display is enabled mid-card.
- Weather/CPU updates remain accepted and cached while Twitch hides their dashboards.
- Weather/CPU use latest snapshots, never queued historical telemetry.
- Stale/missing weather or CPU data disappears; fall back to connected screensaver type one.
- Relay disconnected/unavailable: weather/CPU unavailable; use screensaver type two beneath any eligible generic card.
- Already queued, unexpired generic cards may display without the relay.
- Both screensavers run entirely on-device; no network/provider dependency.
- Type two includes a subtle connection-loss symbol. No endless full-screen `CONNECTING` label.
- Recompute the base before card exit reveals it, and during later state changes; never reveal stale telemetry.
- An active Twitch card also finishes; queued Twitch-origin cards are suppressed outside the override or after relay loss.

### Decision summary — locked for implementation

Exact fields, bounds, compatibility and failure behavior live in section 14. This table is a navigation aid, not a second set of defaults.

| Area | Decision | Contract |
|---|---|---|
| Startup | Persist override, preference and monitored mode/channel together, separately from OAuth; first-run display is Generic and ingestion is disabled unless explicitly configured | 14.1 |
| Notifications | Keep existing POST; 8 s default hold, 30 s maximum; separate 60 s default waiting expiry, 10 min maximum; explicit provenance | 14.2, 14.5 |
| Weather | One externally supplied location; Celsius default, explicit Fahrenheit supported; 10 min default freshness | 14.2 |
| CPU | One configured host; aggregate CPU plus memory as the sole secondary device metric; 2 s sampling, 10 s default freshness | 14.2, 14.9 |
| Protocol | Add all-or-nothing `CAP_DESK_V1` to TSB/3; preserve old records/vectors and ACK meaning; separate processed cursor and credit reports | 14.3–14.6 |
| Delivery | Generic-only reconnect replay for capable peers; Twitch episodes prevent disable/re-enable resurrection; control bypasses event credit | 14.5 |
| Admin | Local vanilla HTML/CSS/JS and SVG charts; same-origin SSE using pinned Tapir/Ox; bounded authoritative snapshots | 14.7 |
| Runtime Twitch | Stable OAuth owner, serialized source switching, reliable generation fences; persisted settings take precedence over startup defaults | 14.1, 14.6 |
| Network | Loopback default; explicit LAN bind/Host/origin configuration; fail startup on obsolete management-auth settings | 14.8 |
| Companion | Python 3.12+, asyncio, dbus-fast, HTTPX, `/proc`; Ubuntu 24.04 GNOME/Wayland is the first acceptance target, subject to actual capture proof | 14.9 |
| Visuals | Orbital Breathing at provisional 20 Hz with defined palettes and measurable release targets | 14.10 |
| Identity/site | Keep Twitch Screen and existing identifiers; static GitHub Pages project site, prepared but not published | 14.11 |
| Licensing | MIT original software, CC-BY-4.0 original docs/media, CERN-OHL-P-2.0 original hardware designs; preserve third-party terms | 14.11 |
| Execution prerequisites | Discover env path/device/OBS/visual evidence at H preflight; absence blocks only dependent verification | 8, 14.12 |

Initial scope: global display control, private-network management without application authn/authz,
modern relay-served admin UI, runtime single-channel Twitch monitoring, external weather clients and a shipped Linux host companion,
bounded RAM replay/history/charts and negotiated device queue reporting. No priority/preemption queue.
Per-device display preferences, provider collectors and multi-channel monitoring belong in the backlog.

### Approved private-network management boundary

- No relay login, accounts, roles, Basic/Bearer requirement or producer API tokens for the supported private deployment.
- Any peer that can reach the relay can read status/notifications and change display/Twitch settings.
- Network isolation is the access boundary: trusted LAN/VPN interface, firewall and no internet exposure/port forwarding.
- Private network does not mean hostile web pages cannot reach local services; retain independent browser-origin/Host defenses.
- This replaces earlier “keep management authentication” requirements. Update config validation, middleware, OpenAPI, templates and tests together.
- Twitch OAuth consent, token refresh, scope checks and OBS WebSocket credentials remain required where those providers require them.
- No relay auth change is implemented by this plan edit; see section 11 for the new panel contract.

## 2. What already exists

Do not rebuild these capabilities.

- `POST /api/v1/notifications` already accepts `type`, `title`, `body`, optional `ttlMs`.
- It already broadcasts cards through the real TCP delivery path.
- Generic kinds already exist: `info`, `message`, `warning`, `alert`.
- Firmware already renders those kinds. Improve/reuse the card instead of duplicating it.
- Existing API currently requires Basic or Bearer authentication. Planned private-network management removes that requirement.
- Disabled/simulated/live Twitch modes already exist. They select ingestion, not display layout.
- EventBus, DeviceHub, heartbeat, reconnect, ACK and bounded replay already exist.
- There is no backend display-mode command or weather/CPU snapshot contract.
- No relay admin panel or browser update feed exists; Swagger is API reference, not the requested control panel.
- Twitch mode/channel are currently startup configuration; live source owns its OAuth routes and uses captured channel state.
- Notification history/replay counts and ACKs exist, but do not reveal the actual device pending queue or active card.
- No companion daemon/package exists. Desktop capture and CPU/memory collection below are new planned work, not current capabilities.

Important correction: `CONNECTING` means relay link down, not Twitch offline.
A connected device with offline Twitch currently shows the offline dashboard.
The new design must separate transport health, Twitch health and selected display mode.

### Current source anchors

- Push API: `twitch-screen-relay/src/twitchscreen/relay/device/NotificationApi.scala:17–21,69–78,106–114`.
- Hub publication: `twitch-screen-relay/src/twitchscreen/relay/device/DeviceHub.scala:78–89`.
- Twitch routing: `twitch-screen-relay/src/twitchscreen/relay/device/NotificationRouter.scala:29–41`.
- Protocol messages: `twitch-screen-relay/src/twitchscreen/relay/protocol/Tsb3Message.scala:22–43`.
- Relay assembly: `twitch-screen-relay/src/twitchscreen/relay/Dependencies.scala:32–66`.
- Card widget: `twitch-screen-firmware/src/ui_notify.cpp:117–147,226–353`.
- Idle/link coupling: `twitch-screen-firmware/src/ui_idle.cpp:370–428`.
- Link hooks: `twitch-screen-firmware/src/link_client.h:27–42`.
- Queue and UI loop: `twitch-screen-firmware/src/main.cpp:39–89,113–123`.

## 3. Approved behavior

### Screen selection priority

Apply this order whenever state changes or a notification finishes:

1. Any already active card, including Twitch-origin: finish its entire entrance/hold/exit presentation. Eligibility/expiry changes affect waiting cards only.
2. Relay disconnected/unavailable: local screensaver type two.
3. Relay connected but awaiting authoritative initial control: screensaver type one.
4. Relay connected and Twitch display enabled: existing Twitch dashboard.
5. Twitch display disabled and preferred weather/CPU snapshot fresh: selected dashboard.
6. Otherwise: local screensaver type one.

Generic cards can overlay every base screen. Preserve FIFO among eligible generic cards.
An uninterrupted burst can delay base-screen visibility; bound card duration and expire old cards.

### Twitch override

- Preserve Twitch OAuth mechanics and disabled/simulated/live source behavior.
- Add bounded runtime source/channel reconfiguration for the admin UI; selecting a display never starts/stops ingestion implicitly.
- Live ingestion configuration or a usable token does not imply a broadcast is running.
- Management explicitly enables/disables Twitch display; no automatic online/offline switching initially.
- While enabled, use existing live/offline dashboard according to observed Twitch state.
- Provider offline/unavailable does not become relay-disconnected or expose weather/CPU.
- Disable override: restore preferred non-Twitch dashboard if available, otherwise screensaver type one.
- Updating preferred dashboard while overridden must not hide Twitch.
- Generic notification admission/delivery must never depend on Twitch grant, health or display override.

### Relay availability and recovery

Use established device session and heartbeat, not Twitch health or HTTP liveness.
Distinguish connecting, welcomed/awaiting control, ready and disconnected.
A silent TCP connection becomes unavailable after bounded existing link detection.
Once unavailable is detected, hide weather/CPU; do not leave stale dashboards behind a warning.

- First boot without relay: screensaver type two; networking retries in background.
- Relay lost: finish active generic card; show type two between remaining eligible queued cards.
- Reconnect: invalidate prior-session dashboard availability; preserve active/queued eligible generic cards separately.
- Synchronize control and explicit fresh/missing status for both weather and CPU before ordinary display operation.
- Become ready only at an explicit synchronization-complete boundary. Heartbeats do not prove application readiness.
- Bound synchronization time; on failure, disconnect/resynchronize rather than waiting forever.
- Scope synchronization to a session generation; reject obsolete-generation updates and define revisions across relay restart.
- Restore weather/CPU only when completed current synchronization provides a still-fresh snapshot.
- Retain preferred selection internally; do not treat it as proof the dashboard is available.

Backend cannot command a disconnected device. Offline fallback belongs to firmware.

### Screensavers: Orbital Breathing

Approved direction; final visual tuning requires the real round LCD.

Type one — connected:

- Layered concentric arcs with slow rotation.
- Smooth radial expansion/contraction and gradual color drift.
- Seeded randomness changes the pattern continuously, without frame-to-frame jumps.
- Seamless circular/endless motion; no logo, text, flashing or remote assets.

Type two — disconnected/unavailable:

- Same visual language, clearly different appearance.
- Dimmer amber/gray palette, broken outer orbit and slower breathing.
- Small connection-loss symbol; avoid an intrusive status message.

Use bounded procedural LVGL shapes initially, not a full-screen per-pixel shader.
Start with three reused arcs, stable phase offsets and slowly interpolated motion/color targets.
Pause hidden animation and resume its composition after cards; do not restart every interruption.
Use darker arc colors instead of whole-screen opacity animation. This is not physical backlight dimming.
Keep the disconnected outer break persistent and status symbol steady; avoid a progress spinner.
A provisional 20 Hz update target is a measurement starting point, not a performance promise.
Render on the Arduino loop; bound work so networking, cards and watchdog servicing remain responsive.
Measure smoothness, RGB565 stepping, wrap seams, memory headroom and repeated-switch stability on hardware.

### Notification queue and expiration

Reuse current eight-entry pending-card queue and separately active card.
Keep ACK meaning accepted, not rendered. Keep existing finite replay guarantees.

- Ordinary generic notifications use FIFO; no priority preemption initially.
- Limit display duration so one sender cannot occupy the screen indefinitely.
- Add optional expiration separate from `ttlMs`, which remains display duration.
- Drop expired cards before presentation; do not enqueue expired replayed cards.
- Start expiration at publication, not replay receipt. Reconnect must not renew notification age.
- A card already active finishes; expiration determines whether a waiting card starts.
- Preserve sequence progress for deliberately expired records; never repeatedly replay them.
- Weather/CPU snapshots never enter the notification/card queue.

Use section 14.2's duration/expiry defaults and sections 14.3–14.5's monotonic aging, processed cursor and legacy fallback.
Generic expiry is an approved extension; priority, cancellation and deduplication remain later work.

### Notification provenance and disposition

- Assign origin at publication; preserve it through negotiated wire records and replay.
- Origin is independent of visual kind, severity and text. A WARNING is not automatically Twitch-origin.
- Management-pushed cards remain independent, including Twitch-looking kinds; do not infer origin from their appearance.
- Classify Twitch-provider warnings and other relay diagnostics by their actual source; pin the taxonomy in the contract.
- Outside Twitch override, or after relay loss, terminally suppress pending Twitch-origin cards rather than defer them.
- Suppressed history must not reappear after re-enable/reconnect. An active Twitch card finishes; no pending successors start.
- Capable peers advance the separate processed cursor for terminal expiration/suppression; ACK remains enqueue-only and never implies rendering.
- Update the normative acceptance/disposition contract explicitly; preserve legacy behavior through negotiation.
- Keep eligible generic FIFO independent of these rules.

Current shared bounded replay can still let Twitch traffic evict generic records.
Document that retention limitation; separate reserved capacity is optional later work.

### Glance-first presentation

Generic cards containing text: compact metadata strip and readable wrapped body.
Bodyless generic cards: headline-focused layout. Reuse current card objects and chat-style layout patterns.
Use sections 14.2, 14.3 and 14.10 for empty-body, ellipsis, byte/text limits and unsupported-character behavior; no scrolling initially.
Keep sequence numbers diagnostic, not dominant content.

Weather: large temperature and unit, short condition, bounded location label.
CPU: large usage percentage, bounded host label and at most one secondary metric initially.
Omit/mark missing optional values; never present them as a measured zero.
Smooth decorative gauges only; show current numeric values directly and never extend freshness through smoothing.

Before the first card-exit frame exposes the base, recompute and validate it.
Continue applying control/connectivity/freshness changes during transitions; never briefly reveal expired telemetry.
Hold duration is separate from total entrance/hold/exit occupancy. All active cards finish the whole presentation; reports estimate total remaining occupancy.

## 4. API implementation scope

Management APIs and the relay-served admin panel require no application authentication or authorization in the private-network deployment.
Use network isolation plus independent browser-origin/Host protections; keep Twitch OAuth and callback verification intact.
Endpoint names and DTOs are selected in section 14.2; the new routes are not yet implemented.

### Notification ingestion

Reuse `POST /api/v1/notifications`; do not add a duplicate `/push` endpoint.
Preserve `type`, `title`, `body`, optional `ttlMs` and existing response fields.
Add optional `expiresInMs` and calibrated `deadline` with section 14.2's units/defaults and section 14.4's age semantics.
Keep generic notification publication independent of Twitch display/ingestion/auth state.

- `ttlMs`: bounded screen hold duration, not message freshness.
- Expiration: how long a not-yet-presented card remains useful after publication.
- Validate both; document truncation and effective firmware limits.
- Existing clients remain usable; explicitly document any tightened duration limit.
- Success means relay accepted publication, not a device displayed it.
- No change to current broadcast scope or best-effort RAM replay.

### Display control

- `GET /api/v1/display`: override, preferred dashboard, revision and snapshot readiness.
- `PATCH /api/v1/display`: update `twitchDisplayEnabled` and/or `preferredDashboard`.
- Omitted fields remain unchanged; reject empty updates and invalid/null fields without mutation.
- Apply partial updates atomically to current state; concurrent changes to separate fields must not erase one another.
- Conflicting writes to the same field use serialized relay acceptance order; richer conflict arbitration is deferred.
- Preferred dashboard values: `generic`, `weather`, `cpu`.
- Separate override flag from preference; do not expose four equal competing modes.
- Repeated equivalent writes are idempotent; invalid writes return `400` without mutation.
- Enabling Twitch takes base-screen priority but lets an active generic card finish.
- Disabling Twitch restores eligible preferred dashboard without changing OAuth or ingestion.
- Success confirms desired state, not visible pixels or device application.
- GET reports relay-known missing/fresh/stale reasons and remaining freshness for both sources.
- Existing device diagnostics expose negotiated capabilities and synchronization evidence actually available to the relay.
- Applied revision/active-card/queue state uses new negotiated device reports; mark legacy or stale reports unknown.
- Device reports are firmware observations, not proof of physical pixels; EVENT ACK is not rendering confirmation.
- Startup configuration remains separate from mutable display/monitoring state.
- Reuse diagnostics in the new admin panel; do not create a second control-state owner.

Persistence: section 14.1's runtime-state file stores both display fields and monitoring preference with atomic writes and owner-only permissions.
Keep Twitch token storage unchanged. No notification-history persistence or new database.

### Weather snapshot

- `PUT /api/v1/weather`: replace latest weather snapshot.
- `GET /api/v1/weather`: inspect latest data and readiness, even while hidden by Twitch.
- Fields/defaults: section 14.2's sourceId, location, temperature, unit, condition, optional humidity/observedAt, freshnessMs and optional calibrated deadline.
- Accept updates regardless of Twitch display, ingestion state or grant.
- External client supplies data; firmware has no provider credentials or direct API calls.
- Data write never selects a dashboard or disables Twitch override.

### CPU snapshot

- `PUT /api/v1/cpu`: replace latest host snapshot.
- `GET /api/v1/cpu`: inspect latest data and readiness, even while hidden by Twitch.
- Fields/defaults: section 14.2's sourceId, host, cpuPercent, optional memoryPercent/temperatureCelsius/observedAt, freshnessMs and optional calibrated deadline.
- Initial source: shipped Linux companion daemon on the monitored computer, not automatic relay-host or ESP32 inference.
- Companion publishes both CPU and memory; keep memory optional in the general API for other compatible producers.
- Accept updates regardless of Twitch display, ingestion state or grant.
- Validate finite values, percentages, optional fields and payload bounds.
- Data write never selects a dashboard or disables Twitch override.

For both sources:

- Each source initially has one global latest-value slot; serialized relay acceptance order wins.
- Source identity is diagnostic, not producer authorization. Configure one producer per slot to avoid alternating hosts/locations.
- Coalesce bursts; never queue historical telemetry. No initial leases or multi-source arbitration.
- Validate units, bounds, source identity and freshness duration.
- Use relay receipt plus validated duration, capped by any calibrated producer deadline; source wall timestamp is informative.
- Preserve the original absolute relay deadline on reconnect and report remaining freshness; do not reset old data to fresh on receipt.
- Firmware ages data using the negotiated monotonic age contract and hides it at expiry.
- Account for transport residence; see the clock/expiry contract below.
- Connected + missing/stale selected data: screensaver type one.
- Disconnected/unavailable relay: screensaver type two, even if cached values remain.

### Runtime Twitch monitoring and admin read model

Selected new routes; transcribe section 14.2's DTOs in A1 and keep existing OAuth paths stable:

- `GET /api/v1/twitch/monitoring`: desired/effective mode and channel, revision, transition state and grant compatibility.
- `PATCH /api/v1/twitch/monitoring`: validated partial channel/monitoring-state update; one active channel initially.
- UI accepts a Twitch login, resolves it through Twitch under bounded lookup, and shows the resolved channel identity.
- Choosing a channel never changes display override or implies ownership/grant. Public monitoring and scoped broadcaster features have different eligibility.
- Invalid channel/lookup failure before acceptance leaves existing settings/source intact. After acceptance, report applying/ready/failed explicitly.
- Configuration updates are idempotent desired state; do not blindly replay OAuth, reconnect or other side-effect actions after ambiguous failures.
- `GET /api/v1/admin/snapshot`: bounded coherent read model for control, monitoring, statuses, queue observations and chart history.
- `GET /api/v1/admin/events`: same-origin SSE feed of coalesced complete snapshots with revision and reset semantics (14.7).
- Reuse existing device/status/activity/notification APIs where sufficient. Admin mutations use shared APIs, not UI-only backend shortcuts.
- No “clear queue”, priority, cancellation or forced replay controls initially; visualization is not queue mutation authorization.

## 5. Architecture changes

### One authoritative API/wire contract checklist

Transcribe section 14's selected contract into normative docs/codecs/shared fixtures in slice A1, using this checklist; do not create competing defaults.

- Field names: required/optional/null behavior, enums, units, bounds and defaults.
- Timing: hold versus total occupancy, waiting expiry, freshness, rounding and zero semantics.
- Identity: provenance, monitored-channel/source generation, telemetry source identity and device/session generation.
- Observability: device-report revision/freshness, queue capacity/ordering, active-card lifecycle and bounded chart/feed history.
- HTTP: partial-update semantics, errors, responses and acceptance-versus-application guarantees.
- Text: HTTP limits versus encoded wire UTF-8 byte limits and actual device font coverage.
- Wire: capability negotiation, codes/layouts, disposition/cursor rules and legacy fallback.

Duration names use explicit unit suffixes. Document observation-time UTC format; keep it informative.
Pin examples against JSON codecs and OpenAPI. Prevent tiny positive durations from silently becoming default-duration wire zero.

### Clock/expiry contract

- Relay owns monotonic deadlines for notification waiting expiry and telemetry freshness.
- Writers check remaining lifetime immediately before delivery and transmit the original absolute relay deadline; do not share pre-aged relative encodings.
- Account for relay queueing, transport delay and firmware residence from complete-frame receipt, including pauses.
- Implement section 14.4's calibrated offset interval, conservative guard and recovery budgets; document its clock-rate assumption.
- Do not assume receipt time is publication time or synchronized wall clocks exist.
- Pin wall-clock jumps, device counter wrap, duration rounding and zero-value semantics in tests.
- Expiration prevents a waiting card from starting; it never cancels an already active generic card.

### Relay

Add bounded display-state ownership for override, preference and latest snapshots.
Integrate with DeviceHub's serialized attachment/publication path.
Keep HTTP handlers thin; no socket writes inside state actors.
Keep Twitch authorization and source lifetime independent from display control.
Add a serialized runtime monitoring controller with owned source-generation scopes and separate desired/effective state.
Cancel/join/close old source workers, chat membership and subscriptions before replacing them; reject old-generation callbacks and queued bus events.
Reset source-specific stats/metadata/history readiness so old-channel data cannot masquerade as the new channel.
Tag Twitch-origin pending/replay cards with source generation; terminally suppress obsolete-channel cards while preserving generic cards and finishing active cards.
Do not clear independent weather/CPU snapshots, reset global EVENT sequence or restart device/HTTP listeners for a channel edit.
Keep OAuth token-store ownership stable across source switches; never automatically revoke a valid grant or authorize a different user.
Mount stable monitoring/OAuth management endpoints through the controller rather than capturing an obsolete source in Apis/StatusApi.
Persist accepted monitor preference separately from startup env defaults; define restart precedence in the migration guide.

Separate contracts:

- EVENT: finite card, sequence, ACK, expiration and bounded replay.
- Display control: absolute override/preference/revision; resend current state after reconnect.
- Dashboard data: absolute typed snapshot, remaining freshness; latest wins.

Generic events bypass Twitch display/source eligibility checks.
Twitch-specific eligibility must not suppress generic warnings/messages by accident.
Hidden weather/CPU data remains queryable and refreshed at the relay.
Device diagnostic state distinguishes desired control, supported capability and unavailable data.
Add negotiated device reports for pending FIFO, active presentation, applied display revision and expiry/suppression/refusal counters.
Report on changes and synchronization, coalesce bursts and keep memory/frame sizes bounded; no per-frame presentation telemetry.
Admin projection separates relay history/replay, transport backlog and device-local queue rather than calling all three “the queue”.
Keep diagnostic/browser fan-out non-blocking; slow tablets must not stall Twitch ingress, DeviceHub or device delivery.
Maintain bounded in-memory chart history; reconnect uses authoritative snapshot/reset rather than assuming every browser event was retained.

Control must not compete in the card FIFO or silently disappear when outbound queues fill.
Coalesce superseded state, or disconnect and resynchronize when bounded delivery cannot progress.
A paused EVENT can block later TCP bytes. Decode/classify the complete EVENT before requiring queue capacity.
Expired, duplicate or suppressed records that need no slot must progress without a capacity wait.
Bound aggregate control/synchronization delay during replay bursts, not just each EVENT's individual pause.
On exceeded budget, disconnect/resynchronize while preserving accepted/active eligible generic cards.
Test sustained slot-release/replay traffic and recovery convergence; reconnect must not repeat the same blockage indefinitely.
Preserve generic FIFO, expired-record sequence progress and notification origin classification.

### Firmware

Build one host-testable screen/session controller before widget integration; no temporary second controller.
Separate base-screen selection from the notification overlay.
Preserve existing Twitch dashboard inside that controller.
Add local type-one/type-two screensavers, weather widget and CPU widget.
Feed it relay-session readiness, override, preference and per-source freshness.

- Finish active generic card on every control change; do not cancel its hold timer.
- Recompute base screen before exit reveals it, and afterward, using approved priority rules.
- Retain queued eligible generic cards across disconnects; skip expired cards before showing.
- Hide weather/CPU when stale, missing, unavailable or overridden by Twitch.
- Route typed messages through transport-independent hooks.
- Keep buffers/queues bounded and LVGL calls on the Arduino loop.
- Reuse widget objects; stop timers/animations for covered or hidden views.
- Emit bounded device queue/presentation reports on meaningful transitions; identify retained cards across relay/source generations.
- Reporting must not change EVENT ACK semantics, block queue admission, or prevent offline card completion.

Screen-selection, freshness and expiration rules must be host-testable without LVGL hardware.
Physical checks still prove animation smoothness, colors, layout and resource headroom.

### Protocol: materialize the selected profile before runtime integration

TSB/3 currently has no display-control/weather/CPU/expiration contract.
Keep existing layouts and 20 golden vectors unchanged unless a reviewed migration requires otherwise.
Do not overload Twitch STATS fields or reserved EVENT bytes.

Selected approach: section 14.3's additive DESK/1 profile within existing TSB/3 frame limits.
Codes, widths, generations, calibrated deadlines and device reports are specified there; sections 14.4–14.5 define timing and admission.
A1 checks offsets, independent vectors and old/new peer behavior before runtime integration. Correct demonstrated technical defects with evidence; do not silently change compatibility.

For capable peers, greeting order is:

1. WELCOME.
2. DISPLAY_STATE, WEATHER and CPU with current-connection identity; snapshots carry original absolute deadlines or explicit missing/stale status.
3. STATS with section 14.3's capability-only generation tail, then explicit SYNC_END. Bound this phase; reject obsolete-generation state.
4. Initial DEVICE_REPORT establishes progress/credit; eligible generic replay and normal operation follow.

Capture the six-frame initial batch atomically; order newer control/snapshot publication after it as specified in 14.3.
Invalidate previous-session dashboard availability until this phase completes.
Keep legacy minimum capacity `replayBufferSize + 18`; reserve six capable greeting admissions and stage capable replay behind credit.
Current attachment queues the greeting before starting the writer; do not assume concurrent draining. Test full replay at the minimum supported capacity.

Legacy peers retain their exact WELCOME/replay/STATS behavior.
New firmware with legacy relay uses documented fallback; never advertise unsupported controls as applied.
Keep existing heartbeat, EVENT ACK and BYE contracts.
Display revision and EVENT sequence are separate; do not reuse EVENT ACK as proof of screen selection.
Use section 14.3's legacy matrix: no legacy device expiry/control/report guarantee; preserve its existing full-queue behavior.

## 6. Build order

Small verified slices. Materialize/validate the selected runtime contracts first; documentation evolves with every slice.
Do not turn “update everything” into unrelated rewrites or blind dependency upgrades.
Use sections 7–12 as acceptance references; update their evidence, not duplicate feature promises.

### A0. Reproducible startup and inventory

- [x] Inventory runtime, deployment, all docs, hardware/CAD, tooling, licensing and future site touchpoints.
- [x] Fix Compose hardcoded live/channel/empty chat settings using section 14.8's disabled/empty/hide defaults and explicit env interpolation.
- [x] Prepare non-Twitch quickstart and safe env templates; exclude real secrets from git/build contexts.
- [x] Create the slice/evidence handoff record described in 14.12 and pin browser-test tooling.
- [ ] Until A3 removes current mandatory management auth, use isolated test-only management configuration for A0 smoke; never publish its values.
- [ ] Final credential-free quickstart and startup verification are accepted in A3, not prerequisites of A0.

Acceptance: documented Compose startup works without Twitch credentials; local env overrides reach the container correctly.
Verify: actual Compose path with current test-only management requirements, private-network binding and isolated smoke; do not print expanded secret configuration.
Depends on: none. Areas: Compose, ignores/templates, setup guide, smoke tools.

### A1. Materialize and validate locked contracts

- [x] Transcribe section 14's locked decisions into API/OpenAPI schemas, normative protocol and shared fixtures; check section 5's field checklist.
- [x] Validate origin/disposition, separate processed cursor/ACK, calibration, credits, generations and control-progress budgets with concrete examples.
- [x] Verify byte lengths/greeting capacities and old/new peer cases; add extension vectors without changing existing vectors.
- [x] Run early isolated Notify-capture and real-socket SSE/timeout feasibility checks; record constraints before E3/C4 expansion.

Acceptance: both peers have unambiguous screen, queue, expiry and recovery rules.
Verify: field/limit review and old/new peer matrix; examples checked against schemas.
Depends on: none; A0 can run in parallel. Areas: contract docs, schemas and shared fixtures.

### A2. Prove one host-testable screen/session controller

- [x] Define arbitration, overlay lifecycle, synchronization readiness and per-source freshness without LVGL dependency.
- [x] Cover full-card completion, first reveal frame, terminal suppression/expiry and invalidation across sessions.
- [x] Inject clocks/transports; prove deadline/wrap behavior and no stale-screen restoration.

Acceptance: controller satisfies approved priorities before any new widget is connected.
Verify: deterministic host state-machine tests and sanitizers.
Depends on: A1. Areas: firmware controller and native test configuration.

### A3. Private-network HTTP boundary

- [x] Remove relay login/token/role requirements and mandatory HttpAuthConfig startup validation across management APIs.
- [x] Extract browser-origin/Host/Fetch-Metadata protection from Basic-auth code; retain callback verification and body/deadline limits.
- [ ] Update private bind/allowed-host configuration, Compose/templates/OpenAPI, migration warnings and old auth tests/tools.

Acceptance: final non-Twitch Compose quickstart/startup and private UI/API work without management credentials; hostile browser origins cannot trigger mutations or read the admin feed.
Verify: credential-free startup, no login challenges, allowed private origins/CLI clients, cross-site/Host and OAuth callback tests.
Depends on: A0, A1. Areas: HTTP middleware/config, templates, OpenAPI and tests.

### B1. Integrate synchronization and expiry-aware admission

- [ ] Extend capable relay/firmware peers with section 14.3 records, clocks and basic DEVICE_REPORT credit/progress; retain exact legacy behavior.
- [ ] Decode/classify before capacity waiting; terminally skip records and advance processed progress while keeping ACK enqueue-only.
- [ ] Bound aggregate control delay; verify recovery convergence and minimum-capacity greeting.

Acceptance: full queues cannot resurrect stale data, suppress generic cards or indefinitely stall control.
Verify: fake-clock/transport tests, real TCP peer tests and extension-vector parity.
Depends on: A1, A2. Areas: codecs, hub/session, admission and shared fixtures.

### B2. Independent generic cards

- [ ] Prove management push with every Twitch ingestion/display/health state.
- [ ] Use content-first/bodyless layouts; enforce agreed hold/expiration/text behavior.
- [ ] Preserve FIFO and eligible queued cards through relay loss; skip expired records before presentation.

Acceptance: independent generic cards finish fully and expose the current valid base, including during slide-out.
Verify: API/queue/presentation tests and real-device text/transition checks.
Depends on: B1. Areas: notification API, provenance, shared card and controller binding.

### B3. Local Orbital Breathing screensavers

- [ ] Bind connected/disconnected arc compositions to the existing controller; no temporary arbitration layer.
- [ ] Resume phase after cards; persistent broken orbit and steady subtle symbol when disconnected.
- [ ] Remove endless CONNECTING; measure provisional animation budget and bound loop/render work.

Acceptance: both effects work offline; networking/cards remain responsive.
Verify: host selection tests and physical LCD smoothness, colors, watchdog and memory checks.
Depends on: A2, B2; isolated visual prototype can start earlier. Areas: LVGL widgets and controller binding.

Checkpoint: run first hardware demo from section 8; generic cards plus both screensavers work without Twitch.

### B4. Device queue/presentation reporting

- [ ] Complete B1's same report with pending/active/applied lifecycle observations and counters; no second protocol or B1 dependency on this slice.
- [ ] Coalesce meaningful changes without starving card/link work; define unknown/stale fallback for old/disconnected devices.
- [ ] Distinguish admission from presentation; avoid reusing misleading current “shown” enqueue counters.

Acceptance: relay can visualize an actually reported device FIFO/active card without inventing it from ACKs or replay history.
Verify: native lifecycle/codec tests, legacy fallback, duplicate/stale reports, offline completion and measured hardware overhead.
Depends on: B1, B2. Areas: negotiated codec, firmware controller/queue hooks and relay session diagnostics.

### C1. Relay display control

- [ ] Add atomic partial PATCH, desired state/revisions, agreed persistence and relay-known readiness reasons.
- [ ] Publish control on changes/reconnect; keep OAuth/ingestion separate.
- [ ] Test concurrent field updates, equivalent writes, source-slot policy and truthful diagnostics.

Acceptance: override preserves preference and never implies streaming or rendered pixels.
Verify: API/state tests and real-socket synchronization tests.
Depends on: B1. Areas: display package, DeviceHub, API assembly and storage.

### C2. Bind device control to existing controller

- [ ] Decode override/preference and feed A2's controller; do not rebuild arbitration.
- [ ] Finish active cards; suppress ineligible pending Twitch-origin cards terminally.
- [ ] Apply current control before reveal; preserve reconnect readiness and bounded recovery.

Acceptance: enabled Twitch wins only base-screen selection; independent generic cards still finish.
Verify: host integration tests plus physical repeated switching.
Depends on: C1, B2, B3. Areas: link hooks, main and controller binding.

Checkpoint: control and notification overlays work through reconnect with real TCP and the actual LCD.

### C3. Runtime Twitch-channel monitoring

- [ ] Add desired/effective single-channel state/API and persistence; validate resolution/grant compatibility without editing secrets in UI.
- [ ] Reconfigure owned source scope safely; fence old callbacks/events, reset Twitch state and suppress obsolete pending cards.
- [ ] Keep OAuth routes/grant storage stable and control/display/generic delivery independent during failures and switches.
- [ ] Activate Twitch alert rules from effective runtime monitoring state, not captured startup mode; retire old-generation alerts and reset Twitch absence/offline timers on switch.
- [ ] Preserve independent device/failure monitoring and historical alerts with generation labels; missing initial Twitch rules must not prevent later activation.

Acceptance: changing monitored channel takes effect without container restart and cannot blend old/new data or stop generic notifications.
Verify: fake Twitch clients, worker teardown, lookup/provider failures, switch races, grant mismatch, restart precedence and source-generation tests.
Also prove alert activation/retirement and timer reset for disabled→live, live→disabled and channel switch; device/failure rules keep working.
Depends on: A1, A3, C1. Areas: source controller, auth/status/alert wiring, stats/router and monitoring API.

### C4. Bounded admin read model and push feed

- [ ] Build coherent control/status/device-queue snapshots and bounded notification/chart history from actual relay/device observations.
- [ ] Add same-origin event-driven browser feed with revision/reset, slow-client coalescing and disconnect cleanup.
- [x] Verify streaming support against pinned Tapir/Netty sync APIs; do not guess library support or weaken unrelated request limits.

Acceptance: slow/disconnected browsers cannot block producers or display stale queue/chart values as current.
Verify: real streaming connections, snapshot/event races, overflow/reset, session expiry, memory bounds and proxy buffering/timeouts.
Depends on: A3, B4, C1, C3. Areas: admin projection/API, hub/link observations and HTTP lifecycle.

### C5. Modern relay-served admin panel

- [x] Serve bundled local assets with same-origin APIs; no separate frontend server, login, CDN or external chart service.
- [ ] Build mode selector, Twitch channel/OAuth controls, reason-coded status and bounded queue/charts from section 11.
- [ ] Cover loading/empty/disconnected/error/applying states and mobile/tablet/keyboard/reduced-motion behavior.

Acceptance: operators can control display and monitoring and inspect real queue state from a phone/tablet without a token or refresh loop.
Verify: real-browser interactions, no overflow, accessible controls, XSS/cross-site defenses and no repeated polling requests.
Depends on: C4. Areas: relay `resources/admin/`, asset routes, chart components and browser tests.

### D1. Weather relay slice

- [ ] Add validated global latest-weather slot/API with source identity and remaining freshness.
- [ ] Accept/cache hidden updates; coalesce bursts and preserve readiness/age across synchronization.

Acceptance: data writes never alter preference/override or accumulate telemetry history.
Verify: API/codec/fake-clock tests and invalid-write preservation.
Depends on: C1. Areas: weather package, hub and fixtures.

### D2. Glance-first weather widget

- [ ] Render large temperature/unit, short condition and bounded location; handle absent fields honestly.
- [ ] Bind selected/fresh/connected/ready eligibility to controller; stale data disappears without a reveal flash.

Acceptance: weather never shows during Twitch override, stale data or relay unavailability.
Verify: host expiry/arbitration tests and physical layout checks.
Depends on: C2, D1. Areas: weather widget/model/hooks.

### E1. CPU relay slice

- [ ] Add validated global latest-host slot/API; bound/coalesce updates and define competing-producer behavior.
- [ ] Cache while hidden; preserve source identity and remaining freshness on reconnect.

Acceptance: invalid writes preserve valid state; historical telemetry never enters replay.
Verify: API/codec/saturation/fake-clock tests.
Depends on: C1. Areas: CPU package, hub and fixtures.

### E2. Glance-first CPU widget

- [ ] Render large usage, host label and at most one secondary metric; missing is not zero.
- [ ] Use controller eligibility; numerical updates stay current and smoothing never extends freshness.

Acceptance: CPU never shows during Twitch override, stale data or relay unavailability.
Verify: host expiry/arbitration tests and physical layout checks.
Depends on: C2, E1. Areas: CPU widget/model/hooks.

### E3. Companion capture and measurement foundations

- [ ] Add `twitch-screen-client/` with minimal pinned dependencies, validated configuration and foreground daemon command.
- [ ] Prove section 14.9's selected low-level dbus-fast adapter on the supported session bus; no notification-daemon replacement or broad bus logging.
- [ ] Normalize safe generic cards, filters/urgency/replacements and sampled CPU/memory snapshots through testable adapters.

Acceptance: synthetic desktop notifications are captured without changing normal desktop delivery; measured CPU/memory have documented units and warmup semantics.
Verify: isolated real session-bus/fake notification server, denied-monitor/owner-restart cases, typed payload fixtures and injected /proc or metrics-reader tests.
Depends on: A1. Areas: client package/config, capture adapter, normalizer, sampler and tests.

### E4. Companion relay delivery and user-service integration

- [ ] Send bounded generic FIFO to existing notification POST; independently coalesce fresh CPU/memory into CPU PUT.
- [ ] Enforce expiry/backpressure, timeout/ambiguous-POST semantics, private endpoint validation and graceful signal shutdown.
- [ ] Ship non-root systemd user unit, foreground diagnostics, installation/removal instructions and reproducible CI/package checks.

Acceptance: one user-session daemon feeds notifications and CPU/memory to the relay; relay outage cannot stall desktop notifications, grow memory without bounds or replay stale telemetry.
Verify: real child-process/fake-HTTP/session-bus integration, slow/hung relay, burst/expiry/ambiguous acceptance, stop/restart and installed service on supported desktop.
Depends on: E3, E1, A3, B1. Areas: client sender/runtime/service packaging, docs and local test runner.

Weather/CPU/client slices can run in parallel after A1 validates the shared contracts. Coordinate shared codec/hub/controller edits.
Client capture compatibility is an early risk spike; do not defer it until physical end-to-end acceptance.

### F. Repository-wide documentation and release consistency

- [ ] Complete section 9 inventory; update root/component READMEs, all affected guides/references, ADRs and contributor instructions.
- [ ] Add producer examples, companion installation/configuration/privacy guide, migration guide, E2E runbook, safe env templates and licensing/asset notices.
- [ ] Align CI/tooling/package metadata and CAD documentation; repair broken links/agent context without fabricating history.
- [ ] Document admin UI, runtime monitoring, reporting/chart limits and auth-removal network migration; update the HTTP-boundary ADR.

Acceptance: every public reader journey describes shipped behavior; no unreviewed stale Twitch-only assumptions.
Verify: link/anchor checks, example/schema checks, actual quickstarts and independent source-backed docs review.
Depends on: A0 onward; finalize after runtime slices. Areas: all docs, CI, packaging and metadata.

### G. Free/open-source product microsite

- [x] Build section 10's static landing page with real product information and honest visuals/status.
- [x] Add docs/source/build CTAs, accessible responsive styling and lightweight orbital demonstration.
- [ ] Add reproducible local preview/build and CI artifact/manual-deployment configuration using section 14.11's selected licensing/Pages target; audit actual asset rights.

Acceptance: useful product page works without relay, credentials or tracking; does not advertise unshipped features as available.
Verify: real browser desktop/mobile/keyboard/reduced-motion checks, links/assets/base-path and build tests.
Depends on: F's product content/identity; implementation can parallel runtime after A1.
Areas: `site/`, Python static build/preview, assets and CI. No existing web toolchain needs preserving.

### H. Full device + Docker + worxbend/OBS end-to-end acceptance

- [ ] Implement repeatable credential-safe local runner/runbook and artifact collection from section 8.
- [ ] Execute offline/disabled/simulated scenarios before live `worxbend` channel checks with connected hardware.
- [ ] Exercise relay panel on mobile/tablet, actual queue reports/feed reconnect and scoped channel-selection/restore behavior.
- [ ] Run the companion against the Docker relay in the real desktop session; prove desktop Notify → generic LCD card and measured CPU/memory → CPU dashboard.
- [ ] If needed, start OBS test stream on verified `OBS AI` placeholder scene; collect real evidence and restore owned state.

Acceptance: no claimed hardware/live pass without actual observation; blocked provider events remain explicitly unverified.
Verify: automated gates, real HTTP/TCP/serial, physical LCD evidence, Twitch state and OBS event/status evidence.
Depends on: C5, D2, E2, E4, F, user-connected device and preflight prerequisites.
Areas: proposed local E2E tooling, sanitized reports, device/OBS/Twitch integration.

### I. Major-update release checkpoint

- [ ] Review actual API/wire/config compatibility; record versioning and upgrade/rollback instructions.
- [ ] Re-run full automated gates and H after fixes; finalize docs/site against verified implementation.
- [ ] Produce release checklist/artifact manifest, license coverage and evidence-linked known limitations.

Acceptance: runtime, deployment, docs, site and release assets agree; every required gate passed or visibly blocked.
Verify: clean-checkout quickstarts, full test evidence, site build and human hardware observations.
Depends on: F, G, H. No automatic commit, tag, push or public deployment without explicit release authorization.

## 7. Verification gates

From `twitch-screen-relay/`:

- `./mill --no-server -j 1 test`
- `./mill --no-server mill.scalalib.scalafmt/checkFormatAll`

From `twitch-screen-firmware/`:

- `pio test -e native`
- `pio test -e native-sanitized`
- `python tools/test_runner_contract.py` using the PlatformIO Python environment.
- `pio run -e esp32dev` after preparing local credentials; never overwrite an existing header.

From repository root:

- `python3 tools/check_protocol_vectors.py`
- `python3 -m unittest discover -s tools/tests -v`
- `docker build --check twitch-screen-relay`
- `docker build -t twitch-screen-relay:review twitch-screen-relay`
- `python3 tools/smoke_container.py twitch-screen-relay:review`

Additional acceptance scenarios:

- [ ] Generic push works with Twitch enabled, disabled, offline, unavailable and missing grant.
- [ ] Enabling Twitch during a generic card lets it finish; Twitch appears afterward as the base screen.
- [ ] Generic cards overlay Twitch/weather/CPU/both screensavers and reveal current correct base screen afterward.
- [ ] Disabling Twitch restores eligible preferred dashboard, not a stale captured screen.
- [ ] Weather/CPU never show while Twitch override is enabled; hidden updates remain accepted.
- [ ] Weather/CPU stale or missing data disappears into connected screensaver type one.
- [ ] Relay disconnect/unavailability hides weather/CPU and selects type two with subtle status symbol.
- [ ] Unexpired queued generic cards can finish/show while relay is disconnected.
- [ ] First boot without relay runs local type two; no endless full-screen CONNECTING.
- [ ] Both local screensavers loop smoothly without network assets or disruptive random jumps.
- [ ] Expired waiting/replayed cards are skipped without a stuck replay sequence; active cards finish.
- [ ] Reconnect sends current override/preference and actual remaining freshness before eligible replay.
- [ ] Old snapshots never become fresh merely because a device reconnects.
- [ ] Full card/outbound queues cannot leave control synchronization stuck indefinitely.
- [ ] Preferred dashboard survives agreed restart policy; initial unsynchronized state uses type one when connected.
- [ ] Legacy peers follow documented fallback; unsupported controls are not reported as applied.
- [ ] Invalid/oversized/hostile cross-site writes leave state unchanged; valid private management needs no credentials.
- [ ] Twitch OAuth, source lifetime, metadata, stats and existing replay tests remain green.
- [ ] Real LCD confirms text fit, colors, animation smoothness, watchdog health and memory headroom.
- [ ] Synchronization with missing weather/CPU completes; old generations and absent completion cannot preserve old dashboards.
- [ ] Maximum replay at minimum permitted outbound capacity succeeds for capable and legacy peers.
- [ ] Expired/suppressed frames progress through full queues; repeated slot release cannot reset aggregate control delay forever.
- [ ] Queuing/transport/frame-pause delays, wall-clock jumps and device counter wrap never renew expired data.
- [ ] Provider warnings and management warnings/Twitch-looking kinds follow provenance, not appearance.
- [ ] Disable/re-enable does not resurrect terminally suppressed Twitch history; active Twitch cards finish.
- [ ] Partial updates preserve omitted fields; simultaneous preference/override writes do not erase one another.
- [ ] Docs examples agree with JSON/OpenAPI/wire duration rounding, zero semantics and text/font limits.
- [ ] Diagnostics distinguish desired/known/unknown; no acceptance response is labeled proof of visible pixels.
- [ ] Real documented Compose startup works without management credentials; no env secret enters git, image layers, UI/site bundles or shared reports.
- [ ] Mode selector maps four visible choices to override/preference correctly and never starts/stops ingestion or interrupts active cards.
- [ ] Channel changes validate/apply live without restarting relay; old-channel workers/events/stats/pending cards cannot bleed into the new source.
- [ ] OAuth identity mismatch appears as partial/blocked capability, never “fully authorized” because channel text changed.
- [ ] Reported per-device FIFO/active state is separate from replay/history and transport backlog; stale/legacy state is labeled unknown.
- [ ] Admin feed initial snapshot/reconnect/overflow reset and multiple tabs/slow clients preserve correctness and bounded resources.
- [ ] Mobile/tablet charts/queue controls work with touch, keyboard and reduced motion; user text cannot inject HTML/scripts.
- [ ] Private API has no login/token requirement; Origin/Host defenses and provider callback verification survive auth removal.
- [ ] Microsite build, links, accessibility and base-path behavior pass; source/hardware licenses and attribution are present.
- [ ] Companion tests cover actual Notify capture, filters/privacy, CPU delta/memory definitions, bounded delivery and process shutdown.
- [ ] Real session daemon forwards synthetic desktop cards without suppressing desktop popups; its measured CPU/memory reaches API, admin and LCD.
- [ ] Full live/device acceptance follows section 8, with evidence and cleanup, before major-update completion.

Existing baseline passed: 529 relay tests, formatting, 20 vectors, 18 root-tool tests,
five native suites, five sanitized suites and five runner-contract tests.
This baseline does not validate new features, live Twitch acceptance or physical rendering.

Integration wave 13: independent raw counts and exact application/package/cleanup readback are in `.agent-workflow/evidence/integrator/wave-13/{counts,verification}.json`. Full gates verify 738 relay tests/91 suites, 26 firmware cases/396414 checks, 206 source and installed companion tests, 98 site, 89 root and 22 relay-tool tests. Frozen Main/Compose and all four baseline/current TCP peer combinations pass; mounted admin snapshot/SSE/panel pass real Main, certificate-verified Caddy and Chromium controls/FIFO workflows. Installed producers retain exact numeric/epoch/original-hold/credit evidence. Only pinned streaming-support and bundled-local-assets checkboxes are promoted; complete C4/C5 producer/browser/race/resource, full failure/provider/suspend/default ESP32, reliability diagnosis, reader/rights/hosted and physical/live H/I acceptance remain open. Target build fails on missing credentials.h. No protected AGENTS retry, upload, service enable, private capture, live action or publication occurred. allSoftwareVerified=false; productAccepted=false. Current matrix and assignments: `.agent-workflow/jobs/integrate-13-integrator/notes.md`. Earlier integration paragraphs are historical evidence, not current route status.

Integration wave 12: independently verified raw current gates and exact artifact/cleanup witnesses are in `.agent-workflow/evidence/integrator/wave-12/{gates,integration-gates,producer-gates,verification}.json` and `production-acceptance/`. Full gates verify 726 relay tests/87 suites, 26 firmware cases/396414 checks, 190 source/installed companion tests, 95 site, 88 shipped root and 16 relay-tool tests. Actual frozen Main and documented rebuilt Compose pass mandatory DESK/reconnect/classic-pressure plus canonical CPU/weather bursts, retained-eight zero-credit synchronization, source/control transitions and every chronological original-expiry view/report fence. All four baseline/current peer combinations pass over real TCP using the exact extracted Compose assembly; baseline firmware is its original transport/queue socket adapter, not a board. Corrected native SHA-256 788b67f2325988cd40bfb59b85b55cfadddb891c56adc41f2f4cbcd5eef0754a also passes installed synthetic Notify/measured numeric CPU/memory application, response-loss/no-resend and denied-monitor continuity; holds are 8001/8002 ms, with historical failed witnesses retained. Internal reconnect/pressure/terminal/scheduled reset, continuous-control/kernel writer and installed same-port/monitoring/capture/stop gates pass with exact cleanup. Relay's earlier initial PATCH 503 remains unexplained despite this full gate and three focused repetitions passing. C4 advances only to in_progress for installed timed/history/pressure input owners; actual admin snapshot/events/root remain 404 in HTTP/Chromium, so SSE/panel acceptance remains blocked. Added safe non-Twitch .env.example and actual template Compose/POST/history/cleanup verification; only A0's template checkbox is promoted, no full slice accepted. Observer CI/template/matrix-copy red/green regressions and actual notice/reader inventories are retained. Target build fails on absent credentials.h; supported desktop/service/LCD/60-minute soak/mobile/provider/worxbend/OBS/rights/protected AGENTS denial remain explicit. allSoftwareVerified=false; productAccepted=false. Next assignments: integrate-12-integrator/notes.md.

Integration wave 11: independent gates and raw readback are indexed in `.agent-workflow/evidence/integrator/wave-11/gates.json`, `integration-gates.json`, `final-gates.json` and `verification.json`. Current gates verify 710 relay tests/85 suites, 26 firmware cases, 174 source/installed companion tests, 87 site tests and 85 shipped root tests. Actual rebuilt default Docker now passes the mandatory classic-pressure/DESK/same-epoch-reconnect native application command; canonical monitoring returns 200. Internal retained-eight/zero-credit/terminal/scheduled-reset reports conserve credits. Actual continuous-control/kernel-writer execution verifies timestamped original lifecycles and remote accepted snapshots; per-original-identity phase reversals are rejected. Installed companion passes fifteen same-port recovery checks, monitoring/capture transitions and held-response shutdown with exact measured-value/acceptance correlation. Admin snapshot/events/root remain 404 in real HTTP/Chromium: production SSE and panel workflows are blocked, not inferred from isolated feasibility. Default ESP32 remains classic and complete default four-peer/provider/suspend/source acceptance remains pending. Numeric grant admission and absolute settings admission/abandonment findings are independently verified at their exercised software layers. Root/API/setup/migration/H/site status and shared lifecycle CI are reconciled with red/green evidence; failures are retained. No additional checkbox or full slice is promoted. Target build fails on absent credentials.h; LCD/60-minute measured soak/supported desktop/service/mobile/provider/OBS/rights and protected AGENTS.md denial remain explicit. allSoftwareVerified=false; productAccepted=false. Next assignments: integrate-11-integrator/notes.md.

Integration wave 10: independent full gates and exact raw readback are indexed in `.agent-workflow/evidence/integrator/wave-10/gates.json`, `integration-gates.json` and `verification.json`. Current gates verify 691 relay tests/82 suites, 26 firmware cases, 164 source/installed companion tests, 75 site tests and 83 final root tests. Actual internally gated reconnect/retained-eight pressure/scheduled reset conserve all reports; the terminal fixture checks every ready report and all sixteen application views/eight uncovers, including transient negative mutations. Installed companion passes fifteen same-port recovery checks and five graceful JVM exits; two stops exceed ten seconds, so no five-second relay-shutdown claim is made. Actual rebuilt Docker still fails required production DESK/reconnect; monitoring/admin/feed/root remain 404 in real Chromium. Scheduled source/reset/retry, stable identity-checked webhook deduplication and authoritative alert fencing are installed and independently exercised; complete provider/suspend/source/writer and canonical monitoring/admin/SSE/panel acceptance remains pending. H preflight/source-backed docs/CI freshness-verdict coverage and resolved firmware license-obligation summaries are repaired with failing-then-passing root regressions. A0's evidence handoff/browser pin checkbox is promoted with current board, pinned Chrome/Playwright and actual browser evidence only; no full slice or product acceptance is promoted. Target build fails on absent credentials.h; LCD/60-minute measured soak, supported desktop/service, physical mobile/provider/OBS and complete rights prerequisites remain explicit. Protected AGENTS.md denial is respected. allSoftwareVerified=false; productAccepted=false. Next assignments: integrate-10-integrator/notes.md.

Integration wave 9: independent full gates and exact raw verification are indexed in `.agent-workflow/evidence/integrator/wave-09/gates.json`, `integration-gates.json` and `verification.json`. Current totals: 667 relay tests/76 suites, 26 firmware cases, 154 source/installed companion tests, 75 site tests and 81 final root regressions. Actual internal capable reconnect/retained-eight pressure/explicit hub reset conserve every report; installed companion passes thirteen same-port recovery checks and five graceful JVM exits. Stable OAuth diagnostics and independently started disabled Main authorization pass with synthetic valid configuration and no token mutation. Actual rebuilt Docker required production DESK/reconnect still exit 1 without readiness/reports; monitoring/admin/feed/root remain 404 in real Chromium. Automatic coordinated source/reset/webhook/alerts and production SSE/panel remain blockers. OAuth/migration/network setup copy and CI failure coverage repaired with red/green root tests. Target build fails on absent credentials.h; LCD/60-minute measured soak, supported desktop/service, physical mobile/provider/OBS and rights prerequisites remain missing or unverified. No acceptance checkbox promotion, protected AGENTS.md retry, upload, service enable, private capture, live action or publication. allSoftwareVerified=false; productAccepted=false. Next four-way assignments: integrate-09-integrator/notes.md.

Integration wave 8: independent full gates are indexed in `.agent-workflow/evidence/integrator/wave-08/gates.json`; raw readback/conservation is `verification.json`. Actual internal-gated DeviceHub/DeviceSession plus native firmware passes two-session independent-cursor reconnect and retained-eight/zero-credit/minimum-capacity replay pressure, including current control and original CPU/weather expiry. Deferred-report liveness, fair heartbeat, partial-control deadlines and final-assembly same-port recovery repairs pass independent regressions; installed companion passes eleven actual recovery checks. Actual rebuilt Docker remains classic; required production DESK and reconnect exit 1 without ready/reports. Clock/display/weather/CPU are 200; monitoring/admin/feed/root are 404, confirmed in real Chromium. Shared discontinuity/source ownership and production profile/admin/SSE remain software blockers. Target ESP32 build fails honestly on absent credentials.h; no overwrite/upload. Full H preflight remains blocked on target configuration, LCD/60-minute measured soak, supported desktop/service, trusted physical mobile/provider/OBS and rights inputs. CI now exercises the actual short-write reconnect test rather than skipping it. No acceptance checkbox promotion; allSoftwareVerified=false; productAccepted=false. Protected AGENTS.md denial remains binding. Next assignments: integrate-08-integrator/notes.md.

Integration wave 7: independent current gates are indexed in `.agent-workflow/evidence/integrator/wave-07/gates.json`. Real rebuilt Docker/current application pairing verifies clock/display/weather/CPU HTTP, weather null/default/Unicode/nonmutation/original-expiry repairs, installed measured companion uploads and durable display restart. Classic reconnect and ten cards across eight pending slots pass. Required DESK exits 1 without readiness/reports; monitoring/admin/feed/root return 404, confirmed by actual browser. Production DESK/shared reset/SSE/panel and full companion reliability remain software blockers. Independent recovery exposed a shutdown timeout retained in `wave-07/recovery.log`; diagnostic follow-up must not erase it. No acceptance checkbox promotion. allSoftwareVerified=false; productAccepted=false. Private target configuration, LCD/60-minute resource soak, supported desktop/service, mobile/provider/OBS, rights and protected AGENTS.md denial remain explicit blockers. Next assignments: integrate-07-integrator/notes.md.

Integration wave 6 independently executes current gates in `.agent-workflow/evidence/integrator/wave-06/gates.json` and final shared regressions/recovery in `final-gates.json`. The initial HTTPS smoke failed because remainingExpiryMs advances; retained identity/content comparison and its regression repair that gate, with real certificate-verified rerun in `private-https-repaired.log`. Actual rebuilt Docker returns clock/display/CPU 200; installed companion measured CPU/card calibration and exact readback pass. Production Unicode/integer/body/header parity and display partial merge/no-op/restart intent pass. Classic actual firmware reconnect and ten complete cards across eight pending slots pass. Required DESK exits 1 without readiness/reports; weather/monitoring/admin/feed/root remain 404, confirmed by real browser. Shared discontinuity reset, complete negotiated report/credit/time/recovery, capable failure matrix, SSE and admin workflows remain unaccepted. No whole-slice or checkbox promotion. allSoftwareVerified=false; productAccepted=false. Target configuration, LCD/60-minute soak, supported desktop/service, mobile/provider/OBS, complete rights and protected AGENTS.md denial remain explicit blockers. Scoped tests are authorized after prerequisites, not universally forbidden. Current assignments: integrate-06-integrator/notes.md.

Integration wave 5 independently passed 28 indexed commands in `.agent-workflow/evidence/integrator/wave-05/gates.json`: 603 relay tests/63 suites, 24 firmware suite-environment cases, 107 source and 107 installed companion tests and 46 site tests; final root regressions pass 74 tests. Actual rebuilt Docker and firmware application pairing verifies classic reconnect/replay and ten ordered cards at eight-pending queue pressure. Required DESK readiness fails; classic and capable greetings negotiate classic. Seven canonical/admin routes and production admin root return 404; installed companion calibration exits blocked without writes; actual browser has no mounted panel. Real report/credit/calibration/recovery, SSE and admin workflows remain unaccepted. Startup precedence/degradation, canonical pairing state path, measured-value checker and shared documentation regressions pass at their tested layers. No checkbox is promoted. Scoped physical/live authorization is acknowledged; target credentials, visual/soak, supported desktop/mobile and provider/OBS prerequisites remain blocked or unverified. Historical offline upload is not network/LCD acceptance. allSoftwareVerified=false; productAccepted=false. Protected AGENTS.md denial and unresolved rights remain explicit.

Integration wave 4 independently passed the available gates indexed in `.agent-workflow/evidence/integrator/wave-04/gates.json`, with final shared regressions in `final-gates.json`: 591 relay tests/61 suites, 24 firmware suite-environment cases, 87 source and 87 installed companion tests, 45 site tests and 72 root tests. Real classic/capable HELLO both negotiate classic; seven canonical/admin HTTP routes return 404. Production new/new credit/report/calibration/recovery, canonical companion pairing, SSE and relay-admin browser acceptance remain blocked. Setup-guide boundary contradictions were repaired with regression tests; no acceptance checkbox is promoted. Safe H preflight reports blocked scenarios without physical/live actions. allSoftwareVerified remains false; protected AGENTS.md denial, private target build, rights and physical/live H/I blockers remain explicit.

Integration wave 3 independently executed the current gates indexed in `.agent-workflow/evidence/integrator/wave-03/gates.json`. Initial installed-companion upload/readback timing regression failed; both complete source and installed suites pass after aligning the two baselines, recorded in `final-gates.json`, with the failure retained separately. Current totals: 571 relay tests/58 suites, 24 firmware suite-environment cases, five runner tests, 72 root tests, four Compose guards, 72 source and 72 installed companion tests and 40 site tests. Stable Chrome for Testing 155.0.8059.39 passes actual local browser behavior; Docker/Compose/HTTPS, source-artifact audit and dependency gates pass. No additional acceptance checkbox is promoted. Actual capable HELLO still negotiates classic and canonical/admin routes return 404; new/new production report-credit/calibration/recovery, production SSE and admin-browser gates remain blocked. Synthetic ESP32 compilation does not verify the private checkout build. Physical/live H/I, complete rights and protected AGENTS.md remain blocked.

Integration wave 2 independently passed 26 indexed executable gates: 552 relay tests/56 suites, formatting, 22 native/sanitized/LVGL suite-environment cases, five runner tests, synthetic ESP32 compilation, 71 root tests, four Compose tests, 60 source and 60 installed companion tests, 28 site tests, real local Chromium, Docker/Compose and certificate-verified Caddy HTTPS with notification readback. Fresh dependency audit passes with two existing exceptions. Evidence: `.agent-workflow/evidence/integrator/wave-02/gates.json` and raw logs. The checkout private-credentials build is still blocked; synthetic compilation is not that gate.

No additional acceptance checkbox is promoted. Real classic and DESK-capable HELLOs both negotiate classic; actual clock/display/weather/CPU/monitoring/admin routes return 404. New/new production report-credit/calibration/recovery, production SSE over 60 seconds and real admin browser workflows cannot be accepted while owners are absent. Static-site Chromium remains a restricted old-beta behavioral baseline, not release-security approval. Physical/live H/I and protected AGENTS.md repair remain blocked.

## 8. End-to-end execution runbook

Required after implementation, not executed by this planning edit.
User will connect the physical device. User authorized Docker relay testing on Twitch `worxbend`,
reading the needed local env credentials, and starting an OBS test broadcast if needed on the `OBS AI` placeholder scene.
This authorization is for the scoped test; it is not authorization to change stream keys, spend money or publish releases.
OBS running/on that scene is user-provided context; re-check at execution time.

### 8.1 Preflight and secrets

- [ ] Record commit/diff, firmware build identity, device ID/serial path, OS/tool versions, available ports and current containers.
- [ ] Discover device dynamically; verify target board before upload. Never assume `/dev/ttyUSB0` or overwrite local credentials.
- [ ] Find the existing env file. No env file was found inside this checkout during planning; it may be external.
- [ ] Load only needed values privately into the runner/Docker runtime; user authorized their use, not disclosure.
- [ ] Validate required key presence without printing values. If file/credentials are unavailable, ask for location or secure setup; never guess.
- [ ] Preserve existing Twitch token volume; env client credentials are not a replacement for the persisted broadcaster OAuth grant.
- [ ] Check secret exclusion in git ignores, Docker contexts, test artifacts and site builds; protect raw local evidence.
- [ ] Do not print `.env`, expanded `docker compose config`, full container environment, stream keys or OAuth callback codes.
- [ ] Validate existing public authorization metadata via `GET /api/v1/twitch/authorization`: usable identity must be `worxbend`, with needed scopes.
- [ ] Reuse current OAuth flow if grant is missing; complete required consent securely. Do not revoke valid grants for a test.
- [ ] Check trusted-LAN reachability, private HTTP binding/allowed hosts, firewall, published device port, container permissions and redirect URL.
- [ ] Identify logged-in desktop session/bus and notification daemon, prove scoped monitoring permission/adapter compatibility using only synthetic messages.
- [ ] Record any existing companion service; do not replace it or capture unrelated private desktop content. Choose synthetic-only application filters for tests.
- [ ] Management auth credentials are no longer required. Env secrets remain needed for Twitch/OBS/Wi-Fi where applicable; never expose them in panel data.

Missing device, env, OAuth grant, OBS control or visual evidence is a named blocker—not a simulated success.
Do not inspect unrelated home-directory credentials while searching for the env file.

### 8.2 Docker setup and ownership

Build current relay code and run the actual documented Compose path, not only the standalone smoke container.
Proposed execution, after A0 makes environment overrides explicit:

```sh
# Working directory: twitch-screen-relay/
# ENV_FILE: discovered absolute private env-file path; do not put secret values in the command.
docker compose --env-file "$ENV_FILE" -p twitch-screen-e2e up -d --build relay
```

- [ ] Verify service health plus credential-free private management API/device session; container liveness alone proves neither Twitch nor LCD operation.
- [ ] Use explicit test modes: disabled first, simulated next, live with channel exactly `worxbend` last.
- [ ] Compose env-file interpolation must map intended keys into container environment; hardcoded YAML must not defeat overrides.
- [ ] Name/track test-owned resources. If existing service owns required ports/volume, reconcile with the user; never silently replace it.
- [ ] Keep authorized token storage and new display-state persistence intact. Separate disposable fixtures from real grant storage.
- [ ] Record sanitized image identity, configuration metadata, mode/channel, device/session and bounded readiness deadlines.

Runner should expose named scenarios and fail with actionable diagnostics. New runner paths/flags are implementation targets, not existing commands.
Home: `tools/e2e_device.py`; guide: `docs/guides/end-to-end-testing.md`.
Normal CI runs fake-clock/peer/container cases without secrets; hardware/live/OBS cases are explicit local opt-in.

### 8.3 First hardware demo and deterministic matrix

Companion scenarios (after E4):

- [ ] Start a foreground test-owned companion in the actual user session, pointed at the Docker relay; desktop bus stays on host, not inside relay container.
- [ ] Submit uniquely labeled synthetic desktop notifications through a conforming notification client (for example `notify-send` if installed).
- [ ] Verify desktop delivery remains normal, relay accepts generic card and actual LCD presents it; inspect reported active/pending queue without treating ACK as pixels.
- [ ] Sample real CPU/memory, compare with independently read host counters using the same definitions and bounded timing tolerance; select CPU through admin UI.
- [ ] Enable Twitch while sampling; show updates continue hidden, then disabling override reveals fresh host data.
- [ ] Stop/restart companion, restart synthetic notification server in isolation and lose/recover relay; prove status/expiry, no stale backlog or renewed old metrics freshness.
- [ ] Exercise exclusions/redaction with synthetic private-looking fixtures; real personal notifications are not required test evidence.
- [ ] Controlled CPU load is opt-in, bounded and test-owned; no stress/memory exhaustion or killing desktop notification daemon to manufacture failures.

First prove the defining experience:

Disconnected orbit → connected orbit → generic card → lose relay during hold → card finishes → disconnected orbit.
Repeat with a waiting generic card that expires. Capture the actual LCD, not just ACKs.

Then execute:

- [ ] Disabled ingestion/no Twitch grant: generic push, FIFO, connected/disconnected animation and recovery.
- [ ] Generic card active while enabling Twitch: finish entrance/hold/exit, then current Twitch base appears.
- [ ] CPU selected/fresh: enable Twitch; push newer CPU while hidden; disable override and reveal newest fresh CPU.
- [ ] Repeat hidden-update scenario for weather. Missing/expired snapshots disappear; optional missing values are not zero.
- [ ] Lose relay during telemetry/card presentation; detect bounded unavailability and show type two without stale dashboard flashes.
- [ ] Reconnect with missing/stale sources and expired replay; complete synchronization, do not renew old ages.
- [ ] Fill pending queue and drive bounded bursts; verify expiry/suppression/cursor progress and aggregate control recovery.
- [ ] Test provenance with management warning, management Twitch-looking card and provider warning; no generic suppression by appearance.
- [ ] Restart container and device separately; verify agreed persistence, fresh synchronization and bounded-RAM replay limits.
- [ ] Invalid/oversized/hostile-browser writes preserve prior state; repeated partial updates preserve omitted fields.
- [ ] Use actual admin UI to select all display choices, edit monitoring settings and inspect the reported per-device queue.
- [ ] Restore `worxbend` before live testing; use fake channel switches for destructive races, never revoke grants to simulate mismatch.
- [ ] Simulate missing/stale queue reports and browser feed loss; verify unknown/reconnecting labels, no counterfeit active-card proof.
- [ ] Repeat mode/card transitions and measured soak; record heap/stack/watchdog/network responsiveness and animation timing.

Use fake clocks/peers for exact time, generation, legacy and pathological-queue cases.
Use real HTTP + TCP + serial for integration, and the physical device for display/resource claims.
Synthetic fixtures are labeled fixtures; never count them as real Twitch-origin events.

### 8.4 OBS-controlled live worxbend test

Prefer authenticated OBS WebSocket v5 using an available verified client; inspect its actual interface before issuing requests.
Use connection-scoped request IDs, bounded deadlines and subscribed stream-state events, not blind sleeps or toggle retries.

1. Read OBS connection/version, current program scene and output state; save the baseline.
2. Verify scene is exactly `OBS AI` and is the intended placeholder. If different, pause and ask; do not switch to private content.
3. Verify relay authorization/channel is `worxbend` and OBS's configured destination matches the intended Twitch test.
   Inspect needed destination metadata privately; never expose or change the stream key/service/profile.
4. If already streaming, observe existing output without restarting it; do not claim ownership or stop it afterward.
5. If inactive and live output is needed, issue explicit StartStream, not ToggleStream. Mark test-owned output only with confirmed state.
   On timeout, reconcile current state before any retry; never assume the command failed.
6. Confirm both OBS output-active event/status and actual Twitch online transition through live ingress/Helix state.
   Allow a bounded provider-observation window; account for existing polling cadence without adding another application polling loop.
7. Enable Twitch display explicitly; verify actual metadata/stats reach the LCD. Starting OBS must not implicitly change override.
8. While live, send generic card and hidden weather/CPU updates; prove completion, Twitch priority and fresh return after disabling override.
9. Exercise available real EventSub/IRC paths and record provenance. Do not purchase subs/bits, raid another channel or generate paid events.
   If a controlled chat/follow action needs another account or extra public mutation, obtain separate authorization or mark live coverage blocked.
10. Finish owned broadcast in cleanup; observe OBS stopped and Twitch offline transition, then verify existing Twitch offline dashboard while override remains enabled.
    If baseline stream belonged to the user, do not stop it; mark offline-transition coverage unverified or schedule separately.

Live paid/rare-event coverage may remain unavailable. Use labeled simulated/recorded fixtures for those rendering paths,
while reporting live ingestion as unverified for events not actually observed.

### 8.5 Evidence, visual proof and cleanup

Proposed artifact directory: `artifacts/e2e/<run-id>/`, ignored locally until sanitized evidence is deliberately published.

For each named scenario record:

- Scenario ID, timestamps, expected/observed result, pass/fail/blocked and evidence paths.
- Commit/diff identity, image and firmware versions, mode/channel, device ID and negotiated capabilities.
- Redacted API results, relay logs, serial transitions, sequence/disposition and synchronization evidence.
- LCD photo/video or explicit user-observed sign-off for layout/animation/card completion; serial logs alone are insufficient.
- OBS stream-state evidence and Twitch online/offline/ingress observations for live scenarios.
- Measured render/network timing, memory behavior and soak duration; never substitute unmeasured performance claims.

If no camera/visual capture is available, obtain user observation and label it manual; mark absent visual checks blocked.
Shared artifacts/site assets must contain no tokens, Wi-Fi credentials, stream keys, private desktop content or identifying chat without review.

Cleanup runs on success, failure and cancellation:

- [ ] Reconcile and stop only output started by this test; never stop a pre-existing user broadcast.
- [ ] Restore test-modified display intent/preference, monitored channel/mode and any owned temporary settings; leave OBS scene/profile/service untouched.
- [ ] Stop/remove only test-owned companion processes/services, synthetic bus fixtures, containers and temporary fixtures; never `down -v`, delete token stores or prune unrelated Docker resources.
- [ ] Close serial/OBS sessions; retain sanitized evidence and report any cleanup failure immediately.

Completion requires the automated matrix plus real-device and scoped live evidence.
If a required gate is blocked, do not label the major update fully verified.

## 9. Repository-wide product and documentation update

“Complete update” means review every maintained surface for this major change, change affected material,
and explicitly record unchanged surfaces. It does not require replacing sound architecture or altering mechanics unnecessarily.

### Coverage inventory

- Root `README.md`: broader desk-display positioning, current feature matrix, real visuals, non-Twitch first success and links to microsite/docs/source.
- `docs/README.md`: first-use paths for non-streamers, streamers, makers, integrators and contributors.
- `docs/guides/`: revise all existing guides for actual setup/display/queue/recovery behavior; retain Twitch simulation and OAuth guidance.
- Add guides for generic push, display control, private admin UI, runtime Twitch monitoring, queue/chart interpretation, Linux companion installation/service/privacy and weather/CPU producers, migration and section 8's E2E testing.
- `docs/reference/architecture.md`: separate ingestion, desired display state, snapshots, overlay controller and connectivity/freshness.
- `docs/reference/http-api.md` and generated Swagger: credential-free private management, PATCH/monitoring/feed schemas, origin/Host rules, queue-report limits, bounds and truthful guarantees.
- `docs/reference/relay-configuration.md`: defaults, private bind/allowed hosts, env mapping, runtime-state precedence, persistence, freshness/expiry and network isolation.
- `twitch-screen-firmware/docs/PROTOCOL.md`: authoritative negotiated extensions, timing/greeting/disposition and legacy matrix.
- All component READMEs: local commands, purpose, version/config/protocol compatibility and links to detailed guides.
- CAD/build documentation: broader product description, current BOM/wiring/assembly/download links and honest mechanical validation status.
  Retain CAD geometry unless runtime/hardware changes actually require it; do not invent a newly validated physical fit.
- `CONTRIBUTING.md`, documentation maintenance guide, ADR index/new decisions and agent context.
  Repair `CLAUDE.md`'s missing `AGENTS.md` target and dead review/task links; keep historical records historical.
- CI, root tools, container build/Compose, safe env examples, companion package/user-service, packaging and firmware version metadata.
  Add contracts/E2E/site/docs checks; audit dependency/security exceptions without blind upgrades.
- Add release/change notes and migration/rollback instructions, with actual compatibility/versioning decision based on shipped diff.
- LICENSE/attribution: no LICENSE is currently present. Apply section 14.11's delegated license selections and verify actual third-party coverage.
  Audit fonts, Twitch artwork, photos and third-party assets; do not imply ownership or relicensing rights not established.

### Consistency rules

- Name approved behavior once; docs/site summarize and link to authoritative references rather than fork contracts.
- Document with each slice, not only after code is finished. Final review covers every existing maintained document.
- Keep current/implemented/planned/experiment labels honest until verification; update diagrams and examples to actual final code.
- Preserve independent-project/Twitch-not-affiliated notice and trusted-LAN/plain-TCP limitations.
- Free/open-source software is the target; physical parts, printing/hosting and provider use can still cost money.
- No public “licensed open source” claim until selected licenses are actually added and asset coverage is checked.
- No forced repository/path/package rename. Broader product identity can change public copy without breaking existing URLs/config identifiers.

Acceptance: a fresh user can build, deploy, push a generic card, control display and optionally connect Twitch from checked-in instructions.
Verify links/anchors/media, parse examples, compare against source/OpenAPI, run quickstarts and perform independent factual review.

## 10. Product microsite

New static product site, separate from relay management. Proposed source directory: `site/`.
No current web/package toolchain exists in this checkout; use section 14.11's static HTML/CSS/JS plus small Python build/preview.
A site choice must not add a runtime dependency to relay or firmware.

### Content and landing-page structure

1. Product hero: actual circular desk object, plain description, free/open-source positioning and primary “Build one”/“View source” actions.
2. Everyday uses: mirrored desktop notifications, measured CPU/memory, fresh weather, local screensavers, Twitch dashboard and mobile-friendly private admin panel.
3. How it works: external clients/Twitch → relay → ESP32/LCD, plus device-local offline animation and trusted-LAN boundary.
4. Product tour: real photos/video and truthful demos of cards, override and connected/disconnected appearance.
5. Build/setup: BOM, enclosure/downloads, firmware/relay quickstarts and documentation links.
6. Integrations: generic push examples, shipped Linux companion and external weather/telemetry clients; future ideas visibly labeled roadmap.
7. Project information: license, attribution, contributor/issues links, known limitations and Twitch non-affiliation.

### Design and implementation requirements

- Base the visual identity on the round physical display and Orbital Breathing, not a generic SaaS card-grid template.
- Make one memorable circular product/demo element; keep surrounding content legible and quiet.
- Design deliberate type/color/layout tokens before coding; mobile-first, descriptive alt text, keyboard focus and readable contrast.
- Respect reduced motion and offer pause/static fallback for ambient demos. No flashing or motion-dependent instructions.
- Optimize existing photos/CAD previews instead of copying huge CAD/binary assets into site output.
- Label CAD renders, UI mockups, browser simulations and physical test captures accurately.
- Any interactive demo is local and illustrative: never connects to live relay, accepts credentials or controls OBS/Twitch.
- No accounts, paid gate, analytics, third-party trackers or mandatory cookies. Software source/setup stays freely accessible.
- Keep forms unnecessary; CTAs lead to repository/docs/downloads instead of collecting data.
- Add page title/description, favicon, social preview and valid links. Canonical URL/sitemap depend on chosen hosting, not an invented domain.
- Test relative/base-path URLs for GitHub Pages project hosting; keep site independently deployable as static files.

### Site verification and publication

- [x] Reproducible local preview/build with documented commands; pinned toolchain if one is introduced.
- [x] Real-browser desktop and narrow-screen checks; keyboard navigation, reduced motion, contrast and image loading.
- [x] Automated link/asset/content checks, mobile overflow tests and bounded page weight.
- [ ] CI builds a site artifact without real env secrets; add deployment workflow with least required permissions.
- [ ] Cross-check feature/license/setup copy against shipped code, docs and E2E evidence.
- [ ] Approve hosting target/base URL and actual public deployment separately; verify served target after any publish.

Selected hosting target: GitHub Pages at section 14.11's project base path. Actual publication still requires explicit release authorization.
Public microsite and private relay admin panel are both required for this major update; they are separate surfaces.
The public static site must never connect visitors to a private relay or bundle private configuration.

## 11. Relay admin panel

Required first-release UI. “Primitive” means narrow scope and small dependencies, not unstyled or desktop-only.
One well-designed responsive control panel, served by the relay on its existing HTTP origin.
Proposed entry: `/`; keep `/api/v1/*` APIs and `/docs` Swagger intact. Proposed assets: relay `resources/admin/`.
Public microsite remains a separate static product page; do not deploy the admin panel on GitHub Pages.

### 11.1 Display selector

- Show four understandable choices: Twitch, Generic, Weather and CPU. Backend still owns override + remembered preference.
- Twitch selection sends `twitchDisplayEnabled = true`; leave `preferredDashboard` intact.
- Generic/Weather/CPU selection atomically sets the matching preference and disables Twitch override.
- Display selection never changes monitored channel, ingestion state, OAuth grant or OBS output.
- Show selected intent, remembered fallback and reported device state separately; do not claim “now showing” from a PATCH alone.
- Allow selecting missing/stale telemetry; explain connected screensaver fallback until fresh data arrives.
- While a card is active, show “applies after this card” rather than implying preemption.
- Read canonical state after a successful mutation; on timeout reconcile before retry. Prevent double-submit and restore failed optimistic state.
- A multi-device relay shows per-device application/readiness even though display intent is initially global.

### 11.2 Twitch integration and monitored channel

- Channel input with explicit Apply, validation and resolved identity; do not rebuild source on every keystroke.
- Separate monitoring enable/disable from Twitch display selection. Simulated mode is clearly labeled a development/demo source.
- Show desired/effective channel and ingestion mode, transition state, live/offline status, metadata and connection health.
- Show OAuth grant login/scopes/expiry and capability mismatch separately from channel text.
- Provide “Connect Twitch” using existing consent flow and explain when broadcaster-specific scopes are required.
- Mobile/tablet consent requires a browser-trusted private HTTPS relay origin/callback, exact registered Twitch redirect URI and narrowly configured proxy Host/origin handling.
- Ship/document that private HTTPS deployment path and test it on another device; HTTPS proxy terminates TLS but never adds application login or public exposure.
- Current default callback is `http://localhost:8080/api/v1/twitch/callback`; on a phone it points to the phone, not the relay. Existing validation allows HTTP only on loopback.
- If private HTTPS is not configured, explicitly label Connect as unavailable on remote mobile browsers and offer desktop-local/tunneled consent instructions; all other private panel functions still work.
- Do not weaken callback validation to accept plain HTTP LAN redirects or derive callback URLs from untrusted request headers.
- Preserve existing consent/token refresh/security mechanics; no admin login is introduced by Twitch's provider sign-in.
- Application client credentials stay server-side in env/config; no client-secret/stream-key editors or raw token displays.
- Changing channel does not change an account's broadcast, automatically authorize the selected user, or promise access to restricted metrics.
- Single monitored channel initially; multiple subscriptions/channel dashboards and multi-account token vaults are out of scope.
- UI works without configured Twitch credentials: show setup guidance while display/generic/telemetry control stays usable.
- Persist runtime monitor preference with explicit env/default precedence. GET config remains startup config; monitoring endpoint reports effective runtime state.

Channel-switch invariant: no old channel stats, late callbacks or pending Twitch cards may appear as new-channel data.
Finish already active cards; keep generic FIFO/weather/CPU untouched. Reset channel-specific charts/readiness and identify generations.
A failed accepted switch shows desired/effective divergence and actionable error; never silently display stale old-channel stats as current.

### 11.3 Status overview

Reuse existing health/status/devices/stats/activity/alerts APIs and expose bounded admin projections where needed.

- Relay liveness/uptime/version; browser-feed connection and last complete snapshot age.
- Devices connected/ready/awaiting synchronization, capability/firmware metadata and last report time.
- Desired versus firmware-reported base screen/applied revision, with explicit fallback reasons.
- Twitch provider connectivity, channel live/offline and grant compatibility—none substitutes for relay/device health.
- Weather/CPU source identity, missing/fresh/stale status and remaining freshness, including hidden snapshots.
- Notification arrival/disposition counts, capacity pressure, drops/refusals, replay retention and synchronization failures.
- Optional collapsed existing logs/activity/alerts with bounded page size and redaction; no full env/token dump.
- Twitch alert activation follows effective monitoring state; switching retires old-generation active alerts and resets Twitch timers without resetting device/failure monitoring. Historical entries remain explicitly labeled.

Use labels/icons as well as color. “No device”, “never reported”, “stale”, “unsupported”, “applying” and “provider unavailable” are distinct.
Do not turn missing measurements into zeros or relay-known estimates into firmware observations.

### 11.4 Notification queue visualization: show what is actually known

Present separate labeled layers:

1. Relay publication/replay history: bounded retained notifications, provenance/channel generation and waiting expiry.
2. Relay transport backlog: per-connection frames waiting to write/backpressure; not all are notification cards.
3. Device FIFO: firmware-reported ordered pending cards, capacity and active card, scoped to a particular device.

Use device selector and a compact ordered queue strip/list: active card, then pending cards.
Show safe title/body previews only when correlated to known bounded metadata; otherwise show card identity/kind.
Details may show sequence/generation, origin, waiting age/expiry and last observed disposition.
Keep an empty queue distinct from an unavailable/stale report. Never infer pending = published minus ACK.

Negotiated reporting is fixed in 14.3; A1 adds fixtures, B1 implements required progress/credit and B4 completes lifecycle diagnostics:

- Report boot/session identity, monotonic report revision and observation age/freshness.
- Include pending capacity/count and ordered identities for at most the existing eight pending cards, plus separately active card.
- Active state covers entrance/hold/exit and completion; activeRemainingMs estimates total remaining presentation at report observation time.
- Include applied display revision/base-screen reason and bounded counters for expired/suppressed/refused records.
- Preserve original event/relay/source identity for cards retained across reconnect/restart; current seq alone can be ambiguous.
- Coalesce state changes and send initial authoritative report after synchronization. No per-animation-frame reports or new device polling loop.
- Rate-limit/coalesce excessive transitions, but ensure a final empty/idle snapshot can replace intermediate ones.
- Legacy peers report unsupported/unknown; disconnected devices show last-known observation with age, not a live queue.
- Firmware reports are evidence of firmware lifecycle, not independent proof that pixels rendered or someone read the card.
- Current queue `shown()` increments on enqueue. Rename/reinterpret in diagnostics explicitly; never label it completed presentations.

No cancel/clear/reorder/priority/replay buttons initially. Operator can inspect state and optionally send one explicit test card through existing POST.
A test-card timeout is ambiguous because POST is not deduplicated; do not automatically resend.

### 11.5 Charts and event-driven updates

First charts answer operational questions rather than simulate a full monitoring product:

- Notification arrival rate and expired/suppressed/refused counts over time.
- Firmware-reported pending queue depth/capacity per device; transport pressure stays a separate series.
- Twitch viewers/chat activity for the effective channel when available.
- CPU/weather show current accepted values and freshness; historical charts are deferred as selected in 14.7.

Enforce section 14.7's time, bucket, series/device, connection and encoded-payload limits.
Use memory rings/downsampling, not a database. Label restart resets, outages and source changes; do not interpolate missing values as measurements.
Keep archived old-channel/source series clearly labeled or reset them at the generation boundary.
Provide chart captions, units, no-data states and accessible textual/table summaries; hover is not the only way to inspect values.

Load an authoritative snapshot, then subscribe to same-origin server-pushed updates. No setInterval API polling.
Selected transport: SSE using pinned Tapir/Netty sync support confirmed by local jar inspection; verify lifecycle/timeouts in A1's early spike and C4's full integration.
Only a demonstrated incompatibility may justify a documented bounded WebSocket correction; no polling fallback or framework rewrite.
Close page subscriptions on teardown; reconnect with backoff and a fresh snapshot/reset after history gaps.
Order snapshots/changes with a stream generation/revision; reset on relay restart and reject old-generation messages.
Coalesce bursts; bound per-browser queues/connections and release resources on disconnect. Observer traffic cannot block device/Twitch work.
Transport heartbeats and local countdown rendering are allowed; neither is an application data-polling loop.
Review HTTP inbound-read timeouts and proxy buffering for long-lived feeds without disabling protection for normal requests.

### 11.6 Visual and mobile/tablet quality

- Compact modern instrumentation style: deliberate typography, spacing, semantic colors and restrained borders/shadows.
- Primary controls first; statuses next; queue/charts below. Avoid a dense desktop table squeezed onto a phone.
- Phone: single-column sections and touch-friendly segmented controls. Tablet: balanced control/status and queue/chart columns.
- Responsive at 320, 768, 1024 and 1440 CSS-pixel widths; portrait/landscape and zoom must not produce page-level horizontal overflow.
- Large labeled touch targets, keyboard navigation, visible focus, usable form errors and accessible live-status announcements.
- Respect reduced motion; avoid chart animation storms, color-only status, hover-only actions and noisy full-screen spinners.
- Include initial loading, truly empty, connection-lost, stale, applying, success and recoverable-error states.
- Bundle styles/fonts/icons/chart code locally; no CDNs, trackers, chart service or cloud dependency.
- Use small HTML/CSS/JS modules or a justified minimal pinned build toolchain; no large SPA framework by default.
- Serve compiled assets from the relay image with correct MIME types, route fallback, cache/version policy and CSP.
- Do not cache/replay mutation requests offline; cached UI shell must not pretend control succeeded while relay is unreachable.

### 11.7 Private-network security without application login

Network membership—not accounts/roles—is the explicit trust model. Anyone reaching the service can control it.

- Remove mandatory management credential checks/challenges and startup requirements. Do not hide auth behind a UI-only bypass.
- Document migration for old Basic/Bearer env keys and clients; deprecated keys must not falsely imply protection.
- Bind/publish only on intended private interfaces, restrict via firewall/VPN and never expose naked admin APIs through a public proxy.
- Define allowed Host names/private IPs and ports; reject unexpected Host values to reduce DNS-rebinding exposure.
- Keep same-origin Origin/Fetch-Metadata checks independent of auth; reject cross-site/null-origin browser management requests.
- Do not allow wildcard CORS. CLI/private producers without browser Origin remain usable under the network trust model.
- Enforce JSON/content types for mutations, body/field/deadline/resource limits and safe error messages.
- OAuth callbacks are explicit exceptions to normal browser-origin checks, with existing state validation; webhook signatures remain intact.
- Render notification/chat/channel text as text, not HTML. Test stored/reflected XSS and hostile browser requests.
- Do not embed secrets in assets/snapshots/feed/log views; preserve redaction across every new route.
- Add CSP/frame restrictions and defined private proxy Host handling. Browser safety checks are not advertised as user authentication.

### 11.8 Admin UI acceptance

- [ ] Fresh private deployment opens panel and controls APIs without login or management-token setup.
- [ ] Four visible display choices map correctly; active generic cards finish while pending intent is shown honestly.
- [ ] Channel Apply validates and hot-reconfigures one source; display preference and generic delivery survive success/failure.
- [ ] Wrong/missing grant explains restricted features without blocking independent notifications or exposing secrets.
- [ ] Mobile/tablet Twitch consent works through the documented trusted private HTTPS callback; loopback-only deployments show truthful desktop-consent guidance.
- [ ] Runtime enable/disable/channel changes activate or retire Twitch alerts correctly, without stale timers or loss of independent device/failure monitoring.
- [ ] Real device pending/active queue appears through reports; legacy/disconnected devices remain visibly unknown/stale.
- [ ] Charts reflect bounded real observations and reset/gap semantics; two tabs/slow clients do not exhaust memory or stall delivery.
- [ ] Real-browser mobile/tablet, keyboard, reduced-motion, empty/error/reconnect and no-polling tests pass.
- [ ] Hostile Origin/Host/XSS checks pass with app auth removed; authorized provider callbacks still work.
- [ ] Public microsite remains separate and contains no private control routes/data/configuration.

## 12. Linux desktop companion daemon

Required companion for testing and ordinary use—not a one-off fake producer.
Runs on the monitored desktop as its logged-in user, observes submitted desktop notifications and pushes CPU/memory snapshots to the relay.
Home: `twitch-screen-client/`; delivery: section 14.9's installable Python package, foreground command and systemd user service.
Initial supported platform: Linux with a Freedesktop notification daemon/session bus. Windows/macOS/mobile capture is later scope.
No new relay login/token, no privileged root service, no desktop notifications generated by the relay itself.

### 12.1 Desktop notification capture

- Freedesktop notifications use `org.freedesktop.Notifications` on the session bus, object `/org/freedesktop/Notifications` and the `Notify` method.
- Listen to actual notification submissions event-by-event; do not poll notification history or scrape desktop UI/log files.
- Ordinary `NotificationClosed`/`ActionInvoked` signals do not contain full newly submitted notification text; subscribing only to signals cannot implement capture.
- Prefer a dedicated private D-Bus monitoring connection using `org.freedesktop.DBus.Monitoring.BecomeMonitor` with narrow nonempty Notify rules.
- Monitoring is bus-policy/implementation privileged, even on a session bus; prove permission and desktop compatibility before promising universal capture.
- A monitor connection cannot send bus messages. Use a separate ordinary connection for owner-change/status requests; validate ownership/path/interface/method and bound decoded input.
- Do not claim ownership of `org.freedesktop.Notifications`, replace/proxy the notification server or interfere with desktop popups/actions/closure.
- If scoped monitoring is denied, report capture unavailable and provide a supported daemon-specific event adapter if available. CPU/memory keeps working independently.
- Do not silently escalate to root, loosen bus policy, log the whole bus, or fall back to periodic screen/history scraping.
- Reattach on session-bus/notification-owner changes; namespace replacement IDs by sender and daemon/session generation.
- Capture proves a submitted Notify request, not a shown popup. Define optional return/error correlation before claiming daemon acceptance.
- Portal/native-only notification paths are not universally covered by this interface; declare tested daemon/desktop versions and unsupported producers honestly.

Grounding references (planning reads, not local desktop compatibility evidence):
- https://specifications.freedesktop.org/notification-spec/latest-single/
- https://dbus.freedesktop.org/doc/dbus-specification.html — Monitoring.BecomeMonitor semantics and policy.

### 12.2 Generic-card mapping, privacy and filtering

- Map summary/body/app label to bounded existing generic `title`/`body`; origin stays generic, never Twitch.
- Preserve useful UTF-8 text, strip notification markup/control content safely and truncate by API/wire/font constraints.
- Map desktop urgency low/normal/critical to info/message/alert as selected in 14.9; unsupported hints are ignored, not blindly serialized.
- Never forward action callbacks, icons/image blobs, URLs as executable actions or arbitrary hints/files/env payloads.
- Make application include/exclude filters and body-redaction/summary-only controls explicit; document that content becomes readable on the trusted relay and physical desk display.
- Forwarding is explicitly enabled in client configuration; test profile allows only uniquely labeled synthetic test applications.
- Do not log notification bodies by default. Logs expose capture/send health and counters, not personal messages; diagnostics use synthetic fixtures.
- Exclude the companion's own notifications and avoid feedback loops. Do not equate urgency with permission to interrupt an active card.
- Before publication, coalesce pending replacements only with a verified sender/session-scoped identity; a zero replacement ID is not a shared notification key.
- Already accepted replacements may appear as new cards: initial relay has no cancellation/update/deduplication contract.
- Desktop `expire_timeout` is not relay `ttlMs`: define bounded relay waiting expiry separately from on-screen hold.
- Desktop timeout `-1` means server default and `0` means never expire there; both still map to finite companion queue/relay waiting bounds.
- Desktop close/dismiss signals never interrupt active generic cards or imply remote cancellation.

### 12.3 CPU and memory measurements

- Push through `PUT /api/v1/cpu`, using section 14.2's source/host fields, CPU/memory values and calibrated freshness deadline.
- CPU means aggregate host utilization from deltas between consecutive monotonic samples; discard warmup/reset/invalid deltas rather than inventing a zero sample.
- Pin accounting, including Linux idle/iowait/guest conventions, in fixtures and documentation; raw cumulative counters are not percentages.
- Memory means physical RAM used = total minus available, expressed as percentage of total. Do not confuse “free” RAM, page cache or swap with unavailable RAM.
- Use section 14.9's injected `/proc` reader adapter and selected dependency pins; lock transitive/build hashes during E3.
- Optional temperature remains absent when unsupported; do not send fabricated temperatures. Missing measurement is not zero.
- Sample on a configurable bounded monotonic cadence; CPU deltas require counter sampling, so this is an intentional exception to notification/browser no-polling policy.
- No relay health/API polling loop: sampling pushes current data; notification capture and connection/recovery triggers remain event-driven.
- Keep measurement/capture separate from network work. One slow HTTP request must not stall notifications or sampling.
- CPU/memory is latest-only: at most one pending snapshot replaces older unsent data. Skip overlapping sends and never accumulate a historical telemetry FIFO.
- Track age from measurement, not last upload/retry; deduct local queue age from freshness and discard expired data. Reconnect samples anew rather than renewing old metrics.
- Section 14.4's age/uncertainty contract covers in-flight transport too; wall timestamp alone does not establish synchronized clocks or strict freshness.
- Writes never select CPU mode, disable Twitch, change channel or create notification cards.

### 12.4 Sending, process ownership and failure semantics

- Use explicit configured trusted relay base URL; no cloud discovery, token or management credentials.
- Support HTTP on trusted LAN and certificate-verified HTTPS; never disable TLS verification or follow redirects to arbitrary destinations.
- Validate endpoint scheme/host/path and config bounds; limit request/response bytes, concurrent sends and total exchange deadlines.
- Bounded notification FIFO by count/encoded bytes and waiting age; define documented drop/coalescing counters and overflow policy.
- Keep networking off the D-Bus callback. Rate-limit bursts; queue admission/drop must not slow the real desktop daemon.
- No durable notification spool/database initially. Expired notifications and historical metrics are never resurrected after outage/restart.
- Retry/backoff only eligible sends with section 14.9's bounded delays/jitter and confirmed-safe outcome rules.
- POST success means relay acceptance only. A timeout after possible acceptance is ambiguous: record/drop it rather than automatically resending and duplicating cards.
- Distinguish confirmed pre-send failure from ambiguous send outcome. Retry only confirmed-safe, still-unexpired notification attempts.
- Idempotent CPU PUT may retry only the newest still-fresh observation; distinguish 4xx contract errors from transient failures and avoid tight failure loops.
- Foreground process supports bounded health diagnostics and SIGTERM/SIGINT cancellation/join. No unmanaged child workers or recursive self-daemonizing.
- systemd user unit runs without root against the correct logged-in desktop session; do not hardcode another user's bus address.
- Document install/start/stop/status/journal/uninstall plus session/environment setup; startup requires a usable session, not blanket root/linger privileges.
- Prevent accidental duplicate client instances per configured source. Do not install/enable the service during planning or replace existing user services during E2E.
- Notification capture and CPU sender have independent degraded state. Relay admin shows telemetry freshness/source; any client health shown remotely must use explicit observations, not infer capture health from CPU data.

### 12.5 Companion verification and acceptance

- [x] Unit fixtures cover Notify text/urgency/timeouts/replacements, sender/owner isolation, markup, bounds, filters and redaction.
- [x] Isolated real session bus/fake notification daemon proves actual method capture, permission failure, server restart and normal desktop delivery.
- [ ] CPU warmup/delta/reset and memory availability fixtures pass; real values are compared with independent counter readings under stated timing tolerance.
- [x] Real child process + fake relay verifies notification POST and CPU/memory PUT while other subsystems remain responsive.
- [ ] Burst, slow/hung relay, 4xx/5xx, disconnect, ambiguous POST and freshness/expiry tests enforce bounded memory and no stale replay.
- [ ] Graceful stop/restart releases bus/HTTP workers; systemd user installation is exercised on the declared supported desktop.
- [ ] Real Docker relay + device demo proves synthetic desktop Notify → generic card and measured CPU/memory → fresh CPU dashboard, including hidden updates during Twitch override.
- [ ] CI uses synthetic buses/HTTP/counters without personal notifications or providers; supported desktop/hardware claims require local observed evidence.
- [ ] Client commands/dependencies/service packaging, privacy defaults and limitations appear in docs/microsite/license/release inventory.

## 13. Boundaries and later roadmap

### First-release boundaries

- Preserve Twitch OAuth/refresh/callback mechanics; add owned runtime source/channel reconfiguration. Display intent never authorizes an account or starts a broadcast.
- OBS start/stop is scoped local E2E tooling only—not new application broadcast control.
- No automatic Twitch online/offline switching initially; approved screen fallbacks remain automatic.
- Four UI display choices map to Twitch override + remembered non-Twitch preference; generic notifications remain independent overlays.
- No weather/CPU dashboards during override, relay loss or stale/missing data; active generic cards are never preempted.
- Runtime/docs/deployment/product site and relay admin panel are required. Database, large management suite and CAD geometry redesign are not.
- Use private-network UI/API without application authn/authz; retain independent browser defenses and Twitch/OBS provider authentication.
- Preserve trusted-LAN device boundary; no device TLS/auth redesign or provider credentials on firmware.
- Shipped companion pushes host CPU/memory and desktop notifications; weather remains externally supplied.
- No relay-side host/provider polling collectors or telemetry history replay; periodic host counter sampling belongs only in the companion.
- No durable/exactly-once guarantee, priority/preemption, arbitrary HTML, uploaded client images or client-defined widgets.
- Do not commit/tag/push, publish website/releases or change live account settings beyond authorized testing without approval.

### Optional later improvements

Keep these out of first-release acceptance unless promoted explicitly.

- [ ] Quiet desk profile: reduced motion and optional actual backlight PWM; preserve card completion.
- [ ] Orbital fingerprint: stable per-device seed gives a recognizable local animation identity.
- [ ] Build/render/backup companion: external completion/failure cards with expiry; document ambiguous-retry duplicates.
- [ ] Stream Deck/OBS intent button: explicit display override, remembered preference, no OAuth or broadcast-start coupling.
- [ ] Reserved generic replay capacity: prevent Twitch bursts evicting generic records, with bounded memory and merged ordering tests.
- [ ] Per-device preferences/override and targeted notifications.
- [ ] Multi-host/location selection or more sophisticated producer arbitration; no producer credentials required in the private trust model.
- [ ] Notification priorities, explicit cancellation and deduplication keys.
- [ ] Built-in weather provider or relay-side host collector; Linux companion is required now, not roadmap.
- [ ] Automatic EventSub-driven Twitch override with manual-policy rules.
- [ ] Scheduled dashboard rotation, richer telemetry or scrolling cards after demonstrated need.
- [ ] Persistent notification history and richer rendering receipts; lightweight reported queue/applied-state diagnostics are required now.

## 14. Locked implementation contract and handoff

These are implementation decisions, selected on 2026-10-11 under the user's instruction to decide autonomously.
They resolve earlier provisional wording. Keep sections 3 and 7's behavior/acceptance guarantees; use this section for exact choices.
Unmeasured hardware behavior, desktop compatibility, third-party rights and unavailable credentials remain evidence gates, not open product preferences.

### 14.1 State ownership, persistence and restart

- Store one versioned `data/runtime-state.json`, configurable by `RELAY_RUNTIME_STATE_FILE`, alongside but separate from the existing Twitch token file.
  Schema 1 contains only `{schemaVersion, display: {twitchDisplayEnabled, preferredDashboard}, monitoring: {mode, channel}}`.
  Persist **both** display fields and both monitoring fields. `channel` may be null only in stored/read state when no channel has ever been configured.
- With no state file: display is `{twitchDisplayEnabled:false, preferredDashboard:"generic"}`; monitoring takes explicit startup mode/channel, otherwise `disabled`/null.
  Existing live deployments upgrading to the new firmware must explicitly select Twitch once; never infer display intent from a grant or a live channel.
- With a valid state file: saved desired settings win over env/default mode/channel. Credentials, redirect URLs, transport, network and resource limits remain startup configuration.
  Expose `settingsSource: "persisted" | "startup"` in monitoring GET. Keep config GET truthful about startup configuration.
  A saved live preference without usable application configuration remains desired live but effective disabled/failed with `configuration_missing`; generic operation still starts.
- A present corrupt/unsupported state file fails startup with its path and a safe error, without printing content or overwriting it.
  Document explicit stopped-service backup/reset recovery. Missing file is first run; permission/I/O errors are not treated as missing.
- Atomic replacement: bounded 16 KiB file, schema validation, mode 0600, parent 0700, temporary file in the same directory, flush/fsync, rename and directory sync where supported.
  Serialize writes in a bounded settings worker (16 pending commands); do disk I/O outside DeviceHub's actor. Only publish a committed desired state to the hub/controller.
  Equivalent PATCH is a no-op: no new revision, disk write or source rebuild. Invalid input never mutates state.
  Storage failures return 503; an uncertain rename/durability outcome must be reconciled from storage and canonical GET, not reported as definitely unchanged.
- Use a fresh random nonzero 64-bit `relayEpoch` per runtime sequence space, and a unique nonzero 64-bit `connectionId` per connection within that epoch.
  Keep the legacy random 32-bit WELCOME `session_id` for old peers. JSON encodes 64-bit identities as exactly 16 lowercase hexadecimal characters.
  Revisions/source generations/episodes are u32, start at 1 within an epoch, and never wrap. Exhaustion refuses the relevant mutation/publication with 503 until an orderly runtime restart.
- Snapshots, notification replay/history, reports, charts and counters are RAM-only. Restart restores intent, not availability or old telemetry.

### 14.2 HTTP DTOs, defaults and validation

All new endpoints use `/api/v1`, camelCase JSON, explicit millisecond suffixes and existing JSON error shape with an optional stable `code` field.
Reject unknown request fields, duplicate keys, explicit nulls, non-finite numbers, invalid enum case and oversized bodies before mutation.
PATCH requires at least one recognized field; omitted fields remain unchanged. Apply partial changes to current state in serialized acceptance order.
Response-only optional measurements are null when absent; zero is always a real measurement. UTC timestamps use RFC 3339 `Z` strings with millisecond precision.
Existing notification request fields and response fields remain; new response metadata is additive. Existing legacy endpoints otherwise retain their documented schemas.

| Route/request | Required fields | Optional fields and defaults | Bounds/semantics |
|---|---|---|---|
| `POST /notifications` | `type`, `title`, `body` | `ttlMs=8000`, `expiresInMs=60000`, `deadline` absent | Existing kinds; nonblank trimmed title; body may be empty; each text field at most 4096 UTF-16 code units and valid Unicode; hold 100–30000 ms; expiry 1–600000 ms |
| `PATCH /display` | At least one of the following fields | `twitchDisplayEnabled`, `preferredDashboard` | Boolean; enum `generic`, `weather`, `cpu`; one atomic change |
| `PUT /weather` | `sourceId`, `location`, `temperature`, `condition` | `unit="celsius"`, `humidityPercent`, `observedAt`, `freshnessMs=600000`, `deadline` | Temperature C −100…100 or F −148…212; unit `celsius`/`fahrenheit`; humidity integer 0…100; freshness 1000…1800000 ms |
| `PUT /cpu` | `sourceId`, `host`, `cpuPercent` | `memoryPercent`, `temperatureCelsius`, `observedAt`, `freshnessMs=10000`, `deadline` | Percent 0…100; temperature −20…150 C; freshness 1000…60000 ms |
| `PATCH /twitch/monitoring` | At least one field | `mode`, `channel` | Mode `disabled`/`simulated`/`live`; channel lowercase normalized login matching `[a-z0-9_]{1,25}`; reject URLs/empty/null |
| `GET /clock` | None | None | Non-cacheable `{relayEpoch, monotonicMs}` sampled for this response; calibration only, not a health-polling API |

- `sourceId`: 1–31 ASCII `[A-Za-z0-9._-]` characters. `location`/`host`: nonblank, at most 128 Unicode scalar values; wire label is at most 31 bytes after the peer's text policy.
  No automatic geolocation, timezone lookup, provider fetch, host discovery or source arbitration. Explicit unit applies to that weather sample; Celsius is the omitted-unit default.
- Weather `condition`: `unknown`, `clear`, `partly_cloudy`, `cloudy`, `fog`, `drizzle`, `rain`, `snow`, `thunderstorm` (wire codes 0…8 in that order).
  Temperature is rounded to 0.01 of its stated unit; CPU/memory to 0.01 percent; round half away from zero. Reject out-of-range values before rounding.
  Optional CPU temperature is accepted/queryable, but the first device CPU widget's secondary metric is **memory only**.
- `observedAt` is optional producer metadata, defaulted to relay receipt time and labeled accordingly. Never derive freshness from it.
  GET source DTO: `{relayEpoch, revision, status, reason, receivedAt, observedAtSource, remainingFreshnessMs, data}`.
  `status` is `missing|fresh|stale`; `data` is null if never supplied, otherwise the last accepted bounded payload even when stale. Firmware must hide stale data.
- PUT returns 200 with the same canonical source DTO; invalid writes preserve the previous slot. Each accepted replacement gets a new source revision.
  Both telemetry endpoints admit at most 10 writes/s each (global token bucket, burst 20); excess is 429 with `Retry-After: 1`, no mutation.
  Device publication coalesces to at most 5 updates/s/source; expiry is based on acceptance, never delayed until this publication.
- Notification hold rounds **up** to a 100 ms quantum and the response reports the effective hold; 101 ms becomes 200 ms, never wire zero.
  Expiry is independent of hold. A 1 ms useful lifetime may expire before any device can start it; acceptance does not guarantee presentation.
  Tightening the old 6553500 ms API hold maximum is an explicit major-update migration change. Keep existing Twitch per-kind holds of 6/8/10 s.
- Notifications add `relayEpoch`, `origin`, `sourceGeneration`, `twitchEpisode`, `expiresInMs`, `remainingExpiryMs` to responses/history.
  HTTP publishers always get `origin:"external"`, even for Twitch-looking kinds; clients cannot set provenance or generation.
  Global POST rate limit is 10/s, burst 20. History still uses the existing bounded replay rings, not an unbounded second content archive.
- `deadline`, when supplied, is `{relayEpoch, monotonicMs}` in the relay's calibrated clock domain (14.4).
  Its effective deadline is the earlier of this value and receipt time plus the validated duration. Wrong epoch is 409 `clock_epoch_changed`; already expired is 410 `expired` without publication/mutation.
  Ordinary producers may omit it and get **receipt-based** lifetime. The shipped companion must supply it to preserve age from capture/measurement across HTTP delay.
- Display GET/PATCH response: `{relayEpoch, revision, twitchDisplayEnabled, preferredDashboard, weather, cpu}`; weather/cpu contain readiness/revision/remaining age, not duplicated full payloads.
  Device application is exposed through device/admin reports, never asserted by PATCH success.
- Monitoring GET/PATCH response: `{relayEpoch, revision, settingsSource, desired, effective, transition, sourceGeneration, resolvedChannel, grantCompatibility, error}`.
  `desired` and `effective` contain mode/channel; transition is `idle|applying|ready|failed`; errors use stable safe codes.
  `resolvedChannel` is null or `{id, login, displayName}`. Compatibility is `not_required|missing|expired|mismatched|partial|usable`, with per-feature eligibility and missing scopes.
- Bound ordinary management exchanges to 5 s of application work. Channel resolution has a 3 s budget before acceptance; exhaustion returns 503 and preserves settings.
  Source application is asynchronous after a 200 accepted desired-state response. It is never held open until Twitch becomes healthy.

### 14.3 Additive wire profile: DESK/1 inside TSB/3

Keep version byte 3, the 8-byte little-endian header, 248-byte payload ceiling, existing type layouts, reserved bytes and all 20 golden vectors.
Allocate `CAP_DESK_V1 = 0x00000010`. It is one complete profile: control, two snapshots, calibrated deadlines, processed progress, queue credit and device reports.
Partial implementations must not advertise it. Existing ACK remains highest successfully **enqueued** sequence, never rendering or expiry/suppression confirmation.
The separate processed cursor below records accepted **or deliberately disposed** records and is used for capable replay only.

Notation: `u8/u16/u32/u64/i16` are explicit little-endian widths; `s32` means a NUL-padded 32-byte text field, not an integer.
Layouts below list fields in exact order, without implicit alignment. Offsets are cumulative widths. A1 must generate offset/length assertions and independent Scala/C++ vectors from these tables.

| Record | Code | Payload bytes | Ordered fields |
|---|---|---:|---|
| HELLO extension | `0x01` | 92 total | Unchanged base 60; `profile:u16=1, reserved:u16=0, previousRelayEpoch:u64, processedSeq:u32, deviceBootId:u64, probeDeviceMs:u64` |
| WELCOME extension | `0x20` | 60 total | Unchanged base 24; `profile:u16=1, reserved:u16=0, relayEpoch:u64, connectionId:u64, sampledRelayMs:u64, echoedProbeDeviceMs:u64` |
| STATS extension | `0x22` | 60 total | Unchanged base 32; `relayEpoch:u64, connectionId:u64, sourceGeneration:u32, statsRevision:u32, availability:u8, reserved:3 zero bytes` |
| TIME_REQUEST | `0x05` | 8 | `probeDeviceMs:u64` |
| DEVICE_REPORT | `0x06` | 244 | Header and nine entries defined below |
| TIME_RESPONSE | `0x26` | 32 | `relayEpoch:u64, connectionId:u64, echoedProbeDeviceMs:u64, sampledRelayMs:u64` |
| DISPLAY_STATE | `0x27` | 32 | `relayEpoch:u64, connectionId:u64, revision:u32, sourceGeneration:u32, twitchEpisode:u32, enabled:u8, preferred:u8, reserved:u16=0` |
| WEATHER | `0x28` | 68 | `relayEpoch:u64, connectionId:u64, revision:u32, deadlineRelayMs:u64, status:u8, unit:u8, condition:u8, presence:u8, temperatureCenti:i16, humidityPercent:u8, reserved:u8=0, location:s32` |
| CPU | `0x29` | 68 | `relayEpoch:u64, connectionId:u64, revision:u32, deadlineRelayMs:u64, status:u8, presence:u8, cpuBasisPoints:u16, memoryBasisPoints:u16, temperatureCenti:i16, host:s32` |
| SYNC_END | `0x2a` | 40 | `relayEpoch:u64, connectionId:u64, displayRevision:u32, weatherRevision:u32, cpuRevision:u32, statsRevision:u32, sourceGeneration:u32, twitchEpisode:u32` |
| DESK_EVENT | `0x2b` | 196 | Unchanged EVENT payload base 168; `relayEpoch:u64, sourceGeneration:u32, twitchEpisode:u32, deadlineRelayMs:u64, origin:u8, reserved:3 zero bytes` |
| PROGRESS_BARRIER | `0x2c` | 20 | `relayEpoch:u64, connectionId:u64, throughSeq:u32` |

- Negotiate from HELLO capability plus valid profile-1 tail; unsupported/missing tail removes DESK capability and uses legacy behavior.
  Old relays ignore the appended HELLO bytes and unknown capability. Capable WELCOME must carry the complete matching tail; a malformed negotiated tail closes/resynchronizes.
- `preferred`: generic=0, weather=1, cpu=2. Snapshot status: missing=0, fresh=1, stale=2. Weather unit: celsius=0, fahrenheit=1.
  Weather presence bit 0 means humidity; CPU bits 0/1 mean memory/temperature. Missing optional numeric fields are zero-filled but MUST be ignored without their presence bit.
  For missing/stale wire snapshots set deadline and all value/text/presence fields to zero; relay GET may retain stale diagnostic data.
  Never accept unknown required enums as meaningful state; skip/count the invalid record and let the bounded sync/application deadline force resynchronization.
- New records require negotiated DESK capability. Validate epoch/connection on state/time/report/barrier records, revisions and fixed fields before applying.
  DESK_EVENT's identity is `(relayEpoch, seq)` and its connection is the carrying TCP session; reject an unexpected epoch.
  Capable STATS requires its complete tail; match epoch/connection/sourceGeneration before applying it, including during source switches. Older-generation queued STATS must never repaint the new channel.
  STATS availability is unknown=0, observed=1, unavailable=2. Source reset/disabled uses unknown; render unavailable numeric values as `--` within the Twitch layout, never as measured zeros or as a weather/CPU fallback.
  Provider component health remains separately reported; an IRC failure alone does not erase valid Helix measurements.
  In DESK sessions accept cards only via DESK_EVENT; plain EVENT is a protocol violation requiring resynchronization. Legacy sessions receive only plain EVENT.
- Desktop font support remains printable ASCII initially; do not advertise UTF-8 coverage. Reuse existing folding/control sanitization, truncate without broken code points, and use `...` ellipsis.
  Keep 47/95 content-byte card limits. Use `C`/`F` suffixes without assuming a degree glyph. HTTP preserves valid original text for diagnostics.
- Data revisions may repeat during synchronization; equal revision must describe equal content/deadline. Ignore lower revisions within the current connection.
  Before SYNC_END, stage state only. Complete when all referenced revisions/generations match; missing sources are explicit status records and do not prevent completion.
- Greeting for capable peers is exactly WELCOME, DISPLAY_STATE, WEATHER, CPU, extended STATS, SYNC_END. All six are captured as one coherent hub transaction.
  Changes accepted afterward follow this initial batch, even if newer state is already queued. A newly expired initial value simply becomes unavailable at its deadline.
  Only after a matching initial DEVICE_REPORT may event replay/delivery begin. SYNC_END readiness has a 5 s deadline from WELCOME receipt; initial report has a 5 s relay deadline.
- Retain outbound default 128 and minimum `replayBufferSize + 18` for legacy greeting (64 + 16 + 2 = 82 at defaults).
  Capable greeting needs six reserved admissions, independent of replay. **Do not preload capable replay before the writer starts.** Stage references to at most the retained 80 records at default capacities, and drain with credits.
  Test both minimum capacities and default/full replay. The configured replay limit continues to bound staged references when enlarged.
- Legacy matrix: old/old unchanged; new relay/old device gets exact classic greeting, EVENT, STATS, holds, ACK and best-effort replay with no new controls or device expiry guarantee;
  new device/old relay runs the classic Twitch dashboard/cards when linked and local disconnected screensaver when down, and reports no applied control claims;
  new/new uses DESK/1. Unknown legacy card provenance stays `unknown`, never inferred from visual kind. Retained legacy identities use epoch zero and must not be correlated to current relay history.
  New API validation applies to all producers, but legacy receivers cannot enforce waiting expiry after send. Mark that limitation in admin/migration docs.

DEVICE_REPORT has a 100-byte header followed by nine fixed 16-byte entries (active first, then eight pending FIFO slots), for 244 payload / 252 frame bytes:

| Header fields in order | Bytes |
|---|---:|
| `relayEpoch:u64, connectionId:u64, deviceBootId:u64, reportRevision:u32` | 28 |
| `observedDeviceMs:u64, observedRelayLowerMs:u64, grantTotal:u64, processedSeq:u32, enqueuedSeq:u32` | 32 |
| `displayRevision:u32, sourceGeneration:u32, twitchEpisode:u32, weatherRevision:u32, cpuRevision:u32` | 20 |
| `baseScreen:u8, reason:u8, activePhase:u8, pendingCount:u8, activeRemainingMs:u32` | 8 |
| `expiredTotal:u32, suppressedTotal:u32, refusedTotal:u32` | 12 |

Each entry is `originalRelayEpoch:u64, seq:u32, kind:u8, origin:u8, reserved:u16=0`; unused entries are all zero.
Origin codes: external=0, twitch=1, relay=2, unknown=255 (unknown only for retained legacy cards).
Base screen: connected_orbit=0, disconnected_orbit=1, twitch=2, weather=3, cpu=4.
Reason: selected=0, relay_down=1, synchronizing=2, generic_selected=3, data_missing=4, data_stale=5, twitch_override=6, legacy=7, clock_unavailable=8.
Active phase: none=0, entrance=1 (includes severity flash), hold=2, exit=3. Remaining time covers the entire remaining presentation, not just hold.
Capacity is fixed at eight pending plus one active; reject pendingCount > 8. Report revision starts at 1 per connection, increases without wrap.
Counters saturate at u32 max and are scoped to deviceBootId. Sequence fields refer only to the current relay epoch; entries preserve original identities across reconnect.
Send on meaningful changes, with a 200 ms minimum spacing, latest-state coalescing and an idle refresh every 5 s; this is transport observation, not per-frame UI telemetry.
Report creation samples current state immediately before encoding. Reserve one replaceable report slot and sufficient TX space for a 252-byte report plus heartbeat/ACK (512-byte firmware TX FIFO).
Never block offline card completion on a report. `observedRelayLowerMs` is the conservative earliest relay-domain observation time computed from the accepted calibration's lower offset bound and drift guard (14.4); zero means unavailable.
Relay reports observation-age upper bound `nowRelayMs - observedRelayLowerMs`, separately from time since receipt; reject implausible future bounds and label unavailable calibration unknown.
Mark observations stale at an age upper bound over 15 s, unknown on boot/session mismatch, and historical immediately on disconnect. A delayed report must not become fresh merely by arriving.

### 14.4 Clock domains and end-to-end lifetime

- Use injected monotonic elapsed milliseconds for deadlines; wall time is metadata. Firmware extends its wrap-safe millisecond counter to u64, tested around u32 wrap.
  Relay uses elapsed `System.nanoTime`; companion uses Linux `CLOCK_BOOTTIME` for local age, so suspend does not preserve pending desktop content.
  Every stored duration is finite and at most 30 minutes. Never recompute a deadline from the original duration on resend/reconnect.
- Clock calibration uses request-send time `d0`, response-receipt time `d1`, and server sample `r` taken between those endpoints.
  In a constant-rate interval, relay-minus-device offset is bounded by `[r-d1, r-d0]`. Do not assume symmetric network latency or synchronized wall clocks.
  Accept round trips <= 1000 ms; require calibration no older than 60 s. Device refreshes with TIME_REQUEST every 20 s, separate from unchanged ping/ACK behavior.
  WELCOME's probe echo supplies initial calibration. Reject obsolete/out-of-order probe replies.
- To convert relay deadline `E` conservatively, use local deadline `d0 + (E-r) - guard`, with signed checked arithmetic.
  `guard = 50 ms + ceil((abs(E-r) + (d1-d0)) / 1000)` covers a chosen 1000 ppm relative-rate budget plus scheduling granularity.
  An already-past result is expired. Recalibration may shorten an existing local deadline but never extend it.
  This is an explicit operational clock-rate assumption, not proof against arbitrary broken clocks; tests cover the bound and diagnostics expose calibration age/RTT.
- For companion HTTP uploads, calibrate from GET /clock, recording its own `d0/d1`. Convert local capture/measurement deadline `L` to relay deadline `L + (r-d1) - guard`.
  Supply this absolute deadline and matching epoch with each POST/PUT. A request arriving after a timeout cannot acquire a new useful lifetime at the relay.
  Clock exchanges are triggered by startup/recovery or a send needing expired calibration, not an independent health-poll loop. Response headers `X-Relay-Epoch` and `X-Relay-Monotonic-Ms` on successful writes can refresh calibration using that exchange's send/receive times.
  If calibration is unavailable, keep only locally unexpired pending work; never send an unaged fallback payload from the companion.
- At relay publication assign absolute notification deadlines; at snapshot acceptance assign absolute freshness deadlines, capped by any supplied producer deadline.
  Per-peer writers inspect the current deadline/eligibility immediately before reserving/writing an event, never cache a relative remaining lifetime in shared Outbound bytes.
  Absolute relay deadlines on wire plus calibration account for arbitrary queue/transport/frame residence; firmware records them even when a complete frame waits for classification.
  Expiry governs whether a pending card starts. Once presentation begins, entrance/hold/exit always finish.
- If calibration expires, invalidate weather/CPU immediately and disconnect/resynchronize; already accepted generic cards retain their conservative local deadlines and may finish offline.
  Synchronization cannot make an old card/snapshot younger. No valid clock means no newly admitted capable card or available telemetry.
- Detect relay suspend/clock discontinuities by comparing wall and monotonic deltas at most once per second. A discrepancy over 2 s conservatively invalidates all waiting replay and telemetry,
  closes sessions and starts a fresh runtime epoch/sequence space while keeping persisted desired state and the stable OAuth owner. Cancel/recreate source scopes and reset their stats/alerts under the new epoch so old callbacks cannot reuse numeric generations.
  A wall-clock correction may therefore discard pending data; it must never extend it.
  Device loop gaps over 60 s invalidate calibration before further admission/reveal. Normal 49-day millisecond wrap does not trigger a false discontinuity.

### 14.5 Queue credit, progress, provenance and terminal suppression

- Keep one FIFO of eight pending cards plus one active card. No preemption, durable spool or priority. Eligible generic cards retain relative FIFO order among mixed records.
  Recompute the base before **every** exit/reveal callback and before LVGL rendering after a state/time change; keep state updates active during slide-out.
  The active card finishes regardless of origin, link, override, generation or its waiting expiry. Twitch eligibility is checked only before starting pending cards.
- `processedSeq` means highest sequence completely handled in ordered delivery: enqueued, duplicate, expired, suppressed, or explicitly covered by PROGRESS_BARRIER.
  `enqueuedSeq`/legacy ACK retain enqueue-only meaning. Update both independently; never move processed progress past a refused/unhandled event.
  Decode and classify complete frames before consulting capacity. Expired/suppressed/duplicate records require no queue slot and may advance processed progress.
- Resume capable peers only when HELLO.previousRelayEpoch matches and `0 < processedSeq <= latestSeq`; otherwise baseline both current-epoch cursors at latestSeq and replay nothing.
  Retained generic cards remain in the device FIFO with their original epoch/seq/deadline; do not relabel them after baseline. A fresh boot never replays historic cards.
- Use cumulative **credits**, independent of ACK: after synchronization, the device grants `8 - pendingCount` event admissions via DEVICE_REPORT.grantTotal.
  The relay counts DESK_EVENT frames sent/reserved in this connection, and must keep that count <= the largest valid grantTotal received.
  Every received DESK_EVENT consumes one granted admission; release a replacement credit immediately on terminal skip, or when an enqueued card leaves the pending queue (start/expiry/suppression).
  Coalesced duplicate reports cannot create credit. Credit resets on each connection; never reuse an old connection's counters. Counter overflow requires reconnect, never wrapping.
- Control, snapshot, synchronization, TIME and PROGRESS_BARRIER records use no event credit. Use a bounded prioritized writer with one latest replaceable slot per control/source,
  an immutable initial six-frame batch and an ordered event/barrier stream. Each scheduling pass serves pending control before another event, then fairly services all pending state types.
  Superseded state may coalesce after initial sync, but control's episode/generation fences must survive coalescing.
  A full device queue normally waits on event credit **without blocking TCP control or heartbeat reads**; no reconnect loop is needed to drain ordinary replay.
  An unexpected no-slot event is a counted refusal and immediate disconnect without advancing processed progress. Keep legacy full-queue behavior only on legacy sessions.
- Control accepted at the relay must be firmware-reported within 5 s on a ready connection, otherwise close/resynchronize it. This budget is per latest accepted control revision and cannot be reset by event slot release.
  Writer no-progress timeout is 3 s for capable sessions; missing expected clock/sync/report traffic also forces recovery. A new control revision cannot mask failure to deliver earlier fences: carry the cumulative episode/generation state.
  Under a stream of new control writes, enforce a 5 s oldest-unapplied-control budget, not a freshly reset timeout for every write.
- For capable replay, send only retained, unexpired **external/relay** cards. Twitch records are terminally omitted on every reconnect, including events published while that device was absent.
  This deliberately favors current Twitch state over replayed celebrations; normal connected Twitch delivery remains intact. Legacy Twitch replay is unchanged.
  After the ordered replay prefix, send PROGRESS_BARRIER through the captured latest sequence. During normal delivery, barriers also cover filtered/expired ranges in order.
  Never send a barrier across an event still waiting for credit or admission. Newly published events follow the captured replay prefix; bounded retention/overflow can still lose records as documented.
- `origin` is assigned at publication: management/companion pushes = external; simulator/live audience/lifecycle/provider/OAuth diagnostics = twitch; independent relay/device failures = relay.
  Make this typed metadata at producer boundaries, not a string/title/kind heuristic. Preserve it in retained records and new wire events. Relay diagnostics remain generic overlays.
- `sourceGeneration` increments for accepted mode/channel switches (including disable/simulated/live); `twitchEpisode` increments whenever override changes or a source generation changes.
  Twitch records carry both; non-Twitch records carry zero for both. Publish Twitch cards for capable delivery only when override is enabled and the source generation is current.
  Store ineligible Twitch history as terminally ineligible for capable delivery; later enabling cannot make it eligible. Keep bounded history for diagnostics and the legacy path.
- On new control, remove pending Twitch cards whose generation/episode differs or whose override is off. On link loss remove **all** pending Twitch cards.
  A disable followed rapidly by enable still changes episode even if only the last control value reaches the device. Offline→re-enable→reconnect cannot resurrect Twitch records because capable reconnect never replays them.
  Source switching likewise preserves active cards and generic FIFO, while purging obsolete pending Twitch records. Replay metadata/history is not proof of device presentation.

### 14.6 Reliable source switching and admin state

- Introduce one runtime settings/monitoring controller and keep DeviceHub as authoritative owner of delivery state, revisions, eligibility and published snapshots.
  Keep OAuth/token maintenance in application scope even while monitoring is disabled. Mount OAuth/monitoring/status routes once through stable owners, not a captured replaceable TwitchSource.
- Resolve/validate a live channel before accepting its desired state. Disabled/simulated mode permits format-validated stored channel without a provider lookup; report it unresolved until live resolution succeeds.
  Simulated mode with no channel uses `demo` as effective display identity without writing a fake desired broadcaster/grant.
  Missing app credentials reject a new live PATCH with 409 `configuration_missing`; missing/mismatched user grant does not block public Helix/IRC monitoring.
- Under one serialized acceptance transaction, persist desired state, install a new authoritative generation fence in the hub, invalidate Twitch stats/metadata/charts and retire old-generation active Twitch alerts.
  Only then cancel/join old source workers and close chat/EventSub resources; allow 5 s for teardown. If teardown cannot be proven complete, mark failed and do not start a competing source.
  New source has 15 s for initial application/readiness, then reports failed/degraded while bounded existing recovery continues (1 s exponential backoff capped at 30 s, jitter <=25%).
  Never automatically revert to an old channel after acceptance. A new PATCH supersedes prior application work with a new generation.
- Fence events **at publication into authoritative consumers**, including callbacks queued before cancellation. Tag all Twitch bus messages with `(relayEpoch, sourceGeneration)`.
  EventBus remains bounded/lossy for observations, logs and metrics. It must not be the sole carrier of generation changes, reset commands, display mutations or report/control acknowledgments.
  Statistics folds compare their local generation with an authoritative snapshot before applying an event or publishing a timer tick; reset before using a newer generation and reject older messages.
  Hub checks the generation again before accepting stats/cards, closing the race between validation and a concurrent source switch.
- Alert rules are initialized independently of startup Twitch mode. Activation uses effective runtime mode/generation; reset Twitch absence/offline timers and retire active old-generation alerts on switches.
  Keep independent device/failure rules and their timers. Historical alert entries retain origin/generation labels. A source switch is not a fabricated StreamEnded/StreamStarted event.
- Grant matching uses resolved broadcaster identity and actual scopes, not entered channel text. Wrong user's grant remains stored/refreshed but ineligible for broadcaster-scoped features.
  Public stream/viewer/chat monitoring continues when possible; follow/subscriber/EventSub features expose individual eligibility. Keep current transport-specific provider requirements.
- Admin state is an immutable, revisioned projection of authoritative snapshots, with per-component revision/observedAt and source generation.
  Lossy observation updates may coalesce, but the latest snapshot must be replaceable/recoverable independently of EventBus delivery. Source/display transitions trigger a reliable projection refresh.
  A coherent response must not pair new-channel identity with old-channel stats; omit/mark unavailable any component whose generation is not the selected effective one.
  Do not claim a global physical instant across asynchronous devices; report each device's observation age honestly.

### 14.7 Admin UI, feed and bounded history

- Use vanilla HTML/CSS/ES modules and hand-built accessible SVG charts, bundled under relay `resources/admin/`; no npm/runtime framework or chart dependency.
  Serve `/` plus versioned assets on the relay origin; keep Swagger `/docs`. No service worker, CDN, external font request or offline mutation cache.
- Use Tapir 1.13.31's existing `sttp.tapir.server.netty.sync.serverSentEventsBody`/Ox Flow support. Widen the endpoint/middleware assembly to carry required streaming capabilities; do not use unsafe casts to erase them.
  Retain ordinary request-body and whole-request limits. After a validated SSE GET, replace the connection's inbound-idle-only timer with streaming-aware lifecycle handling;
  outbound SSE comments do not reset Netty's current inbound ReadTimeoutHandler. Close/write-timeout failed feeds without weakening unrelated routes.
- `GET /admin/snapshot` returns `{relayEpoch, revision, capturedAt, display, monitoring, sources, devices, notifications, charts, limits}`.
  Devices carry connection/capability/firmware metadata, desired/applied revision, report status/age, active/pending entries, transport counts and disposition counters.
  Capture metadata currently discarded after HELLO; unknown/unsupported/stale remains explicit. No secrets or full startup-env dump.
- `GET /admin/events` is SSE with `Cache-Control: no-store`, no compression/proxy buffering, event IDs `<relayEpoch>:<revision>`, and event types `snapshot`/`reset`.
  Register observer and capture initial snapshot atomically in the projection owner; every connection starts with a complete snapshot, then sends coalesced complete snapshots.
  The UI may first GET a snapshot, but the feed's first complete snapshot supersedes it; ignore older revisions. No delta reconstruction or Last-Event-ID replay promise in release one.
  A restart/history gap sends reset/full snapshot; duplicate revisions are harmless. Heartbeat comments every 15 s, reconnect delay 1…30 s with <=25% jitter.
- Limit 8 concurrent SSE connections globally, 2 per normalized remote IP, 1 latest pending snapshot per client, at most 4 snapshots/s/client, and 512 KiB encoded snapshot size.
  Reject excess connections with 429. Proxy remote identity is trusted only from the explicitly configured proxy hop; otherwise use socket address.
  Use a 10 s pending-write deadline and release subscription resources on disconnect/cancel. Keep HTTP's existing 128 total connection cap; feeds cannot take all slots.
- Chart history: 15 minutes, 5 s buckets, 180 buckets/series; 16 charted device identities maximum, selected by connected first then most recently seen, deterministic device-ID tie break.
  Show all admitted devices in bounded status (existing 64-session cap), with an explicit chart-unavailable label beyond the chart limit.
  Per charted device retain queue depth plus expired/suppressed/refused deltas; globally retain notification arrivals, viewers and chat rate. CPU/weather historical charts are deferred; current values are required.
  Encode chart series compactly as `{startAt, bucketMs, values:[number|null]}` rather than repeating timestamps/objects for every bucket; bound all device labels/previews before projection.
  Source-generation change resets Twitch series; missing/stale samples are gaps, not zero or interpolated measurements. Rates are clearly labeled relay/device observations, including dropped-observation limitations.
  Disconnect entries expire after 15 min; device reconnect reuses chart identity with a visible session/boot boundary. Notification preview list is newest 80 maximum with 160-character safe previews.
- The panel shows the required four-choice display selector, explicit channel Apply, independent monitoring mode, OAuth feature compatibility, sources, device selection, queue and charts.
  Preserve remembered preference when selecting Twitch; select another choice with one atomic PATCH. Reconcile after timeout; never automatically retry notification POST or OAuth actions.
  Active-card UI uses report-derived remaining total duration as an estimate and freezes/labels it stale when feed/report freshness expires.
- Build real-browser checks using a pinned Playwright test-only dependency selected/locked in A0; no browser framework in shipped assets.
  Assert widths 320/768/1024/1440, keyboard/focus, reduced motion, touch targets >=44 px, WCAG AA contrast, XSS-safe text, and no periodic API fetch loop.

### 14.8 Private networking, browser boundary and migration

- Native relay defaults HTTP/device bind to `127.0.0.1`; Docker still binds container listeners to `0.0.0.0`, but Compose publishes both on `${RELAY_BIND_IP:-127.0.0.1}`.
  A LAN example requires an explicit host interface IP and maps mode/channel/chat/redirect/allowed-host settings into the container. Defaults: mode disabled, channel empty, chat hide.
  Never use wildcard host publishing as the credential-free quickstart. Device use requires the documented private LAN/VPN/firewall configuration.
- Add `RELAY_HTTP_ALLOWED_HOSTS` and `RELAY_HTTP_ALLOWED_ORIGINS` as exact comma-separated authorities/origins; defaults cover `localhost:8080`, `127.0.0.1:8080`, `[::1]:8080` and their HTTP origins.
  Non-loopback access requires explicit entries, including port. Normalize case/default ports/IP literals through URI parsing; reject userinfo, duplicate Host, malformed authorities and wildcard entries.
  Host validation is independent of Origin and applies before handlers, including feed/assets/docs and callbacks. Callback paths are exceptions only to cross-site Origin/Fetch-Metadata checks, not Host validation.
- For management/browser reads and writes, reject `Origin:null`, unlisted Origin or mismatching effective scheme/authority, and `Sec-Fetch-Site` other than `same-origin`/`none` when present.
  CLI/companion requests without browser headers are allowed after Host validation. No wildcard CORS; no permissive preflight response. Require JSON for JSON mutations.
  Protect the OAuth authorize GET as a management action; callback state/signature validation remains intact. Security classification must cover new asset/feed routes explicitly.
- Add `RELAY_HTTP_PUBLIC_ORIGIN` and an exact trusted-proxy address list for optional HTTPS termination. Default trusts no forwarding headers.
  Preserve external Host at the proxy; accept forwarded scheme/client address only from the configured immediate proxy, strip/reject ambiguous forwarded chains, and compare against the configured public origin.
  Ship a small private Caddy deployment example using `tls internal`, LAN-only listener and streamed feed proxying; explicitly install its CA on consenting client devices during setup, never disable certificate checks.
  Pin the chosen Caddy image digest when building the deployment example and record it; do not invent a digest in this plan.
- For mobile OAuth, use a configured private hostname resolvable on LAN/VPN, trusted HTTPS and the exact registered callback. Keep loopback-only consent guidance when that path is unavailable.
  WebSocket EventSub remains default. If retaining webhook transport, expose **only** `/api/v1/twitch/eventsub` through a separately documented restricted public ingress;
  its signature verification remains mandatory and no public route may proxy the admin/API wildcard. Public callbacks do not authorize public management exposure.
- Any nonempty old `RELAY_HTTP_AUTH_*` key or old nonempty HOCON auth value fails startup with an actionable migration error naming keys only.
  Empty old keys may warn once; remove them from shipped templates/tools. A supplied Authorization header does not confer privilege and is never logged.
- Admin CSP: self-hosted scripts/styles/assets, `connect-src 'self'`, no plugins, `base-uri 'none'`, `frame-ancestors 'none'`; add nosniff and no-referrer.
  Remove inline styles/scripts or use explicit hashes where provider callback pages need them. Review Swagger's required CSP separately instead of weakening the admin policy.
  Add `.env*`, private keys and runtime state exclusions to Docker context as well as git; keep safe example files explicitly included.

### 14.9 Companion package, capture and operational defaults

- Package/CLI: `twitch-screen-client`, source `twitch-screen-client/`, Python >=3.12, asyncio structured task ownership, TOML configuration via stdlib `tomllib`.
  Initial pins: `dbus-fast==5.2.0`, `httpx==0.28.1`, build backend `hatchling==1.32.4`. Lock transitive/build dependencies with hashes in E3 after installation/security verification.
  Use dbus-fast's low-level message interface, not undocumented guessed monitor helpers; do not substitute the older dbus-next package silently.
  `/proc/stat` and `/proc/meminfo` readers need no psutil dependency. Tests use stdlib unittest and isolated child processes/buses.
- First supported acceptance target: Ubuntu 24.04 LTS, GNOME 46 Wayland, Python 3.12, user session bus and the desktop's Freedesktop Notify endpoint.
  This is a test target, not a preclaimed pass. Also test with isolated dbus-daemon + a fake notification server; record actual desktop/package versions in evidence.
  If scoped monitoring is denied, keep CPU working and capture explicitly unavailable; support an alternative daemon adapter only after concrete source-backed proof and tests, without root/bus-policy changes.
  Portal/native-only notification paths are outside the first capture claim. No unsupported universal Linux claim.
- CLI commands: `run --config PATH`, `check-config --config PATH`, `status --config PATH` (local bounded diagnostics), plus `--version`.
  Default config is `$XDG_CONFIG_HOME/twitch-screen-client/config.toml` or `~/.config/twitch-screen-client/config.toml`; no hardcoded user bus address.
  Require explicit relay URL; allow only HTTP(S), no userinfo/query/fragment, root base path, redirects disabled, TLS verification enabled, HTTPX `trust_env=False`.
- Ship a wheel, example config and a non-root `twitch-screen-client.service`; install into a dedicated user venv using pinned requirements.
  `Restart=on-failure`, `RestartSec=5`, `TimeoutStopSec=5`, graphical-session ownership, no automatic linger. Hold an advisory instance lock under `$XDG_RUNTIME_DIR` keyed by sourceId.
  Provide install/start/status/stop/uninstall commands, but no install/enable side effect during planning. Graceful cancellation closes both D-Bus connections and HTTP clients within 5 s.
- Defaults: CPU/memory enabled; notification forwarding **disabled until explicitly enabled**. On enable, summary-only is default; body forwarding is a separate opt-in.
  Include/exclude lists are case-folded exact application-name matches; empty include means all except excludes only after forwarding is enabled. Exclude wins; always exclude this client.
  App names are user-facing filters, not authenticated identities. E2E config includes only `twitch-screen-e2e` synthetic app messages.
- Use narrow nonempty Notify monitor rules with path/interface/member checks and current destination-owner validation. A second normal connection observes daemon ownership/status.
  Bound message size at the decoder/transport boundary before allocation (64 KiB), not merely after creating a huge Python object; prove the chosen library's limit or add an isolated bounded adapter.
  Overflow/disconnect fails capture closed and reconnects with backoff while CPU delivery continues; never inspect unrelated bus messages as fallback.
  Track unique sender + daemon generation + returned notification ID when available. Correlate method returns only with bounded pending call entries (128, 5 s); no broad body logging.
  If reliable return correlation is unavailable, disable replacement coalescing rather than guess IDs. Original submissions still forward independently with finite expiry.
- Strip markup through a parser (text extraction, no HTML execution), decode entities once, normalize whitespace/control characters and UTF-8 safely.
  Urgency low/normal/critical maps to info/message/alert. Title is summary, falling back to app name then `Notification`; prepend bounded app label to body only if bodies are enabled.
  Relay hold is 8000 ms. Waiting lifetime is 60000 ms for desktop timeout -1/0; a positive timeout is clamped to 1000…60000 ms. It never cancels an active remote card.
- CPU: sample every 2000 ms, configurable 1000…60000 ms. Use deltas of user+nice+system+idle+iowait+irq+softirq+steal; do not add guest/guest_nice again.
  Busy = total − idle − iowait. Reject nonpositive/reset/inconsistent deltas and warmup; never publish a fabricated zero. Memory = `(MemTotal-MemAvailable)/MemTotal*100`; absent/invalid required counters mean skip that field/sample.
  Companion requires a valid CPU measurement; memory absence is explicit degraded state, never zero. Skip intervals spanning suspend or >2× configured sampling period and rewarm CPU deltas.
  Freshness is `max(10000, 3*sampleIntervalMs)`, capped at 60000; sourceId is required stable config and host label defaults to short hostname, never machine-id/MAC.
- Two independent HTTP send workers/connections maximum: one notification FIFO and one latest-only CPU slot. No overlapping CPU writes.
  Request/response limits 16 KiB/64 KiB; total exchange deadline 3 s, connect deadline 1 s, calibration RTT <=1 s. Bound diagnostic status to counters/reasons, not content.
  Notification queue: 32 entries and 64 KiB encoded total, drop expired first then refuse newest; no disk spool. Send at most 2 notifications/s, burst 4.
  CPU slot replacement is constant memory. A slow request cannot hold the D-Bus callback or sampler.
- Retry confirmed pre-send connection failure only, with 1 s exponential backoff capped at 30 s and <=25% jitter, never beyond local expiry.
  Treat write/read timeout, connection loss after possible write and any uncertain 5xx POST outcome as ambiguous: count/drop, do not resend.
  4xx is a contract/config error (429 backs off without blind POST duplication; 409 clock epoch forces recalibration); safe rejected responses may retry only while still fresh.
  CPU PUT retries only the newest observation using its original absolute deadline. Recovery discards expired work and samples anew; no renewed freshness.

### 14.10 Presentation and hardware budgets

- Connected orbit palette: background `#080C12`, arc targets `#185B63`, `#326F91`, `#6B598B`; disconnected targets `#574531`, `#756047`, `#494C51` on the same background.
  Three reused arcs, 3/4/2 px widths, radii 70/89/108 px, sinusoidal radial excursion ±4/±5/±3 px; stable seeded phase offsets.
  Connected rotation periods 38/57/83 s and breathing 8 s; disconnected rotation 70/101/137 s and breathing 13 s.
  Interpolate palette targets over >=20 s. Keep a persistent >=30° outer-orbit break and a steady small connection-loss glyph in disconnected composition.
- Update at 20 Hz while visible; pause hidden rendering and preserve phase/composition through cards. Use integer/fixed-point state where practical, reused LVGL objects and no full-screen opacity layers for orbit effects.
  Numeric telemetry is immediate; decorative gauges may ease over <=250 ms and stop at freshness expiry. Bodyless generic cards use headline layout; nonempty bodies use compact metadata and up to five wrapped lines/ellipsis, no scrolling.
- Physical acceptance targets: p95 controller/animation update <=5 ms, p95 loop-to-loop interval <=50 ms and no interval >200 ms attributable to the new UI in the defined test;
  minimum free heap >=40 KiB, largest free block >=16 KiB, loop-task stack headroom >=2 KiB, zero watchdog resets during 60 min mixed-mode/card/reconnect soak.
  Capture RGB565 stepping, wrap seams and text fit on actual 240×240 LCD. These are targets to measure, not claims about the current build.
  If a target fails, optimize/measure first; 10 Hz orbital animation is the first permitted fallback, documented with evidence. Do not lower memory/watchdog correctness gates silently.

### 14.11 Identity, licensing, microsite and release

- Keep public name **Twitch Screen**, repository/package/config identifiers and URLs. Use subtitle **“A small display for your whole desk.”** and lead with generic notifications/CPU/weather before optional Twitch integration.
  Use the same restrained dark/cyan/orbital visual vocabulary for site/admin; Twitch purple remains specific to Twitch. System fonts avoid shipping another font dependency.
- Target site URL/base path `https://worxbend.github.io/twitch-screen/` and `/twitch-screen/`, inferred from this repository's existing public source links.
  Build static `site/` HTML/CSS/JS with a small Python asset-copy/validation build and local preview; no Node build runtime. Keep relative paths/base configurable and test project-path hosting.
  CI builds an artifact; Pages deployment is a separate manual workflow requiring release authorization. Do not enable Pages or claim this URL is already published.
  Initial compressed page payload target <=1.5 MiB with lazy-loaded below-fold media, one local illustrative orbital demo, pause/reduced-motion support and no private relay connection.
- Selected licensing policy under the delegated decision: MIT for original software/scripts/site/admin code; CC-BY-4.0 for original documentation/photos/illustrations;
  CERN-OHL-P-2.0 for original CAD/hardware design sources and derived design exports. Code examples embedded in docs remain MIT.
  Add root licensing map, full license texts under `LICENSES/`, SPDX/file or directory-level mapping and `THIRD_PARTY_NOTICES.md` during F.
  Preserve dependency/font/artwork licenses and notices; Twitch names/logos are excluded from an assertion of project ownership or trademark rights.
  Verify asset provenance from repository/source history. Replace/exclude an asset with unknown redistribution rights; ask only if owner-only information is indispensable, not to repeat this license preference decision.
- Treat this as a breaking product release because of auth/config/hold-limit changes; prepare a major-version migration and rollback guide using the repository's actual version baseline discovered in A0.
  Relay-first rollout preserves legacy devices; firmware upgrade enables DESK/1. Firmware downgrade loses new display features and age/report guarantees. Keep runtime-state/token backups before rollback; never delete grants.
  Do not invent a numeric release tag before inventory, tag/push automatically, publish artifacts or make license coverage/physical/live claims before their gates pass.

### 14.12 Execution sequence, completion record and remaining evidence

1. A0 inventory/startup scaffolding and A1 normative contract/fixture work can begin immediately under these decisions.
2. Run the E3 isolated desktop capture feasibility check and C4 minimal real-socket SSE/timeout feasibility check early, before committing to packaging/UI work. They need no real private desktop content.
3. A2 proves one controller. A3 supplies the private HTTP boundary. B1 implements the six-frame sync, clocks, basic DEVICE_REPORT credit/progress and classified admission **together**;
   B4 enriches lifecycle/counters/admin diagnostics on that same report, rather than creating a second reporting protocol. This removes a B1→B4 dependency cycle.
4. B2/B3 establish generic/offline behavior; C1/C2 connect production persistence/control; C3 implements stable OAuth/runtime source switching; C4/C5 implement projection/feed/panel.
   B1 uses the final control/snapshot models with default Generic and explicit missing sources; C1/D1/E1 add the public mutation/storage paths without changing greeting shapes.
5. D/E widgets/producers, E4 delivery/service, F docs, G site and H physical/live acceptance follow their existing dependencies. Record actual evidence per slice; finish I only when mandatory gates pass or explicitly remain blocked.

Add `docs/implementation-status.md` in A0 with slice ID, status (`pending|in_progress|passed|blocked`), commit/diff identity, commands/results, evidence paths, deviations and next concrete action.
Keep this plan's checkboxes synchronized with that record. Record baseline evidence as baseline, not evidence for unimplemented features.
No commit/push/deploy permission is implied by this handoff. A later agent can implement and verify locally without reopening settled design questions.

Required new regression cases, in addition to section 7:

- Initial synchronization with eight retained pending cards grants zero event credit but still applies current control and completes; dequeue subsequently permits replay.
- Duplicate/coalesced reports, lost connection, terminal skip and queued-card suppression cannot manufacture or leak credits; old-connection grants are rejected.
- PROGRESS_BARRIER never overtakes an unadmitted event; processed resume skips terminal dispositions while enqueue ACK remains truthful.
- Disable/enable while a device is absent, rapid control coalescing, and old-generation STATS/callbacks cannot resurrect Twitch history or mix channels.
- Wall jumps, suspend, device u32 wrap, delayed clock response, late HTTP upload and delayed device report never renew freshness or make an old observation current.
- Losing a bus observation/reset event cannot undo the authoritative generation fence or leave the final admin state permanently stale.
- A real SSE connection remains open beyond 60 s with no inbound application traffic; disconnect releases resources and the ninth feed is rejected without starving HTTP/device work.
- Every persisted-field combination survives restart; invalid writes/no-op PATCH/storage failure/corrupt file have the specified outcomes; env values cannot silently override saved intent.
- All four old/new peer combinations preserve the selected fallback, unchanged base vectors and <=256-byte frames.
- Source-switch teardown timeout never leaves two active source owners; generic delivery and stable OAuth routes survive the failure.
- Notification forwarding defaults off, summary-only opt-in works, capture denial leaves CPU operational, and oversized D-Bus input is bounded before allocation.
- Constant valid traffic/repeated slot release cannot postpone an unapplied-control deadline forever; credit-controlled replay converges after pressure clears.

Review baseline on 2026-10-11 at `b30b91f`: 529 relay tests, five native firmware suites, 20 shared protocol vectors and 18 root-tool tests passed.
Sanitizer/ESP32/container/live/physical checks were not rerun in that review. `PLAN.md` was the only untracked source document; no runtime changes were made.
Known inventory chores: `CLAUDE.md` points to absent `AGENTS.md`; public docs link to absent `tasks/` reports; dependency exceptions in `tools/relay_dependency_exceptions.json` expire 2026-10-25.
Reassess those dependency exceptions from source/audit evidence before release; do not mechanically extend expiry or fabricate missing historical reports.

Execution-only blockers may include missing hardware, env path, OAuth consent, trusted mobile HTTPS, actual desktop monitor permission, OBS connection or camera/user observation.
Continue independent code/tests/docs while blocked; report exact blocked scenario and needed evidence. Do not substitute a mock for a claimed real-device/live pass.
Existing section 8 scoped testing authorization remains bounded by verified target/scene/resource ownership and the active user's instructions.

### 14.13 Source grounding for selected integration choices

- [Tapir Netty direct style and SSE](https://tapir.softwaremill.com/en/latest/server/netty.html#server-sent-events); local 1.13.31 jar inspection confirmed `serverSentEventsBody`, `OxStreams` and SSE encoding support. End-to-end timeout/lifecycle compatibility still needs the early spike.
- [Freedesktop notification methods](https://specifications.freedesktop.org/notification/latest-single/#protocol) and [D-Bus BecomeMonitor](https://dbus.freedesktop.org/doc/dbus-specification.html#bus-messages-become-monitor): method capture and privileged monitor semantics underpin the adapter; they do not establish local permission.
- Selected package versions were verified against [dbus-fast](https://pypi.org/project/dbus-fast/5.2.0/), [HTTPX](https://pypi.org/project/httpx/0.28.1/) and [Hatchling](https://pypi.org/project/hatchling/1.32.4/) release metadata on 2026-10-11. Verify availability/security and lock hashes when installing; a justified patch correction is an engineering decision, not another preference round.
- License texts/policy references: [MIT](https://opensource.org/license/mit), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) and [CERN OHL variants](https://ohwr.org/licences/). These selections do not relicense third-party works.
