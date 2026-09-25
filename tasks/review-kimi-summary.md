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

## Round 3 (ultracode swarm)

Date: 2026-09-25. The planner listed 181 findings from
[REVIEW_SUMMARY.kimi.md](../REVIEW_SUMMARY.kimi.md) as K-001 to K-181 and checked
each one against `origin/main`. Four lanes (relay-twitch, relay-core, firmware,
repo) worked on the open ones in isolated worktrees. Each unit was written test
first, reviewed by independent judges, rebased and fast-forwarded onto `main`
with no force pushes. The evidence for each unit is in the lane records:

- [review-kimi-round3-relay-twitch.md](review-kimi-round3-relay-twitch.md)
- [review-kimi-round3-relay-core.md](review-kimi-round3-relay-core.md)
- [review-kimi-round3-firmware.md](review-kimi-round3-firmware.md)
- [review-kimi-round3-repo.md](review-kimi-round3-repo.md)

| Disposition | Count |
| --- | --- |
| Closed on audit: already resolved on `main` before round 3 | 132 |
| Fixed this round, in 38 commits | 44 |
| User decision: owner-level threat-model items | 3 |
| Skipped: needs hardware or the owner's secrets file | 2 |
| Rejected | 0 |

No unit was rejected or left uncommitted. K-139 was left out of the
planner's lists, so the integrator audited it by hand. It was already fixed in
1918581 and is counted under closed on audit. The 132 audit closures are the
planner's result, based on the earlier registers above. The integrator did not
re-verify them one by one.

### Integrated validation (origin/main `c23f2fc`, fresh worktree)

| Check | Result |
| --- | --- |
| Relay `compile` with `-Werror` | Passed |
| Relay full `./mill test` | 49 suites, 527 tests, 0 failed, 0 ignored. It passed on the first run, so no rerun was needed |
| `checkFormatAll` | 173 Scala sources passed |
| Firmware `native` + `native-sanitized` | 10/10 suite runs passed. The runner contract passed 5 tests |
| ESP32 `esp32dev` build (example credentials) | Passed: 67,276 B RAM, 1,154,981 B flash |
| Protocol golden vectors | All 20 vectors agree across the spec, relay and firmware |
| `tools/tests`, `compileall` | 18 tests OK; compileall OK |
| Runtime dependency audit | 181 coordinates, 2 excepted advisories, 0 unexcepted |
| `docker build --check`, image build, container smoke | Passed: public/protected HTTP, redaction, WELCOME, STATS, EVENT and shutdown BYE (512 MiB) |
| actionlint | Not installed on the integration host, so it was not run |

Integration needed no fixes.

### Residual gaps and known flakes

- **Owner decisions still open.** K-002 (H2), K-177 (FW-10) and K-180 (FW-18)
  are one decision about the threat model. See
  [ADR-0002](../docs/decisions/0002-device-link-trust-boundary.md).
- **Hardware and owner actions.** K-109 needs a stack high-water measurement
  on a real board. K-169 is a one-line comment in the owner's git-ignored
  `src/credentials.h`. K-118's reduced flush area was not checked on a device.
  No board was flashed this round.
- **Flakes seen in lane runs** (none showed up in the integrated run):
  - The K-017 trickle-body test in `ManagementAuthSuite` failed once under
    parallel load.
  - The K-057 PONG-failure fixture flaked. Its root cause was fixed in 544a872.
  - `DeviceLinkSuite` has a `bytesSent` read-after-write race.
  - `SequenceExhaustionSuite:134` failed once.
  - Firmware `test_presentation` errored once and passed when rerun.
- **Weak regression guards noted by judges.**
  - K-011: the callback race is proven only by a pure test.
  - K-086: removing the Done-branch close shows only as a timing change,
    because a 2 s backstop still closes the socket.
  - K-025/K-107/K-118: the LVGL glue is not built on the host.
