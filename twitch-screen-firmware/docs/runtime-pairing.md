# Actual firmware application pairing

The Linux runtime peer runs the firmware's actual LinkSession, Controller,
Receiver and shared application/LVGL callbacks over a nonblocking TCP socket.
It renders to a real LVGL memory display. It does not access an ESP32 or prove
physical pixels, stack headroom, watchdog behavior or soak acceptance.

The peer is a deliberately invoked integration executable, not an automatic
unit suite. Its test-only receiver can request DESK; production `main.cpp`
continues to omit the profile until complete capable pairing is verified.
The peer uses a fixed synthetic identity and accepts only loopback targets.
Do not run this harness against an existing production relay: it publishes
synthetic cards and can reclaim another peer with the same synthetic ID.

## Reproduce

Use the pinned PlatformIO installation described in the component README.
From the repository root, choose a scratch build directory and compile:

```sh
export PLATFORMIO_BUILD_DIR="$HOME/.hermes/cache/scratch/firmware-runtime-peer"
PIO="$HOME/.platformio/penv/bin/pio"
"$PIO" test -d twitch-screen-firmware -e native-runtime-peer --without-testing
```

PlatformIO reports this build as SKIPPED because testing was intentionally
omitted. That output establishes compilation only. Exercise the resulting
executable with the commands below; do not claim a runtime pass from the build.

Start a separate current-source relay from `twitch-screen-relay/`. Ensure the
chosen loopback ports are unused. Use a fresh private scratch parent for each
run, so existing saved intent cannot override the disabled test mode. `TMPDIR`
must identify your private scratch directory; this command does not read or
change the checkout's default runtime-state file.

```sh
PAIRING_STATE_DIR="$(mktemp -d "${TMPDIR:?Set TMPDIR to your private scratch directory}/firmware-pairing.XXXXXX")"
RELAY_RUNTIME_STATE_FILE="$PAIRING_STATE_DIR/runtime-state.json" \
RELAY_TWITCH_MODE=disabled RELAY_NOTIFICATION_TTL='1 second' \
RELAY_HTTP_HOST=127.0.0.1 RELAY_HTTP_PORT=18085 \
RELAY_DEVICE_HOST=127.0.0.1 RELAY_DEVICE_PORT=18095 \
RELAY_HTTP_ALLOWED_HOSTS=127.0.0.1:18085 ./mill --no-server run
```

Wait for the actual health endpoint to return 200, then run from repository root:

```sh
python3 twitch-screen-firmware/tools/test_runtime_peer.py \
  --binary "$PLATFORMIO_BUILD_DIR/native-runtime-peer/program"
python3 twitch-screen-firmware/tools/pair_runtime.py \
  --binary "$PLATFORMIO_BUILD_DIR/native-runtime-peer/program" \
  --http-port 18085 --device-port 18095 \
  --evidence "$HOME/.hermes/cache/scratch/firmware-pairing-evidence" \
  --queue-pressure
```

Stop only the relay process started for this test when finished.
No service installation, Twitch credentials or connected board is needed.

## Assertions and evidence

The first gate validates argument refusal and a real no-listener connection.
The pairing harness publishes two synthetic one-second INFO cards through the
actual HTTP API. It requires two completed LVGL lifecycles, at least two TCP
greetings, distinct sequences and replay after a forced reconnect. Optional
queue pressure delays dequeue four seconds, publishes ten cards and requires
exact publication order, an observed peak of eight pending entries and ten
completed entrance/hold/exit lifecycles. Child execution has hard deadlines.
Evidence directories are private on creation; the harness saves parsed stdout,
stderr and its final assertions. The harness checks the workflow STOP sentinel.

A separate invocation requires DESK readiness. Its exit code is 1 when the
current relay negotiates classic fallback, even when classic STATS work.
The combined harness can return 0 when classic gates pass and this refusal is
honest. Add `--require-desk` to make capable synchronization and two ordered
generic card lifecycles mandatory: fallback then makes the harness exit 1.
The capable gate publishes only after `desk_ready`, not merely WELCOME. It checks
retained card identity/provenance and full entrance/hold/exit completion, without
assuming DESK events call the classic notification hook. Explicit classic gates
omit DESK in HELLO even when the relay supports it.

Check `requiredDeskReadinessPassed` and `capableApplicationPairing.passed`
independently. Neither a classic pass nor an honest fallback is DESK acceptance.
The current-source wave-5 run observed readiness as false.

