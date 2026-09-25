# twitch-screen — Consolidated code review

**Consolidated:** 2026-09-25. **Scope:** relay, ESP32 firmware, TSB/3 protocol, demo server, CAD, documentation and tooling.

The three reviews describe a strong protocol and concurrency foundation, with important weaknesses in deployment, Twitch integration, connection liveness and notification recovery. Prioritize those failures before cosmetic refactoring. Passing codec tests do not establish reliable delivery through reconnects, queue overflow or relay restarts.

This document consolidates the supplied reports; it is not a new full-codebase audit. Findings describe the reviewed snapshot unless a current check is explicitly recorded. Recommendations are proposed work, not changes made by this consolidation.

## Sources and evidence

| Key | Source | Scope and baseline |
|---|---|---|
| **K** | [REVIEW_SUMMARY.kimi.md](REVIEW_SUMMARY.kimi.md) | Monorepo review, four review areas; dated 2026-09-25; no commit recorded. |
| **D** | [REVIEW_SUMMARY_CODEX.md](REVIEW_SUMMARY_CODEX.md) | Relay review, ten numbered findings and four optional improvements; explicitly reviewed commit `4cd4ea4375ce83e96d8c24c2b5a4bcb8c60ff820`. |
| **C** | [REVIEW-REPORT.claude.md](REVIEW-REPORT.claude.md) | Monorepo review, 94 numbered findings after its own deduplication; dated 2026-09-25; no commit recorded. |

`RLY`, `FW`, `PROTO` and `REPO` IDs from C are retained for traceability. Additional findings from K and D receive subsequent IDs; CAD findings receive `CAD` IDs. The register contains **121 review items**, including optional improvements and environment notes, not 121 independently reproduced defects. Multiple source references on one row represent one consolidated item. Broader source findings retain related subcases within the same row.

Evidence labels:

- **R:** the named source reports a reproduction or direct measurement. It was not rerun here unless stated otherwise.
- **A:** source inspection or contract analysis reported by the named reviewer.
- **O:** improvement, documentation, coverage or environment observation; not proof of a runtime failure.

Source agreement strengthens confidence but does not establish independent experiments. Historical line numbers are navigation hints and may have moved.

### Current working-tree qualification

At the initial consolidation, HEAD matched D's commit, but the working tree contained changes to Docker packaging, configuration, Twitch authentication, polling, webhooks and tests, plus new authentication files. Those edits were already present when consolidation began. In particular:

- **RLY-04: the reported Docker syntax defect is corrected in the current working tree.** `Dockerfile:48` now contains `EXPOSE 8080 8099`. During this consolidation, `docker build --check -f twitch-screen-relay/Dockerfile twitch-screen-relay` exited **0**, with “Check complete, no warnings found.” A full image build was not performed.
- Authentication and configuration findings, especially **RLY-02, RLY-10, RLY-11, RLY-23 and RLY-49**, need reassessment against the in-progress changes before being treated as open tickets. File changes alone are not evidence that a finding is resolved.
- All other register entries remain historical findings with current closure unassessed. No application source or original review report was changed by this consolidation.

## Priority and disagreements

**High** means resolve before relying on the affected deployment path. **Medium** means a functional or contract failure under the stated conditions. **Low** means narrower impact or hardening. **Info** means optional design, tooling, documentation or environment work. These are consolidated priorities, not a mechanical conversion of each reviewer's labels.

| Topic | Difference between reports | Consolidated treatment |
|---|---|---|
| Docker `8099//` | K calls it inert; D #1 and C RLY-04 reproduce a validation failure. | The historical defect was a build blocker. Current syntax check passes; full image build remains unverified. |
| Demo-server failure | K calls the disconnect bug and obsolete quick-start Critical; C uses Low for the retired server and Medium for misleading docs. | Fix the supported onboarding path promptly. Keep the retired-server bug Low and retire the server rather than porting it to TSB/3. |
| Relay verdict | K says approve with comments; D and C identify deployment and runtime failures. | Do not use a blanket approval. Acceptance depends on the affected path and fresh validation of existing fixes. |
| Webhook duplicates and metadata | K/C place these lower; D #6/#9 reproduce user-visible failures. | Medium: retain D's concrete reproductions as evidence (RLY-19/RLY-20). |
| Relay test count | K reports 137/137 **tasks**; D/C report 245 **tests**. | Preserve the units. Do not present 137 as a conflicting test count. |
| PlatformIO results | K cannot use the broken local environment; C reports successful runs using a Python-path workaround. | Both can be true. Distinguish stock-environment failure from workaround/direct-compiler success. |
| Uniform HTTP errors | K praises the standard error shape but also identifies unhandled plain-text 500s; C says some tests assert only status. | Typed errors and unexpected exceptions are different paths. Retain RLY-53 and strengthen the specific response-body assertions. |
| CAD refactoring | K labels duplicated geometry Required; C reports no comparable CAD runtime defect. | Preserve the CAD findings as Low/Info, with validation gaps distinguished from duplication. |
| Missing quality tools | C lists absent Scalafix/WartRemover/coverage; D explicitly says absence is not itself a defect. | Tool adoption is optional (RLY-39). Missing enforcement of an advertised policy is separate (RLY-38). |
| Codec confidence | C calls the codec memory-safe after sanitizer/fuzz runs. | Record “no faults found in the reported runs”; these runs are useful evidence, not a proof for all inputs. |
| Large files and architecture | K finds no justified large-file split; D recommends defect-focused changes. | Preserve actor ownership, direct-style orchestration and total codecs. Extract only where it removes duplication or fixes a concrete lifecycle boundary. |

## Server endpoint authorization — requested policy

**User requirement, added after the source reviews:** support **HTTP Basic Auth or a static API token** for sensitive server endpoints. Either valid credential authorizes the same management operations; callers do not need both. Non-sensitive endpoints remain public, and Twitch callbacks must not require management credentials. This section defines the requested remediation for **RLY-01/RLY-05**; authentication has not been implemented by this document update.

### Static configuration and request credentials

Load credentials from application configuration populated by environment variables at startup. Proposed variable names for implementation:

| Environment variable | Purpose |
|---|---|
| `RELAY_HTTP_AUTH_BASIC_USERNAME` | Static management username for HTTP Basic Auth. |
| `RELAY_HTTP_AUTH_BASIC_PASSWORD_HASH` | Salted password hash for verifying the Basic Auth password; keep the verifier in configuration rather than persisting a plaintext password. |
| `RELAY_HTTP_AUTH_API_TOKEN` | Static, independently generated management API token for automation. This is separate from Twitch access tokens and the EventSub signing secret. |

Basic clients send `Authorization: Basic <base64(username:password)>`; API clients send `Authorization: Bearer <api-token>`. Credentials belong in headers, never query parameters. Rotation happens by updating the environment and restarting the application; no credential-management API or user database is required.

Validate the credentials at startup, with no default password/token or fallback to unauthenticated management access when settings are missing or invalid. Treat the password verifier and API token as secrets: mask them in `/config`, exclude them and authorization headers from logs/exports, and compare tokens without timing-dependent early exits. Use HTTPS for credential-bearing network requests, directly or through a trusted TLS-terminating proxy.

### Endpoint classification

The following is the proposed classification based on the current route definitions. “Public” means no Basic Auth/API token is required; callback-specific validation still applies. Read-only endpoints can be sensitive when their responses expose internal state, account details, addresses or event history.

