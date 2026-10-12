# Changelog

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