Current capable relay control/source authority, reports, cumulative credit,
clock reset, minimum frame capacity and slow-writer recovery remain prerequisites
for B1/B4 acceptance. These classic gates are not substitutes for them. Physical
acceptance belongs to the exclusive integrator. `productAccepted` stays false.

## Socket-written report observations

The executable emits `device_report_written` only after `send()` has accepted all
bytes of a complete report. Its bounded frame reader survives short writes and
resets on connection close; it does not inspect partial frames as observations.
This is local transport evidence, not proof the relay accepted the report or the
LCD rendered it. The mandatory capable gate requires initial eight credits,
monotonic report revisions/cursors/observation times, active entrance/hold/exit,
and final empty state. Every report must conserve this fixture's two unexpired
external admissions: initial grant 8, grant 9 after the first dequeue and grant 10
only after the second dequeue. Active completion releases no credit. Ordered
pending identities and unchanged expiry/suppression/refusal counters justify each
observation; admission/completion updates may coalesce. This narrow fixture gate
is not general terminal-disposition or pressure acceptance. No queue is inferred
from EVENT ACKs.

Run the verdict and actual-executable regression against an isolated synthetic
socket server using canonical fixtures:

```sh
python3 twitch-screen-firmware/tools/test_capable_pairing.py \
  --binary "$PLATFORMIO_BUILD_DIR/native-runtime-peer/program"
```

This exercises normal writes and 13-byte short writes, fragmented six-frame
synchronization, two real application/LVGL card lifecycles and matching reports
received by the fixture. It is explicitly synthetic, not production Scala relay
acceptance. Without `--binary`, only verdict fixtures run; runtime cases are skipped.

## Retained queue and zero-credit recovery

Run the actual application executable against a separate, explicitly synthetic
socket fixture:

```sh
python3 twitch-screen-firmware/tools/test_runtime_recovery.py \
  --binary "$PLATFORMIO_BUILD_DIR/native-runtime-peer/program" \
  --evidence "$HOME/.hermes/cache/scratch/firmware-recovery-evidence"
```

This gate admits eight unexpired external cards before an intentional reconnect.
The second connection must complete synchronization while retaining all eight
pending identities, initially granting zero event credit. Separate CPU and weather
runs apply fresh source data and display control, then let the original source
deadline expire. Twitch override changes must progress without dequeuing any
card. The real LVGL adapter must hide expired telemetry and keep both
telemetry roots hidden while covered. All eight cards subsequently complete in
FIFO order. Every second-session observation conserves credit; card completion
cannot release another credit. The fixture also answers actual TIME_REQUEST
traffic and verifies 13-byte socket writes, processed resume and report fields
against received bytes. Evidence includes parsed observations and raw payloads
in separate `cpu-recovery-observations.json` and
`weather-recovery-observations.json` files. Neither run proves production relay
weather/CPU wire delivery. The mounted HTTP slots are implemented; complete
negotiated delivery remains a separate dependency.

`desk_ready` identifies each newly synchronized epoch/connection, not merely the
first successful session in a process. `application_view` reports the actual
rendering adapter's selected base and LVGL hidden flags only on meaningful
changes. It also records `statsSourceGeneration`, `statsRevision` and projected
`statsAvailability`: an obsolete source observation becomes unavailable at the
control fence until matching current-source STATS arrives. It records no
notification text or host labels. Complete
`device_report_written` observations additionally expose display/source/episode,
weather/CPU revisions and base/reason fields. These are local software
observations, not a claim that a production relay accepted reports or that LCD
pixels were observed. Without `--binary`, the runtime regression is skipped.

Current production DESK pairing, minimum relay outbound capacity, source/episode
switch races, discontinuity reset and slow-writer recovery remain separate
acceptance gates. The synthetic reconnect result does not close them.

## Actual capable reconnect

Add `--require-desk --desk-reconnect` to `pair_runtime.py` to require same-epoch
recovery as a separate gate. It publishes two one-second cards, reconnects at six
seconds with 13-byte socket writes, then publishes one new card after the second
local synchronization. Exactly two connections, three complete lifecycles,
independent resumed enqueue progress and conserved per-connection credit are
required. Repeated local `desk_ready` messages without newly delivered work fail.
The gate saves `capable-reconnect-observations.json` and reports
`capableReconnect.passed` separately from first-session acceptance.

