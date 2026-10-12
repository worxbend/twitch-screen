# DESK controller implementation status

The DESK controller and codecs are implemented and exercised by native tests.
The existing TCP LinkSession now has opt-in negotiation, dispatch, calibration and
atomic report delivery. The Arduino application uses the same controller for classic
cards, with real LVGL hold/exit/completion callbacks and input-before-render ordering.
Controller-selected Twitch/orbital bases now bind to production main; weather/CPU
fixed-object rendering adapters are exercised through actual Receiver codecs in the
LVGL memory display. Production main now enables the full DESK/1 profile only
through `initializeApplicationBoot`: base widgets must be built, notification
widgets and every lifecycle callback must exist, and boot identity must be nonzero.
The link advertises DESK only after this succeeds; classic relay fallback remains.
Connected-board relay/API pairing and physical LCD acceptance remain pending. The shared
`desk_application.h` greeting binding mutates classic baseline state only for a
classic effective-capability greeting; a DESK greeting preserves the Receiver's
staged session. Every `test_desk_link` welcome callback now uses this same binding,
including real synthetic TCP, full-retained reconnect and classic fallback tests.
The standalone `ui_orbit.cpp` adapter reuses three local arcs and one ASCII
broken-link glyph with a visible-only 50 ms timer. The actual LVGL memory-display
test covers fixed object/timer bounds, hidden pause, retained phase, gradual palette
drift and explicit teardown. Main now binds this adapter through `ui_base.cpp`, and
first boot renders disconnected orbit instead of CONNECTING. Physical B3 appearance
and resource acceptance remain pending.
Runtime initialization does not imply session readiness: matching calibrated
six-frame synchronization and SYNC_END are still required before telemetry shows.
Weather is relay-pushed; firmware never polls Open-Meteo or any HTTP provider.

