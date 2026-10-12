# Linux desk companion

Implementation status: E3 foundations and E4 delivery are implemented and tested
against isolated synthetic D-Bus and HTTP peers. Production relay integration,
Ubuntu 24.04 / GNOME 46 Wayland compatibility, installed user-service operation
and LCD acceptance remain pending. Basic installed-client CPU/card upload and exact
readback have now been exercised against a disposable current-source relay. An
installed foreground daemon also continued accepted measured CPU uploads with
`monitor_denied` on a private synthetic bus; ordinary fake-daemon delivery remained
intact. The installed client also passed actual assembled-relay delayed receipt,
committed response loss without resend, restart/epoch recovery and private synthetic
Notify capture across a fake-daemon owner restart. These checks preserve ordinary
fake-daemon delivery, summary-only privacy and measured CPU acceptance. They do not
verify the real desktop, installed service, full production failure matrix or display.
Do not treat a passing package test as those acceptance
gates. Version 0.1.0 identifies this new package, not a product release.

The foreground daemon forwards supported Freedesktop `Notify` submissions and
samples aggregate host CPU plus physical RAM availability. CPU sampling continues
when scoped notification monitoring is denied or the notification daemon fails.
The client never selects a dashboard, changes Twitch settings or starts a stream.
Weather is supplied by other producers, not collected by this client.

## Build and test

The reviewed hash locks target CPython 3.12 on Linux x86_64. Other architectures
and Python wheel tags require a separately reviewed lock. Runtime uses Python
3.12+, dbus-fast 5.2.0 and HTTPX 0.28.1. Build backend is Hatchling 1.32.4.

From this component directory:

```sh
python3.12 -m venv .venv
.venv/bin/python -m pip install --require-hashes -r requirements.lock -r requirements-build.lock
PYTHONPATH=src .venv/bin/python -m unittest discover -s tests -v
.venv/bin/python -m pip check
.venv/bin/python -m hatchling build -t wheel
.venv/bin/python tools/check_package.py dist/twitch_screen_client-0.1.0-py3-none-any.whl
.venv/bin/python -m pip install --no-deps dist/twitch_screen_client-0.1.0-py3-none-any.whl
.venv/bin/twitch-screen-client check-config --config config.example.toml
```

Tests need `dbus-daemon`, a writable `TMPDIR` and the checkout's canonical
`protocol/desk-v1-http-examples.json`. The foreground-child outage test stops and
restarts only its private synthetic bus at the same endpoint. It proves measured
CPU uploads continue while capture is unavailable and normal fake-daemon delivery
resumes after reconnect, without changing the child's configuration.
The HTTP lifecycle tests also use real loopback sockets to verify that continuous
response bytes cannot extend the three-second total exchange deadline, a truncated
accepted POST is ambiguous and never retried, and CPU uploads continue while a
notification response remains unfinished. Cancellation closes both send workers.
These checks use synthetic peers, not the production relay.

Tests create private synthetic buses and
loopback HTTP listeners. They never connect to the real desktop bus, install a
service, change bus policy outside test-owned buses or capture personal messages.
Root CI should run these commands; workflow wiring belongs to the integrator.
The standalone wheel does not depend on test fixtures or workflow evidence.

For an offline dependency/notice inventory, retain the reviewed wheel downloads and
run `.venv/bin/python tools/check_dependencies.py --wheelhouse /absolute/path/wheels --output /absolute/path/dependencies.json`.
The tool checks every locked wheel hash and distribution version, then records
license declarations and bundled-notice hashes. Metadata and notice presence are
not a full vulnerability audit or redistribution-rights clearance. Preserve upstream
notices in installed dependencies; do not relicense them under the project's MIT
license. Runtime and build dependency inventories are labeled separately.

## Configure and run

Copy `config.example.toml` to
`$XDG_CONFIG_HOME/twitch-screen-client/config.toml`, or
`~/.config/twitch-screen-client/config.toml` when XDG_CONFIG_HOME is unset.
Choose a stable `source_id`, explicit trusted relay URL and optional human-readable
host label. Run as the logged-in desktop user, not root.

```sh
twitch-screen-client check-config --config /absolute/path/config.toml
twitch-screen-client run --config /absolute/path/config.toml
twitch-screen-client status --config /absolute/path/config.toml
```

