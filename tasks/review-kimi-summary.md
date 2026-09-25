# Kimi review remediation — integrated result

Date: 2026-09-25. Original review: [REVIEW_SUMMARY.kimi.md](../REVIEW_SUMMARY.kimi.md).

Every finding explicitly named in that summary has a disposition in the four
registers below: implemented and tested, already resolved on the baseline, or
retained with a concrete contract/design rationale. The review's approximate
Low/Nit totals include an external swarm archive absent from this checkout;
unnamed archive findings are not claimed as individually verified.

| Scope | Finding-by-finding evidence |
| --- | --- |
| Twitch authorization, EventSub, polling and health | [Twitch register](review-kimi-twitch.md) |
| Relay protocol, sessions, replay and device API | [Device register](review-kimi-device.md) |
| ESP32 transport, codec, display and test harness | [Firmware register](review-kimi-firmware.md) |
| Configuration, HTTP, consumers, diagnostics and tools | [Integration register](review-kimi-root.md) |

## Workflow and review

The requested Ultracode workflow was unavailable locally. Native orchestration
used three isolated implementation worktrees and the integrating agent. Device
and Twitch agents cross-reviewed their changes; the integrator reviewed firmware.
Cross-review fixed two further auth/readiness races with regressions. The final
rebase onto `origin/main` at `d9e13ae` preserved concurrent RLY-55 session-ID
injection and tests; an independent merge review approved that resolution.
Changes are small conventional commits integrated directly on `main`.

## Final local validation

| Check on the rebased tree | Result |
| --- | --- |
| Relay full suite, compiler warnings as errors | 418 tests in 37 suites passed, none ignored |
| Scala formatting and `git diff --check` | Passed |
| Firmware native and ASan/UBSan | All 10 suite runs passed with `-Werror` |
| Firmware custom test-runner contracts | 5 tests passed; now part of CI |
| ESP32 build | Passed; 67,220 bytes RAM, 1,153,789 bytes flash in local build |
| Protocol parity | All 20 vectors and 213 prose assertions agree |
| Repository tooling | 11 Python tests and Python compilation passed |
| Resolved runtime dependency audit | 181 coordinates; 0 unexcepted advisories |
| Docker build checks and image assembly | Passed |
| Rebuilt image smoke, 512 MiB limit | Public/protected HTTP, redaction, WELCOME, STATS, injected EVENT and final shutdown BYE passed |

The two existing exact dependency exceptions still expire 2026-10-25; their
reassessment is recorded for 2026-10-18. No exception was broadened or extended.

## Deployment and compatibility notes

- H2 uses the review's documented trusted-home-LAN option, recorded in
  [ADR-0002](../docs/decisions/0002-device-link-trust-boundary.md). TSB/3 remains
  plaintext and unauthenticated. Flash credentials assume trusted physical
  access; USB remains the update path. TLS, encrypted provisioning and OTA were
  not implemented or represented as fixed.
- The device management response now nests counters under `traffic`; consumers
  must update field paths. Unknown notification timestamps are omitted rather
  than rendered as 1970. The [HTTP reference](../docs/reference/http-api.md)
  documents both changes and usable-grant authorization semantics.
- No physical board was flashed. Render-stack minimum, display appearance,
  watchdog expiry and real hardware outage recovery remain physical validation
  limits. Runtime diagnostics now support those measurements.
- No live Twitch account was used; provider behavior is covered through scripted
  adapters and transport tests. The existing local relay process and private
  credentials were preserved.