- **Operator-visible changes.**
  - `delivered` in `/api/v1/status` subscribers now counts handler completions,
    and a new `enqueued` field was added (K-153).
  - Startup-log section labels are now kebab-case HOCON names (K-101).
  - A misspelled relay config key now fails startup (K-016). A misspelled key
    given only as a `-D` system property is still ignored, but it is masked.
  - The accept-failure WARN now includes the consecutive-failure count (K-147).
- **Stale note.** `tasks/review-kimi-device.md` still marks KIMI-P15 as
  "retained deliberately". K-141 (c23f2fc) replaced that decision.

### Every finding

| ID | Severity | Finding | Disposition | Evidence |
| --- | --- | --- | --- | --- |
| K-001 | High | H1 Hardcoded scope requirements cripple WebSocket EventSub transport (all registrations gated on follow scope; missing demands unrequested scopes) | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-002 | High | H2 Device link is plaintext and unauthenticated (threat model decision) | User decision | Owner: keep trusted-LAN model or add TLS / pre-shared HELLO token ([ADR-0002](../docs/decisions/0002-device-link-trust-boundary.md)) |
| K-003 | Medium-bug | FrameReader timeout escapes as SocketTimeoutException instead of FrameTimeout value | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-004 | Medium-bug | DeviceHub tell operations can kill the hub actor (Ox 1.0.8 rethrows into fork, fails supervised scope) | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-005 | Medium-bug | Reclaim rate budget is global (16/min hub-wide), not per device id | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-006 | Medium-bug | AlertMonitor blocks its fold thread on a full hub snapshot every tick | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-007 | Medium-bug | Credential handle published out of order; no happens-before edge to twitch4j readers | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-008 | Medium-bug | /twitch/authorization 'authorized' reports true for expired/rejected grants | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-009 | Medium-bug | Every ingestion (re)start publishes up to 3 false failure events | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-010 | Medium-bug | Flow consumers (StatsAggregator, AlertMonitor folds) have no failure isolation | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-011 | Medium-bug | Non-atomic compound read of TwitchAuth state (current, accessToken, scopes) | Fixed this round | `83c2be2`; [relay-twitch](review-kimi-round3-relay-twitch.md) |
| K-012 | Medium-bug | Webhook dedup ID consumed before dispatch (redelivery after failed dispatch silently dropped) | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-013 | Medium-bug | Subscription reconciliation reads one page and matches only current callback URL | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-014 | Medium-bug | TokenFile assumes a full single channel.write (short write can replace good token file) | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-015 | Medium-bug | Ping/idle cross-field check compares untruncated durations against truncating wire | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-016 | Medium-bug | Unknown/mis-spelled secret keys render unmasked in /config | Fixed this round | `658e7ad`; [relay-twitch](review-kimi-round3-relay-twitch.md) |
| K-017 | Medium-bug | No idle/read timeout on HTTP listener (slowloris exhausts 128 connections) | Fixed this round | `08fcaab`; [relay-twitch](review-kimi-round3-relay-twitch.md) |
| K-018 | Medium-bug | Cleartext management credentials with no warning (plain HTTP, host 0.0.0.0 default) | Fixed this round | `cebab02`; [relay-twitch](review-kimi-round3-relay-twitch.md) |
| K-019 | Medium-bug | Duplicate WELCOME mid-stream silently rewinds session state | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-020 | Medium-bug | Task watchdog init silently no-ops; effective timeout 5 s not 10 s | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-021 | Medium-bug | DNS Pending has no deadline; lost lwIP callback wedges the link forever | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-022 | Medium-bug | Full-queue pause disables silence timeout without upper bound | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-023 | Medium-bug | App-level Serial.printf bypasses nonblocking logging discipline | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-024 | Medium-bug | CONNECTING timers/animations churn the heap while hidden | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-025 | Medium-bug | Per-STATS label updates are unconditional (including hidden group) | Fixed this round | `938019a`; [firmware](review-kimi-round3-firmware.md) |
| K-026 | Medium-bug | gen_vectors.py prose cross-check can silently degrade to a no-op | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-027 | Medium-bug | Greeting tests never assert quiescence | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-028 | Medium-bug | Session test assertion weaker than its name (only >= 56 bytes of HELLO) | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-029 | Medium-bug | ASCII assertion sweeps binary fields, holds only by accident | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-030 | Medium-bug | byeAdvice offers BYE code 4 (InvalidSequence) that spec §6.7 forbids | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-031 | Medium-bug | Firmware HELLO strings (DEVICE_ID/fw_version) bypass §9 sender rules | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-032 | Medium-smell | DeviceHubState Large Class / Divergent Change | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-033 | Medium-smell | Queue-BYE-then-close duplicated 4x | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-034 | Medium-smell | Per-kind policy scattered across four matches | Fixed this round | `93e7e80`; [relay-core](review-kimi-round3-relay-core.md) |
| K-035 | Medium-smell | Two construction vocabularies for EVENT semantics (EventRecord factories vs NotificationRouter) | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-036 | Medium-smell | DeviceSession Long Parameter List / Data Clump (sink, counters, config, caps[, socket]) | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-037 | Medium-smell | One new counter = five edits in two files | Fixed this round | `67b7645`; [relay-core](review-kimi-round3-relay-core.md) |
| K-038 | Medium-smell | maintainSubscriptions Long Method + transport Switch Statements | Fixed this round | `234eb5e`; [relay-twitch](review-kimi-round3-relay-twitch.md) |
| K-039 | Medium-smell | Scope-name literals scattered across five sites | Fixed this round | `2bcca20`; [relay-twitch](review-kimi-round3-relay-twitch.md) |
| K-040 | Medium-smell | Health components keyed by raw strings at 19 call sites | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-041 | Medium-smell | Three near-identical attempt variants | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-042 | Medium-smell | Duplicated transport-to-domain mapping (RLY-20 follow-through) | Fixed this round | `b142b2a`; [relay-twitch](review-kimi-round3-relay-twitch.md) |
| K-043 | Medium-smell | HelixPoller 9-param signature + closures into TwitchAuth | Fixed this round | `7f1fa24`; [relay-twitch](review-kimi-round3-relay-twitch.md) |
| K-044 | Medium-smell | Anemic config types / Feature Envy (TwitchConfig.validate enforces sub-config invariants) | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-045 | Medium-smell | Config reader boilerplate duplicated 9x | Fixed this round | `5f68358`; [relay-twitch](review-kimi-round3-relay-twitch.md) |
| K-046 | Medium-smell | Magic numbers for HTTP operational limits (128, 65536 also in 413 body) | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-047 | Medium-smell | TwitchAuth side-effecting constructor (creates Actor, mutates foreign handle) | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-048 | Medium-smell | TwitchAuth Divergent Change (token lifecycle + OAuth state CSRF jar) | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-049 | Medium-smell | EncodeResult uses in-band negative sentinels | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-050 | Medium-smell | link_client.cpp is a C-style singleton (~20 file-scope mutable globals) | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-051 | Medium-smell | deliverFrame Long Method (five responsibilities) | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-052 | Medium-smell | Five parallel switches on NotifyKind | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-053 | Medium-smell | clearDecor/panel-size duplicated across UI files (240,240 appears 5x) | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-054 | Medium-smell | uiNotifyShow Long Method | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-055 | Low | Operator disconnect drops queued frames where reclaim drains | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-056 | Low | Over-limit connections refused silently (no log/counter) | Fixed this round | `41f1138`; [relay-core](review-kimi-round3-relay-core.md) |
| K-057 | Low | Failed PONG write discarded | Fixed this round | `a43a815, deflake 544a872`; [relay-core](review-kimi-round3-relay-core.md) |
| K-058 | Low | Oversize-drop keeps link after EVENT drop (latent) | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-059 | Low | Dead refresh token retried forever with no terminal state | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-060 | Low | CAS-lost refresh leaves an unrevoked rotated token at Twitch | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-061 | Low | Spurious 'recovered' on first health observation | Fixed this round | `846866a`; [relay-twitch](review-kimi-round3-relay-twitch.md) |
| K-062 | Low | Link-down card discards the failure reason | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-063 | Low | Absent webhook 'event' masked by all-None payload | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-064 | Low | Chat/WS path doesn't null-normalize channel-update fields (RLY-21 residual) | Fixed this round | `b142b2a`; [relay-twitch](review-kimi-round3-relay-twitch.md) |
| K-065 | Low | Callback URL validation case-sensitive | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-066 | Low | twitch.oauth.scopes never validated in live mode | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-067 | Low | Sub-millisecond intervals admitted | Fixed this round | `08cd1c1`; [relay-twitch](review-kimi-round3-relay-twitch.md) |
| K-068 | Low | Memory-sizing config knobs unbounded | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-069 | Low | /status blocks on an unbounded hub ask | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-070 | Low | Readiness/auth-config default bypasses validation | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-071 | Low | Alert message freezes elapsed duration at first raise | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-072 | Low | Alert monitor runs with zero rules | Fixed this round | `544a872`; [relay-core](review-kimi-round3-relay-core.md) |
| K-073 | Low | EventBus subscriber overflow is silent | Fixed this round | `2b44bb5`; [relay-core](review-kimi-round3-relay-core.md) |
| K-074 | Low | Basic-auth timing fingerprint | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-075 | Low | Basic-permit exhaustion yields 503 for legit logins, undocumented at site | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-076 | Low | Case-sensitive Host/Origin compare | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-077 | Low | TokenFile leaves stale .tmp copy | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-078 | Low | TokenFile does not re-check permissions | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-079 | Low | TokenFile unbounded load | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-080 | Low | Webhook freshness checked before HMAC | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-081 | Low | AttachedDevice Data Class + Feature Envy | Fixed this round | `db559f8`; [relay-core](review-kimi-round3-relay-core.md) |
| K-082 | Low | Replay-ring merge duplicated | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-083 | Low | FrameReader.read Long Method | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-084 | Low | Version-mismatch Refusal duplicated | Fixed this round | `a43a815`; [relay-core](review-kimi-round3-relay-core.md) |
| K-085 | Low | protocolVersion: Int Primitive Obsession | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-086 | Low | Boolean blindness in DeviceSession write loop | Fixed this round | `0cb01ad`; [relay-core](review-kimi-round3-relay-core.md) |
| K-087 | Low | Boolean blindness in WebhookDeduplication.claim | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-088 | Low | Boolean blindness in ManagementAuth.checkBasic | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-089 | Low | Six never-read EventSubPayload fields | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-090 | Low | awaitCredential test-only dead code | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-091 | Low | Webhook API constructed unconditionally | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-092 | Low | Stringly-typed Either[String, _] OAuth errors | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-093 | Low | Bus-fold scaffolding duplicated (AlertMonitor vs StatsAggregator) | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-094 | Low | Rule knowledge shotgun (AlertRule + MonitorState) | Fixed this round | `544a872`; [relay-core](review-kimi-round3-relay-core.md) |
| K-095 | Low | Three parallel RelayEvent classifications | Fixed this round | `39a70c5`; [relay-core](review-kimi-round3-relay-core.md) |
| K-096 | Low | Bounded-append ring implemented 3x | Fixed this round | `7911556`; [relay-core](review-kimi-round3-relay-core.md) |
| K-097 | Low | Otel appender install unscoped | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-098 | Low | Global resize in composition root | Fixed this round | `bd24d3e`; [relay-core](review-kimi-round3-relay-core.md) |
| K-099 | Low | ConfigApi.resolved() does a second independent config load | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-100 | Low | Sensitive.Empty sentinel instead of Option | Fixed this round | `1c20a49`; [relay-twitch](review-kimi-round3-relay-twitch.md) |
| K-101 | Low | Config section registries restated 4x (ConfigApi Shotgun Surgery) | Fixed this round | `89e7048`; [relay-twitch](review-kimi-round3-relay-twitch.md) |
| K-102 | Low | Triplicated enum config readers | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-103 | Low | Backoff burned while DNS pending / close-worker busy | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-104 | Low | WELCOME.caps trusted verbatim as effectiveCaps | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-105 | Low | Backoff reset contradicts PROTOCOL.md §12 | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-106 | Low | xTaskNotifyGive(closeTask) unguarded null handle if worker creation failed | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-107 | Low | Replayed cards still play slide-in despite comment/§6.4 | Fixed this round | `10f6d70`; [firmware](review-kimi-round3-firmware.md) |
| K-108 | Low | initDsc trusts generator size with no static_assert | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-109 | Low | LVGL render stack headroom on 8 KB loop task unmeasured | Skipped (owner/hardware action) | Needs a physical ESP32 run (~10 min worst-case load, stack high-water log); instrumentation already in `main.cpp` |
| K-110 | Low | LVGL boot allocations unchecked -> panic boot loop | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-111 | Low | Two overlapping WiFi reconnect policies | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-112 | Low | Header extraction duplicated in proto_codec | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-113 | Low | Encode->check->write boilerplate x4 | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-114 | Low | byeFloorMs/byeForceMax temporary-field clump | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-115 | Low | static const in header instead of constexpr | Fixed this round | `2f55a86`; [repo](review-kimi-round3-repo.md) |
| K-116 | Low | Magic numbers (3072, 15000, shift caps 6/5, watchdog 10) | Fixed this round | `5e33873`; [firmware](review-kimi-round3-firmware.md) |
| K-117 | Low | Uptime/viewer labels realloc every tick | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-118 | Low | Every live card pays two full-screen animations | Fixed this round | `8c1070d`; [firmware](review-kimi-round3-firmware.md) |
| K-119 | Low | Infinite animations run on hidden groups | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-120 | Low | LV_USE_FLOAT 1 unused | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-121 | Low | TX FIFO O(n) memmove per partial write | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-122 | Low | WiFi PSK baked into flash without flash encryption | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-123 | Low | Fuzz harness is differential-only | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-124 | Low | Relay greet predicate transcribed into C++ test | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-125 | Low | Real-sleep timing assertion | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-126 | Low | Host test builds lack -Werror | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-127 | Low | TTL clamp boundary 6000/6001 untested on both sides | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-128 | Low | Magic timing literals in session tests | Fixed this round | `214c730`; [firmware](review-kimi-round3-firmware.md) |
| K-129 | Low | PONG-overflow probe can block for minutes | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-130 | Low | Crash before summary leaves no test record | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-131 | Low | formatCount rounding boundaries untested | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-132 | Low | smoke_container.py has third hard-coded copy of vector V1 and never asserts STATS/EVENT delivery | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-133 | Low | check_protocol_vectors.py brittle anchors fail with bare tracebacks | Fixed this round | `ba40347`; [repo](review-kimi-round3-repo.md) |
| K-134 | Low | LogBuffer redaction regex compiled per log line x3-4 fields | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-135 | Low | WireStrings.fold compiles regex + allocates per character on hot encode path | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-136 | Low | Suppressed-bot log builds event.summary eagerly at INFO | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-137 | Low | Per-skipped-frame DEBUG builds describe eagerly | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-138 | Low | Identical frames re-encoded per session | Fixed this round | `9f313b2`; [relay-core](review-kimi-round3-relay-core.md) |
| K-139 | Low | Dedup claim is O(n) per CAS retry | Closed on audit | Missing from the round-3 plan; the integrator checked it: `WebhookDeduplication` tracks `nextExpiry` and prunes only when an entry is due ([Twitch register](review-kimi-twitch.md), 1918581) |
| K-140 | Low | 50 ms deadline watcher per session | Fixed this round | `db41f1c`; [relay-core](review-kimi-round3-relay-core.md) |
| K-141 | Nit | Unreachable WrongDirection fallbacks | Fixed this round | `c23f2fc`; [relay-core](review-kimi-round3-relay-core.md) |
| K-142 | Nit | Dead EventValue.toInt truncation hazard | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-143 | Nit | Absent wire timestamp surfaces as 1970 in HTTP API | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-144 | Nit | WireStrings.truncate breaks cap contract for width <= 3 | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-145 | Nit | Folding gaps for ẞ/ligatures | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-146 | Nit | Deadline overflow on absurd budgets | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-147 | Nit | Accept-failure logging goes silent at the cap | Fixed this round | `41f1138`; [relay-core](review-kimi-round3-relay-core.md) |
| K-148 | Nit | 'attached as #N' logged on refused attach | Fixed this round | `cd61e30`; [relay-core](review-kimi-round3-relay-core.md) |
| K-149 | Nit | Write watchdog uses idle timeout during handshake | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-150 | Nit | Manual lifecycle card re-broadcasts STATS | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-151 | Nit | Heartbeat PING drops uncounted | Fixed this round | `cd61e30`; [relay-core](review-kimi-round3-relay-core.md) |
| K-152 | Nit | Duplicate subscriber names allowed | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-153 | Nit | SubscriberStats.delivered overstates delivery | Fixed this round | `2b44bb5`; [relay-core](review-kimi-round3-relay-core.md) |
| K-154 | Nit | Actor mailboxes implicitly bounded at 16 — undocumented load-bearing invariant | Fixed this round | `16add4f`; [relay-twitch](review-kimi-round3-relay-twitch.md) |
| K-155 | Nit | lowerCaseEnums dead with stale comment | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-156 | Nit | WWW-Authenticate sent on 403/503 | Fixed this round | `cb4505d`; [relay-twitch](review-kimi-round3-relay-twitch.md) |
| K-157 | Nit | Redundant endsWith(CallbackPath) require | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-158 | Nit | Hostname accepts embedded whitespace | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-159 | Nit | Scalar fallback silently coerces non-lists | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-160 | Nit | Negative uptime on backward clock step | Fixed this round | `6826a26`; [relay-core](review-kimi-round3-relay-core.md) |
| K-161 | Nit | Pipeline insertion couples to Netty internal handler name | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-162 | Nit | Unreachable terminal throw in TwitchRetry | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-163 | Nit | TwitchRetry has no jitter | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-164 | Nit | Optional.orElse(null) round-trips | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-165 | Nit | Raw-hex kindIsKnown duplicating the enum | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-166 | Nit | '== 0' instead of nullptr throughout proto_codec.cpp | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-167 | Nit | Unused includes | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-168 | Nit | Chained ternary for CONNECTING dots | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-169 | Nit | Stale 'protocol v2' comment in local credentials.h | Skipped (owner/hardware action) | One comment line in the owner's git-ignored `src/credentials.h`; the tracked template is already correct |
| K-170 | Nit | Unseeded random() jitter | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-171 | Nit | Two counters lack descriptions | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-172 | Nit | Redundant ACK test case | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-173 | Nit | asInstanceOf instead of named failure | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-174 | Nit | Single-case HealthStatus enum | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-175 | Theme | twitch4j/OAuth error text reaches device display unsanitized | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-176 | Theme | Activity export and on-screen alert cards don't share sanitization policy | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-177 | Theme | BYE retry_after uncapped floor (FW-10, ~18 h suppression) remains valid | User decision | Owner, together with H2: if a cap is chosen, change PROTOCOL.md §12.1 first, then clamp `floorMs` |
| K-178 | Theme | Dependency exceptions expire 2026-10-25 (RLY-41) | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-179 | Theme | TSB/3 section 6 frame layouts exist 3x (Tsb3.scala, proto_codec, hand-written test builders): cross-language drift risk | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
| K-180 | Theme | No OTA update path (FW-18): a known, deferred item that still applies | User decision | Owner, together with H2: OTA needs authenticated/signed images first; FW-18 stays open |
| K-181 | Theme | Two error philosophies coexist: the protocol package is total (Either), but exceptions leak at its edges (FrameReader timeout, Ox fork semantics) | Closed on audit | Already resolved on `main` before round 3; see the round-1/2 registers |