| Access | Endpoint(s) | Reason / required behavior |
|---|---|---|
| Public | `GET /api/v1/health` | Minimal liveness response; container health checks remain unauthenticated. |
| Public | `GET /api/v1/stats` | Aggregate stream figures intended for the display/dashboard; keep credentials, account details and internal diagnostics out of this response. |
| Public | `/docs` and its generated OpenAPI/schema/static assets | Public API reference containing schemas, not live configuration or credentials. Document both authentication alternatives on protected operations only. |
| Public callback | `POST /api/v1/twitch/eventsub` | Twitch must reach challenge, notification and revocation handling without management credentials. Retain HMAC signature/timestamp validation and the deduplication requirement in RLY-19. |
| Public callback | `GET /api/v1/twitch/callback` | Browser return from Twitch OAuth must work without management credentials. Require a valid, expiring, single-use OAuth state before exchanging a code or changing the linked account. |
| Sensitive — Basic Auth or API token | `GET /api/v1/config`, `GET /api/v1/logs`, `GET /api/v1/status` | Effective configuration, logs and detailed readiness expose operational/account information even when secret values are masked. |
| Sensitive — Basic Auth or API token | `GET /api/v1/devices`, `GET /api/v1/devices/{connection}` | Device identity, addresses and connection diagnostics. |
| Sensitive — Basic Auth or API token | `GET /api/v1/notifications`, `GET /api/v1/activity`, `POST /api/v1/activity:export` | Notification/event history and exported operational data. |
| Sensitive — Basic Auth or API token | `GET /api/v1/alerts`, `GET /api/v1/alertRules` | Internal failures and configured monitoring policy. |
| Sensitive — Basic Auth or API token | `POST /api/v1/notifications`, `POST /api/v1/devices/{connection}:disconnect`, `POST /api/v1/alerts/{id}:acknowledge` | Operations that change the screen, device connections or alert state. |
| Sensitive — Basic Auth or API token | `GET /api/v1/twitch/authorize`, `GET /api/v1/twitch/authorization`, `DELETE /api/v1/twitch/authorization` | Starting account linking, reading authorization details and revoking access are management actions. The public callback exception does not expose the rest of `/twitch/*`. |

Apply authorization at the sensitive endpoint boundary before its handler runs, including in simulated mode. Keep public routes explicitly classified rather than adding authentication to every endpoint or treating every GET as public. Give every future route an explicit access classification. Preserve callback signature/state checks independently of management authentication, even when a caller supplies valid management credentials.

Missing or invalid credentials on a sensitive endpoint return the standard JSON error shape with HTTP 401 and appropriate authentication challenges, without executing the handler. Basic authentication used by browsers also needs cross-site request protection for management actions; a CORS allowlist alone does not replace that protection. A shared listener is acceptable when these route boundaries are enforced; separate listeners or callback-only proxying remain optional deployment isolation.

### Acceptance criteria

- Each sensitive operation succeeds with valid Basic credentials alone and with a valid API token alone; missing, malformed or incorrect credentials produce 401 without data disclosure or side effects.
- Health, aggregate stats and documentation work without credentials. Signed EventSub challenge/delivery and a valid OAuth return also work without management credentials; invalid signatures, stale/replayed deliveries and invalid/reused OAuth state are handled by their own validation rules.
- Starting/revoking Twitch authorization is protected while the exact callback routes remain public. A valid management token never bypasses callback verification.
- Missing/invalid configured credentials fail startup rather than expose sensitive routes. Updated credentials take effect after restart and old credentials are rejected.
- The new secret fields and request authorization values are absent from logs, activity exports, public responses and unmasked configuration output; tests cover Basic-browser cross-site requests and both authentication alternatives in OpenAPI.

## Work to prioritize

1. **Validate deployment and secure sensitive endpoints.** Confirm the existing Docker fix with a full image build; implement environment-configured Basic Auth or API-token access according to the endpoint policy above, preserving public non-sensitive routes and Twitch callbacks; enforce request-size limits (RLY-04, RLY-01, RLY-05, RLY-06).
2. **Make Twitch initialization and recovery reliable.** Reassess the authentication work, validate transport-specific credentials, start callback/device listeners before ingestion, retry recoverable startup failures, and derive health from ongoing observations (RLY-02, RLY-03, RLY-10, RLY-11, RLY-23, RLY-43).
3. **Fix reproduced session and API failures.** Bound stalled writes, reject malformed duplicate HELLO, handle huge TTLs as input errors, deduplicate webhooks and update webhook metadata tracking (RLY-07, RLY-51, RLY-52, RLY-19, RLY-20).
4. **Settle delivery/replay guarantees before implementation.** Device overflow, relay EVENT drops and sequence resets are distinct loss mechanisms (PROTO-01 through PROTO-03). Keep the specification and both implementations aligned.
5. **Restore firmware responsiveness.** Remove blocking reconnect work from the UI loop, preserve disconnect edges, bound network writes, then enable watchdog recovery. Fix notification-title layout (FW-01 through FW-05).
6. **Repair onboarding and establish repeatable checks.** Route users and agents to the relay's simulated mode; retire the v2 demo; automate builds, formatting, host tests and vector drift checks (REPO-01 through REPO-04, RLY-38, FW-13, PROTO-08).

## Consolidated finding register

### Relay: integration, security and runtime behavior

Locations in the relay tables are relative to `twitch-screen-relay/`. Abbreviated class names refer to files under `src/twitchscreen/relay/`; test classes are under `test/src/twitchscreen/relay/`.