This narrow scenario starts reconnect with an empty FIFO. It does not replace
retained-eight, terminal dispositions, minimum capacity, reset or slow-reader
acceptance. A post-reconnect event from the real relay demonstrates usable initial
report admission; locally written final reports still do not prove remote
acceptance or physical pixels. Production capability remains gated until the
complete joint profile matrix passes.

Run its verdict and actual-executable regression independently:

```sh
python3 twitch-screen-firmware/tools/test_capable_reconnect.py \
  --binary "$PLATFORMIO_BUILD_DIR/native-runtime-peer/program" \
  --evidence "$HOME/.hermes/cache/scratch/firmware-capable-reconnect"
```

That command uses explicitly synthetic canonical TCP traffic, not a production
relay. Without `--binary`, only verdict tests run and the runtime case is skipped.

## Actual transport failure and generation recovery

Run the failure matrix against the actual application executable:

```sh
python3 twitch-screen-firmware/tools/test_runtime_failures.py \
  --binary "$PLATFORMIO_BUILD_DIR/native-runtime-peer/program" \
  --evidence "$HOME/.hermes/cache/scratch/firmware-runtime-failures"
```

These are explicitly synthetic TCP fixtures, not an installed production relay.
Six tests cover seven scenarios with real LinkSession/Receiver/Controller/LVGL execution:

- Mixed expired, duplicate and suppressed records preserve generic FIFO and the
  enqueue-only ACK while processed progress crosses a barrier. Source/episode
  changes remove waiting Twitch cards, never the already active Twitch card.
  Old-generation STATS stays unavailable until matching new-generation data.
- A ninth uncredited event at eight pending slots forces disconnect. Recovery
  preserves eight original identities, reports the refusal and does not advance
  processed progress across the unhandled event.
- A greeting without SYNC_END times out at the actual five-second budget. Only
  the next complete connection becomes ready or reports queue credit.
- A fresh relay epoch rebaselines current cursors while active and waiting
  generic cards keep their original epoch/sequence. All finish in FIFO order.
- A plain classic EVENT after DESK negotiation closes the session. A following
  progress barrier cannot cross that protocol violation.
- Separate display and CPU equal-revision content conflicts close before a
  following barrier can advance progress. Active and pending generic cards keep
  their original identities and complete after synchronization on a new
  connection. Obsolete connection control/barrier records cannot overwrite
  current state or manufacture processed progress.

Reports are compared with independently received complete payload bytes, using
13-byte socket writes. Evidence includes raw HELLO/report payloads, parsed actual
application observations, ACK values and stderr. Without `--binary`, all actual
runtime cases are skipped. This matrix does not enable ESP32 capability or prove
relay source teardown/reset, production slow-writer behavior, physical pixels or
whole-profile acceptance. The serial queue diagnostic uses `enqueued`, not
`shown`: queue admission is not presentation completion.

## Actual relay-owner terminal recovery

Run the focused current-source owner gate from repository root:

```sh
python3 twitch-screen-firmware/tools/test_relay_terminal.py \
  --binary "$PLATFORMIO_BUILD_DIR/native-runtime-peer/program" \
  --evidence "$HOME/.hermes/cache/scratch/firmware-owner-terminal"
python3 twitch-screen-firmware/tools/test_relay_terminal_verdict.py \
  --observations "$HOME/.hermes/cache/scratch/firmware-owner-terminal/native-observations.jsonl"
```

The first command snapshots current relay production sources, resources, pinned
build inputs and its actual socket-test helper into a fresh private `TMPDIR`.
It adds the firmware-owned Scala integration main only to that disposable
snapshot. It never edits the relay checkout or copies runtime state/token files.
Only this integration main and helper compile as test sources; a passing result
is not a full relay-suite pass. The relay build needs its documented JVM/Mill
setup and cached or reachable dependencies.

The actual DeviceHub/DeviceSession uses an explicit internal DESK opt-in and
minimum outbound capacity. The firmware application reads at most seven bytes
per socket read and writes at most thirteen bytes per send. An active external
card covers a full eight-entry pending FIFO. Rapid display/source fences remove
one waiting Twitch card; original waiting expiry removes one external card.
An ineligible later Twitch publication advances processed progress through a
barrier without advancing the enqueue-only cursor. Reconnect must preserve six
original pending identities plus the active card, independently resume both
cursors and deliver new external work, including a Twitch-looking visual kind.
Eight cards complete their full actual LVGL lifecycle in FIFO order. Expired
CPU data must remain unavailable through reconnect and reveal.

