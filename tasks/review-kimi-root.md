# Kimi review remediation — integration, management and tooling

Scope: every explicit finding in `REVIEW_SUMMARY.kimi.md`, checked against
`origin/main` at `d7863f1` before remediation. The summary's approximate Low/Nit
totals refer to an external swarm archive that is not in this checkout; unnamed
archive findings cannot be counted as individually verified.

The requested Ultracode workflow is not installed or present in local workflow
files. Native orchestration uses three implementation agents in isolated
worktrees (Twitch, device/protocol, firmware), the integrating agent, and crossed
reviews. Work is integrated as conventional commits, tested after rebasing, and
pushed directly to `main` under `AGENTS.md`.

## Management, consumers and diagnostics

| Finding in summary | Disposition and evidence |
|---|---|
| AlertMonitor blocks on snapshot | Uses atomic `connectedCount`; no actor query per evaluation. |
| Bare stats/alert forks fail application | Shared `EventBus.foldTimed` catches NonFatal per input, logs and retains previous state. Stats publication isolates failures while retaining successful aggregation. Bus regression injects a bad middle event and verifies the next event and state. Interruption remains uncaught. |
| Config ping/idle fractional seconds | Already fixed on baseline: whole seconds required and compared. Existing RLY-25 constructor/reader regressions retained. |
| Unknown secret keys exposed in `/config` | Case/punctuation-independent sensitive suffix masking covers camelCase, underscore and kebab spellings, including unknown keys. New ConfigSuite regression. |
| Missing HTTP idle/read timeout | Claim partly inaccurate: pinned Tapir already installs a 60-second idle handler. Added a 30-second read deadline after the HTTP codec so partial header bytes cannot keep resetting the deadline. Real socket regressions cover silent and partial-header connections. Expected timeouts close quietly. |
| Cleartext management credentials | Non-loopback startup WARN describes TLS proxy and direct-access restrictions. |
| Sub-config feature envy | OAuth and EventSub own their live-mode validation; TwitchConfig coordinates only activation and its own fields. |
| Duplicated validated readers | `derivedValidated` centralizes derived reader construction and error conversion. |
| HTTP operational magic numbers | Named connection/body/deadline constants; 413 response uses actual configured bound. |
| Callback case sensitivity / redundant suffix check | URI scheme/host normalized; exact path checked once. |
| Unvalidated scopes | Distinct syntactically valid nonblank names; reduced and empty scope lists remain supported. Twitch provider validates whether names exist, allowing future scopes without a relay release. |
| Sub-millisecond intervals / unbounded memory knobs | Minimum scheduling resolution, minimum one-second count windows, bounded buffers/backlog/outbound queue validated at construction and HOCON loading. |
| Default HTTP auth bypass | HttpConfig requires an auth value and validates it during construction. Missing/default credentials cannot form a valid HTTP config. |
| Status blocks / backward-clock uptime | Device agent provides cached immutable hub snapshot; uptime clamped at zero. |
| Alert elapsed message freezes | Open alert message updates without new ID, raise time or acknowledgement reset. Regression asserts this. |
| Zero-rule monitor still runs | No subscription, timer or worker when no rules are enabled. |
| Silent EventBus overflow | Drop totals logged at powers of two; bounded diagnostics under sustained overload. |
| Basic timing fingerprint | Already mitigated: password verification runs for both wrong and correct usernames. New regression proves verifier calls for both; malformed headers remain cheap rejects. |
| Basic-check Boolean blindness | `checkBasic` returns success or a typed rejection directly. Invalid credentials and exhausted permits retain distinct 401/503 responses; the caller only applies the browser-origin policy after successful authentication. |
| Basic permit exhaustion undocumented | Source and API reference document two expensive verifications, 503 retry behavior and independent Bearer path. Existing concurrency regression retained. |
| Case-sensitive Host/Origin | Scheme and authority compare case-insensitively; malicious-origin rejection retained and regression added. |
| Duplicate fold scaffolding | Shared timed fold handles event/timer merge and failure isolation. |
| Rule knowledge split across state and rules | Rule evaluation lives alongside descriptions in AlertRule; MonitorState holds transitions only. |
| OTel appender unscoped | Scope finalizer installs noop before SDK close; regression proves subsequent logs stop exporting. |
| LogBuffer resize in composition root | Originally retained: the composition root applied deployment configuration to the single Logback-created global appender. Changed in round 3 (K-098, `bd24d3e`): `LogBuffer.resize` now runs in `Main.run` before `Dependencies.create`, so the wiring function only wires. No behaviour change. |
| ConfigApi second config load | Main loads one HOCON snapshot, decodes it, and passes that same source to ConfigApi. |
| Sensitive.Empty sentinel | Originally retained as a masked external-config value checked through `isSet`. Changed in round 3 (K-100, `1c20a49`): `Sensitive.Empty` is deleted and the optional auth secrets are `Option[Sensitive]`. `Sensitive.optionalReader` maps only an exact `""` to `None`, so the HOCON `""`/`${?ENV}` defaults still work and whitespace-only values are still rejected. |
| Config section registries | One exported-section list now owned by Config; rendering consumes it. Typed fields and startup log labels remain explicit rather than introducing reflection. |
| Triplicated enum readers | Shared EnumConfigReader used by mode, transport and chat settings. |
| Three RelayEvent classifications | Originally retained as answering different questions. Changed in round 3 (K-095, `39a70c5`): `RelayMetrics` no longer re-lists every `RelayEvent` case; its counter choice matches exhaustively on `EventCategory`, refined by `RelayEvent.isObservation` and the device connect/disconnect split. Metric names and descriptions are unchanged. |
| Three bounded append expressions | Originally retained as a one-line `Vector` append/`takeRight` idiom. Changed in round 3 (K-096, `7911556`): one pure `appendBounded` extension in `twitchscreen.relay.collection` serves LogBuffer, ReplayBuffers, AlertStore and ActivityLog; each site keeps its own state handling. No behaviour change. |
| Log redaction regex per field | Shared DiagnosticText compiles once and sanitizes/truncates UTF-8. |
| Trust-boundary sanitization | Activity records, stored alerts, logs and Twitch diagnostics use shared bounded sanitization/redaction. Device/router boundary coordinated with device agent. |
| Subscriber delivery metric overstates work | Public/source docs define delivered as queue acceptance, not completed handling; existing field preserved. |
| Duplicate subscriber names | Atomic registration rejects duplicate names without removing original consumer; regression verifies continued delivery. |
| Dead lowerCaseEnums helper | Removed. Notification JSON vocabulary preserved. |
| WWW-Authenticate on 403/503 | Header emitted only for 401; HTTP regression verifies absence on 403. |
| Hostname whitespace / scalar list coercion | Invalid embedded whitespace/controls rejected; only actual string scalar or list accepted. |
| Netty internal handler name | Locate HttpServerCodec by class before inserting guards. |
| Metric descriptions | Added descriptions for observations and link transitions. |
| Single-case HealthStatus | Retained intentionally: liveness has exactly one successful value; enum constrains JSON/OpenAPI to Up. Readiness states belong to TwitchStatus. |