| ID | Priority | Finding, impact and proposed action | Sources / evidence |
|---|---|---|---|
| RLY-01 | High | **Sensitive endpoints lack authorization.** `http/HttpApi.scala:36`, `resources/application.conf:5`, Docker/compose. Reachable clients can post display text, disconnect devices and read diagnostics. Implement the requested endpoint policy: static environment-configured **Basic Auth or API token** on sensitive routes, with public non-sensitive routes and exact Twitch callback exceptions. Keep CORS/exposure deliberate and protect browser-authenticated management actions against cross-site requests. | C RLY-01; K Required/Relay — A; user-specified remediation above |
| RLY-02 | High | **Webhook subscriptions can select the user token instead of an app token.** `twitch/EventSubWebhookApi.scala:168`, `LiveTwitchSource.scala:70`. The reviewed library fallback rejects the affected configuration while status remains healthy. Select credentials explicitly per transport and surface registration failures. Reassess against current authentication edits. | C RLY-02; D #2 — A, pinned-library inspection |
| RLY-03 | High | **Startup failure is permanent and health becomes stale.** `LiveTwitchSource.scala:28–48`, webhook revocation handling, `alerts/MonitorState.scala`. Initial broadcaster lookup failure prevents all ingestion; later disconnects/revocations do not consistently update health or recover subscriptions. Add supervised bounded retries and a shared health model, including degraded states, with disconnect/recovery tests. | C RLY-03; D #4/#5 — A |
| RLY-04 | High, historical | **Dockerfile failed validation.** Historical `Dockerfile:45`: `EXPOSE 8080 8099//`. The documented image-build path was blocked. **Current syntax fix verified here:** line 48 is corrected and `docker build --check` passes. Keep full image build/smoke test as the remaining deployment check. | C RLY-04; D #1 — R; K Nit contradicted; current check passed |
| RLY-05 | Medium | **Public Twitch callbacks share a listener with sensitive management routes.** `EventSubWebhookApi.scala:137`, `HttpApi.scala:42`, and the current `TwitchAuthApi`. Keep EventSub and OAuth callbacks reachable without Basic Auth/API tokens while enforcing their own signature/state validation. Protect sensitive operations on the same listener per RLY-01; listener separation or callback-only proxying is optional isolation, not a blanket-auth requirement. | C RLY-05 — A; user-specified remediation above |
| RLY-06 | Medium | **Request bodies are unbounded before authentication/decoding completes.** `EventSubWebhookApi.scala:143`, `device/NotificationApi.scala:97`. Large concurrent POSTs can exhaust a small host's heap. Enforce transport/endpoint body limits before buffering and validate notification field lengths. | C RLY-06 — A |
| RLY-07 | Medium | **Blocked output prevents idle-timeout progress.** `device/DeviceSession.scala:239,356`. A writer holds the shared sink monitor while blocked; the reader then blocks answering PING. D reproduced attachment surviving 1,800 ms with a 600 ms idle timeout. Add an independent deadline that closes stalled sockets; preserve ordered frames, control-message semantics and final BYE behavior. | C RLY-07; D #7 — R in D |
| RLY-08 | Medium | **Lagging Helix observations can flap stream state.** `twitch/HelixPoller.scala:62`, `ChannelStateTracker.scala:39–58`. An empty poll after EventSub online can emit END then START and reset counters. Model source ordering/authority and hysteresis; test conflicting online/offline observations. | C RLY-08 — A |
| RLY-09 | Medium | **Acknowledged alerts are excluded from open-alert lookup.** `alerts/AlertStore.scala:16`. A persistent condition raises another alert, and the acknowledged one may never resolve. Treat Active and Acknowledged as open for deduplication/resolution; test acknowledge → reevaluate → resolve. | C RLY-09 — A |
| RLY-10 | Medium | **Expired credentials repeatedly fail without useful recovery.** `HelixPoller.scala:45`, `LiveTwitchSource.scala:69–70`. Repeated authorization failures can flood display alerts while totals freeze. Validate/refresh credentials where supported; stop or back off repeated unauthorized polls and expose degraded health. Reassess against current OAuth work; no fixed token lifetime is assumed here. | C RLY-10 — A |
| RLY-11 | High | **WebSocket EventSub accepts missing user credentials and discards registration results.** `LiveTwitchSource.scala:98`. D reports all registrations return false in the affected configuration and pool `connect()` does not repair them. Validate credentials/scopes and registration outcomes; report unsupported/degraded operation explicitly. Distinct from webhook token selection in RLY-02. | C RLY-11; D #2 — A, pinned-library inspection |
| RLY-12 | Low | **Diagnostic behavior lacks focused tests.** `http/ApiSuite.scala:75`; StatusApi, LogsApi, LogBuffer, LogLevel, RelayMetrics, MDC and activity codecs. Add behavior tests around the reported failures and essential headless diagnostics; the absence of tests alone is not a reproduced runtime bug. | C RLY-12 — O |
| RLY-13 | Low | **Device connections/reclaims lack resource and abuse limits.** `DeviceLinkServer.scala:33`, `DeviceHub.scala:135`, session PING handling. A reachable peer can claim a known device ID or consume many sessions. Add connection/reclaim limits and document LAN trust; protocol authentication is an optional design change, not an existing promise. | C RLY-13 — A |
| RLY-14 | Low | **Accept errors retry without backoff.** `DeviceLinkServer.scala:38`. Persistent descriptor/resource exhaustion can spin and flood logs. Add capped retry delay and rate-limited diagnostics. | C RLY-14 — A |
| RLY-15 | Low | **Shutdown BYE races scope interruption.** `Main.scala:32`, `DeviceHub.scala:219–228`. Queuing BYE does not guarantee it reaches the wire before shutdown. Use bounded drain/teardown coordination and test actual scope shutdown; BYE remains advisory. | C RLY-15 — A |
| RLY-16 | Low | **Accepted queue sizes can truncate the greeting burst deterministically.** `config/Config.scala:31`, hub greet. Writer startup follows enqueueing WELCOME, replay and STATS. D reproduced capacity 2 retaining only WELCOME/EVENT2 from a larger greeting. Validate worst-case capacity or stream an ordered initial burst. Existing overflow policy permits drops; distinguish this from PROTO-03's recovery claim. | C RLY-16; D Optional/small queues — R in D |
| RLY-17 | Low | **Operator disconnect reasons are lost.** `DeviceHub.scala:201–212`; `RequestedByOperator`/`ListenerStopped` are unused. Sources describe different generic close messages, but agree intent is lost. Record the explicit reason before teardown, or remove misleading unused cases. | C RLY-17; K Consider/Relay — A |
| RLY-18 | Low | **Per-drop WARN logging burdens the hub actor.** `DeviceHub.scala:283`. A slow client can flood the log ring and add synchronous work to fan-out. Log state transitions and periodic summaries; retain counters for every drop. | C RLY-18 — A |
| RLY-19 | Medium | **Authenticated webhook redelivery publishes twice.** `EventSubWebhookApi.scala:45–93`. D submitted identical signed ID/timestamp/body twice and observed two `Followed` events. After authentication, atomically deduplicate IDs in a bounded expiring store; return success for repeats without republishing. Test concurrent duplicates and expiry. | C RLY-19; D #6 — R; K Consider/Webhooks |
| RLY-20 | Medium | **Webhook channel updates omit metadata-tracker updates.** `EventSubWebhookApi.scala:117`. D reproduced update → online producing empty title/category, unlike the WebSocket path. Update the tracker and share transport-to-domain transitions where useful; test that sequence. | C RLY-20; D #9 — R in D |
| RLY-21 | Low | **Nullable external fields become literal “null”.** `HelixPoller.scala:59`. Cards can display “null” instead of blank/fallback text. Normalize nullable title/game values at the adapter boundary. K also identifies nullable exception-message logging in HelixPoller and DeviceSession. | C RLY-21; K Required/Relay — A |
| RLY-22 | Low | **Alert acknowledge/resolve transitions race.** `AlertStore.scala:29`. Separate read/replace operations can overwrite Resolved with Acknowledged. Make each transition atomic or serialize mutation; test competing transitions. This is separate from RLY-09's state-selection bug. | C RLY-22 — A |
| RLY-23 | Low | **Webhook callback/secret constraints are insufficiently validated.** `Config.scala:64`. Invalid provider settings fail during registration while status may look healthy. Validate provider requirements at configuration load and report actionable errors; recheck the current configuration changes. | C RLY-23 — A |
| RLY-24 | Low | **Configuration validation throws without structured origin/error aggregation.** `Config.scala:23,55–65,96–119`. Constructor `require` failures obscure configuration diagnostics. Return structured reader validation failures or use focused validated types. | C RLY-24; D Optional/config — A |
| RLY-25 | Low | **Intervals and wire-bound settings admit invalid values.** `Config.scala:36`. Zero/negative intervals can busy-loop or immediately time out; subsecond ping intervals can encode as zero. Validate positivity, minimum wire units, upper bounds and cross-field relationships. D successfully constructed zero broadcast/negative ping configurations. | C RLY-25; D Optional/config — R in D |
| RLY-26 | Low | **Whitespace secrets count as configured; representation invites accidental exposure.** `config/Sensitive.scala:6`. Validate nonblank values at the boundary and keep explicit reveal/redaction behavior. Treat unintended future codec derivation as a risk, not a current leak. | C RLY-26 — A/O |
| RLY-27 | Low | **Length error classification contradicts the framing path.** `protocol/ProtocolError.scala:66`, FrameReaderSuite. `LengthOutOfRange` is labeled SkipFrame although framing uses resynchronization. Align the error model, documentation and tests so future changes do not trust an invalid length. | C RLY-27 — A |
| RLY-28 | Low | **Default DEBUG traffic evicts useful in-memory diagnostics.** `resources/logback.xml:17`. Filtering only when `/logs` is read cannot recover evicted INFO/WARN records. Set intentional default/configurable levels and a memory-appender retention threshold. | C RLY-28 — A |
| RLY-29 | Low | **Twitch-event metric includes polls and link events.** `observability/RelayMetrics.scala:47`. Catch-all matching inflates activity and hides future event categories. Use exhaustive classification with separate counters/attributes. | C RLY-29 — A |
| RLY-30 | Low | **Routine poll observations consume activity history.** `activity/ActivityLog.scala:16`. A 500-entry ring loses useful lifecycle history to unchanged observations/chat. Record changes, filter routine observations or separate retention policies by category. | C RLY-30 — A |
| RLY-31 | Low | **Metric collection and SDK lifetimes are not scoped cleanly.** `RelayMetrics.scala:55`, `Otel.scala:28–36`. A blocking actor ask during late collection can outlive the hub; gauge handles are discarded. Scope SDK/gauge cleanup and collect from a nonblocking snapshot. | C RLY-31 — A |
| RLY-32 | Low | **Duplicated, unvalidated page-size inputs.** ActivityApi, LogsApi, NotificationApi, AlertsApi. Nonpositive sizes silently return empty lists and OpenAPI lacks bounds. Share one validated parameter definition with explicit limits. | C RLY-32 — A |
| RLY-33 | Low | **In-memory logs discard causes and do not cap message bytes.** `observability/LogBuffer.scala:43`. Operators lose useful failure context and a record can be large. Retain a bounded, redacted cause summary and cap stored text. | C RLY-33 — A |
| RLY-34 | Low | **Instrumentation version is duplicated.** `RelayMetrics.scala:15`. Hard-coded `0.1.0` can diverge from releases. Use the existing `RelayVersion.current`. | C RLY-34 — O |
| RLY-35 | Low | **Uptime starts after blocking initialization.** `health/StatusApi.scala:48`. Construction time understates process uptime. Capture startup time once in Main and inject it. | C RLY-35 — A |
| RLY-36 | Low | **Activity retention test checks size but not retained entries.** `ActivityLogSuite.scala:17`. Wrong eviction order can pass. Assert entry identities/order and monotonic IDs. | C RLY-36 — O |
| RLY-37 | Low | **Some test names overstate assertions.** `EventBusSuite.scala:43`, `ApiSuite.scala:159,167`. Device-event coverage uses a Twitch event; stalled-subscriber tests overlap; JSON-error tests check status only. Correct behavioral assertions and remove redundant cases. | C RLY-37 — O |
| RLY-38 | Low | **Claimed quality gates are not enforced automatically.** `build.mill:32`, relay README. No CI or `-Werror` was configured although the README promised warning failures. Enforce the policy or correct it; automate compile/tests/formatting and packaging checks. Refresh the stale “100 tests” README claim, preferably avoiding a hard-coded count. | C RLY-38; K Required/Relay and Top 3; D Optional/warnings — O |
| RLY-39 | Info | **Optional static analysis and coverage tooling.** `build.mill:28`. Scalafix, WartRemover and coverage were not configured. Adopt only useful rules/coverage reporting; their absence is not itself a defect or a reason to require a broad tooling migration. | C RLY-39; D Optional/tooling and limits — O |
| RLY-40 | Low | **Build/deployment reproducibility and resource policy need hardening.** Dockerfile, compose, Mill launcher. Floating image tags, unchecked launcher download and unspecified JVM/container memory policy warrant deliberate controls. Pin/verify artifacts and set measured memory limits; retain a workable healthcheck. No compromise is established. | C RLY-40 — A/O |
| RLY-41 | Low | **Dependency compatibility/audit gaps.** `build.mill:22,62–64`. C reports OTel instrumentation/SDK version skew, a previous linkage workaround and legacy Hystrix transitively. Verify compatible dependency alignment and automate an audit; the report does not establish a specific exploitable CVE. | C RLY-41 — A/O |
| RLY-42 | Low | **Initial live observation emits a new stream-start card after relay restart.** `ChannelStateTracker.scala:42`. Unobserved → Live is treated differently from Unobserved → Offline. Decide whether initial state is a silent baseline; preserve genuine later transitions and test both. | C RLY-42 — A |
| RLY-43 | Medium | **Blocking Twitch startup delays listeners and races webhook verification.** `Dependencies.scala:44`, `Main.scala:26`, `EventSubWebhookApi.scala:156`. C identifies delayed device listening; D identifies subscription registration before the callback binds, without reconciliation. Construct dependencies/endpoints first, bind listeners, then start ingestion with retries. Test callback reachability when registration begins. | C RLY-43; D #3 — A |
| RLY-44 | Info | **Build comment still describes NDJSON device messages.** `build.mill:51`. Describe jsoniter's current HTTP/EventSub role. | C RLY-44 — O |
| RLY-45 | Info | **Firmware version can inject line breaks into DEBUG output.** `protocol/WireStrings.scala:87`, FirmwareVersion. Sanitize/escape diagnostic wire strings; do not misstate this as a secret leak. | C RLY-45 — A |
| RLY-46 | Info | **Device-ID validation differs between wire and HTTP.** `protocol/DeviceId.scala:11`. Trimming can alias IDs, and a 64-character bound differs from the 31-byte wire field. Share the documented validator and intentional HTTP error semantics; resolve whitespace treatment explicitly. | C RLY-46 — A |
| RLY-47 | Info | **Duplicate HELLO version check is unreachable.** `protocol/Tsb3Decoder.scala:34`. Remove redundant validation only after preserving version-check ordering and existing protocol behavior. | C RLY-47 — O |
| RLY-48 | Info | **Some configuration knobs have no effect.** `Config.scala:14`. `max-frame-length > 256` and `protocol-version` do not change the advertised wire contract. Remove misleading knobs or constrain/document them. | C RLY-48 — O |
| RLY-49 | Info | **Secret-mask paths can drift from typed configuration.** `config/ConfigApi.scala:31`. C refuted a current leak at its snapshot: all four secrets were covered. Keep only the future drift risk; test redaction of all current secret fields, especially after the authentication edits. | C RLY-49 and Appendix A — O |
| RLY-50 | Info | **Socket tests have timing and port-allocation flake risks.** `DeviceLinkSuite.scala:279`. Sleeps, tight elapsed assertions and probe-then-bind can fail on loaded runners. Prefer server-owned port 0 and condition-based waits with bounded timeouts. | C RLY-50 — O |
| RLY-51 | Medium | **Malformed second HELLO bypasses duplicate-HELLO teardown.** `DeviceSession.scala:253`. D reproduced valid handshake → full HELLO with `rx_max=128` → PONG, rather than duplicate-HELLO refusal. After version validation, recognize the HELLO type before field decoding on established sessions. Test invalid fields and short payloads. | D #8 — R; additional finding |
| RLY-52 | Medium | **Huge positive notification TTL throws and returns HTTP 500.** `NotificationApi.scala:78`. D reproduced `ttlMs=9223372036854775807` escaping duration construction despite the typed-error contract. Validate/clamp raw milliseconds before conversion; test upper boundaries and ordinary behavior. | D #10 — R; additional finding |
| RLY-53 | Medium | **Unexpected exceptions bypass the JSON error contract.** `http/HttpApi.scala:30–38`. K reports the default handler returns plain-text 500, unlike typed errors. Add a standard exception-to-error mapping without exposing internals. This does not replace RLY-52's input validation. | K Required/Relay — A, handler bytecode inspection; additional finding |
| RLY-54 | Info | **Unused EventSub factory helpers.** `twitch/EventSubFactory.java:46–66`. K identifies five unused factories. Confirm current call sites before deleting; do not confuse this with a runtime integration fix. | K Consider/Relay — O; additional finding |
| RLY-55 | Info | **Randomness is a hidden hub dependency.** `DeviceHub.scala:99`. Inject a session-ID/random source alongside the clock if deterministic tests need it. | K Consider/Relay — O; additional finding |
| RLY-56 | Info | **Alert-rule conversion is repeated.** `Dependencies.scala:35–36`, `AlertMonitor.scala:27`. Compute the validated rule set once and pass it to consumers. | K Consider/Relay — O; additional finding |
| RLY-57 | Info | **HTTP definitions depend on initialization order.** `http/Http.scala:11–13`. Replace fragile order-sensitive values with an appropriate method/lazy value or reorder dependencies clearly. | K Consider/Relay — O; additional finding |
| RLY-58 | Info | **API errors lack a machine-readable code.** HTTP error model. Consider a stable error code alongside human text if clients need branching; preserve compatibility. This is an API enhancement, not the JSON inconsistency in RLY-53. | K Consider/HTTP API — O; additional finding |
| RLY-59 | Info | **Device listing is the only unpaged list.** `GET /api/v1/devices`. Decide and document a bounded response or pagination based on supported device counts. | K Consider/HTTP API — O; additional finding |
| RLY-60 | Info | **Notification creation/retry semantics need documentation.** `POST /api/v1/notifications` returns 200 and has no retry-safety note. Document the intended success and duplicate-request contract; 200 versus 201 alone is not evidence of incorrect behavior. | K Consider/HTTP API — O; additional finding |

