# Kimi remediation: relay device and protocol

Scope: every device/protocol finding named in `REVIEW_SUMMARY.kimi.md`, including
structural, low, test, performance and nit entries. Baseline: `d7863f1`.
The review has no individual IDs below H1/H2; the local IDs below identify its
quoted findings for commit traceability.

## Protocol and notification model

| ID | Review finding | Disposition |
| --- | --- | --- |
| KIMI-P01 | FrameReader socket timeout escapes | Fixed: socket timeouts become `FrameTimeout`; explicit budgets retain their duration; an implicit socket timeout reports unknown duration as zero. Other I/O failures remain an explicitly documented transport boundary. |
| KIMI-P02 | FrameReader long read method | Extracted header-window resynchronization/budget state into `HeaderSearch`; the read loop orchestrates header, skip and body reads. |
| KIMI-P03 | Deadline overflow; real-sleep test | Deadline uses elapsed monotonic time, with an injectable monotonic clock. Deterministic trickle and nanoTime wraparound regressions replace the real-sleep codec test. |
| KIMI-P04 | Per-kind policies scattered | TTL, placeholder folding, generic capability and durable replay policies live on `NotificationKind`; existing `DisplayTtl.defaultFor` delegates to this source. |
| KIMI-P05 | Test-only EventRecord construction vocabulary | Removed all semantic factories from production `EventRecord`; retained independent golden-vector fixture builders as test-only `EventRecords`. Production uses the router/EventRequest mapping. |
| KIMI-P06 | WireStrings regex/per-character allocation | Replaced regex collapse and printable-character strings with a single builder pass. ASCII bypasses normalization and map lookup. |
| KIMI-P07 | Narrow truncate width exceeds capacity | Widths below four now emit at most `max(0, width-1)` dots; regression covers widths -1 through 4. |
| KIMI-P08 | Capital sharp S and ligatures | Added explicit capital sharp S and Unicode presentation ligature mappings, with regression coverage. |
| KIMI-P09 | ASCII assertion includes binary fields | Assertion covers actor/text bytes only, with a realistic non-ASCII-valued timestamp in the frame. |
| KIMI-P10 | TTL 6000/6001 boundaries missing | Added exact receiver-clamp boundary tests. |
| KIMI-P11 | Dead EventValue.toInt hazard | Removed unused lossy conversion. |
| KIMI-P12 | Absent wire timestamp becomes 1970 | `Notification.at` and `Notification_OUT.at` preserve `Option[Instant]`. Production-assigned times remain present. |
| KIMI-P13 | Redundant ACK assertion; cast assertions | Removed duplicate unchanged ACK decode; protocol boundary tests pattern-match with named failure messages. |
| KIMI-P14 | InvalidSequence advises forbidden BYE4 | **Already fixed at baseline.** `ProtocolError` has no InvalidSequence variant or mapping. Existing exhaustive variant coverage proves no error advises 4 or 6. Reserved `ByeCode` values remain decodable for peer diagnostics. |
| KIMI-P15 | Unreachable WrongDirection fallback arms | **Retained deliberately.** `expect` returns the complete `MessageType` enum, and the direction-specific decoder must remain total/exhaustive. The fallback is defensive and side-effect-free; deleting it requires casts, throws, or a second type hierarchy with no protocol benefit. |

## Device lifecycle and structure