Every socket-written report is checked for ordered identities, session-scoped
revision/cursors and exact admission/dequeue/terminal credit conservation.
Every ready second-session report must retain connected-orbit/data-stale;
one correct stale report does not excuse a later CPU resurrection. Every
second-session `application_view` must keep both telemetry roots hidden,
including while covered and at each uncover. Ready reveals must retain the
stale fallback. Explicit synchronization before readiness and disconnected
fallback after completed delivery remain distinct, permitted observations.
Missing application or reveal observations fail closed.
Completion cannot release another credit. The gate additionally reads the
actual relay delivery owner's final accepted report, encodes it into
`remote-final-report.hex`, and independently decodes its canonical fields to
compare with the final local observation. This proves that specific report was
accepted remotely; it does not infer remote acceptance of every local write.
The negative verdict command mutates real captured observations to prove that
overgrant, cursor conflation, revived terminal cards, wrong identities, lost
FIFO order and renewed telemetry do not pass. Single late-report and first-uncover
mutations preserve the final correct remote report, so transient regressions
cannot hide behind final-state agreement.

The synthetic CPU/weather retained-eight recovery gate applies the same
post-expiry invariant. Its explicit revision-11 Twitch override may select
Twitch, but revision 12 must restore stale fallback, never expired telemetry.
After running `test_runtime_recovery.py --binary ... --evidence ...`, verify
negative mutations of those actual captures with:

```sh
python3 twitch-screen-firmware/tools/test_recovery_freshness.py \
  --evidence "$HOME/.hermes/cache/scratch/firmware-recovery"
```

Use the same evidence directory passed to the recovery command. These mutations
are test cases based on actual observations, not claimed runtime output.

Evidence includes `source-manifest.json`, `owner.log`, parsed application
observations, final remote report and `verified.json`. Child execution and
listener ownership are bounded and checked. These are actual current-source
internal-owner tests, not default production advertisement, automatic source
teardown/reset, a blocked kernel writer, physical pixels or whole B1/B4
acceptance. The installed production gate remains separately mandatory:
`pair_runtime.py --queue-pressure --require-desk --desk-reconnect`.

## Actual kernel writer and continuous control

Run the two focused gates with the built Linux application:

```sh
python3 twitch-screen-firmware/tools/test_runtime_backpressure.py \
  --binary "$PLATFORMIO_BUILD_DIR/native-runtime-peer/program" \
  --evidence "$HOME/.hermes/cache/scratch/firmware-kernel-backpressure"
python3 twitch-screen-firmware/tools/test_relay_writers.py \
  --binary "$PLATFORMIO_BUILD_DIR/native-runtime-peer/program" \
  --evidence "$HOME/.hermes/cache/scratch/firmware-owner-writers"
python3 twitch-screen-firmware/tools/test_relay_writers_verdict.py \
  --evidence "$HOME/.hermes/cache/scratch/firmware-owner-writers"
```

The first gate shrinks only test-owned TCP buffers. A bounded synthetic heartbeat
burst fills the remote receive window until the firmware's actual `send()` returns
EAGAIN. The fixture stops the burst on that event, preventing application FIFO
exhaustion from masquerading as a write timeout. LinkSession's unchanged pending
output budget closes the connection. A partial final frame remains explicit raw
prefix evidence; it is never decoded or counted as a complete report. Two original
external cards finish their real LVGL lifecycles across recovery. Complete local
reports are compared with the independently received payloads. Host scheduling
allows at most 3500 ms from first EAGAIN to the write-timeout observation; this is
not a hardware timing gate or permission to change the three-second runtime budget.

The second gate snapshots current relay production sources and compiles a separate
firmware-owned main, not the entire relay unit suite. Its first scenario negotiates
an actual capable DeviceSession, admits its initial report and exchanges TIME. It
then stops reading its own tiny-window socket while sending bounded protocol PING
frames. The installed FrameSink blocks in a real kernel write. The unchanged
three-second production watcher closes the socket and the session joins. The
observation wrapper calls the real socket stream: it injects neither a stall nor
an IOException. Linux may adjust requested buffer sizes; evidence records actual
send/receive sizes and syscall duration. Synthetic heartbeat pressure is not a
claim about normal peer traffic rates.