### Firmware

Locations are relative to `twitch-screen-firmware/`.

| ID | Priority | Finding, impact and proposed action | Sources / evidence |
|---|---|---|---|
| FW-01 | Medium | **Blocking reconnect freezes the UI and can hide a WiFi disconnect edge.** `src/link_client.cpp:389,421,456`, `src/main.cpp:96–99`. Repeated blocking WiFi association and TCP/DNS connection work stop LVGL progress; a short drop can recover before link teardown observes it. Use explicit nonblocking reconnect states and latch disconnect edges. Test brief and prolonged outages. | C FW-01 — A |
| FW-02 | Medium | **A hung UI loop lacks watchdog recovery.** `src/main.cpp:95`, `include/lv_conf.h`. An assertion/hang can require a power cycle. Enable loop watchdog coverage after fixing legitimate blocking paths and log reset reasons; test an intentional stall. | C FW-02 — A |
| FW-03 | Medium | **Notification titles wrap over other card content.** `src/ui_notify.cpp:300`. Dots mode with content-sized height does not constrain the title to one line. Set an explicit font-appropriate title height in both layouts and verify long names on the display. | C FW-03 — A, library inspection; no hardware reproduction reported |
| FW-04 | Low | **Blocking boot delays first render and can flash false OFFLINE state.** `src/main.cpp:91`. WiFi setup waits before the first LVGL pump; WiFi-only status precedes WELCOME. Render immediately, drive connectivity through the normal state machine and keep tick timing consistent. | C FW-04 — A |
| FW-05 | Low | **Socket writes/DNS can exceed documented blocking bounds.** `src/link_client.cpp:77`, `src/link_client.h:19,48`. A degraded link can stall animation longer than the stated ~3 seconds. Bound/nonblock network operations, treat stuck output as link failure and correct the contract comments. | C FW-05 — A, pinned-framework inspection |
| FW-06 | Low | **Draw buffers are oversized and rely on incidental alignment.** `src/lv_port.cpp:11–19`. C measured two 28,800-byte arrays, versus 19,200 bytes each for the intended RGB565 bands: 19,200 bytes excess total. Use explicit byte sizing/alignment and useful debug logging; verify rendered bands after changes. | C FW-06 — R, ELF/map measurement |
| FW-07 | Low | **Second draw buffer has no overlap benefit with synchronous flush.** `src/lv_port.cpp:19`. Use a single correctly sized buffer unless measured need justifies asynchronous DMA and explicit flush lifetime management. Account for FW-06 first to avoid double-counting memory savings. | C FW-07 — A |
| FW-08 | Low | **Firmware dependencies float and device-source warnings are weak.** `platformio.ini:15,23–50`. Fresh builds can change framework/LVGL behavior unnoticed. Pin the versions actually verified on hardware, enable useful source warnings and resolve deprecations. C's tested versions are historical evidence, not automatically the desired upgrade target. | C FW-08 — A/O |
| FW-09 | Low | **Duplicate device IDs can reconnect/replace indefinitely.** `src/link_client.cpp:192`, example credentials, relay reclaim. Backoff resets immediately on WELCOME. Require a stable streaming interval before reset, handle REPLACED deliberately and provide unique default IDs. | C FW-09 — A |
| FW-10 | Low | **A large BYE retry floor can delay reconnect for many hours.** `src/link_client.cpp:97,265`. C reports up to about 22.7 hours including jitter. The device follows the present minimum-backoff contract; any ceiling is a protocol-policy change, not a unilateral correctness fix. Review the frozen BYE semantics before choosing one. | C FW-10 — A |
| FW-11 | Low | **Skipped-frame logging can block the UI loop.** `src/link_client.cpp:330`. Per-frame serial output can dominate an RX budget. Rate-limit with suppressed counts or use an explicit debug mode. | C FW-11 — A |
| FW-12 | Low | **Sanitizer/fuzz checks are not repeatable project gates.** `platformio.ini:74`. C's ad-hoc runs found no faults, but the normal suite does not run them. Add a deterministic parser/chunking fuzz test and a supported sanitizer target. | C FW-12 — O |
| FW-13 | Low | **Session behavior lacks host tests and automatic execution.** `platformio.ini:65`, `src/link_client.cpp`. Codec/queue suites do not cover the whole handshake, timeout, backoff or ACK state machine. Inject clock/transport at that boundary and test observable sessions; run host and ESP32 builds in CI. | C FW-13; K Top 3/CI — O |
| FW-14 | Low | **Long body text clips without indicating truncation.** `src/ui_notify.cpp:242,258`. Fixed-height wrapping hides the tail. Use intentional ellipsis behavior and verify representative chat lengths/layouts. | C FW-14 — A |
| FW-15 | Low | **Valid dark chatter colors can be unreadable on black.** `src/ui_notify.cpp:352`. Apply a contrast/luminance policy with a visible fallback and check black/dark colors. | C FW-15 — A |
| FW-16 | Low | **Large numeric labels can overlap.** `src/ui_idle.cpp:128`. Million-scale values remain long K strings. Add bounded M formatting and test/display boundary values. | C FW-16 — A |
| FW-17 | Low | **Crash diagnostics and firmware identity are weak.** `platformio.ini:20`, `src/link_client.cpp:51`. No exception decoder/debug setup and constant `FW_VERSION` obscure field failures. Add useful serial decoding and a build-derived version within the wire width. Device warnings are tracked in FW-08. | C FW-17 — O |
| FW-18 | Info | **Updates require physical USB access.** `platformio.ini:18` uses a non-OTA layout. This is a documented trade-off. Consider authenticated OTA/rollback only if enclosure/service requirements justify the partition and recovery work. | C FW-18 — O |
| FW-19 | Info | **Received text can disturb logs/layout.** `src/link_client.cpp:262`. The protocol permits cosmetic handling of control/non-ASCII bytes. Consider escaping diagnostics and display sanitization consistent with negotiated capabilities. | C FW-19 — A/O |
| FW-20 | Info | **Notification copy widths are not tied together at compile time.** `src/notification_wire.h:28`. Future field-width changes could make a copy unsafe. Add static assertions for actor/text widths. No current over-read is reported. | C FW-20 — O |
| FW-21 | Info | **Header validation is duplicated.** `src/proto_codec.cpp:69–75,112–124`. Consolidate one predicate while preserving the same validation/error behavior. | C FW-21; K Consider/Firmware — O |
| FW-22 | Info | **LVGL settings and connectivity comments are stale.** `include/lv_conf.h:24`, `src/ui_idle.h:14`, `src/ui_idle.cpp:329–344`. Some switches are redundant/obsolete; “subtle LINK DOWN hint” actually means full-screen CONNECTING, gated by WiFi plus WELCOME. Correct the contract and disable unused widgets intentionally. | C FW-22; K Required/Firmware — O |
| FW-23 | Info | **Asset generation depends on the launch directory and has optional rendering/organization improvements.** `tools/make_glitch.py:72`. Anchor paths to the script; consider supersampling and moving generated static data out of a header where useful. Do not require visual redesign to fix path handling. | C FW-23 — O |
| FW-24 | Info, environment | **Local PlatformIO environment is broken.** C reports Python 3.14 with 3.13 packages; K reports missing `platformio`. Recreate the environment and document a reproducible setup. Workaround/direct-compiler success does not repair the environment. | C FW-24; K Verified baselines — R in source reports |
| FW-25 | Info | **Session-change diagnostic hook is unused outside tests.** `src/notify_queue.h:82–83`, `main.cpp` WELCOME handler. The comment says link_client logs the change, but only raw session IDs are logged. Wire change detection into diagnostics or remove the misleading accessor/comment; do not change replay semantics. | K Required/Firmware — A; additional finding |
| FW-26 | Info | **UI presentation depends directly on wire bit masks.** `src/ui_notify.cpp:7,168,351`, `notification_wire.h`. Translate wire flags into named presentation/domain fields at the existing conversion boundary. | K Consider/Firmware — O; additional finding |
| FW-27 | Info | **Unused assignment in notification rendering.** `src/ui_notify.cpp:279`. Confirm and remove the dead store without changing layout behavior. | K Consider/Firmware — O; additional finding |
| FW-28 | Info | **`framesDecoded` name overstates what is counted.** `src/proto_codec.cpp:490–499`. Align the name/documentation with its actual counting stage and the protocol counter contract. | K Consider/Firmware — O; additional finding |