| ID | Review finding | Disposition |
| --- | --- | --- |
| KIMI-D01 | Hub tell exception kills actor | Every mutation uses actor `ask`; failure is delivered to the caller while the actor stays alive. Regression injects a clock failure and then successfully publishes again. |
| KIMI-D02 | Reclaim budget is global | `ReclaimPolicy` maintains an independent 16-per-60-second window per device ID and expires inactive windows; deterministic tests cover independent devices and the exact 60-second boundary. |
| KIMI-D03 | DeviceHubState large class | Extracted `ReplayBuffers`/`ReplayRing`, `ReclaimPolicy`, and `SequenceAllocator`, preserving actor confinement. |
| KIMI-D04 | Queue-BYE-then-close repeated | Shared `queueFinalBye` helper; attached devices own the operation on their queue. |
| KIMI-D05 | AttachedDevice data class/feature envy | Capability filtering and final-BYE admission moved onto the attached device. |
| KIMI-D06 | Replay merge/bounded append duplicated | One ordered merge in `ReplayBuffers`; one bounded append in `ReplayRing`. |
| KIMI-D07 | SessionIo parameter clump | Session socket, sink, counters, config and negotiated capabilities/text policy travel in `SessionIo`. |
| KIMI-D08 | Version refusal duplicated; raw protocolVersion | Shared `versionRefusal`; internal attach requests use `ProtocolVersion`, while HTTP keeps the integer representation. |
| KIMI-D09 | Boolean write loop | Named `WriteResult` replaces the write-success Boolean; the recursive writer separates queue completion, encoding policy, and socket failure. |
| KIMI-D10 | Operator disconnect drops queued frames | Disconnect detaches admission, queues final BYE, and waits outside the actor for the writer to drain, with a two-second close deadline. Socket regression asserts queued EVENT, BYE, EOF in order. |
| KIMI-D11 | Connection-cap refusal silent; accept logging stops at cap | First and every hundredth session-limit rejection logs a cumulative count. Accept failure count no longer saturates at 31; every tenth continuing failure remains visible. |
| KIMI-D12 | Failed PONG ignored | Failed direct PONG immediately ends read handling with WriteFailed. A failed sink becomes terminal, and failed writes never increase sent counters. |
| KIMI-D13 | Oversized EVENT can leave sequence gap alive | Writer stops the session on an oversized EVENT; replaceable frames may still drop. This guard remains unreachable with current validated 256-byte limit and fixed 176-byte EVENT. |
| KIMI-D14 | Greeting tests only check prefixes | Fresh, reconnect/replay, and ahead-of-relay greetings assert 200 ms quiescence after the expected burst. |
| KIMI-D15 | PONG overflow test may block for minutes | Whole search has a two-second deadline as well as a finite frame count. |
| KIMI-D16 | Session test timing magic numbers | Named quiescence, PONG, trickle and scheduling budgets replace the timing literals in the socket suite. |
| KIMI-D17 | Per-skipped-frame debug formatting | `error.describe` is only constructed when DEBUG is enabled. |
| KIMI-D18 | Identical broadcasts encoded per session | A broadcast shares one Outbound value, with lazy immutable encodings for each of the two text policies. Encoding still runs in session writers, never in the hub actor. |
| KIMI-D19 | Per-session 50 ms watcher; handshake uses idle budget | Polling watcher replaced (K-140): FrameSink arms a per-write deadline (WriteDeadline) signalled on write start and cleared in finally; the watcher fork blocks while idle and sleeps exactly until start+budget. Handshake/idle budgets retained. Regressions: WriteDeadlineSuite, DeviceBackpressureSuite idle-session test, existing stalled-write tests. |
| KIMI-D20 | Refused attach logged as attached | Attached log is emitted only if the assigned connection exists in the hub. |
| KIMI-D21 | Heartbeat queue drops uncounted | Failed PING queue offers increase dropped-frame count. |
| KIMI-D22 | Actor mailbox capacity undocumented | Hub documents the Ox default 16 pending-operation mailbox and its failure contract. |
| KIMI-D23 | LinkTraffic fields repeated in Device_OUT | Response contains one `traffic: LinkTraffic`, removing field-by-field counter duplication. This is an intentional management API shape change. |
| KIMI-D24 | Status endpoint waits on unbounded snapshot ask | `hub.snapshot` reads an atomically published immutable snapshot; mutations refresh it even on an exception. Regression reads telemetry while an actor operation is deliberately blocked. |
| KIMI-D25 | Manual lifecycle card repeats STATS | **Retained deliberately.** Protocol §6.5 requires STATS after *any* STREAM_START/STREAM_END event. Suppressing it for a manual card would violate the current wire contract. Existing wire regression stays intact. |
| KIMI-D26 | Diagnostic output sanitization differs | Router sanitizes controls on relay-failure and Twitch-link-down display fields; root integration upgrades these calls to the shared DiagnosticText redaction policy. |
| KIMI-D27 | Lifecycle-ordering test timing assumption | Full validation exposed initial Flow.tick STATS arriving between lifecycle pairs. Test now tolerates preceding periodic STATS while still requiring each EVENT's matching STATS as its immediately following frame, within a bounded search. |

## Validation

- Compilation with the existing `-Werror` policy.
- Focused protocol/device suites, including unchanged 20-vector golden tests.
- Full relay test suite and Scalafmt check; final results recorded below before integration.
- Actual TCP tests cover handshake refusal/deadlines, replay, quiescence, replacement,
  queue overflow, final BYE drain, shutdown, and independent stalled-write teardown.
- No physical device or firmware validation is claimed in this package scope.

## Integration notes

- Device JSON moves all prior top-level counters under `traffic`; identity/baseline
  fields remain at top level. Missing notification `at` is absent rather than a fabricated epoch.
- Root owns DiagnosticText, status consumer, other bus/fold policies and documentation.
- H2 is the parent firmware/documentation task's trusted-LAN decision, not a new protocol field.

### Final package validation

- `./mill -j 2 test`: PASS, 34 suites / 372 tests, zero failures.
- `./mill mill.scalalib.scalafmt/checkFormatAll`: PASS.
- `git diff --check`: PASS.
- Expected JVM dependency notices remain: Scala lazy-value Unsafe and Netty native-access deprecations;
  Scala compilation itself has zero warnings under `-Werror`.
- An earlier full run exposed the lifecycle-test race documented as KIMI-D27; corrected and full suite rerun.