A separate actual hub/session/native application scenario makes 121 display and
source/episode writes while a ten-second external card remains active. One waiting
Twitch card suppresses exactly once; an external Twitch-looking successor survives.
Thirteen-byte writes and seven-byte reads exercise real partial I/O. Every report
conserves admission/dequeue/terminal credit and ordered pending identities. Every
application view keeps telemetry hidden and rejects prior-source availability,
including both real uncover boundaries. Remote accepted report snapshots are
encoded from the installed delivery owner, independently decoded and matched to
complete local observations. Active phases are nondecreasing for each original
relay-epoch/sequence identity, including across reconnect. Coalescing may omit
intermediate phase reports; it may never turn exit back into hold or hold back
into entrance. The same validation runs before local and remote verdicts, so
matching impossible payloads cannot validate one another.

The peer records monotonic `atMs` and original `holdMs` at `started`, then emits
`card_lifecycle` for the actual LVGL hold, exit and completion callbacks with the
same original identity. The continuous-control gate independently requires the
original ten-second and 100 ms holds, ordered callbacks and complete slide-out.
Report `observedDeviceMs`, rather than socket-write order, must fit those callback
phase boundaries. These timestamps prove this memory-display fixture's lifecycle,
not physical rendering or a general hardware timing guarantee.

Sampled relay times check the oldest accepted but
remotely unapplied control age; newer writes cannot reset that age. The final
remotely accepted report must match current fences and empty lifecycle state.

`control-schedule.jsonl`, `remote-report-observations.jsonl`,
`native-observations.jsonl`, `kernel-writer.json` and `remote-final-report.hex` retain
raw evidence. The verdict regression mutates those actual captures; mutations are
labeled tests, not runtime output. Missing reveals, transient wrong bases, false
credits/fences and stale remote application must fail closed.

These finite tests cover installed internal owners, not canonical monitoring API
resolution/source teardown, all suspend/provider races, default Main/Docker/Compose
advertisement or physical pixels. Default capable pairing remains mandatory before
joint production enablement. Parallel workers do not access the board; the exclusive
integrator owns prerequisite-ready target upload, LCD and measured soak acceptance.

## Canonical production API and retained-eight application gate

Use an isolated, test-owned loopback relay with disabled monitoring. The gate
replaces its RAM CPU/weather slots and briefly changes display/monitoring intent;
never point it at an existing service. It restores desired settings and reads
back the restored display. Source revisions, epochs and RAM history necessarily
change; this is not a rollback of a production instance.

```sh
python3 twitch-screen-firmware/tools/test_production_views.py \
  --binary "$PLATFORMIO_BUILD_DIR/native-runtime-peer/program" \
  --http-port 18085 --device-port 18095 --test-owned \
  --evidence "$HOME/.hermes/cache/scratch/firmware-production-views"
python3 twitch-screen-firmware/tools/test_production_views_verdict.py \
  --evidence "$HOME/.hermes/cache/scratch/firmware-production-views"
```

Separate CPU and weather scenarios each issue twelve canonical replacements per
source, then correlate the final accepted revisions with actual receiver numeric
and presence fields. Every connected report, view and adjacent numeric record must
match a synchronized `(epoch, connection)` pair. Connection IDs can repeat after
relay restart; matching revisions or mutually matching foreign epochs do not prove
application. Latest numeric selection is scoped to the original carrying pair.
The shared identity validator also protects restart, retained recovery, terminal
and continuous-control verdicts, rejecting detached numeric observations and a
return to an obsolete carrying session. Missing epoch metadata fails closed;
rebuild the native observer rather than retroactively relabel old captures.
Missing CPU memory remains absent; measured weather humidity
zero remains present. The selected dashboard must reach the real rendering adapter.
One five-second external card covers eight pending external cards. Canonical
monitoring switches disabled to simulated and back while display override remains
off; a later Twitch display override and return must apply without freeing a slot.
The intentional reconnect is triggered by SIGUSR1 only after the final control
report still observes eight pending entries; an arbitrary startup timer cannot
bypass that prerequisite. The native handler only sets a signal-safe flag; the
normal loop closes its owned socket. It retains all eight waiting identities and
initially grants zero credit. Both original telemetry deadlines expire without renewal. Every ready
post-expiry report and every actual view must retain the stale fallback, including
covered transitions. Applied display/source/episode fences are nonzero and cannot
regress within a connection. The final report must match the final accepted
control/source fence across reconnect. A view with an unexpected or regressed
revision is rejected, not filtered out of reveal verification. All
nine original cards complete ordered, timestamped LVGL lifecycles. Completion
cannot create credit. The negative verdict command mutates these real captures;
its fixtures are not additional runtime observations. A separate bounded reader
drains the child's stdout while the harness performs HTTP actions; slow test
logging must not manufacture firmware socket or lifecycle stalls. More than
1024 pending observation lines or a line over 8192 UTF-8 bytes (including its
newline, if present) fails the harness without dropping a transition. The shared
reader limits binary reads before decoding or collecting an oversized record;
an unterminated producer is killed as soon as it exceeds that limit. Strict
UTF-8 decoding rejects malformed bytes. Production, classic/capable pairing,
four-peer and kernel-backpressure observers use this same bounded reader.
`test_production_observer.py` exercises memory residence, prompt cancellation,
fragmented UTF-8, EOF and queue overflow with disposable synthetic children,
not firmware acceptance.