`XDG_RUNTIME_DIR` must be user-owned and private. An advisory source-specific lock
refuses duplicate instances. SIGINT/SIGTERM cancels owned workers and closes bus
and HTTP connections; the service stop budget is five seconds. Local diagnostics
contain bounded counters/reasons, not notification bodies, relay URLs or secrets.
An old status file is historical, not proof that a process is running. The status
reader admits only user-owned private regular files, fixed reason enums, typed
nonnegative counters and bounded queue values. Duplicate fields, arbitrary content,
nonfinite values and missing observation times fail closed. Nonblocking opens reject
FIFOs without waiting for a writer. A held source lock plus an observation no older
than five seconds is required for `running`; future observations are stale with
unknown age, never clamped to fresh. These are local diagnostics, not relay health
or physical acceptance evidence. Status snapshots use exclusive, randomly named
0600 staging files in the runtime directory followed by atomic replacement. Failed
serialization or replacement preserves the previous snapshot and removes only the
writer's own staging file; predictable `.new` members are never opened or truncated.
Shutdown attempts both owned HTTP client closes concurrently, so one failed or
stalled close does not skip the other. The four-second close budget still propagates
failure; signal handlers and the terminal local snapshot are cleaned up even then.
These injected shutdown-failure tests do not prove installed-service acceptance.
Signal registration is inside the same ownership scope: if either startup registration
fails, the runtime closes both HTTP clients, removes only successfully installed
handlers and writes stopped local status before propagating the error. No capture or
send worker starts after partial signal setup.

The relay must implement the canonical GET `/api/v1/clock`, notification POST and
CPU PUT with producer deadlines. Missing calibration prevents uploads; the client
never falls back to renewing old content at receipt. Legacy relays lacking these
routes are not supported by this client. See the authoritative
[desk contract](../docs/reference/desk-contract.md).

Success means accepted canonical state, not visible pixels. POST acceptance must
preserve the submitted body exactly and title after the relay's edge trimming
(code points U+0000–U+0020 only; Unicode spaces are not implicitly removed). A
valid-shaped response changing card text remains ambiguous, does not count as
accepted and is never automatically resent. The client also rejects
internally contradictory freshness status/reason/remaining age, age beyond the
accepted duration, and notification responses changing the requested waiting
lifetime or effective 100 ms hold quantum. Both the daemon and pairing checker
correlate CPU acceptance with the submitted canonical hundredths, optional absence,
freshness and observation provenance. A valid-shaped response with changed measured
values does not increment the accepted counter; it invalidates the matching
calibration and remains retryable. Normal notification delivery remains independent.
Invalid POST success remains ambiguous and is not automatically resent; these checks
do not prove production pairing. Malformed JSON or non-object write responses also
set `invalid_response` and invalidate only the calibration used by that request.
A late corrupt response cannot erase another worker's newer calibration. A later
eligible CPU upload recalibrates independently; the uncertain notification stays
dropped rather than being resent. Real loopback HTTP regressions exercise this
health/recovery behavior, not actual relay corruption or desktop acceptance.

## Explicit relay pairing check

The component checker uses the actual delivery/calibration code and installed
package. Read-only preflight performs only a canonical no-store clock exchange:

```sh
.venv/bin/python tools/check_relay.py --config /absolute/path/config.toml --output /absolute/path/relay-check.json
```

A passed preflight does not mean CPU/notifications or the display work. To exercise
uploads, target only a disposable relay you own, configured for disabled ingestion
with no devices attached. The following command replaces its CPU slot and publishes
one synthetic card; do not run it against a shared deployment:

```sh
.venv/bin/python tools/check_relay.py --config /absolute/path/test-config.toml --output /absolute/path/relay-pair.json --exercise --test-owned
```

The exercise samples actual `/proc` and performs one calibrated CPU PUT. Before
readback or any notification POST, it matches accepted CPU, memory and temperature
to the submitted sample using canonical 0.01 half-away-from-zero rounding. Omitted
optional measurements must be null; supplied measurements cannot become null.
Freshness and observation-time provenance must also match the submitted fields.
It then reads back the exact accepted CPU revision/data and publishes and reads back
one synthetic card by epoch/sequence/ID. Agreement between the relay's PUT echo and
GET alone cannot certify preservation of the submitted measurement.
It never retries POST, changes display settings, connects to the
desktop bus, installs services or claims pixels. Clock failure blocks all mutations;
no legacy receipt-aged fallback exists. Exit 2 means blocked pairing. Output excludes
URLs and response content and remains explicit about unverified production,
desktop and physical gates. The fixture tests validate this checker, not an actual
relay. Quota, sustained outage/backpressure, shared discontinuity reset and complete
companion-to-device acceptance remain separate gates.

## Actual runtime recovery check