### Protocol and cross-component behavior

Protocol sections refer to [TSB/3 PROTOCOL.md](twitch-screen-firmware/docs/PROTOCOL.md). These findings concern behavioral guarantees as well as bytes on the wire.

| ID | Priority | Finding, impact and proposed action | Sources / evidence |
|---|---|---|---|
| PROTO-01 | Medium | **Replay/live bursts overflow the eight-slot device queue without an in-session recovery path.** Firmware `notify_queue.h:88`, relay greet, §§10.3/10.5. Up to 80 replay events arrive faster than cards display; refused events freeze the high-water mark until another greet, potentially causing repeated replay/duplicates. Specify bounded recovery/backpressure and test bursts beyond queue capacity while the connection stays up. | C PROTO-01 — A |
| PROTO-02 | Medium | **Relay sequence reset makes retained new-session events indistinguishable from old history.** Relay `DeviceHub.scala:99–102,258`, firmware greet, §10.2. Depending on relative sequence values, re-baselining or replay filtering can skip retained events. The example is arithmetically wrong: at latest 130, a 64-entry ring retains 67–130, including 67–117. Design explicit restart/sequence ownership; see qualifications below. | C PROTO-02 — A; example checked against local spec here |
| PROTO-03 | Medium | **Relay outbound EVENT drops can be skipped permanently by the device high-water mark.** `DeviceHub.scala:277`, firmware queue, §10.4. A later delivered event advances past the dropped sequence, defeating the documented replay recovery. Choose an explicit delivery guarantee: ordered close/reconnect before crossing the gap, richer acknowledgments/recovery, or documented lossy behavior. Test EVENT drops separately from STATS drops. | C PROTO-03 — A |
| PROTO-04 | Low | **Comments still describe superseded session/ping rules.** Firmware `link_client.h:33`, relay `SessionValues.scala:56`, `DeviceSession.scala:196–197`. `session_id` is diagnostic under the present greet rule; firmware pings periodically, not only after silence. Correct comments without changing the currently shared predicate. | C PROTO-04 — O |
| PROTO-05 | Low | **Lifecycle EVENT can be followed by pre-transition STATS.** Relay `DeviceHub.scala:168`; separate aggregator subscription. Independent subscriber ordering can produce START → offline STATS → live STATS. Deliver the event and corresponding state transition coherently; test the actual frame order. | C PROTO-05 — A |
| PROTO-06 | Low | **Chat rate wraps at the wire width instead of saturating.** Relay `Tsb3Encoder.scala:102`, `StreamStats.scala:64`. C identifies 70,000 encoding as 4,464. Clamp at the u16 boundary and test extrema. The separate suggested unknown-BYE issue was deemed unreachable in C and is not retained as a defect. | C PROTO-06 — A |
| PROTO-07 | Low | **Display-name fallback is applied to generic titles/channel names.** Relay `Tsb3Encoder.scala:73`, §9.3. An emoji-only generic title can become “viewer.” Restrict that fallback to the kinds specified for display names and test ASCII folding per kind. | C PROTO-07 — A |
| PROTO-08 | Low | **Golden-vector drift is not checked automatically.** Relay `Tsb3Vectors.scala`, firmware generated `vectors.h`, §18. Current copies match, but both suites can pass against stale copies. Regenerate/diff firmware vectors and mechanically compare relay vectors with the normative source in CI. | C PROTO-08; K Top 3/CI — O |
| PROTO-09 | Low | **Normative document embeds stale implementation status.** `PROTOCOL.md:830,1038,1054,1109`. It describes corrected firmware behavior and completed migration as unfinished, and names an old suite. Separate historical notes from present requirements and update suite names. | C PROTO-09 — O |
| PROTO-10 | Low | **Unknown startup statistics look like real offline zeros.** Relay greet/StatsState and firmware `ui_idle.cpp:362–388`. The display can briefly show false OFFLINE/zero data after reconnect. Define unknown-state presentation and delivery in the contract; an added flag or suppressed initial STATS requires compatibility review. | C PROTO-10 — A |
| PROTO-11 | Low | **Replay has no age policy.** Relay `DeviceHub.scala:258–262`, firmware `notification_wire.h:15`. Old raids/lifecycle cards may appear current after a long outage. Define maximum age or visibly stale presentation, and test lifecycle cards for streams that have already ended. | C PROTO-11 — A |
| PROTO-12 | Info | **WELCOME timer diagnostics are incomplete.** Firmware `link_client.cpp:206`, §6.2. Log negotiated ping/idle values at each connection as recommended by the spec, not only on mismatch. | C PROTO-12 — O |
| PROTO-13 | Info | **Resync counter units differ across implementations.** Relay `FrameReader.scala:99`, firmware reader, §14. Byte shifts and rejected windows are not comparable. Define one counting unit and align diagnostics/tests. | C PROTO-13 — A |
| PROTO-14 | Medium | **Frozen BYE codes lack defined triggers.** §6.7 codes 4 `INVALID_SEQUENCE` and 6 `FRAME_TOO_LARGE`; relay `ProtocolError.InvalidSequence`. K finds code 4 never constructed and code 6 unreachable through the v3 framing path. Specify triggers or reserve them explicitly as never sent in v3; avoid inventing incompatible semantics for frozen fields. | K Required/Protocol — A; additional finding |
| PROTO-15 | Medium | **Receiver behavior for ACK without CAP_ACK is unspecified.** §6.6 defines sender obligations only. Specify whether receivers ignore/count/refuse it and add matching conformance cases. | K Required/Protocol — A; additional finding |
| PROTO-16 | Medium | **REPLAY on non-EVENT frames lacks receiver semantics.** §§3.2/14. Define handling and counter effects so implementations do not diverge. | K Required/Protocol — A; additional finding |
| PROTO-17 | Info | **Unknown-BYE fallback is attached only to the private-code table row.** `PROTOCOL.md:596`. State the generic teardown rule independently so it clearly covers all unknown codes. | K Consider/Protocol — O; additional finding |
| PROTO-18 | Low | **Sequence exhaustion has no defined operational response.** `PROTOCOL.md:738` forbids wrapping past `0xffffffff`, but does not state what to do at exhaustion. Define teardown/reset/version behavior and boundary tests; the missing detail is not permission to wrap. | K Consider/Protocol — A; additional finding |