`application_telemetry` is a bounded numeric/presence observation, emitted with
meaningful adapter changes, including weather-only revision changes. It carries
no source labels or notification content. The native runtime uses LVGL's tick
callback with the same continuously sampled monotonic clock as its transport;
callback and start timestamps are taken at their actual transitions, not before
an earlier timer pump. This prevents loop-residence artifacts from masquerading
as shortened holds or overlapping lifecycles. This observer change does not
alter the ESP32 clock, policy or capability gate.

A pass against default Main or the documented isolated Compose deployment proves
that specific production API/TCP/native application path. It does not prove
physical pixels, remote acceptance of every socket-written report, live provider
behavior, the admin panel, suspend/reset races or complete B1/B4 acceptance.

## Default production process-restart recovery

Run the actual production Main assembly with the built native application:

```sh
python3 twitch-screen-firmware/tools/test_production_restart.py \
  --assembly twitch-screen-relay/out/assembly.dest/out.jar \
  --binary "$PLATFORMIO_BUILD_DIR/native-runtime-peer/program" \
  --evidence "$HOME/.hermes/cache/scratch/firmware-production-restart" \
  --test-owned
python3 twitch-screen-firmware/tools/test_production_restart_verdict.py \
  --evidence "$HOME/.hermes/cache/scratch/firmware-production-restart"
```

Use a fresh evidence directory. The runner starts only disposable child JVMs on
its own ephemeral loopback ports, with a private fresh settings path and no
provider credentials. It never restarts an existing relay, reads a token store,
accesses the board or enables the ESP32 profile. `--test-owned` is required
before any execution. The supplied assembly must be built from the intended
source snapshot; evidence records its SHA-256 separately from the native binary.

Before the outage, canonical CPU with measured memory zero reaches the real
adapter. A live-monitoring PATCH without application configuration must return
409 `configuration_missing`; canonical GET must retain desired/effective mode,
revision and source generation. Independent generic publication then continues.
An active external card retains its original twelve-second hold while three
external cards wait. The runner terminates its own Main, waits a bounded outage,
and starts the exact assembly with the same ports and committed settings.
One waiting card expires at its original one-second deadline during the outage.
The other two retain original epoch/sequence identities and FIFO order. The
new runtime epoch baselines current cursors at zero and grants six credits for
the two remaining pending cards; a newly published successor uses sequence one
in the new epoch. All four eligible cards finish actual LVGL callbacks.

Persisted CPU preference survives; runtime revisions restart at one, and RAM
telemetry remains explicitly missing. Every ready new-epoch report and all four
actual reveals require connected orbit/data-missing with hidden telemetry.
Each report conserves session-specific admissions, pending identities and
credits. Completion grants no additional slot. `application_view` and adjacent
`application_telemetry` carry both relay epoch and connection ID: connection IDs
may repeat across epochs. The native change is additive observation metadata;
it preserves the continuously sampled LVGL tick and strict original holds.

Negative verdict cases mutate actual captures, rejecting expired-card revival,
identity relabeling, overgrant, missing reveals, shortened holds, renewed cached
telemetry, old-epoch views and source-admission mutation. Evidence includes raw
HTTP exchanges, callback/report/view observations, fixture, verdict and exact
owned child/listener cleanup. A normal JVM SIGTERM exit may be 143; it is recorded,
not relabeled as a zero exit. Socket-written reports still do not prove remote
acceptance of every report or physical pixels. This gate proves process-restart
recovery, not an OS suspend/wall-clock discontinuity, live provider teardown,
full source/reset/failure acceptance, Docker restart or hardware acceptance.

## Actual baseline/current four-peer compatibility

