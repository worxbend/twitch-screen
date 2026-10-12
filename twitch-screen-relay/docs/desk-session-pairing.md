# Installed DESK session pairing gate

The actor-owned `DeviceHub` now attaches a `DeskConnection` from one immutable
publication snapshot. `DeviceSession` uses the same socket reader, guarded sink,
write-deadline watcher and hub ownership as classic sessions. The explicit
`deskEnabled = true` parameter is an integration-only gate. The production
listener does not supply it and continues to negotiate classic capabilities.
Do not advertise DESK until the complete reset and failure matrix passes.

The gated session sends WELCOME, DISPLAY_STATE, WEATHER, CPU, STATS and SYNC_END
as one ordered initial batch. It accepts DEVICE_REPORT readiness and cumulative
credit, answers TIME probes, projects live display/source publications and uses
absolute application deadlines. Hub admissions do not wait on socket writes.
Duplicate reports do not refresh the report deadline. CPU/weather wire updates
coalesce independently with a 200 ms minimum interval. Captured original relay
deadlines are not reconstructed from later HTTP remaining lifetimes.

## Reproduce actual native application pairing

From the repository root, build the firmware-owned native application into a
private scratch directory. No serial device is opened:

    PLATFORMIO_BUILD_DIR="$HOME/.hermes/cache/scratch/relay-desk-peer" \
      "$HOME/.platformio/penv/bin/pio" test -d twitch-screen-firmware \
      -e native-runtime-peer --without-testing

From `twitch-screen-relay/`, invoke the explicit test-classpath entry point:

    ./mill --no-server -j 1 test.runMain twitchscreen.relay.device.NativeDeskPairing \
      "$HOME/.hermes/cache/scratch/relay-desk-peer/native-runtime-peer/program" \
      "$HOME/.hermes/cache/scratch/relay-desk-evidence"

The harness owns an OS-assigned loopback port, a fresh disabled-source hub and
one bounded native child. It publishes exactly two synthetic external INFO
cards only after DESK readiness. It saves `native-observations.jsonl` and stderr,
reaps the child and closes its listener. The test-only firmware enables the
profile; production firmware remains gated. Validate the saved observations
with the firmware owner's `capable_verdict`, using expected sequences 1 and 2;
readiness alone is not credit or lifecycle acceptance.

The wave-8 run verified two complete LVGL lifecycles and nine full socket-written
reports, including conserved grant 8 through 10 and ordered pending identities.
The real-TCP `DeskSessionSuite` separately exercises greeting coherence, readiness,
external publication, TIME correlation and live display readback. This is host
application evidence, not a physical pixel, flash, watchdog or soak verdict.

## Reconnect pressure and explicit epoch reset

The same actual session/application harness also supports two-session reconnect,
retained-eight minimum-capacity pressure, and an explicit hub reset during a card:

    ./mill --no-server -j 1 test.runMain twitchscreen.relay.device.NativeDeskReconnect \
      "$HOME/.hermes/cache/scratch/relay-desk-peer/native-runtime-peer/program" \
      "$HOME/.hermes/cache/scratch/relay-reset-evidence" reset

From the repository root, validate every raw socket-written report and lifecycle:

    python3 twitch-screen-relay/tools/check_native_epoch_reset.py \
      "$HOME/.hermes/cache/scratch/relay-reset-evidence"

The reset fixture preserves one active and one pending old-epoch external card,
then publishes new-epoch sequence 1. It checks full FIFO completion, original
identities, independent new cursors, conserved credits and explicitly missing old
telemetry under preserved CPU preference. No fixed sleep triggers the reset: the
harness reacts to a socket-written active-hold report. It closes and verifies its
owned listener and reaps the native child. This invokes the real hub reset
transaction; it does not invoke the scheduled detector.

## Scheduled application clock correction

Use `automatic` instead of `reset` with `NativeDeskReconnect` to inject a ten-second
wall correction during the reported active hold. The installed one-second detector,
source owner and hub reset perform the transition; the runner does not call hub reset
for that mode. The same raw verifier checks exact fixture identity/FIFO/credit and
telemetry invalidation. Its historical `automaticResetVerified: false` field remains
conservative: the verifier is a lifecycle/credit oracle, not an independent audit of
application scheduling or complete system suspend/provider recovery. Read the fixture's
`automaticDetectorObserved` and tested-layer metadata alongside `RuntimeRecoverySuite`
and `RuntimeSourceFailureSuite` evidence. Neither mode enables default production DESK.

## Still pending

Complete live source replacement/application recovery and deliberately blocked/partial
writer/source/episode failure matrix; mounted monitoring/admin/feed; complete production
capability enablement.
Retained-eight/zero-credit/minimum-capacity and explicit reset already have scoped
internal-gated evidence, not default production acceptance. Physical/live acceptance
belongs to the exclusive integrator after prerequisites. Product acceptance remains
false. See [runtime ownership](runtime-ownership.md).
