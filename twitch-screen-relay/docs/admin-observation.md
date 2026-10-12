# Canonical admin observations and SSE

The production relay mounts `GET /api/v1/admin/snapshot` and
`GET /api/v1/admin/events`. Both use the existing private-network browser/Host
boundary without management credentials. Snapshot responses and feeds are
non-cacheable. Firmware observations are not proof of physical LCD pixels.

## Snapshot authority and bounds

The hub captures publication, durable display/source fences, CPU/weather readiness,
retained notification metadata and device observations. Refresh is independent of
the lossy EventBus. Monitoring joins must match the captured epoch, source generation
and desired mode/channel. Local projection construction runs in the settings worker's
serialized acceptance order; a monitoring mutation cannot install a newer hub fence
between the monitoring read and its hub join. Encoding and socket emission run after
that local capture releases acceptance. A genuinely superseded epoch capture still
fails closed rather than combining generations.

A pending SSE wake may predate a source switch. It recaptures current authority
instead of joining that stale row to newer monitoring. Same-epoch duplicate or
regressive wakes emit no new frame. Reading the latest hub capture does not refresh
the hub or create a self-triggered feed loop.

Canonical responses contain display, monitoring, sources, devices, notifications,
charts and limits. Encoding is checked against 512 KiB before HTTP delivery.
Notification previews preserve at most 160 Unicode scalar values per field, with
at most 80 retained records. The projection does not change delivery replay capacity.

At most 64 device identities are retained. Connected devices take precedence over
historical identities. Disconnect marks observations historical immediately;
retention expires after the original fifteen-minute deadline, never renewed by a
read. Reconnect replaces the same device identity; epoch reset discards old-domain
history. Unknown, unsupported, fresh, stale and historical reports remain distinct.
An unavailable FIFO is null, not an inferred empty queue. Report freshness uses its
calibrated observation lower bound, not time since receipt.

DESK transport counts come from its actual writer lanes. Classic transport counts
come from tracked queue admissions and writer removal. They count frames waiting
to write, not firmware pending cards or kernel bytes. Reserved events are cumulative
connection admissions, not rendered-card counts. Historical responses report zero
current queued transport frames; last-known firmware state remains explicitly
historical.

## Chart observations

Hub-owned chart history uses five-second buckets and at most 180 buckets per series.
At most 16 device identities are charted, selected connected-first, then by most
recent observation and deterministic device-ID tie break. Status remains available
for all admitted identities; `chartAvailable` identifies the smaller chart subset.

Notification arrival buckets use authoritative sequence changes, not lossy bus
counts. Viewers and chat rate use observed STATS; unknown/unavailable or old samples
become gaps. Twitch source-generation changes reset only Twitch chart series.
Device series contain reported pending depth and observed expired/suppressed/refused
counter deltas. Boot/connection boundaries produce gaps instead of fabricated
counter deltas. Counter coalescing can only establish observed deltas, not precise
individual disposition times. CPU/weather historical charts remain deferred.

Source/card expiry, report stale boundaries, historical eviction and chart buckets
are owned observation deadlines. The worker recaptures through the actor when a
deadline is reached. This is not browser API polling and never renews telemetry.

## Streaming lifecycle

The assembly carries typed OxStreams requirements without capability casts.
Each SSE connection atomically registers and captures its initial hub observation.
It starts with a complete canonical snapshot and then emits coalesced complete
`snapshot` or `reset` events. IDs are `<relayEpoch>:<revision>`. Revision gaps and
new epochs use reset events; Last-Event-ID does not promise history replay.

Limits: eight feeds globally, two per normalized remote address, one latest pending
capture per subscription, four snapshots per second and a ten-second pending-write
handler. Heartbeat comments run every fifteen seconds. `X-Accel-Buffering: no` and
`Cache-Control: no-store` are emitted. Forwarded client identity is used only from
an explicitly trusted immediate proxy; other forwarding remains rejected by the
existing boundary.

Only an accepted canonical SSE GET response promotes the connection's inbound-idle
handler. Ordinary header/body deadlines and boundary checks remain installed.
Stream completion restores ordinary idle protection. Further pipelined requests
on a live stream close the connection. Channel close, stream cancellation, failed
preparation and hub shutdown release observer admission.

## Executable checks and remaining acceptance

From `twitch-screen-relay/`, after building the assembly:

    python3 tools/check_admin_runtime.py --assembly out/assembly.dest/out.jar --evidence /absolute/private/evidence

Add `--slow-reader` for real unread kernel-window pressure and `--native PATH` for
actual native classic-pressure/DESK/reconnect pairing. `--browser-executable PATH`
adds the test-only Playwright panel gate; use the repository's pinned browser test
environment and reviewed executable. No test accesses the board or providers.

Actual Main execution has exercised canonical schemas, complete initial/publication
and source-expiry snapshots, admission/release, a feed beyond sixty seconds without
inbound application traffic, heartbeat comments, real unread socket pressure and
actual native application pairing. Unit tests cover projection bounds, generation
joins, chart gaps/resets/counters and streaming lifecycle protection.

Complete C4 acceptance still needs independently verified final producer correlation
and broader epoch/reset/coalescing/resource and monitoring-race gates. Actual
certificate-verified Compose proxy streaming is exercised below. The initial
display-PATCH 503 reliability observation from wave 12 remains unresolved; these
changes do not claim its repair.

## Installed panel and browser evidence