From this checkout, use an installed companion interpreter, the current relay
assembly, Java and `dbus-daemon`. From the relay directory, build the assembly with
`./mill --no-server -j 1 assembly`. The recovery command starts only a disposable JVM with
disabled ingestion, fresh private state and dynamically selected loopback ports.
It never targets an existing relay or the real desktop bus:

```sh
.venv/bin/python tools/check_runtime_recovery.py --assembly ../twitch-screen-relay/out/assembly.dest/out.jar --evidence /absolute/private/path/recovery --test-owned
```

The harness copies the assembly into private scratch and records its SHA-256 so
both launches exercise the same artifact. A bounded fault gateway forwards actual
HTTP responses; it does not synthesize acceptance, clock or error responses. It
checks delayed card/CPU receipt returns 410 without replacing accepted state,
committed POST response loss publishes once without automatic resend, and relay
restart rejects an old epoch before recalibration accepts the original-age card.
Before restarting, the harness persists Twitch override with remembered CPU
preference and reads back fresh explicitly synthetic weather/CPU fixtures. It
exercises ordinary keepalive and explicit `Connection: close` ingress. After restart,
canonical GETs must retain both display fields under a new epoch while weather,
CPU and notification history are empty. Persisted intent is not persisted data
availability or proof that a device applied the selection.
A held committed POST proves measured CPU acceptance proceeds before the POST
response timeout. A forty-card burst admits exactly thirty-two cards, refuses
eight, and expires all pending cards without publication or retained queue bytes.
Actual CPU bucket exhaustion returns canonical 429, preserves revision/data and
allows only the replaced latest sample after backoff. Priming is bounded and
re-drains refilled tokens before the measured retry; it does not modify quotas.
If a token refills before the worker's first PUT, pressure setup may repeat at most
five times. Every primer response and setup attempt remains in private evidence;
the accepted quota scenario still requires an actual 429 followed by only the newest
original-age upload. The harness never retries a failed complete suite silently.
Two stopped-JVM scenarios exercise recovery with missing and previously valid
shared calibration. Each outage lasts at least 3.2 seconds by default; optionally
pass `--outage-ms 15000` for fifteen-second intervals. The argument accepts
3200–60000 ms and is checked before starting resources. Uncalibrated workers
attempt only clock exchanges and prune expired cards/CPU. With cached calibration,
requests reaching the fault gateway before upstream connection failure are ambiguous:
each attempted POST is dropped without resend, not labeled a confirmed pre-send
failure. Remaining cards and old CPU expire with zero retained queue bytes; idle
workers issue no health polling. Fresh work after restart rejects usable cached old
epoch calibration or refreshes expired calibration before any write. Both paths
reach exact measured CPU/card readback without stale publication. Request identities
and terminal counters independently account for all thirty-two admitted cards;
scheduling can change how many expire versus become ambiguous. Actual sixty-second
intervals have also passed. These are finite software evidence, not sustained
outage, soak, shared-discontinuity or system-service acceptance.

The client-facing gateway stays fixed; the restarted upstream uses fresh loopback
ports by default. Pass `--same-ports` to keep both original upstream listener ports;
a bind failure remains a blocked result rather than silently changing ports.
Same-port acceptance was independently verified against the integrator's final
wave-8 assembly. Earlier artifacts failed HTTP rebinding with `Address already
in use`; preserve those failures rather than treating all source versions as accepted.
Rerun the current artifact with `--same-ports` after relay changes.
The report records each JVM exit code, elapsed stop time and forced-kill outcome
separately from listener closure, and records scratch removal on blocked runs.