### Repository, demo and CAD

| ID | Priority | Finding, impact and proposed action | Sources / evidence |
|---|---|---|---|
| REPO-01 | Medium | **Root onboarding still claims NDJSON/demo compatibility.** `README.md:16,29–43`. A TSB/3 device cannot communicate with the v2 simulator. Describe binary TSB/3 and make relay simulated mode the supported quick-start. | C REPO-01; K Critical #2 — A |
| REPO-02 | Medium | **Firmware README and project skills teach retired behavior.** Firmware README; `.agents/skills/twitch-protocol-testing/SKILL.md`; `.agents/skills/esp32-firmware-workflow/SKILL.md`. References to v2, demo-server, a skeleton relay, no unit tests and drop-oldest are stale. Rewrite for TSB/3, refuse-newest, native tests and relay simulation. C identifies an additional ESP32 skill reference beyond K's list. | C REPO-02; K Critical #2 — A |
| REPO-03 | Low | **Retired demo remains discoverable without clear retirement handling.** `demo-server/twitch_server.py:2`. Prefer removal after fixing references; if kept temporarily for historical reasons, clearly banner it as incompatible. The normative spec rejects porting it to TSB/3. | C REPO-03; K Critical #2 — A/O |
| REPO-04 | Low | **Legacy demo disconnect kills broadcast workers.** `demo-server/twitch_server.py:104–112`, plus unlocked client access at line 45. K reproduced `getpeername()` after close raising EBADF; concurrent removal can also raise KeyError. Retirement removes the affected path; if retained for use, use stored addresses, synchronized access and resilient loops. | C REPO-04; K Critical #1 — R in K |
| REPO-05 | Info | **Project skill discovery differs by agent.** Root README skills table and `.claude/.kimi/.kimi-code` skill links. C says embedded skills are missing from some agent catalogs while Codex can read `.agents/skills` directly. Add discovery links after correcting REPO-02; no broken symlinks were reported. | C REPO-05 — O |
| REPO-06 | Low | **Generated CAD artifacts increase repository history size.** CAD `.gitattributes`, `output/render_meshes`, previews and deliverables. C estimates about 40 MB tracked. Committed output is deliberate; consider excluding reproducible intermediates, smaller previews and LFS/release assets without silently changing the deliverable policy. | C REPO-06 — A/O |
| REPO-07 | Info | **Legacy demo trigger is unauthenticated on all interfaces.** `demo-server/twitch_server.py:220`. If the tool remains runnable, prefer loopback by default and explicit exposure. Retirement supersedes this hardening work. | C REPO-07 — A |
| CAD-01 | Low | **Vent/base-slot geometry is duplicated.** `scripts/pod_geometry.py:105–118`, `scripts/verify_assembly.py:26–35` under the CAD project. Share geometry helpers as already done for hole/screw centers; keep validation assertions meaningful and independent of construction mistakes. | K Required/CAD — O; additional finding |
| CAD-02 | Low | **Cavity-profile taper formula is repeated three times.** `scripts/pod_geometry.py:61–62,93–94,214–215`. Extract a named `cavity_profile` helper with explicit inset/taper semantics. Preserve geometry outputs. | K Required/CAD — O; additional finding |
| CAD-03 | Low | **Discrete extraction sweeps can miss between-step collisions.** `scripts/verify_assembly.py:63–79`. Document sampling limits and use finer/adaptive or continuous checks if collision clearance is required. The report identifies a proof gap, not an observed collision. | K Consider/CAD — A/O; additional finding |
| CAD-04 | Info | **Recompute test assumes `UsbCenterX = 0.0`.** `scripts/verify_assembly.py:89–94`. Test changes relative to the original parameter and restore it, rather than coupling the test to one default. | K Consider/CAD — O; additional finding |
| CAD-05 | Low | **Wall verification calls `min(samples)` without guarding empty input.** `scripts/verify_walls.py:21`. Fail with a useful diagnostic when no samples exist, rather than an unrelated exception. | K Consider/CAD — A; additional finding |
| CAD-06 | Info | **Part grouping uses loosely constrained strings.** `scripts/verify_assembly.py:60–61`. Centralize the allowed groups/names if that prevents typo-driven omissions. | K Consider/CAD — O; additional finding |
| CAD-07 | Info | **Unused imports in CAD scripts.** `export_project.py`, `verify_exports.py`, `verify_walls.py`, `package_project.py`. Remove confirmed unused imports during focused maintenance. | K Consider/CAD — O; additional finding |
| CAD-08 | Info | **Render dimensions are duplicated.** `scripts/package_project.py:20`, `scripts/render_previews.py:18–19`. Share one output-resolution setting so packaging and rendering stay aligned. | K Consider/CAD — O; additional finding |

