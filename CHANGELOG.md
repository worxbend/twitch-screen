# Changelog

## Unreleased

## v0.1.0-beta.3 — 2026-10-13

Source-only verification correction; firmware presentation behavior is unchanged.
The published beta.2 tag is retained rather than rewritten.

### Fixed

- Restart verification distinguishes legitimate card completion during an outage
  from identity relabeling. Active identity, remaining FIFO and reveals follow
  actual callback/observation chronology, not an assumed cold-start duration.
- Strict retention, original holds, expiry, cursor reset, credit conservation and
  source/session fencing remain checked; delayed restart and negative mutations
  supplement the active-overlap path.

### Limitations

- Beta.2's main checks passed, but its tag integration and one rerun failed the
  timing-specific restart assertion. This correction does not change beta.2's
  historical check results or move its tag.
- No new hardware flash, physical LCD/provider/desktop acceptance, binary-rights
  clearance or Pages deployment is claimed. All beta.2 source-only exclusions
  and the two dependency exceptions expiring 2026-10-25 remain unchanged.

## v0.1.0-beta.2 — 2026-10-13

Source-only prerelease. Binary redistribution and complete product acceptance
remain subject to [third-party notices](THIRD_PARTY_NOTICES.md) and
[implementation status](docs/implementation-status.md).

### Changed

- Generic holds each photo for one minute of visible time instead of five seconds.
  Random network downloads and weather text overlays are unchanged.
- Native Twitch chat appears without slide animations and adapts its hold to
  queue pressure: 2.5 seconds when quiet, 500 ms with one or two waiting cards,
  and 250 ms with three or more. New arrivals accelerate an active chat card
  without changing FIFO order. Other notifications and explicit HTTP durations
  keep their existing holds.

### Fixed

- SSE termination is transport-cancellation-owned, preventing concurrent stream
  completion from starving virtual-thread carriers on two-CPU JVMs. Real-socket
  regressions fill all eight admissions, disconnect/reopen and shut down at full
  admission with both two and eight processors. CI integration pins the smaller
  topology; harness cleanup preserves failures and kills/reaps hung test-owned JVMs.
- DESK delivery and encoding accept the receiver-default zero hold only for
  native Twitch Chat. Hub-to-wire regressions preserve provenance, deadlines
  and explicit-duration validation for all other publications.
- Admin-panel source/report expiry now advances on local deadlines while SSE
  stays open; one elapsed-time sample controls rendering and the next deadline.
- The native application peer refreshes waiting backlog after starting a card,
  matching ESP32 behavior at adaptive-chat pressure thresholds.
- Severity-card occupancy estimates match the actual two-pulse LVGL entrance;
  the existing animation itself is unchanged.
- Relay test fixtures no longer require a pre-existing `TMPDIR`. Integration
  rejects missing immutable baseline sources with an actionable diagnostic.

### Verification and repository maintenance

- Expanded controller and real-LVGL regressions for explicit TTLs, mixed queues,
  late pressure, monotonic shortening, expiry/suppression, clock rollover,
  lifecycle/reveal order and full-minute photo boundaries.
- CI prepares isolated scratch directories, fetches baseline history, runs fresh
  forked relay tests, disables checkout credential persistence and requires all
  six verification jobs to succeed through a fail-closed aggregate check.
  Only allowlisted synthetic baseline diagnostics are retained for seven days.
- Restored the missing root agent instructions and corrected contributor build,
  Compose, authentication and verification guidance, with regression checks.
- Local full relay, five native firmware environments, ESP32 compilation,
  contract/tool tests, companion source/installed wheel, container smoke and
  actual Main/native/Compose/browser restart, process-gap and authority gates
  passed. Site unit/build/artifact and source/exact-artifact browser checks passed.
- No new firmware upload, physical LCD inspection, live-provider consent,
  actual machine-suspend or complete desktop/mobile/product acceptance is claimed.
  Dependency audit retains two reviewed exceptions expiring 2026-10-25;
  no exception was extended. No binaries, private evidence or configured
  credentials are release assets, and no Pages deployment is included.

## v0.1.0-beta.1 — 2026-10-12

First tagged, source-only preview. This is not a declaration of complete product
acceptance. See [implementation status](docs/implementation-status.md) and
[third-party notices](THIRD_PARTY_NOTICES.md) before packaging or redistributing.

### Added

- DESK/1 control and reporting alongside the existing TSB/3 device link, with
  Generic, Twitch, Weather and CPU display selection and notification precedence.
- Relay admin controls, live snapshot/event observations, CPU and weather inputs,
  persisted runtime settings and built-in Open-Meteo weather fetching.
- Linux companion sources, packaging, configuration and desktop-integration tests.
- Standalone Generic random network photos with Kyiv weather text over every
  photo, independent Wi-Fi/internet/relay states and an offline breathing display.
- Protocol fixtures, runtime/integration verification tools, expanded CI, setup
  documentation, licensing maps and a prepared static project site.

### Fixed

- Generic now requests a new random Picsum image over HTTPS for each prefetch,
  follows its signed Fastly URL and never inserts a weather-only slideshow item.
  The current photo remains visible when the next download is late or fails;
  random provider selection can repeat an image.
- Progressive JPEG decoding packs RGB565 by row and the display uses smaller
  partial tiles, preserving contiguous byte-addressable memory on ESP32 WROOM.
- CPU host text clears the inner circular gauge rather than masking its border.

### Verification and limitations

- The final Generic firmware was built, flashed and observed on the connected
  ESP32: 26 random network downloads and consumed image generations in a bounded
  three-minute run, fresh Kyiv weather, and no observed TLS/decode/watchdog errors.
- Focused native renderer tests verify weather overlays, circular label bounds,
  five-second visible photo intervals and retention of a valid photo on failure.
- Final physical LCD appearance, full independent review, broader provider/desktop/
  mobile acceptance and complete distribution-rights clearance remain open.
  The requested sixty-minute soak was waived, not verified.
- No private credentials, configured firmware binaries, generated build outputs
  or local agent evidence are release assets. No GitHub Pages deployment is part
  of this release. Firmware/media/CAD distribution restrictions still apply.