The production assembly now serves the vanilla panel at `/` and exact versioned
CSS/JavaScript routes under `/admin/assets/v1/`. The root route matches only `/`;
unknown paths remain 404 and APIs/Swagger remain independently reachable. Assets
retain correct MIME types, nosniff, same-origin CSP, no-referrer and no-store.
The earlier loading failure is retained: the pathless HTML route shadowed both
assets with HTML. Actual Netty routing and real Chromium now exercise the repaired
mount; removing nosniff was neither necessary nor permitted.

Current software checks cover 320/768/1024/1440 CSS-pixel widths, keyboard focus,
44-pixel targets, reduced motion, all four display choices with remembered
preference, monitoring Apply, numeric CPU/memory and pushed expiry, stored XSS-safe
text, no periodic API fetch, explicit single test-card POST, and two-tab admission
with third-feed rejection. These are production-origin browser checks, not physical
phone/tablet, provider-consent or LCD evidence. Queue presentation uses the canonical
report authority; no queue is reconstructed from ACK/history. Missing calibration
keeps application/FIFO fields null, including an otherwise empty admitted report.

Actual final Main and rebuilt documented Compose pair the epoch-scoped native
application through mandatory classic-pressure/DESK/same-epoch-reconnect scenarios.
Certificate-verified Caddy checks exercise exact asset MIME, canonical complete
snapshots, stripped forged client identity, per-client feed admission, coalesced
revision-gap reset, original CPU expiry, source-generation/chart reset and an
inbound-silent feed beyond sixty seconds with heartbeat comments. The test CA stays
local to the runner; no host/client trust is installed and named token/CA volumes
are retained. The Compose browser gate uses direct loopback, not physical HTTPS
mobile consent.

Run the installed proxy gate from `twitch-screen-relay/`:

    python3 tools/check_admin_https.py --evidence /absolute/private/evidence

Use the pinned test-only Playwright environment when adding `--browser-executable
PATH`; add `--native PATH` for actual native pairing. This runner builds the checked-in
Compose/private-HTTPS overlay using isolated ports and named resources, then verifies
container/network/listener cleanup. It never accesses hardware or provider grants.

Complete C4/C5 acceptance still requires final independently pinned producer-to-panel
numeric/queue correlation, broader monitoring/reset/race/resource/browser failure
coverage and the required physical/mobile/provider gates. The historical display
PATCH 503 remains unexplained. No complete slice or product acceptance is implied.

## Concurrent captures and browser recovery

Run the installed concurrency checker from this component directory:

    python3 tools/check_admin_concurrency.py --assembly out/assembly.dest/out.jar --evidence /absolute/private/evidence

It starts a test-owned credential-free Main, opens two actual feeds, and concurrently
performs thirty monitoring edits, sixty partial display edits and sixty canonical
reads. It checks complete snapshot schemas, epoch/source/chart joins, increasing
feed revisions and final accepted intent/CPU convergence. Every response retains
its actual status, safe synthetic body and elapsed time; no mutation is retried.
Readers and listeners must close at cleanup. This finite run does not establish the
cause of the older PATCH 503 or prove arbitrary overload/suspend behavior.

Optional `--browser-executable PATH --native PATH` runs the pinned Chromium checks
and actual native application FIFO against the same Main after closing both feeds.
The browser gate exercises missing-configuration rejection without mutation, browser
offline/online events, unavailable canonical readback, one failed test-card attempt
without automatic retry, fresh pushed CPU recovery and 200% CSS zoom. Frozen source
and chart labels explicitly say that the last complete snapshot is not current.
Browser network emulation and CSS zoom are software tests, not a physical tablet,
mobile consent, OS-wide outage or complete accessibility audit.

The panel tracks epoch transitions separately from same-epoch revision advances. A
canonical GET can adopt a new epoch even if old-epoch pushes arrived during the
read. That adoption closes and retires the old subscription before opening a new
one; queued old first-snapshot, reset and error callbacks cannot regain authority.
A feed epoch transition rejects a delayed foreign-epoch GET. Epochs are opaque
identities, not numerically ordered values. Same-epoch regressive events are ignored;
duplicate revisions do not renew observation lifetimes.

Source remaining freshness and firmware report age also expire locally using
monotonic elapsed time since the last accepted complete snapshot. One deadline
timer targets the earliest source or report boundary, independently of the feed
health watchdog. A stalled but open feed can remain connected while measurements
and queue observations become stale. Retained numbers remain explicitly last-known;
missing calibration is unknown, not fresh. No deadline callback polls an API or
renews a captured lifetime.

From this component directory, run the shipped-module deterministic regressions:

    node --test tools/tests/panel_authority.cjs
    node tools/tests/panel_epoch.cjs

Use the pinned test-only Playwright environment for actual browser scheduling:

    python tools/check_admin_authority.py --assembly out/assembly.dest/out.jar --evidence /absolute/private/evidence --browser-executable /absolute/pinned/chromium --native /absolute/native/program

This runner restarts test-owned Main processes and delays actual decoded canonical
response callbacks and real SSE events. It verifies both GET/feed race directions,
retired first/reset callback rejection, legitimate new subscriptions, and local
CPU/weather/report expiry while delivery is held open. It then releases real
updates and verifies recovery. No snapshot or measured value is fabricated; no
request deadline or CSP is relaxed. This is scheduled software transport evidence,
not a machine-suspend, physical phone, provider or LCD acceptance claim. The full
contrast audit and broader failure/resource matrix remain pending.