A foreground installed child then captures uniquely labeled synthetic Notify
submissions across fake-daemon owner loss/restart. Both ordinary daemon replies
and actual relay history are checked, bodies remain redacted, and real CPU/memory
sampling continues. It verifies SIGTERM exit, reaps owned children, closes owned
listeners and removes temporary state. The same installed child also survives an
actual relay restart without changing its PID or configuration. Fresh measured
CPU/memory must match forwarded new-epoch PUT payloads; the next synthetic Notify
must reach the ordinary daemon and appear exactly once in the new relay history.
Old accepted cards must not be resubmitted after restart. The same foreground
process is also exercised across a complete private synthetic bus outage and
same-endpoint bus restart. While capture is unavailable, at least two newly
measured CPU/memory uploads must match actual relay readback; capture/card counters
and retained history cannot grow or gain waiting lifetime. After bus recovery, the
next synthetic Notify must receive an ordinary daemon reply and reach relay history
once, with unchanged child PID/configuration and summary-only privacy.
This is foreground HTTP/private-bus
recovery evidence, not coordinated suspend reset or capable-device application.
A second foreground installed child uses a
separate private bus that denies scoped monitoring. Two synthetic submissions must
still receive ordinary fake-daemon replies while capture remains `monitor_denied`.
The child must make no notification HTTP attempt and preserve existing history,
apart from decreasing remaining expiry. At least two measured CPU/memory uploads
must continue; actual PUT payloads forwarded to the relay must match canonical GET
readback. Shutdown must exit cleanly and publish stopped local status without
notification content. The same unchanged configuration then starts another child,
which must acquire the released source lock, use a new PID, expose zero card/capture
counters and pending bytes, and upload newly measured CPU/memory. Its actual new
PUT payloads must match readback before SIGINT stops it with terminal local status.
No real user service is installed or enabled. The restarted denial child also
continues measured uploads when its private bus subsequently stops. Capture health
must change from `monitor_denied` to `unavailable`: a historical policy refusal
cannot mask a later transport failure. The same PID/configuration must remain alive,
with no notification HTTP attempts and exact newly measured CPU/memory readback.
This is private synthetic denial and
foreground restart evidence, not desktop permission or installed-service acceptance.
Private result/log files retain failures. CPU readback uses a bounded correlation
barrier because GET can observe a committed PUT before the gateway records its
completed response. Only an exact accepted epoch/revision/data match and the actual
submitted measurement qualify; local counters or equal numeric values are not proof.
The barrier wakes on recorded HTTP responses, never resends PUT, and retains raw
readback, payload, response and status observations on timeout or assertion failure.
Exit 0 means these named software scenarios passed, not full E3/E4, supported
Ubuntu/GNOME popup behavior, installed systemd service, negotiated device delivery,
LCD pixels or product acceptance. The checkout's `tests/test_transport.py` supplies
only the private synthetic bus fixture; the standalone wheel contains neither this
harness nor test fixtures.

A separate installed foreground-stop gate targets an owned assembled relay:

```sh
.venv/bin/python tools/check_runtime_stop.py --assembly ../twitch-screen-relay/out/assembly.dest/out.jar --evidence /absolute/private/path/stop --test-owned
```

This gate withholds the actual response to a committed synthetic notification POST.
A path-scoped fault cannot intercept CPU PUT. While POST remains unfinished, a new
measured CPU/memory upload must match the exact accepted epoch/revision/data and
submitted values. SIGTERM must stop the installed child within five seconds before
the response is released, without recording notification acceptance or ambiguity.
The ordinary synthetic notification daemon must still reply normally after the
companion stops. A same-config foreground restart must reacquire source ownership,
start with empty card counters/bytes, accept a new CPU sample and not resend the
previous committed card or renew its age. SIGINT then closes that child. Private
proof retains raw requests/responses/readbacks, stopped status, PIDs, timing and
cleanup on failure as well as success. This finite child/JVM/private-bus gate is not
systemd installation, supported-desktop popup proof, capable-device/LCD acceptance
or a claim about relay shutdown duration.

When the assembly mounts the canonical monitoring API, an additional owned gate
checks source switching without provider credentials:

```sh
.venv/bin/python tools/check_runtime_monitoring.py --assembly ../twitch-screen-relay/out/assembly.dest/out.jar --evidence /absolute/private/path/monitoring --test-owned
```

The installed CPU-only child keeps one PID/configuration through disabled-to-simulated,
a simulated channel change and simulated-to-disabled. Each transition requires a
new measured CPU/memory upload with exact accepted revision/data/submission readback.
Explicit HTTP synthetic generic cards remain external and independent; they are not
claimed as desktop capture. Monitoring never changes remembered display intent.
Source/episode fences may legitimately advance display control revision even when
that intent is unchanged. A live request without app configuration must return the
canonical 409 and preserve desired state/revision. The finite gate reconciles one
asynchronous transition after a new CPU observation; it does not add daemon API
polling, test live providers, certify all source races or accept admin/device pixels.
An older assembly missing `/api/v1/twitch/monitoring` stays blocked, not silently
replaced with an internal helper or an invented success response.

Add `--capture` to that command to exercise the same installed foreground process
with notification forwarding on a private synthetic bus. Each accepted monitoring
change triggers one uniquely labeled synthetic Notify submission. The ordinary fake
daemon must reply, and exactly one original-age external card must correlate with
its actual POST response and relay history. The child PID/configuration, summary-only
privacy and measured CPU/memory delivery stay unchanged across all three transitions.
Final stopped counters and history must conserve all three captured cards, with no
ambiguous send, retained backlog or duplicate publication. This option never uses
the desktop session bus, installs a service or claims popups/LCD pixels.