## Qualifications on proposed fixes

These are consolidation judgments about recommendations, not additional findings or accepted architectural decisions.

- **A time-based sequence seed is not yet an accepted solution to PROTO-02.** C proposes epoch seconds, but §10.1 currently requires sequence numbering from 1 and reset on restart. A new time seed also needs a demonstrated monotonicity guarantee across event rates, quick restarts and clock changes, plus exhaustion handling. Persisted sequence ownership or explicit session identity are alternatives to evaluate. Correct the ring arithmetic regardless of the chosen design.
- **Do not silently change frozen BYE semantics.** FW-10's proposed retry cap conflicts with the present interpretation of `retry_after_s` as a requested minimum. PROTO-14 must clarify unused codes without repurposing existing meanings. Any contract change must follow the protocol's evolution rules.
- **ACK cannot simply become flow control under the current contract.** §6.6 says it is informational and forbids making delivery conditional on it. Queue enlargement alone is finite capacity, not a complete sustained-overload policy. PROTO-01/03 need a designed recovery/loss policy and end-to-end tests.
- **Closing on an EVENT drop needs ordered teardown.** Stop advancing that connection past the gap, preserve frame boundaries and ensure the stalled-output problem cannot prevent closure. Queueing a BYE behind a blocked writer is insufficient (RLY-07).
- **A nonblocking PONG path must preserve the protocol contract.** C suggests skipping PONG when a lock is busy; §6.3 requires prompt PONG handling. Use deadlines and an explicit control-write/teardown policy rather than accepting indefinite silence as a complete fix.
- **Unknown STATS and replay-age changes affect behavior across both sides.** Adding a reserved-bit meaning, omitting greeting STATS or filtering old events requires explicit compatibility and user-visible semantics, not just a local UI patch.
- **Optional tools/features stay optional.** Scalafix, coverage tooling, OTA, DMA and API ergonomics are not prerequisites for every correctness fix. Use the existing stack and focused boundaries; no report justifies replacing the effect/concurrency model or adding generic infrastructure.

## Validation evidence and limits

### Results reported by the source reviewers

These results belong to the earlier review runs. They were not rerun on the modified working tree during consolidation.

| Check | Reported result | Source / limitation |
|---|---|---|
| Relay compile | Pass, zero project warnings | K; C reports clean main/test compilation. D explicitly reused existing outputs, not a clean build. |
| Relay tests | 245 passed, zero failures/errors/skips | D/C; C reports 17 suites. K's 137/137 figure counts tasks and is not a second test count. |
| Scala formatting | Pass, 100 sources | D/C; C records scalafmt 3.9.4. |
| Dockerfile check | Failed on `8099//` | D/C historical result; superseded for syntax by the current check below. Neither proves a full image build. |
| Webhook/API probes | Duplicates, stale start metadata and huge-TTL 500 reproduced | D; scratch probes, not committed regression tests. |
| Socket probes | Stalled-output timeout, invalid duplicate HELLO and small-queue greeting loss reproduced | D; real sockets with targeted configurations. |
| Firmware host suites | 5,372 codec + 849 link-wire checks = 6,221, zero failures | K used direct g++ because PlatformIO was broken; C reports native tests using its environment workaround. These are assertion checks, not 6,221 distinct test cases. |
| ESP32 build | Pass with workaround; RAM 105,280/327,680 B (32.1%); flash 1,224,313/3,145,728 B (38.9%) | C; stock PlatformIO failed. Tested stack recorded as espressif32 7.1.2, Arduino-ESP32 2.0.17, LVGL 9.6.0, TFT_eSPI 2.5.43. |
| Firmware diagnostics | Host codec warning-clean; extra device flags surfaced LVGL deprecations/conversion notes | C; distinguish host-codec warnings from the broader firmware build. |
| Sanitizers/fuzzing | No ASan/UBSan faults in the reported suites and 200k-stream experiment | C; finite exploratory testing, not a memory-safety proof. |
| Golden vectors | 20/20 match; firmware regeneration identical; 213 prose assertions cross-checked | K/C; automation gap remains PROTO-08. |
| CAD script syntax | All eight scripts passed `py_compile` | K; syntax checks do not prove geometry or physical fit. |
| Legacy demo disconnect | EBADF after socket close reproduced | K; retired tool, not relay failure. |
| Trace-correlation probe | Timed out after 30 seconds; inconclusive | D; correctly excluded as a finding. |
| Static analysis/coverage | Scalafix/WartRemover/coverage not configured; hadolint unavailable | D/C as applicable; absence is reported, not represented as a failed code-quality check. |