Build the current native application, then supply an explicitly chosen current
relay assembly. The following command builds the mission's exact baseline relay
and baseline firmware transport from Git into private scratch, without modifying
either checkout or reading credentials:

```sh
python3 twitch-screen-firmware/tools/test_four_peer_matrix.py \
  --binary "$PLATFORMIO_BUILD_DIR/native-runtime-peer/program" \
  --new-assembly twitch-screen-relay/out/assembly.dest/out.jar \
  --evidence "$HOME/.hermes/cache/scratch/firmware-four-peer"
```

Baseline is `b30b91f4a29bc70a50428ac1bf6894e3ba4b7ac2`, not a synthetic relay.
Its original LinkSession, codec and NotifyQueue run through a small test-only
nonblocking socket adapter under ASan/UBSan; that adapter has no LVGL or display
claim. The old relay receives a disposable generated test-only management token;
the token is not printed or persisted in evidence. The current relay receives
no obsolete auth configuration. All four old/new combinations must receive two
ordered synthetic publications across an intentional reconnect. Only new/new
negotiates DESK. Current firmware with the old relay must use classic Twitch
fallback and emit no applied-control/report claims. Current firmware additionally
proves real LVGL callback completion; old firmware proves queue transfer only.
The runner records exact source, assembly and peer hashes and reads back released
owned listeners. Missing baseline build dependencies fail the command, not the
compatibility gate silently. This finite matrix is not target upload, physical
acceptance or full legacy pressure/suspend/provider acceptance.

A separate running-relay gate tests the original baseline firmware against an
already started disposable Main or actual Compose container, rather than only an
extracted Docker assembly:

```sh
python3 twitch-screen-firmware/tools/test_legacy_production.py \
  --http-port 18085 --device-port 18095 --test-owned \
  --evidence "$HOME/.hermes/cache/scratch/firmware-legacy-running"
python3 twitch-screen-firmware/tools/test_legacy_production_verdict.py \
  --evidence "$HOME/.hermes/cache/scratch/firmware-legacy-running"
```

The caller must own the running loopback relay and record its actual image,
assembly, published ports and final cleanup separately. The gate refuses execution
without `--test-owned`, requires disabled monitoring, compiles only exact Git
baseline firmware bytes with the existing socket adapter under ASan/UBSan, then
correlates two real canonical publications with original enqueue/dequeue order
across reconnect. The complete first transfer must occur between the first and
second welcomes; the second must occur after the second welcome. Dequeue cannot
precede enqueue and the summary must follow both transfers. Fresh HTTP evidence
records the welcome ordinal that triggered each publication. Aggregate counts
alone are not reconnect proof. It leaves the caller-owned listener running and reaps only its
own peer. It does not use a synthetic relay or claim old firmware LVGL/pixels.
This is one running-relay compatibility row, not all four combinations or a
full legacy-pressure acceptance gate. The negative verdict rejects fabricated
capability, missing/reordered transfers, duplicate publications and false counts.

## Default production process-suspension recovery

Supply the exact current production assembly and freshly compiled native peer:

```sh
python3 twitch-screen-firmware/tools/test_production_suspend.py \
  --assembly twitch-screen-relay/out/assembly.dest/out.jar \
  --binary "$PLATFORMIO_BUILD_DIR/native-runtime-peer/program" \
  --evidence "$HOME/.hermes/cache/scratch/firmware-process-suspend" --test-owned
python3 twitch-screen-firmware/tools/test_production_suspend_verdict.py \
  --evidence "$HOME/.hermes/cache/scratch/firmware-process-suspend"
```

The runner starts disposable loopback Main and native children with private scratch
settings, disabled ingestion and no provider credentials. It sends a thirty-second
active card, one waiting card with a sixty-second lifetime and one longer-lived
waiting card. It stops only its own native child with SIGSTOP, confirms the actual
stopped status through waitpid, waits at least 61 seconds, then sends SIGCONT.
During the pause, real monitoring APIs accept simulated-to-disabled source changes
and select Weather. Canonical CPU/weather retain their original sixty-second
freshness and must be stale before the native process resumes.

