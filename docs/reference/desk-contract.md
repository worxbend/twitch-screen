# DESK/1 HTTP and application contract

Status: normative target contract, materialized for A1. This document does not claim
that production routes, desktop capture, device rendering or release acceptance work.
The unchanged classic wire contract and negotiated extension are specified in
[PROTOCOL.md section 20](../../twitch-screen-firmware/docs/PROTOCOL.md#20-desk1--complete-additive-profile-normative).

## Authoritative artifacts

| Artifact | Interface |
|---|---|
| `protocol/desk-v1-http.schema.json` | JSON Schema 2020-12 `$defs`, strict request/response/storage DTOs |
| `protocol/desk-v1-openapi.json` | OpenAPI 3.1 standalone target; same schemas, no management authentication |
| `protocol/desk-v1-http-examples.json` | Named `{schema,value}` examples checked against schemas |
| `protocol/desk-v1-layouts.json` | Exact codes, lengths, field names/types/offsets; packed LE |
| `protocol/desk-v1-offsets.md` | Complete normative byte tables, including all nine report entries |
| `protocol/desk-v1-vectors.json` | Additive `{name,record,flags,fields,hex}` fixtures; complete frames |
| `protocol/desk-v1-vectors.tsv` | Same vectors for independent C++/Scala probes |
| `tools/check_desk_contract.py` | Stdlib strict parser/schema/fixture checker and semantic reference helpers |
| `tools/tests/test_desk_contract.py` | Independent contract tests; not production peer acceptance |

Legacy V1–V20 and their existing Scala/C++ copies MUST remain byte-for-byte unchanged.
`protocol/legacy-vector-preservation.json` additionally pins the entire legacy vector
section hash. Peer owners integrate actual runtime codecs with additive fixtures;
contract probes do not import either implementation. OpenAPI here is not a substitute
for checking eventual generated production Swagger/JSON codec parity.

Schema vocabulary uses `x-maxUtf16CodeUnits`, `x-validUnicode`, and `x-nonblank` because
ordinary JSON Schema maxLength counts Unicode scalar values, not JVM UTF-16 code units.
Implementations MUST enforce these assertions in addition to standard JSON Schema.
Examples are illustrative public synthetic content, never real desktop notifications.

## HTTP boundary and rejection ordering

All paths below are relative to `/api/v1`. JSON is camelCase. Millisecond duration
names are explicit. Request JSON MUST reject unknown fields, duplicate keys (including
nested keys), explicit null, invalid Unicode, non-finite numeric values, enum case
errors and body overflow before mutation. No implicit string-to-number coercion.
Booleans are not numbers. Integer-valued JSON numbers are integers regardless of
textual exponent notation. Optional REQUEST fields are omitted, not null; optional
RESPONSE measurements are explicitly null when unavailable. Zero is a measurement.

Ordinary relay request-body ceiling remains 64 KiB; persisted state and companion
outbound requests have separate 16 KiB ceilings. Companion must count actual encoded
UTF-8/JSON escape bytes: two individually valid maximum text fields may exceed its
smaller outbound limit. Notification fields count UTF-16 units, source labels count
Unicode scalars, wire fields count bytes; these are deliberately different limits.
UTC metadata uses valid RFC 3339 `YYYY-MM-DDTHH:mm:ss.SSSZ` calendar timestamps.

Rejection sequence is boundary/Host/Origin/content-type/body/JSON/schema validation,
then serialized domain validation and capacity checks, then commit/publication. For
otherwise valid input, validate supplied deadline epoch before deadline age; validate
both before reserving an accepted POST sequence or replacing a telemetry slot.
Rate limiting may reject before clock validation, but MUST precede any mutation.
Do not expose private content in errors.

| Status | Stable code | Mutation guarantee / client action |
|---|---|---|
| 400 | invalid_request | Schema/domain error; unchanged; fix input |
| 403 | browser_boundary_rejected | Host/Origin/Fetch-Metadata rejection; unchanged |
| 409 | clock_epoch_changed | Supplied epoch differs; no publication/replacement; recalibrate original local deadline |
| 409 | configuration_missing | New live monitor request lacks app configuration; unchanged |
| 410 | expired | Effective producer deadline already passed; no publication/replacement; drop |
| 413 | body_too_large | Body rejected before DTO/publication |
| 415 | unsupported_media_type | JSON mutation without supported JSON media type; unchanged |
| 429 | rate_limited | No publication/replacement; `Retry-After: 1`; bounded backoff if original lifetime remains useful |
| 503 | safe operation-specific code | Exhaustion/storage/lookup/teardown failure; never infer all 503 outcomes are nonmutating |

Existing error shape is `{error: string}` with optional additive stable `code`.
An uncertain persistence rename/durability result requires storage/GET reconciliation;
it is not a definitely unchanged 503. Proxy errors, malformed/truncated responses,
write/read timeout or connection loss after possible send are ambiguous POST outcomes.
Do not automatically resend an ambiguous notification: there is no deduplication key.
PUT may retry only newest still-fresh observation with original deadline. A successful
HTTP response proves accepted state/publication, not device application or pixels.

### Private-network boundary

No management login, accounts, Basic/Bearer requirement or producer tokens. Anyone
who can reach this service can control/read it. Trusted LAN/VPN/firewall is the access
boundary; never expose admin publicly. Native bind defaults loopback, Compose host
publishing defaults `127.0.0.1`. Non-loopback access requires explicit allowed Host
and origin authorities including ports. Reject duplicate/malformed Host, userinfo,
wildcards, null/unlisted/mismatching Origin and cross-site Fetch-Metadata. CLI/companion
without browser headers remain allowed after Host validation. No wildcard CORS.

Apply Host protection to assets/docs/feed/callback too. OAuth callbacks bypass only
normal cross-site Origin checks, retaining state/signature validation. Authorize GET
remains management. Twitch/OBS provider authentication is unchanged. Nonempty obsolete
RELAY_HTTP_AUTH_* or HOCON auth settings fail startup naming keys, not values.
Forwarded scheme/client identity is accepted only from exact trusted immediate proxy,
with configured public origin and no ambiguous chains. Remote mobile OAuth needs a
trusted private HTTPS hostname and exact registered redirect; loopback-only consent
is not mobile acceptance. Admin CSP uses self assets/connect, no plugins, no base,
no framing; nosniff/no-referrer. No credentials/config dump in response/feed/assets.

## Routes, fields and defaults

| Route | Required request fields | Optional/defaults | Bounds |
|---|---|---|---|
| POST /notifications | type, title, body | ttlMs=8000; expiresInMs=60000; deadline omitted | title nonblank trimmed; body may be empty; each <=4096 UTF-16 units; hold 100–30000; waiting lifetime 1–600000 ms |
| PATCH /display | At least one field | twitchDisplayEnabled; preferredDashboard | boolean; generic/weather/cpu; omitted unchanged, null/empty invalid |
| PUT /weather | sourceId, location, temperature, condition | unit=celsius; humidityPercent; observedAt; freshnessMs=600000; deadline | freshness 1000–1800000; C −100…100 or F −148…212; integer humidity 0–100 |
| PUT /cpu | sourceId, host, cpuPercent | memoryPercent; temperatureCelsius; observedAt; freshnessMs=10000; deadline | freshness 1000–60000; percent 0–100; temperature −20…150 C |
| PATCH /twitch/monitoring | At least one field | mode; channel | disabled/simulated/live; lowercase `[a-z0-9_]{1,25}`; reject URL/empty/null |
| GET /clock | None | None | non-cacheable relayEpoch/monotonicMs sampled during exchange |

Notification type vocabulary remains info/message/warning/alert/stream_start/
stream_end/follow/sub/gift/raid/chat/bits. HTTP publishers ALWAYS get external origin,
even with Twitch-looking kind/title; no producer-set provenance/generation fields.
SourceId is 1–31 ASCII `[A-Za-z0-9._-]`. Location/host are nonblank <=128 scalars.
One global latest slot per source type; serialized acceptance order wins. Identity
is diagnostic, not authorization. Configure one producer per slot; no discovery,
lease, provider collection, geolocation, multi-source arbitration or telemetry FIFO.

Weather conditions map to wire codes 0–8 in order: unknown, clear, partly_cloudy,
cloudy, fog, drizzle, rain, snow, thunderstorm. Temperature and CPU/memory round to
0.01, half away from zero AFTER range validation. Optional CPU temperature is accepted
and queryable; device CPU widget uses memory as its ONLY secondary metric.

HTTP notification hold rounds UP to 100 ms and the response reports effective hold:
101 becomes 200, never wire zero, including external/manual Chat. Waiting expiry
remains independent; 1 ms may expire before any device starts it. DESK publication
requires an explicit hold of 1–300 deciseconds, with exactly one exception: native
Twitch Chat (`kind=Chat`, `origin=Twitch`) sends `ttl_ds=0` to delegate adaptive pacing
to the receiver. The firmware holds it for 2500 ms with no waiting cards, 500 ms with
one or two, and 250 ms with three or more; backlog changes never extend its original
hold. Non-chat Twitch holds remain 6/8/10 s. Zero for any other kind/origin and holds
above 300 deciseconds remain rejected at relay publication boundaries. Immutable
classic wire vectors retain their historical explicit Chat holds; zero already means
receiver default in the shared EVENT base, and the DESK firmware decoder accepts it.
Tightened former HTTP hold maximum 6553500 to 30000 ms is an explicit breaking
migration, not legacy wire change. Whole entrance/hold/exit occupancy exceeds hold
for animated cards; active report estimates all of it.

Global POST and each telemetry PUT use 10/s, burst 20 token buckets; excess 429,
Retry-After 1, no mutation. Device snapshot publication coalesces to <=5/s/source;
acceptance time, not coalesced-send time, establishes original freshness deadline.
Telemetry writes never select dashboard/override or create card events.

### Deadline and calibration

Optional `deadline={relayEpoch,monotonicMs}` is absolute RELAY monotonic time, not
Unix milliseconds. Effective deadline is `min(supplied, receipt+validatedDuration)`.
Wrong epoch is 409, expired at `now >= deadline` is 410, without mutation. Omitted
producer deadline gives receipt-based lifetime; ordinary producer age before receipt
is then not guaranteed. Companion MUST calibrate and supply original capture/sample
deadline, with no unaged fallback. Successful write headers `X-Relay-Epoch` and
`X-Relay-Monotonic-Ms` sample within the same exchange and identify that accepted epoch.
If epoch changes between commit and response, do not attach new-epoch calibration to
old-epoch acceptance; omit calibration headers and force fresh GET /clock. A cached
sample is not a valid header calibration. GET /clock uses Cache-Control: no-store.

HTTP monotonic integer schema uses exact JSON-safe range 0–9007199254740991; opaque
u64 identities use hex strings. Wire counters retain their explicit u64 widths.
Durations remain <=30 minutes. Signed checked clock differences/conversions must
never wrap. Wire clock/drift/receipt/residence rules are normative in PROTOCOL §20.3.
For d0=1000,d1=1100,r=5000,E=65000, device conservative deadline is 60889.
For local L=61000 using that exchange, companion sends relay deadline 64790.
At relay receipt 65000 this request is expired, NOT renewed for 60000 more milliseconds.

`observedAt` is informative producer metadata, defaulted to relay receipt and labeled
producer/relay_receipt. NEVER derive freshness from wall time. GET source response is
`{relayEpoch,revision,status,reason,receivedAt,observedAtSource,remainingFreshnessMs,data}`.
Missing revision is 1 (explicit initial state), no timestamps/data, remaining zero.
Accepted replacement increments revision. Fresh/stale reason is fresh/expired;
clock_unavailable is a conservative invalidation reason. Retain last bounded accepted
payload even stale; missing optional measurements in data are null. data includes
normalized unit/freshness/observedAt, not producer calibration deadline. Missing/stale
wire payload is zero-filled, independently of HTTP diagnostics. Remaining is max(0,
originalDeadline-now), never original duration on reconnect.

## Control, monitoring, persistence and application

Display GET/PATCH returns `{relayEpoch,revision,twitchDisplayEnabled,preferredDashboard,
weather,cpu}`. Source summaries carry revision/status/reason/remainingFreshnessMs,
not full duplicate payloads. PATCH merges fields atomically against current settings
in serialized acceptance order. Equivalent write is no-op: no revision, disk write or
source rebuild. Concurrent separate-field writes must not erase one another. Desired
state success is not applied-device proof. Device report can apply control while an
active card remains visible; do not wait 30-second hold before the 5-second apply budget.

Base selection beneath active card: relay down uses disconnected orbit; linked but
unsynchronized uses connected orbit; ready Twitch override uses existing Twitch
layout; otherwise fresh selected weather/CPU; otherwise connected orbit. Data freshness
and relay readiness are independent of provider grant/health. Hidden telemetry remains
accepted/latest. Recompute before every reveal and render after state/time changes.
Every already active card finishes; waiting Twitch cards suppress terminally outside
override/current episode/generation or on relay loss. Generic FIFO remains independent.

Monitoring GET/PATCH returns `{relayEpoch,revision,settingsSource,desired,effective,
transition,sourceGeneration,resolvedChannel,grantCompatibility,error}`. settingsSource
is persisted/startup; mode/channel pairs may have null channel only in read/storage
state. Transition idle/applying/ready/failed is explicit. ResolvedChannel is null or
{id,login,displayName}. GrantCompatibility is `{status,features,missingScopes}` with
status not_required/missing/expired/mismatched/partial/usable and per-feature eligibility.
Feature names public_stream/public_chat/followers/subscribers/eventsub identify bounded
observations, not a promise every transport supports every restricted feature. Error
is null or existing error shape plus safe code. Wrong grant remains stored/refreshed
but cannot authorize a different broadcaster. Public monitoring may continue.

Resolve live login under 3000 ms budget BEFORE acceptance; failure preserves old
settings/source. Missing app config rejects new live PATCH 409 configuration_missing.
Disabled/simulated mode permits format-validated unresolved stored channel. Simulated
without channel uses effective demo identity, not fake desired broadcaster/grant.
After 200 desired-state acceptance, source application is asynchronous. Bound ordinary
application work to 5000 ms; never hold response waiting for Twitch health.

One versioned data/runtime-state.json (RELAY_RUNTIME_STATE_FILE override) stores only
schemaVersion=1, both display fields, monitoring mode/channel. Separate OAuth token
storage stays unchanged. No file means Generic/override false and explicit startup
monitor config else disabled/null. Valid saved desired state wins env/defaults.
Saved live without app config may start desired live/effective disabled failed with
configuration_missing while generic operation works. Corrupt/unsupported/present
unreadable state fails startup without overwrite/content logging.

Persist <=16 KiB using owner-only 0600 file/0700 directory, same-directory temp,
flush/fsync/rename/directory-sync where supported. Bounded serialized settings worker
has 16 pending commands; disk I/O outside hub. Publish only committed desired state.
Storage failure 503 may require reconciliation if rename outcome uncertain. Revisions,
source generations/episodes u32 start at 1, never wrap; exhaustion rejects mutation until
orderly fresh-epoch restart. Availability/history/reports/charts/counters remain RAM-only.

Source switch commits desired settings and authoritative hub generation fence, resets
Twitch stats/metadata/chart readiness and retires old-generation active Twitch alerts,
THEN cancels/joins old resources. Teardown <=5000 ms; on failure do not create competing
source. New application <=15000 ms before failed/degraded state, bounded recovery
1 s exponential to 30 s with <=25% jitter. Never silently revert accepted channel.
Fence `(relayEpoch,sourceGeneration)` at consumers and again at hub; lossy EventBus
cannot be sole carrier of reset/control/report acknowledgments. Stats timer folds
recheck authoritative generation. Stable application-scoped OAuth owner/routes, generic
cards/weather/CPU/listeners/event sequence survive switch. Twitch alert rules depend
on effective runtime generation, not startup mode; independent device/failure timers
survive. Historical source/alert entries stay generation-labeled.

## Admin read model, SSE and bounds

GET /admin/snapshot returns a complete bounded immutable projection:
`{relayEpoch,revision,capturedAt,display,monitoring,sources,devices,notifications,charts,limits}`.
The schema fixes exact nesting; source/device observations remain separately aged.
Do not pair new-channel identity with old-generation stats; omit/gap unavailable
components. No claimed global physical instant across asynchronous devices.

DeviceObservation includes connection/boot/capabilities/firmware metadata, desired and
applied display revisions, report revision/status/observation-age bound, active/pending
identities, transport pressure, saturating disposition counters and chart availability.
Unknown queue is null, known empty queue is []; inactive is null. reportStatus is
unsupported/unknown/fresh/stale/historical. Disconnect is historical immediately;
age upper bound >15000 ms stale. Missing calibration/session mismatch is unknown.
Old retained identities may exceed current cursor/latestSeq and retain original epoch.
ACK is enqueue-only; processed cursor is terminal handling; report is firmware lifecycle;
none proves pixels. Preview metadata is optional correlation, never reconstruct queue
as published-minus-ACK. No cancel/clear/reorder/priority/forced replay endpoints.

SSE GET /admin/events uses event types snapshot/reset and IDs `<relayEpoch>:<revision>`.
Every subscriber starts with complete snapshot; atomic register+capture prevents races.
Subsequent events are coalesced COMPLETE snapshots, not deltas. UI's initial GET is
superseded by feed first snapshot; reject older revisions in same epoch, reset on new
epoch/history gap. Last-Event-ID replay is not promised. Heartbeat comments every
15000 ms are transport-only, carry no revision ID; no API setInterval polling.
Reconnect 1–30 s with <=25% jitter; close subscriptions on teardown.

Limits: 8 global feeds, 2/normalized remote IP; 1 latest pending snapshot/client;
<=4 snapshots/s/client; <=512 KiB encoded snapshot; 10000 ms pending-write deadline;
existing 128 total HTTP connections and 64 device-session cap remain. Reject ninth
feed with 429. Slow browsers never block ingress/device writers. Cache-Control no-store,
no compression/proxy buffering. Only explicit trusted proxy hop supplies remote identity.
Release subscriptions on disconnect/cancel/failed write/stop.

Pinned Tapir 1.13.31 ServerSentEvent has no comment field. Demonstrated correction:
use bounded typed SSE-frame adapter over OxStreams streamTextBody(TextEventStream),
retaining existing ServerSentEvent encoder for data/reset and emitting raw bounded
`: heartbeat\n\n` comments. Keep capability-bearing endpoint types, no unsafe casts.
Promote inbound-idle handling only AFTER validated successful SSE response. Comments
do not reset Netty inbound ReadTimeoutHandler. Keep normal request/body deadlines;
replace only dedicated validated feed connection timer with streaming/write lifecycle.
Close feed connection at end (or restore normal defenses before reuse); explicitly own
binding.stop(). This is not a WebSocket/polling/product-scope change.

Charts: 15 min/5000 ms buckets/180 entries; 16 charted device IDs selected connected
first then most recently seen, deterministic ID tie break. Status contains all <=64
admitted sessions and explicit uncharted flag. Per device queue depth and expired/
suppressed/refused deltas; global arrivals/viewers/chat rate. Compact
`{startAt,bucketMs,values:[number|null]}`; null gaps, never interpolate missing zeros.
Twitch generation resets its series; source boundaries visible. Disconnect status
expires after 15 min. Notification previews newest 80, each title/body <=160 scalars.
CPU/weather history deferred; current accepted values required. Snapshot size checked
on encoding, not merely estimated from individual limits.

## Text, companion and feasibility constraints

Printable ASCII LCD policy initially; firmware MUST NOT advertise UTF-8 font coverage.
HTTP preserves valid original Unicode for diagnostics. Relay sanitizes C0/DEL/C1
controls to spaces, folds using existing section 9 mappings, collapses folded spaces,
then byte-truncates with `...` without splitting codepoints. Labels <=31 content bytes,
actor/title <=47, body <=95. C/F suffixes need no degree glyph. Preserve bodyless card
semantics. Existing display-name viewer fallback remains; empty body remains empty.
No HTML/scrolling/client images/actions or priority/preemption extension.

Companion is Python >=3.12, dbus-fast 5.2.0, HTTPX 0.28.1, Hatchling 1.32.4; no silent
library substitution. Use two separate bounded D-Bus connections: monitor for narrow
Notify methods, ordinary owner/status. Do not own/replace notification daemon or alter
bus policy/root. Captured Notify means submitted request, not daemon acceptance/popup.
CPU collection continues if monitor denied. Portal/native-only notification paths are
outside claim; Ubuntu 24.04/GNOME 46 Wayland remains actual acceptance target, not pass.

Stock pinned decoder permits 128 MiB and greedy socket reads, insufficient for locked
bound. Complete D-Bus wire frame MUST be <=65536 bytes including fixed 16-byte header,
header fields, eight-byte alignment padding and body. Guard advertised envelope BEFORE
requesting remainder/allocation, including both connections' first Hello; authentication
lines separately bounded. Decode only bounded memory with pinned low-level codec in
isolated adapter, disable FD negotiation, fail capture closed. Bounded wire bytes alone
are not proof against internal array/signature/nesting amplification or CPU time.
Require fragmented/malformed length/signature/compiled-decoder tests and real guarded
private-bus auth/Hello/BecomeMonitor/Notify/owner-restart/denied-monitor/cleanup proof.
Monitor cannot send messages or auto-reply to captured calls. Replacement coalescing
stays disabled unless narrow return-ID correlation is actually proven (128 calls/5 s).

Forwarding defaults OFF; after explicit enable summary-only default, body separate
opt-in. Casefolded exact app filters are labels, not authenticated identities. No real
private message capture/logging needed. Urgency low/normal/critical maps info/message/
alert. Timeout -1/0 maps 60000 ms waiting lifetime; positive clamp 1000–60000. Hold 8000.
No remote cancellation from desktop close. Two HTTP workers maximum: 32 notification
FIFO entries/64 KiB encoded, expire-first/refuse-newest, 2/s burst4; CPU latest-only
single slot. Requests/responses 16/64 KiB, total 3000 ms/connect 1000 ms, no redirects,
TLS verification on, trust_env false. Retry only confirmed pre-send or canonical safe
rejection while original deadline remains; ambiguous POST drops. Stop joins <=5000 ms;
no service install/enable or real desktop capture during contracts work.

CPU samples /proc every 2000 ms (config 1000–60000): total deltas user+nice+system+
idle+iowait+irq+softirq+steal, no double guest; busy total-idle-iowait. Reject warmup/
reset/nonpositive/inconsistent deltas and >2x interval/suspend gaps. Memory physical
used=(MemTotal-MemAvailable)/MemTotal*100. Missing is absent/degraded, never fabricated
zero. Freshness max(10000,3*sampleIntervalMs) capped 60000; original measurement age
survives queues/retries, reconnect samples anew. Upload never selects CPU dashboard.

## Verification meaning and unresolved evidence

Contract checker/tests and independent C++/Scala probes check lengths/offsets, signed
LE fields, presence/zero semantics, text bounds, hold/rounding, clock conversion,
credit conservation, barrier ordering, retained identities and peer matrix. They do NOT
accept production integration or LCD operation. Actual runtime codec binding, full
TCP/HTTP/controller/LVGL tests, desktop service, mobile HTTPS, hardware/live and release
gates remain owned by their tracks/integrator.

Early real-socket relay SSE evidence ran 75005 ms without inbound application traffic,
retained ordinary read timeout, released reset client producer and stopped listener.
This proves selected Tapir/Ox feasibility only, not production boundary/proxy/feed limits.
The initial companion import failure has been resolved through approved pinned
installation. Contracts independently reran the isolated guarded private-bus suite:
all ten tests passed using dbus-fast 5.2.0's compiled decoder. Both connection roles
are guarded before authentication/Hello; exact 65536-byte frames pass and 65537-byte
frames fail. Fragmentation, internal extents/signatures, first-Hello attacks and
real-bus oversized traffic fail closed. Four synthetic Notify submissions matched
four normal fake-daemon deliveries, including owner replacement, without monitor
replies. A denied-monitor bus preserved the ordinary connection; owned buses and
sockets were cleaned up. This closes A1's isolated Notify feasibility gate, not E3/E4
or supported-desktop acceptance. Source and executable evidence remain private local
workflow artifacts; they are not dependencies of this public normative document.

The feasibility adapter additionally bounds authentication lines to 1024 bytes,
signatures to 255 bytes with 32 array/struct levels, runtime container depth to 64,
and structural validation work to 65536 nodes before compiled decode. These are
fail-closed adapter constraints, not a universal decoded-heap or wall-clock guarantee.
Highly nested or FD-bearing input is outside admitted capture traffic. Production
still requires structured deadlines/cancellation, bounded handoff, reconnection,
normalization/privacy, real CPU sampling, HTTP delivery and packaging. No missing
product preference was substituted for a demonstrated technical gate.