## Installed producer-to-admin push check

When the assembly mounts canonical admin snapshot/SSE and monitoring routes:

```sh
.venv/bin/python tools/check_runtime_admin.py --assembly ../twitch-screen-relay/out/assembly.dest/out.jar --evidence /absolute/private/path/admin --test-owned
```

The checkout gate subscribes to the actual same-origin SSE endpoint before launching
an installed foreground companion on a private synthetic notification bus. Three
monitoring transitions must each preserve measured CPU/memory acceptance and ordinary
Notify replies. Exact submitted measurements, accepted epoch/revision/data and captured
external identities must occur in schema-validated complete pushed snapshots. The gate
uses event-driven correlation, not periodic admin fetches. It reads one final canonical
snapshot and records the root HTTP status without treating that status as panel/browser
acceptance. Missing routes, malformed/oversized events, unexpected epochs or regressing
revisions fail closed; no API response is substituted. Evidence retains actual events,
requests/readbacks, immutable assembly identity and owned cleanup. This finite Main
check does not certify final Compose/proxy deployment, device queue reports, installed
systemd service, desktop popups, LCD pixels or live providers. Tools and private fixtures
are not included in the standalone wheel.

## Companion-to-native-application Compose check

Use an installed companion interpreter, a locally built immutable relay image ID,
and the firmware owner's current `native-runtime-peer` executable:

```sh
.venv/bin/python tools/check_runtime_device.py --image-id sha256:EXACT_LOCAL_IMAGE_ID --binary ../twitch-screen-firmware/.pio/build/native-runtime-peer/program --evidence /absolute/private/path/device --test-owned
```

Replace the placeholder with the exact ID from `docker image inspect`; tags are not
accepted. This checkout tool starts the actual checked-in Compose service under a
unique project on dynamic loopback ports. It uses fresh test-owned state, never an
existing grant volume, and preserves its state volume during cleanup. It never uses
`down -v`, reads a private env file, installs a user service or accesses the board.
The native executable is copied before execution and its SHA-256 is retained.
The async witness reader limits lines to 8192 bytes before JSON/UTF-8 decoding and
retains at most 10000 records. Oversize, malformed, duplicate-key or non-object
records fail closed; reader failure/EOF wakes waiting correlations instead of
silently dropping transitions or waiting for the full scenario timeout. Real
owned-child pipe tests cover fragmentation, final EOF, overflow and cancellation.
The asyncio transport has its own buffering; this is a record-admission bound,
not a claim that total process allocation is at most 8192 bytes.
The tool requires the checkout's firmware lifecycle helpers; neither those helpers
nor test fixtures enter the standalone companion wheel.

A private synthetic bus and fake notification daemon feed the installed foreground
companion. Two ordinary Notify replies correlate with exactly two original-age
external POST publications, DESK events and ordered native controller/LVGL card
lifecycles. One actual committed POST response is lost; the companion must record
ambiguity without resending the card. Independently measured CPU/memory are checked
against the accepted HTTP payload, actual TCP basis points/revision/deadline and an
uncovered CPU application view with its adjacent numeric `application_telemetry`
record. CPU, memory and presence must match the canonical submitted values and wire
values, with the same relay epoch, connection, CPU revision and applied display revision.
The numeric proof requires prior readiness for that exact epoch/connection. Connection
IDs and revisions can repeat after restart; foreign or missing observer epochs cannot
certify a current application. Missing,
changed, covered or unrelated numeric evidence fails the gate; visible revision alone
is not numeric application proof. The evidence records both raw observer rows and their
index, so independent verification can reject detached or stale correlations. These
are receiver/application-adapter observations, not physical pixels.
Hidden updates continue while Twitch overrides the
base; turning off the override restores fresh CPU. Monitoring switches do not
restart or reconfigure the companion. Every report must conserve this fixture's
credits. Shutdown releases the owned child, private bus, gateways and Compose
container; exact project/volume identities remain in private evidence.

Add `--deny-monitor` to run the independent CPU path with monitoring denied by a
private synthetic bus policy. Two ordinary fake-daemon replies must still succeed,
while three newly measured HTTP/wire/native CPU application correlations pass across
monitoring changes. Capture/card counters and POST attempts must remain zero, and
every idle report must retain exactly eight credits. The foreground child keeps its
PID/configuration and stops cleanly. This option changes no desktop bus policy.