The normative wire contract is [PROTOCOL.md, section 20](PROTOCOL.md#20-desk1--complete-additive-profile-normative).
This document describes implementation boundaries; it does not replace that contract.

## Ownership and interfaces

- `src/desk_clock.h`: wrapping-millisecond extension, session-scoped calibration,
  checked clock-domain translation and conservative deadlines. A new session cannot
  reuse an old calibration while awaiting a probe. Recalibration may only shorten
  a previously accepted local deadline.
- `src/desk_controller.h`: one transport-independent DESK policy owner. It owns
  synchronization readiness, base selection, eight pending cards, a separate active
  card, source/episode fences, cumulative credits and processed/enqueued cursors.
  Card records retain their original epoch, sequence and conservative deadline.
- `src/desk_view_boundary.h`: tested ordering for bounded input dispatch, fresh clock
  reconciliation, selection application and render/reveal. Its view is a rendering
  adapter, not another mode-selection owner.
- `src/ui_base.cpp` and `src/desk_ui_application.h`: rendering-only application
  adapter. Reconcile current time/policy before LVGL pumping and every real reveal.
  Fixed weather/CPU objects use static bounded text, enabled fonts and ellipsis.
  Missing optional humidity/memory stays empty; measured zero is shown. CPU uses
  memory as its only secondary metric. Label bytes unsupported by the ASCII font
  become underscores. Views never choose modes or own queues.
- `src/desk_state_codec.h`: typed time/control/weather/CPU/synchronization/barrier
  payloads, explicit byte operations and full-known-wire comparison.
- `src/desk_message_codec.h`: extended HELLO/WELCOME/STATS, TIME_REQUEST and
  DESK_EVENT. Classic prefixes use the existing codec's receiver rules. Invalid
  HELLO tails fall back; invalid negotiated WELCOME tails reject the handshake.
- `src/desk_report_codec.h`: the 244-byte DEVICE_REPORT payload and nine fixed
  entries. It permits retained original-epoch and epoch-zero legacy identities
  independently of the current cursor. A codec alone does not establish report
  scheduling, observation freshness or delivery.
- `src/desk_report_scheduler.h`: one replaceable unencoded report intent. It samples
  the current controller and calibration at whole-frame TX admission, coalesces
  changes with 200 ms minimum spacing and refreshes idle observations every 5 s.
  Its sink must accept the entire frame atomically or leave its FIFO unchanged.
  Failed admissions preserve intent and report revision. The host tests cover
  partially drained FIFO bytes, latest-state retries, zero-credit full reconnect,
  final empty state, stale clocks and old-session rejection. This scheduler is
  invoked by the opt-in TCP loop through `desk_receiver.h`.
- `src/desk_receiver.h`: loop-owned calibration, strict initial frame ordering,
  raw-known-byte revision fencing, generation-filtered STATS and report scheduling.
  STATS revision floors survive source switches within a connection. The shared
  `stats_projection.h` application mapping preserves availability; control alone
  projects unknown values when cached STATS belongs to the prior generation.
  Pre-render/reveal projection compare-skips identical samples so uptime still advances.
  Unknown/unavailable Twitch measurements display `--`, not measured zeros.
  Profile admission requires an explicit complete-profile flag and a nonzero boot
  identity. Production main opts into the complete profile with identity initially
  zero, then supplies identity through the checked real-UI boot initializer.
  Uninitialized applications still send classic HELLO.

Native structs are domain objects, never overlays on packed wire data. The largest
report frame is 252 bytes. The existing LinkSession now owns its specified 512-byte
TX FIFO and one replaceable uncommitted report intent. Partially transmitted bytes
are never replaced. Twenty-four bytes remain reserved for ACK/heartbeat traffic.

## Required integration order

1. Extend the existing session while preserving classic greeting, parser pause,
   EVENT ACK and vector behavior. Keep DESK advertisement disabled during partial
   integration.
2. Sample/extend `millis` before admission and rendering. Invalidate calibration on
   a loop gap over 60 seconds. Match probe, epoch and connection before accepting
   calibration; preserve converted deadlines across reconnection.
3. Decode and validate full known records before handing domain projections to the
   controller. Compare retained known payload bytes for equal revisions before
   receiver string normalization; ignore only appended unknown tails. A freshness
   projection alone cannot validate changed labels or measurements.
4. Stage the six-frame greeting and complete readiness only at matching SYNC_END.
   Missing sources are explicit records. A full retained FIFO grants zero credits
   but does not prevent synchronization or control reception.
5. Replace application queue/policy ownership with the controller, rather than
   copying the queue or adding another selection engine. Preserve legacy sequence
   behavior and migrate retained classic cards with unknown provenance/epoch zero.
6. Bind actual entrance/hold/exit callbacks. Call the tested boundary before LVGL
   pumping, before the first exit reveal and before completion reveal. Reconcile
   throughout slide-out. Input dispatch inside callbacks must remain bounded and
   nonblocking; no recursive LVGL pump or blocking socket calls.
7. Sample reports from committed controller state immediately before TX admission.
   Pending-to-active transfer releases one credit; active completion releases none.
   Reports must not block offline completion. Apply the 200 ms coalescing and
   5-second refresh rules without per-frame report traffic.

## Verified host behavior

The native controller tests cover override/preference/freshness priorities, explicit
missing-source synchronization, timeout recovery, old-session rejection, zero-credit
full-FIFO reconnection, terminal duplicate/expiry/suppression, refusal cursor holes,
original retained identities, source switches and rapidly coalesced Twitch episodes.

Presentation tests preserve active cards through expiry, control and link changes.
They test complete entrance/hold/exit occupancy, replay and severity estimates,
selection before first reveal and selection changes during slide-out. A fake input/
view boundary witnesses dispatch/apply/pump/reveal order. Deterministic sustained
traffic checks credit conservation, bounded queue size and FIFO after every mutation.
Clock tests cover normal u32 wrap, long loop gaps, both signs of clock offset, every
allowed RTT, calibration age, session replacement and arithmetic overflow. Maximum
weather freshness is tested at acceptance after an aged calibration, including its
60-second usable boundary; the horizon is not incorrectly measured from the old
calibration sample. Recalibration still cannot renew accepted local deadlines.

The codec suite reads all 23 canonical additive vectors directly from
`protocol/desk-v1-vectors.tsv`; it does not maintain a competing fixture copy.
It constructs independent typed models from fixture fields, compares complete encoded
bytes and exercises real decoding on unaligned payloads, every truncated payload,
strict required enums/reserved fields and optional present-zero versus absent values.
All 20 classic vectors remain unchanged.

Run from the repository root using an installed PlatformIO CLI:

```sh
pio test -d twitch-screen-firmware -e native
pio test -d twitch-screen-firmware -e native-sanitized
pio test -d twitch-screen-firmware -e native-lvgl
pio test -d twitch-screen-firmware -e native-lvgl-base
python3 tools/check_protocol_vectors.py
python3 tools/check_desk_contract.py
```

Use the same PlatformIO Python environment for
`twitch-screen-firmware/tools/test_runner_contract.py`. The README's dedicated
`.venv-pio` setup remains an alternative; these tests add no dependencies or pins.

## Still pending

The `test_desk_link` suite exercises actual `link_client.cpp` through fake short-write
transports and an actual loopback TCP socket with a synthetic peer. It checks strict
synchronization, zero-credit reconnect, control under full queue, clock/sync timeouts,
report coalescing and observation time, classic fallback and refusal holes. Actual
outgoing extended HELLO tests use the application's classic admission/capacity/cursor
hooks. They verify repeated classic reconnect replay and DESK-to-classic-to-DESK
migration: the classic prefix preserves the legacy cursor, while the epoch/processed
tail remains in its independent DESK sequence space. Retained cards preserve FIFO
and original identities through those transitions. This does
not pair with the production Scala relay. A deterministic 2,000-operation differential
test compares the controller's classic queue path with the unchanged NotifyQueue.
All ten suites pass in native and ASan/UBSan environments. The `native-lvgl` gate
runs actual notification UI source and LVGL animation/timer completion on a memory-only
display under ASan/UBSan: the card lifecycle suite covers content-first/bodyless
DESK layouts, classic layout preservation, routine, severity and replay cards,
offline completion and callback-before-uncover ordering. Only the base-cover
seam is replaced; this establishes no panel pixels or full DESK base widgets.
The complete ESP32 image
also compiles with synthetic credentials supplied from a scratch-only include path;
this is not a private configuration build or permission to flash that image.

The `native-lvgl-base` gate uses the actual base-cover adapter without replacing
that seam. It feeds Receiver with actual encoded greeting/control/weather/
CPU/event records and exercises LVGL callbacks. It verifies staged data never shows,
classic Twitch fallback, signed values, optional zero/absence, override, hold-time
expiry before first exit reveal, preference/expiry/link changes during visible exit,
and offline completion. This is software precursor evidence, not capable Scala relay
or physical layout acceptance. The additional `test_stats_view` suite runs actual
LinkSession dispatch, the shared application greeting/projection and actual LVGL labels
on the memory display. It distinguishes observed zero from unknown/unavailable chips,
viewers and uptime; rejects lower or conflicting equal STATS revisions across source
switches; invalidates prior-channel values on control alone, including during exit;
and preserves hidden-update behavior and full active-card completion. It also
captures and decodes actual outbound DEVICE_REPORT frames during real LVGL
entrance, hold, exit and completion callbacks. Reports retain active identity,
boot identity, monotonic observation/revision and exactly one credit released at
dequeue; completion clears the active slot without manufacturing another credit.
This uses a synthetic transport, not the production Scala relay. The card layout
opt-in is per card: classic cards keep their original geometry, including when
retained alongside DESK cards. A content layout never decides provenance.

Boot setup initializes one nonzero 64-bit correlation identity using two ESP32
hardware RNG samples, with a nonzero fallback. Receiver rejects zero and later
replacement; reconnect and classic fallback retain it. This is not authentication,
persistence or proof of uniqueness. Production profile readiness is enabled only
after actual base/card/lifecycle initialization succeeds. Host regressions exercise
rejected early initialization, all nine Weather conditions, C/F values and humidity
presence, original expiry on reconnect, and actual LinkSession Weather/CPU switches.

The normal existing-source `pio run -e esp32dev` build passes without changing or
inspecting private credentials. No board was accessed, flashed or inspected in this
firmware implementation slice. Memory-display tests do not establish panel pixels,
physical appearance or render/resource budgets. Real relay-device integration, LCD
layout/text fit, RGB565 appearance, watchdog/heap/stack targets and the 60-minute
mixed-mode soak remain separate required evidence, owned by the parent integrator.
ACK, DEVICE_REPORT and serial diagnostics never prove visible pixels.

Production `[view]` diagnostics are transition-only and drop rather than block on
UART backpressure. `base` codes are connected orbit=0, disconnected orbit=1,
Twitch=2, Weather=3, CPU=4. `reason` codes follow PROTOCOL section 20; selected=0,
relay down=1, synchronizing=2, missing=4, stale=5. `weatherRevision` identifies a
selected Weather application update; `covered=1` means a card hides that base.
`deskReady=1` reflects synchronized application state, not visual acceptance.