## Protocol documentation and tools

| Finding | Disposition and evidence |
|---|---|
| H2 / RLY-13 plaintext identity | Review's documented trusted-home-LAN option chosen. PROTOCOL §2 explicitly describes device/relay impersonation, replacement/backoff, false text/stats and 65535-second retry advice. No claim that self-declared device ID authenticates a peer. |
| Flash PSK / FW-18 no OTA | Physical-access assumption and USB update/recovery documented. Secure boot, encrypted flash provisioning and OTA remain deployment/product changes, not silently claimed implemented. |
| FW-10 BYE retry floor | Retained as normative minimum per §12.1; trusted-relay assumption and maximum suppression documented jointly with H2. Capping it locally would violate protocol. |
| Backoff-reset spec drift | Baseline §12 table and §12.1 already specify 60 seconds stable streaming. Retained; no conflicting reset-on-WELCOME statement. |
| Unbounded replay pause / duplicate WELCOME | Spec now requires bounded pause teardown preserving accepted cards/high-water mark, and duplicate WELCOME teardown without state rewind; firmware agent implements/tests both. |
| gen_vectors zero assertions | Generation fails on zero checked prose fields in any vector. Negative tests mutate all bullets and one vector only. |
| Brittle vector section anchors | Explicit stable start/end anchors; missing/misordered anchors produce actionable errors. All 20 vectors and 213 prose assertions still agree. |
| Smoke third copy of V1 / no STATS or EVENT assertion | Smoke loads V1 from shared spec parser, asserts greeting STATS, injects a card over authenticated HTTP and verifies matching EVENT before shutdown BYE. |
| Dependency exception deadline | Existing exact exceptions still expire 2026-10-25. Owner reassessment explicitly scheduled in dependency register for 2026-10-18; expired exceptions remain CI failures. No blanket extension or dependency downgrade. |

Pinned library behavior was checked against [Tapir 1.13.31 NettyConfig](https://github.com/softwaremill/tapir/blob/v1.13.31/server/netty-server/src/main/scala/sttp/tapir/server/netty/NettyConfig.scala),
its NettyServerHandler source jar, and [Netty 4.2 timeout APIs](https://netty.io/4.2/api/io/netty/handler/timeout/ReadTimeoutHandler.html).

Integration verification and remaining physical/live-provider limits are recorded
in the final summary after all agent commits are combined.