Add `--malformed-response` instead of `--deny-monitor` to corrupt the delivered
body of one actually committed POST. The gateway keeps the decoded real upstream
acceptance and its raw-response hash separate from the explicit malformed-body
fixture. The installed child must drop the ambiguous card without POST resend,
recalibrate, and continue newly measured numeric CPU/memory application after the
second card finishes. The ordinary daemon replies and original device lifecycles
remain required. This tests client recovery from injected corruption, not a claim
that the relay produces corrupt JSON. The two options cannot be combined.

Exit zero covers only this finite named native software chain. The tool separately
records actual admin route availability and never invents queue projections or
counts native LVGL as LCD evidence. Historical 7998/7999 ms callback intervals for
8000 ms holds remain retained strict lifecycle failures. The corrected continuously
sampled native LVGL timing artifact passes the original hold assertions; the newer
epoch-emitting observer preserves that timing fix. Those finite corrected-artifact
results supersede the old observer's timing evidence, not its retained failure logs.
They do not establish complete source/suspend/provider, final deployment/admin/SSE,
supported desktop/user-service or physical/sixty-minute-soak acceptance.

## Joined installed producer/native/panel check

Add `--browser-executable /absolute/path/to/reviewed/chromium` to the Compose
command above. Install the checkout's reviewed test-only browser lock into the
interpreter running the gate first:

```sh
.venv/bin/python -m pip install --require-hashes -r ../site/requirements-browser.lock
.venv/bin/python tools/check_runtime_device.py --image-id sha256:EXACT_LOCAL_IMAGE_ID --binary ../twitch-screen-firmware/.pio/build/native-runtime-peer/program --browser-executable /absolute/path/to/reviewed/chromium --evidence /absolute/private/path/joined --test-owned
```

Use the browser artifact pinned by `site/browser-baseline.json`; Playwright and
Chromium are test tools, not companion runtime or wheel dependencies. The optional
mode runs one real mounted panel against the same owned Compose service, installed
foreground companion, private synthetic bus and native application. It configures
only that disposable relay's exact loopback Host and Origin. No asset, snapshot or
SSE response is mocked or substituted.

Browser display buttons select CPU, enable Twitch during an active captured card,
and restore CPU. Exact measured CPU/memory values must join submitted HTTP payload,
canonical acceptance, wire basis points/deadline, adjacent native numeric application,
complete browser SSE snapshot and rendered DOM. Native delivery and SSE independently
coalesce; the gate selects an actually shared revision, never nearby numeric values.
Raw snapshots and real mutation readbacks remain retained. A newer canonical GET may
overtake an older queued SSE event; the gate verifies that superseded event against
its actual readback rather than falsely claiming the older payload was rendered.
Missing or contradictory numeric/epoch/revision/DOM evidence still fails closed.
Superseded rows must match the newer readback's complete CPU and memory state,
including missing measurements, absent memory versus measured zero, and freshness
labels. They remain raw observations, not evidence that their old payload rendered.

Browser initialization retains its primary error or cancellation. Diagnostic text
collection is best-effort with a one-second budget; owned closure runs even if that
collection fails. Cleanup has a five-second startup-failure budget, and secondary
errors remain explicit in the private proof rather than replacing the primary error.

The second captured card is sent while the first is active. Its committed response
is lost or, with `--malformed-response`, explicitly corrupted. Exactly two external
identities must appear in browser history and a fresh epoch/connection-scoped firmware
report must join the rendered first-active/second-pending FIFO. Both original 8000 ms
holds and complete ordered native lifecycles remain required; neither POST is resent.
`--deny-monitor` instead verifies measured CPU/native/panel continuity with zero card
POSTs and ordinary fake-daemon delivery intact.

Page teardown/reload must recover an authoritative real feed without replaying
notifications. This is not a network-outage test: Chromium's offline flag does not
necessarily sever an existing SSE socket. Browser requests must contain only initial
snapshot/feed, reload recovery and canonical readback after explicit display actions,
not periodic API polling or notification POSTs. Collection validates canonical schemas,
retains at most 256 finite browser event records and closes owned browser resources.
These are fixture observation bounds, not a universal browser heap limit.

This joined finite software gate does not certify all panel concurrency/error/zoom/
contrast workflows, final proxy/mobile acceptance, supported desktop popups, installed
systemd service, physical LCD pixels, live providers or the sixty-minute resource soak.
The exclusive integrator must freeze final artifacts and independently rerun the
complete acceptance matrix. Tools, browser dependencies and private evidence never
enter the companion wheel.