The parent records the pre-launch, confirmed-stop and SIGCONT-bracketing
monotonic timestamps. The last pre-stop report supplies a device-time lower
anchor, not a rendering or teardown marker. Confirmed stopped time bounds the
resume device time from below; elapsed pre-launch time bounds it from above.
The gate refuses uncertainty wider than one second. Every view at or after that
conservative lower boundary is checked, including rows before `link_down`.
Pre-stop fresh views and disconnected cached-but-hidden values remain valid.
`link_down.deskDeadline` independently identifies Receiver gap teardown rather
than a socket write timeout; it does not authorize when freshness checking starts.
The active card finishes its original lifecycle;
the expired waiting card never starts. The eligible waiting card can finish
offline before reconnect: recovery/reporting must not stall it. The final carrying
connection applies the exact current control/source/episode and stale-source
revisions. A new external Twitch-looking card still completes independently.

Every report conserves connection-local cumulative credit and monotonic progress.
Every post-gap view hides both telemetry roots; the first uncover after each exit
is required, not merely a favorable final view. The accepted source history checks
the episode fence independently of the device's reported episode. The canonical
monitoring readback must confirm ready/disabled at the accepted source generation.

The intentionally stopped process cannot run its original hold callback on time.
The gate preserves its thirty-second lower bound, requires prompt callback
recovery after resumption and retains the complete exit duration. Only that
card's hold upper-latency bound is extended in the shared callback verifier.
All original cards stay in one chronology: each successor must start no earlier
than the previous completion. The ordinary successor hold/exit bounds and report
phase-to-callback checks remain strict; coalesced reports need not expose every
phase. Negative tests reject an expired view before transport-loss logging, an
overlapping paused-card completion, unbounded/missing parent timing, renewed
telemetry, manufactured credit, old source/control/episode, missing reveals,
shortened holds and a socket loss falsely presented as receiver-gap recovery.
Older captures without independent parent timing remain historical evidence;
the strengthened gate requires a fresh run and never fabricates timing for them.

Raw HTTP, callback/report/view observations, stopped signal/pause duration,
artifact hashes and exact owned child/listener cleanup are saved privately. A
SIGTERM JVM exit of 143 remains explicit. Socket-written observations are not
proof of remote acceptance of every report. This is actual process suspension,
not machine suspend, wall-clock discontinuity, live provider teardown, the
ESP32 network adapter, physical pixels or full joint profile acceptance.
Production main continues to gate DESK advertisement until those prerequisites
are verified. Cleanup resumes a stopped owned child before terminating it.

## Runtime timing observations

The executable also emits one `runtime_metrics` record at shutdown using the
production bounded histogram. Loop intervals include the 5 ms cadence and
scheduler/network work. The UI section measures actual view/LVGL/card execution.
The normal and 13-byte-write regressions require nonempty metrics and one more UI
sample than loop intervals. No host-dependent performance threshold is asserted.
See [runtime measurements](runtime-measurements.md) for the ESP32 serial fields,
percentile upper bounds, sampled-resource limitations and exclusive-integrator
physical acceptance. Linux ASan observations are not board measurements.

## Direct executable controls

Required environment: `FIRMWARE_PEER_PORT` (1..65535) and
`FIRMWARE_PEER_SECONDS` (1..300). Optional `FIRMWARE_PEER_REQUIRE_DESK` is exactly
0 or 1. Optional `FIRMWARE_PEER_PROFILE_READY` is exactly 0 or 1 (default 1 in this
explicit test executable); 0 omits DESK and cannot be combined with require-DESK.
Optional `FIRMWARE_PEER_RECONNECT_SECONDS` and
`FIRMWARE_PEER_START_DELAY_SECONDS` must be within 1..duration. Optional
`FIRMWARE_PEER_MAX_WRITE_BYTES` is 1..256 (default 256); it bounds each real
nonblocking socket send for short-write tests, not report size or queue capacity.
Optional `FIRMWARE_PEER_MAX_READ_BYTES` is 1..256 (default 256); it bounds each
actual nonblocking socket read to exercise fragmented frames from real relay
writers without changing wire bytes or the production ESP32 transport.
Optional `FIRMWARE_PEER_SEND_BUFFER_BYTES` is 1024..65536; omission retains the
OS default. It requests SO_SNDBUF only on the test-owned Linux socket and emits
`socket_send_buffer` with the actual kernel value. `socket_write_blocked` marks
first real EAGAIN, `link_down` records the bounded disconnect observation, and
`transport_observation` reports total blocked writes and observer errors. Observer
errors make the executable fail; no partial report is counted as complete.
Exit 2 means invalid invocation, exit 1 means the required greeting/readiness
was not observed and exit 0 means that narrow requirement was observed.
Card/replay/pressure correctness is asserted by the Python harness, not merely
by the executable's exit code. JSON observation records never prove LCD pixels.