D's scratch probes used reused compiled classes and emitted an overlapping-Scala-library warning; D also noted runtime `sun.misc.Unsafe` deprecation warnings. C reports pinned-library/bytecode inspection for framework behavior and ELF/map measurements for firmware memory. These qualifications should remain attached to the evidence when reproductions become permanent tests.

The sources do not establish live Twitch/account integration, real webhook delivery, physical ESP32 display behavior, a full container image build, sustained load or physical CAD fit for these findings. None was exercised during this consolidation.

### Checks performed during consolidation

- Read all three source reports and mapped their findings into the register, preserving the 94 C IDs and incorporating K/D additions.
- Inspected repository status and selected changed locations to identify snapshot drift; this was not a review of the in-progress OAuth implementation.
- Read the local sequence, ACK, BYE and PONG contract sections to qualify conflicting remediation proposals.
- Ran the current Dockerfile check successfully. No full image build or application test suite was run for this documentation change.
- Checked register IDs/counts, local links, source coverage, Markdown table structure and unchanged hashes of the original reports.

## Verification plan for remediation

| Workstream | Evidence needed to close the affected findings |
|---|---|
| Deployment/access | Full image build and service smoke test; every sensitive route tested with Basic Auth alone, API token alone and invalid/missing credentials; public routes and both Twitch callbacks tested without management credentials; signature/OAuth-state checks, browser cross-site protection, environment validation/rotation and secret redaction; intended binding/CORS/proxy exposure and oversized request rejection. |
| Twitch lifecycle | Tests for each transport/credential combination and rejected registration; callback reachable before subscription; fail-first lookup recovery; disconnect/revocation/recovery reflected in status and alerts; token-expiry behavior. Follow with a live-account check. |
| Webhook/API/session bugs | Same and concurrent webhook ID delivered once; update → online preserves metadata; TTL boundaries return intended typed errors; malformed second HELLO closes correctly; nonreading peer is torn down within the deadline. |
| Replay/sequence policy | Bursts larger than eight; full relay outbound queue; both relay-restart sequence branches; lost/reordered connections; sequence exhaustion; stale replay; precise counters and expected duplicates/loss under the chosen contract. |
| Firmware recovery/UI | Native state-machine tests plus hardware AP outage, short roam, unreachable relay, stalled writes and watchdog recovery; long/dark text and large numeric values on the actual round display. |
| Alert/observability correctness | Acknowledge → persistent condition → resolve; competing transitions; accurate event metrics, bounded diagnostics and meaningful retention ordering. |
| Repeatable quality gates | Relay compile/test/format, declared warning policy, Docker check, spec-vector drift, native firmware tests and ESP32 build. Add sanitizers where supported; CAD syntax checks alone remain limited. |
| CAD maintenance | Geometry/export/assembly checks after shared-helper changes; explicit empty-sample diagnostics; documented sampling limits and parameter-change tests. |

K proposes five CI jobs (relay, formatting, contract vectors, firmware host, firmware ESP32), selective execution/caching and branch protection. Retain that as a starting proposal, with Docker validation added; its timing estimates and exact workflow/tool choices are not measured requirements. Heavy CAD tooling can remain a separate/manual validation path with its limits stated.

## Strengths to preserve

- Actor-owned relay sequencing, bounded nonblocking subscriber fan-out, explicit wiring and structured resource ownership.
- Total protocol decoding with bounds checks, resynchronization budgets, explicit wire types and mechanically aligned golden vectors.
- Firmware's bounded codec buffers, explicit byte-order handling, forced string termination, wrap-safe timing and single-loop UI ownership.
- Pure aggregation/monitor transitions and a device queue that follows the current refuse-newest/gap-freeze contract, even though the larger recovery policy needs work.
- Existing webhook HMAC/timestamp checks, secret redaction at the reviewed snapshot, non-root container design and substantial host/socket tests.
- CAD's layered geometry/export checks, while preserving an honest distinction between sampled validation and proof of physical fit.

## Refuted, withdrawn and non-findings

Do not reopen these as established defects without new evidence:

| Claim | Disposition from the source review |
|---|---|
| Missing `finally` around `hub.detach` proves ghost attachments | C Appendix A found no reachable failure path supporting that particular claim. This does not refute the separately reproduced stalled-write issue RLY-07. |
| `/config` currently exposes secrets because masking is a manual list | C refuted the leak at its snapshot: four secret fields matched four masks. RLY-49 retains future drift risk and requires reassessment after current configuration edits. |
| ActivityLog has a current multi-writer race | C found one bus-consumer writer and restricted class visibility; no second writer was established. |
| CAD packaging assertions fail under the documented invocation | C found the claim depended on `python -O`, which the documented workflow did not use. Keep only a possible hardening/style consideration. |
| Unknown BYE encoding is currently out of range | C PROTO-06 says the proposed path is unreachable because values are built through `fromWire`; retain only the demonstrated chat-rate width issue. |
| Trace correlation is broken | D's exploratory probe timed out; no conclusion was established. |
| Files must be split because they are several hundred lines long | K found no justified large-file smell in the cited firmware files. Refactor for a concrete responsibility/duplication problem, not line count alone. |

## Source coverage map

All **C RLY-01–50, FW-01–24, PROTO-01–13 and REPO-01–07** are retained under their original IDs. Consolidated severity changes and current-state qualifications are explicit above.

| D item | Consolidated location |
|---|---|
| #1 Docker validation | RLY-04 |
| #2 transport-specific EventSub credentials | RLY-02 and RLY-11 |
| #3 callback startup order | RLY-43 |
| #4 transient initialization failure | RLY-03 |
| #5 stale readiness | RLY-03 |
| #6 webhook redelivery | RLY-19 |
| #7 blocked output | RLY-07 |
| #8 malformed duplicate HELLO | RLY-51 |
| #9 webhook metadata | RLY-20 |
| #10 TTL overflow | RLY-52 |
| Optional configuration, small queues, warnings and focused refactoring | RLY-24/25, RLY-16, RLY-38/39, prioritized work and remediation qualifications |

| K section/item | Consolidated location |
|---|---|
| Critical demo disconnect and stale simulator path | REPO-01–04; retirement also supersedes REPO-07 |
| Required relay exception handler, nullable strings, management access, warnings | RLY-53, RLY-21, RLY-01, RLY-38 |
| Required firmware connectivity comment/session-change diagnostic | FW-22, FW-25 |
| Required protocol BYE triggers, ACK and REPLAY semantics | PROTO-14–16 |
| Required CAD duplication | CAD-01/02 |
| Relay unused factories/reasons, randomness, duplicate rules, initialization | RLY-54, RLY-17, RLY-55–57 |
| Webhook duplicates, Docker typo, stale test count | RLY-19, RLY-04, RLY-38 |
| Firmware duplicated header checks, UI masks, dead store and counter naming | FW-21, FW-26–28 |
| HTTP error codes, devices pagination and notification response/retry semantics | RLY-58–60 |
| Protocol BYE-table placement and sequence exhaustion | PROTO-17/18 |
| CAD sampling, recompute assumptions, empty samples, grouping, imports, resolution | CAD-03–08 |
| Large-file non-finding, strengths, validation baselines and CI proposal | Refuted/non-findings, strengths, validation evidence and verification plan |

The three original reports remain intact as the evidence trail. This document is the combined triage view; closure should be recorded against the actual fix revision and its verification, not inferred from a report's age or from overlapping reviewer opinions.