## Read-only desktop/service preflight

```sh
.venv/bin/python tools/check_desktop_preflight.py --output /absolute/private/path/desktop-preflight.json
```

This checkout tool reads OS, Python/dependency versions, graphical-session metadata,
runtime-directory/socket metadata and selected existing systemd unit state. It never
connects to the desktop bus, calls Notify or BecomeMonitor, reads notification
content, changes services or installs dependencies. Output omits bus addresses and
unrelated environment values. Exit two identifies missing Ubuntu 24.04/GNOME 46
Wayland prerequisites; exit zero means only ready for a separately owned synthetic
probe. Neither result proves monitor permission, popup preservation or installed
service operation. An already active service is explicitly preserved. The exclusive
integrator must collect those actual acceptance observations after resource ownership.

## Privacy and capture scope

Notification forwarding defaults off. Explicitly set `notifications_enabled = true`
to enable it. Summary-only is the default after enable; `forward_body = true` is a
separate opt-in. Test configurations should include only `twitch-screen-e2e`.
Includes/excludes are case-folded exact application-name matches. Exclude wins.
The client always excludes `twitch-screen-client`. Application labels are not
verified sender identities: a producer can choose its own label.

Enabled forwarding exposes submitted text to everyone reaching the trusted relay
and to people near the physical display. There is no management API token or
application login. Keep the relay on a private LAN/VPN with appropriate firewall
rules; never expose it to the internet. HTTPS verifies certificates. Environment
proxies are ignored, redirects disabled, URL credentials/query/fragment/nonroot
paths rejected. Use an explicitly trusted endpoint.

The adapter uses two separate guarded Unix D-Bus connections: narrow receive-only
Notify monitoring, and ordinary owner/status monitoring. It does not own/replace
`org.freedesktop.Notifications`, suppress desktop delivery, send replies from the
monitor, negotiate file descriptors or request broad logging/root privileges.
Monitoring can be denied by bus policy; `monitor_denied` is a capture failure, not
an indication to change policy. Portal/native-only paths are outside this capture
claim. Ubuntu 24.04 GNOME 46 Wayland is the first acceptance target, not a verified
supported-desktop claim yet.

Capture means a submitted request, not daemon acceptance, a desktop popup or LCD
pixels. Replacement/return-ID coalescing stays disabled: repeated submissions are
independent finite cards. Desktop closure never cancels a remote card. Markup is
parsed to plain text; entities decoded once; controls removed; actions, arbitrary
hints, images and files are not forwarded. HTTP retains safe Unicode, but the
initial device font supports printable ASCII only.

Both bus roles enforce the complete 65536-byte envelope before remainder reads.
Authentication lines are capped at 1024 bytes. Structural validation bounds
signatures, nesting, internal lengths and work before the pinned compiled codec.
Partial frames fail closed with bounded residence; idle monitoring remains
push-based. Private codec APIs are isolated in `dbus_transport.py` and exercised
by the real private-bus tests. These limits are not a universal Python heap or
wall-clock performance guarantee.

## Measurement and delivery semantics

CPU is aggregate busy time from consecutive `/proc/stat` deltas. Sum user, nice,
system, idle, iowait, irq, softirq and steal; do not double-count guest counters.
Busy excludes idle and iowait. Warmup, resets, nonpositive deltas, long sampling
gaps and suspend intervals produce no CPU upload. RAM used is
`(MemTotal - MemAvailable) / MemTotal * 100`, not total minus free or swap usage.
Missing memory is omitted and locally degraded, not measured zero. Temperature
is not invented. Default cadence is 2000 ms, configurable 1000–60000 ms.
Freshness is `min(60000, max(10000, 3*sample_interval_ms))`.

To verify accounting against actual kernel input without generating load, run from
this component directory:

```sh
PYTHONPATH=src .venv/bin/python tools/check_metrics.py --output /absolute/path/metrics.json
```

The checker samples real `/proc` data over two seconds, then independently computes
CPU busy deltas and RAM usage from the same captured input. The comparison tolerance
is 1e-10 percentage points. This checks parsing/accounting, not an independent CPU
sensor, real-desktop compatibility, relay acceptance or physical LCD display.
It neither connects to a bus/relay nor installs a service. Missing/reset/suspended
measurements fail the check rather than inventing zero values.

Local age uses Linux CLOCK_BOOTTIME, including suspend. Calibration uses a bounded
GET /clock exchange when needed by an upload, not an independent health-poll loop.
Calibration pairs CLOCK_BOOTTIME with active monotonic time. Before each upload,
a suspend/discontinuity invalidates the shared calibration; exchanges spanning one
cannot refresh it. Both workers then require a new clock exchange. The 10 ms paired
sampling tolerance is covered by the 50 ms conversion guard. No upload fallback
renews content age. Absolute original capture/measurement deadlines survive network
delay and retries; same-epoch retry caps never increase after recalibration.
Success headers refresh only the accepted, still-current calibration and epoch.
Missing, malformed or mismatched sample headers leave acceptance final but force a
fresh GET before the next upload. Late responses cannot erase another worker's newer
calibration. GET /clock must include the `Cache-Control: no-store` directive; a
cacheable response never authorizes an upload. Monotonic sample headers use bounded
ASCII decimal integers, not Python numeric coercions. Wrong epoch triggers recalibration;
expired work is discarded. The 50 ms plus 1000 ppm guard may conservatively discard
very short-lived work. It must never make stale work younger.

Notifications use a 32-entry / 64 KiB encoded pending FIFO: remove expired entries,
then refuse newest on overflow. One worker sends at 2/s, burst four. A single latest
CPU slot replaces unsent observations; its separate worker never overlaps PUTs.
After asynchronous clock calibration or its shared lock wait, the worker rechecks
observation identity and original expiry before starting PUT. Superseded unsent
samples are abandoned without resetting failure backoff. An already-started PUT
cannot be recalled; a replacement remains pending for the next exchange.
Requests/responses are capped at 16/64 KiB. Exchanges have a three-second total
budget and one-second connect budget. A slow request never runs in a D-Bus callback.
No disk spool or historical metric backlog is created.

Only confirmed pre-send connect failures or canonical nonmutating 409/429 rejections
can retry notification POST while its original deadline remains useful. Any possible
send followed by timeout, response loss, malformed/oversized response or uncertain
5xx is counted and dropped, not blindly resent. 410 expires permanently. 4xx errors
need configuration/contract repair. CPU retries only the newest fresh observation.
Backoff starts at one second, doubles to 30 seconds and adds at most 25% jitter.
During CPU backoff, original expiry removes the pending observation promptly.
New samples wake local pruning and replace the single slot, but never reset or
bypass the existing retry budget. Cancellation interrupts the wait immediately.
After an awaited exchange or upload, each owner checks outstanding cancellation
before accepting a result or starting another idle wait. A dependency suppressing
`CancelledError` cannot orphan the CPU/notification worker or count late acceptance.

HTTP success means relay acceptance, not device delivery, display or reading.
Capture health, CPU health and relay delivery health are independent local reasons.
A fresh CPU slot at the relay does not prove desktop capture is healthy.

## Optional user-service installation (manual; acceptance pending)

Do not install or enable this unit until the actual desktop and relay prerequisites
are verified. No install/enable operation is performed by the build or tests.
Run the foreground command first with synthetic-only application filters.

The unit expects a dedicated venv at
`~/.local/share/twitch-screen-client/venv`. Create it and install the hash-locked
runtime requirements and built wheel using the commands above with that venv path.
Copy `systemd/twitch-screen-client.service` to
`~/.config/systemd/user/twitch-screen-client.service`, then:

```sh
systemctl --user daemon-reload
systemctl --user enable --now twitch-screen-client.service
systemctl --user status twitch-screen-client.service
journalctl --user -u twitch-screen-client.service
systemctl --user stop twitch-screen-client.service
```

The graphical session must export its DBUS_SESSION_BUS_ADDRESS and XDG_RUNTIME_DIR
to the user service manager. Diagnose with the foreground process and desktop's
session environment setup; do not hardcode another user's bus or enable blanket
linger/root. The unit is bound to `graphical-session.target`, restarts on failure
with five-second delay and uses owner-only file permissions.

To remove: `systemctl --user disable --now twitch-screen-client.service`, remove only
that unit, then `systemctl --user daemon-reload`. Remove the dedicated venv/config
only if no other owned instance uses them. Do not delete relay token/state stores.

## Acceptance still pending

Production relay/Docker HTTP integration and full source failure/recovery tests
must be independently verified. Real desktop monitor permission and normal popup
behavior, installed user-service start/stop, physical Notify-to-LCD and measured
CPU-to-dashboard operation remain explicit acceptance gates. Synthetic evidence
cannot close them. Comprehensive dependency/license release review is separate
from wheel hash verification and public PyPI release metadata checks.
