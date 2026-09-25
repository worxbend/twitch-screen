# Review Summary — twitch-screen (Kimi swarm review, 2026-09-25)

**Remediation:** see the [integrated result and finding-by-finding dispositions](tasks/review-kimi-summary.md). The original findings below are retained as the review record.

**Scope:** `twitch-screen-relay/` (Scala 3, ~98 source files + tests) and `twitch-screen-firmware/` (C++/PlatformIO, all of `src/`, `include/`, `test/`), plus `demo-server/`, `tools/`, `platformio.ini`, `build.mill`, CI workflow, and `docs/PROTOCOL.md`.

**Method:** 21-agent swarm in 4 passes, every file reviewed line by line, findings cross-checked against `REVIEW_CONSOLIDATED.md` and `tasks/review-*.md` (marked NEW or KNOWN):
1. **Pass 1 — bug hunt** per package/module (9 agents)
2. **Pass 2 — code smells** (Refactoring Guru / Clean Code / Code Complete vocabulary) (5 agents)
3. **Pass 3 — security & performance** per project (4 agents)
4. **Pass 4 — cross-cutting**: protocol parity Scala↔C++, relay concurrency (Ox), embedded robustness & tooling (3 agents)

**Verification:** reviewers ran the firmware host suites (5379 + 849 + 70 + 18 + 3000 fuzz checks — all green), regenerated and diffed the protocol golden vectors (20/20 match on both sides), and inspected pinned library sources (Ox 1.0.8, Tapir 1.13.31, twitch4j, LVGL 9.6.0, Arduino-ESP32 2.0.17) where semantics mattered.

## Totals

| Severity | Count (unique, after dedup) |
|---|---|
| Critical | 0 |
| High | 2 |
| Medium | ~45 |
| Low | ~75 |
| Nit | ~55 |

Overall verdict: the codebase is in strong shape — all previously registered findings (RLY/FW/PROTO registers) were re-verified as genuinely fixed. What remains is two architectural/security decisions, a handful of real Medium bugs, and a long tail of Low/Nit hygiene. No memory-safety defect was found in either codec; all queues/buffers are bounded.

---

## High

### H1 — Hardcoded scope requirements cripple the WebSocket EventSub transport — NEW, bug
`twitch-screen-relay/src/twitchscreen/relay/twitch/LiveTwitchSource.scala:106-108,134,149`
`missing` demands `moderator:read:followers` + `channel:read:subscriptions` even when `twitch.oauth.scopes` never requested them, and the WebSocket branch gates **all** EventSub registrations (including scope-free stream.online/offline/channel.update) on the follow scope. A deliberately reduced-scope grant silently loses all stream-lifecycle events on that transport and degrades health permanently on both. The webhook branch does this correctly.
**Fix:** derive required scopes from `config.oauth.scopes`; register unscoped subscriptions whenever a broadcaster token exists; gate only the scoped ones. Replace Conditional with per-scope predicates shared by both transport branches.

### H2 — Device link is plaintext and unauthenticated (architectural decision to confirm) — NEW, security
`docs/PROTOCOL.md:74`, `twitch-screen-firmware/src/link_transport_esp32.cpp:113-125`
Raw TCP on 8099; the only identity proof is the self-declared `device_id` in HELLO. Any LAN host can impersonate the device (triggering `BYE_REPLACED`, pinning the real device at max backoff) or run a rogue relay pushing arbitrary EVENT text / fake STATS / an 18 h `retry_after` onto the physical display.
**Fix:** either write "trusted home LAN" into PROTOCOL.md §2 as an explicit threat-model assumption with the impersonation consequence, or add TLS with pinned cert (`WiFiClientSecure`/mbedTLS is in the Arduino core) — at minimum a pre-shared token field in HELLO validated by the relay.

---

## Medium — bugs (fix these first)

### Relay — protocol/device
- **FrameReader timeout escapes as an exception, not the `FrameTimeout` value** — `protocol/FrameReader.scala:140`. `SocketTimeoutException` propagates out of `read`, silently violating the `Either[ProtocolError, Frame]` contract; the one production caller catches `IOException` by accident. → catch and map to `Left(FrameTimeout)`, or document the throws clause and delete the dead value.
- **`DeviceHub` `tell` operations can kill the hub actor** — `device/DeviceHub.scala:59,93-94`. In Ox 1.0.8 an exception from a `tell` body rethrows into the fork and fails the whole supervised application scope; one logger/bus failure silently ends all session management. → route hub ops through `ask(...).discard` or wrap tell bodies in a catching guard.
- **Reclaim rate budget is global, not per device id** — `device/DeviceHub.scala:150,156`. One flapping board exhausts the hub-wide 16/min window; other devices reconnecting over a half-open corpse then get `BYE(RateLimit, 60s)`. → key the reclaim window by `DeviceId`.
- **AlertMonitor blocks its fold thread on a full hub snapshot every tick** — `alerts/AlertMonitor.scala:49`. It only needs the connected count; use the existing atomic `hub.connectedCount` (DeviceHub.scala:73). One-line change removing staleness-under-load risk.

### Relay — twitch
- **Credential handle published out of order, no happens-before edge to twitch4j readers** — `twitch/TwitchAuth.scala:139-165`. `install` sets the volatile token ref *before* `updateHandle` rewrites the shared `OAuth2Credential`, whose fields are plain non-volatile; twitch4j threads can pair new state with stale handle fields or never observe the rewrite. → call `updateHandle` before `token.set`; better, hand twitch4j a credential view whose getters read the volatile `AtomicReference`.
- **`authorized` reports true for expired/rejected grants** — `twitch/TwitchAuthApi.scala:69`. `current.isDefined` is intentionally retained after failure, so `/twitch/authorization` says `authorized: true` while health says Degraded (Boolean Blindness over a tri-state). → expose `auth.accessToken.isDefined` or split `grantHeld`/`usable`.
- **Every ingestion (re)start publishes up to 3 false failure events** — `twitch/LiveTwitchSource.scala:56-58`. Routine WebSocket subscription rebuilds flash "authorization required" cards on the device and inflate the failure-rate alert. → add a non-publishing "reset to unknown" on `TwitchRuntimeHealth`, or observe failure only after an actual failed attempt.
- **Flow consumers have no failure isolation** — `stats/StatsAggregator.scala:29-43`, `alerts/AlertMonitor.scala:32-38`. Unlike `EventBus.consume`, folds run bare in `forkDiscard`; any escaped NonFatal ends the application scope (Ox semantics) — a peripheral bug becomes a full relay outage. → same catch-log-continue policy as `EventBus.consume`.
- **Non-atomic compound read of TwitchAuth state** — `twitch/LiveTwitchSource.scala:69,91-92`. Three separate reads (`current`, `accessToken`, scopes) can mix grants during a refresh. → one atomic query on `TwitchAuth` (Move Method).
- **Dedup ID consumed before dispatch** — `twitch/EventSubWebhookApi.scala:61-65`. A failed dispatch makes Twitch's redelivery a silent drop: exactly-once intent becomes at-most-once. → claim only after successful dispatch, or `release(id)` on failure.
- **Subscription reconciliation reads one page and matches only the current callback URL** — `twitch/EventSubWebhookApi.scala:201-206`. Stale subscriptions from old tunnels accumulate against Twitch limits; >100 subscriptions → duplicates. → paginate with `after`; delete this app's subscriptions regardless of callback URL.
- **`TokenFile` assumes a full single write** — `twitch/TokenFile.scala:39`. One `channel.write` permits short writes; a truncated staging file then atomically replaces a good token file. → loop `while (buffer.hasRemaining)`.

### Relay — http/config/security
- **Ping/idle cross-field check compares untruncated durations against a truncating wire** — `config/Config.scala:39-45`. `ping=1500ms, idle=1900ms` validates but encodes as 1s/1s → config-validated reconnect loop. → validate the truncated wire values (`toSeconds`), with margin.
- **Unknown/mis-spelled secret keys render unmasked in `/config`** — `config/ConfigApi.scala:31-36,48-54`. PureConfig's default `allowUnknownKeys = true` ignores `twitch.clientSecret` (camelCase), and `/config` then publishes it verbatim. → `allowUnknownKeys(false)` and/or mask by suffix rule (`*-secret`, `*-token`, `*-password-hash`).
- **No idle/read timeout on the HTTP listener (slowloris)** — `http/HttpApi.scala:42-50`. 128 idle connections exhaust the management API budget. → add `IdleStateHandler` + close-on-idle to the Netty pipeline.
- **Cleartext management credentials with no warning** — `resources/application.conf:4-17`, `Dockerfile:53`. Plain HTTP + `host = 0.0.0.0` default; Basic/Bearer cross the LAN unencrypted. → loud startup WARN when auth is configured and host is non-loopback without TLS.

### Firmware
- **Duplicate WELCOME mid-stream silently rewinds session state** — `src/link_client.cpp:307`. A second WELCOME while `Streaming` re-runs `handleWelcome`, re-baselining `lastSeq_` downward and producing duplicate replay bursts. → treat `T_WELCOME` in `State::Streaming` as teardown; only `T_BYE` is legal in every state.
- **Task watchdog init silently no-ops — effective timeout is 5 s, not 10 s** — `src/main.cpp:102`. IDF 4.4 pre-initializes the TWDT (5 s) before `setup()`; the return value is ignored. → check/log the return; set `CONFIG_ESP_TASK_WDT_TIMEOUT_S=10` or accept 5 s and fix the comment; also surface `enableLoopWDT()` failure.
- **DNS `Pending` has no deadline — a lost lwIP callback wedges the link forever** — `src/link_transport_esp32.cpp:95`. Every later attempt fails as "connect unavailable"; only power cycle recovers. → deadline the Pending state (~10 s) or reset it in the connect-timeout teardown.
- **Full-queue pause disables the silence timeout without an upper bound** — `src/link_client.cpp:495`. `eventPaused` force-refreshes `lastRxAt` forever; a relay dying without FIN during a replay burst leaves the screen frozen-online for up to ~80 min. → cap the pause extension (e.g. 2× negotiated `idle_timeout_s`, then teardown).
- **App-level `Serial.printf` bypasses the nonblocking logging discipline** — `src/main.cpp:43,46,62-66,72-77`. An 80-event replay burst stalls the loop task on UART drain for hundreds of ms — exactly the storm FW-11 protected against in the link layer. → route through the `availableForWrite()`-gated transport log sink.
- **CONNECTING timers/animations churn the heap while hidden** — `src/ui_idle.cpp:288-293,330`. `lv_label_set_text` in LVGL 9.6 always free+malloc+re-layouts; ~192k alloc/free pairs/day after the group is hidden. → pause/resume the timer in `applyVisibility()`; use `lv_label_set_text_static` for the fixed strings.
- **Per-STATS label updates are unconditional** — `src/ui_idle.cpp:380-389`. Six free+malloc+invalidate per STATS frame including the hidden group's chips. → compare-and-skip guards; skip the hidden group.

### Tests
- **`gen_vectors.py` prose cross-check can silently degrade to a no-op** — `twitch-screen-firmware/test/test_proto_codec/gen_vectors.py:129-141`. If the spec's bullet format drifts, generation succeeds with 0 checked assertions and CI stays green. → `sys.exit` when a vector's `checked` count is 0. **This is the single highest-leverage test fix** — it anchors both implementations' golden vectors.
- **Greeting tests never assert quiescence** — `twitch-screen-relay/test/.../device/DeviceLinkSuite.scala:33-45,111-117`. "exactly two frames"/"no replay" read only a prefix; an extra replay EVENT would pass. → assert `device.receive().isEmpty` after the greeting (200 ms timeout).
- **Session test assertion weaker than its name** — `twitch-screen-firmware/test/test_link_session/test_link_session.cpp:100`. Checks only `>= 56` bytes of a 68-byte HELLO, never the content. → assert exact size + `memcmp` against a locally encoded HELLO.
- **ASCII assertion sweeps binary fields, holds only by accident** — `twitch-screen-relay/test/.../protocol/WireStringsSuite.scala:126-136`. Any realistic timestamp breaks/falsifies it. → slice only the actor/text ranges for the ASCII check.

### Cross-cutting
- **`byeAdvice` offers a BYE code the spec forbids** — `twitch-screen-relay/.../protocol/ProtocolError.scala:79`. `InvalidSequence` → code 4, which §6.7 marks "MUST NOT be sent". Unreachable today (also dead code) but a loaded gun. → delete the case and its arms.
- **Firmware HELLO strings bypass §9 sender rules; the relay test double doesn't** — `twitch-screen-firmware/src/proto_codec.cpp:341-349`. `DEVICE_ID`/`fw_version` go out verbatim — no control-byte sanitization; a misconfigured device id earns a BYE(3) loop with no local diagnosis, and golden vectors can't see it. → validate `0x20…0x7e` at boot or sanitize in `packWireString`.

---

## Medium — structural smells (named refactorings)

### Relay
- **`DeviceHubState` Large Class / Divergent Change** — `device/DeviceHub.scala:104-333`. 11 fields, 15 methods: sequencing, replay rings, admission, reclaim, greeting, broadcast, stats, teardown. → **Extract Class**: `ReplayBuffers`, `ReclaimPolicy`, `SequenceAllocator`; actor keeps orchestration. Do this before the PROTO-01/02/03 replay work lands there.
- **Queue-BYE-then-close duplicated 4×** — `DeviceHub.scala:146-154,183-186,258-261`. → **Extract Method** `queueFinalBye(...)`.
- **Per-kind policy scattered across four matches** — `Tsb3Encoder.scala:72-77`, `EventRecord.scala:57-61`, `DeviceHub.scala:308-313,340-341`. → **Replace Conditional with Polymorphism**: push `defaultTtl`/`foldsToPlaceholder`/`isGeneric`/`isDurable` onto `NotificationKind` enum cases.
- **Two construction vocabularies for EVENT semantics** — `EventRecord.scala:131-170` (test-only factories) vs `NotificationRouter.scala:41-111` (production). Two sources of truth for §6.4.1. → make the router the single mapping; move `EventRecord` factories to test sources. Do **not** add an abstraction layer above both (overkill).
- **`DeviceSession` Long Parameter List / Data Clump** — `DeviceSession.scala:140-151,173-180,216-224,240-246`. The `(sink, counters, config, caps[, socket])` clump recurs in 4 signatures. → **Introduce Parameter Object** `SessionIo`.
- **One new counter = five edits in two files** — `LinkCounters.scala:42-108` + `DeviceApi.scala:13-61`. → **Preserve Whole Object**: embed `LinkTraffic` in `Device_OUT`.
- **`maintainSubscriptions` Long Method + transport Switch Statements** — `twitch/LiveTwitchSource.scala:94-152`, transport matched at 4+ sites. → **Replace Conditional with Polymorphism**: `EventSubTransportStrategy` (`WebhookTransport`/`SocketTransport`) + Extract a shared `SubscriptionPlan` builder. This single extraction collapses four Mediums and prevents H1-class desyncs.
- **Scope-name literals scattered across five sites** — `LiveTwitchSource.scala:106,123,134`, `HelixPoller.scala:56-57`, `application.conf:80`. → a `TwitchScopes` object/enum consumed by config, maintenance, and poller.
- **Health components keyed by raw strings at 19 call sites** — `twitch/TwitchRuntimeHealth.scala:12,25`. A typo silently mints a new component. → sealed `HealthComponent` ADT.
- **Three near-identical `attempt` variants** — `LiveTwitchSource.scala:205-210`, `HelixPoller.scala:114-123`, `TwitchOAuthClient.scala:86-88`. → one `TwitchCall.attempt(what)(call)` boundary.
- **Duplicated transport→domain mapping (RLY-20 follow-through, KNOWN)** — `TwitchEventHandlers.scala:88-103` vs `EventSubWebhookApi.scala:119-147`. → shared `EventSubMapping` module with thin per-transport adapters.
- **`HelixPoller` 9-param signature + closures into `TwitchAuth`** — `twitch/HelixPoller.scala:23-33`. → **Introduce Parameter Object** + narrow `TokenProvider` role interface.
- **Anemic config types / Feature Envy** — `config/Config.scala:82-115`. `TwitchConfig.validate()` enforces `TwitchOAuthConfig`/`EventSubConfig` invariants. → validated readers/smart constructors on the sub-configs (like `Hostname`/`Port`).
- **Reader boilerplate duplicated 9×** — `Config.scala:131,209-230`, `HttpAuthConfig.scala:37`. → one `ValidatedConfigReader.derivedValidated[A]` helper.
- **Magic numbers for operational limits** — `http/HttpApi.scala:46,50` (`128`, `65536` also hardcoded in the 413 body). → named constants, ideally promoted to `HttpConfig`.
- **`TwitchAuth` side-effecting constructor** — `twitch/TwitchAuth.scala:32-54`. Creates an `Actor` and mutates a foreign handle in the constructor; every peer uses a factory. → private constructor + companion factory (direct-style convention).
- **`TwitchAuth` Divergent Change** — owns token lifecycle *and* the OAuth state CSRF jar. → **Extract Class** `PendingAuthorizations`.

### Firmware
- **`EncodeResult` uses in-band negative sentinels** — `src/proto_codec.h:258-260`, contradicting the codec's own "no in-band sentinel" rule; a missed `n < 0` check silently casts to `size_t`. → POD `struct EncodeResult { uint16_t size; EncodeError err; }`.
- **`link_client.cpp` is a C-style singleton** — `src/link_client.cpp:50-82`: ~20 file-scope mutable globals; `linkInit` hand-resets 15+ fields and any forgotten field is a latent reconnect bug. → **Extract Class** `LinkSession` struct (one static instance, zero heap); also improves host tests.
- **`deliverFrame` Long Method** — `src/link_client.cpp:320-382`: five responsibilities. → Extract `checkVersion()`, `maybePauseEvent()`, `enforceHandshakeStrictness()`, `noteSkippedFrame()`.
- **Five parallel switches on `NotifyKind`** — `src/notification.h:66-81`, `src/ui_notify.cpp:110-152`. → one `KindPresentation{label, icon, color, holdMs}[]` table indexed by ordinal (also shrinks flash).
- **`clearDecor`/panel-size duplicated across UI files** — `src/ui_idle.cpp:87-92` vs `src/ui_notify.cpp:219-224`; `240, 240` appears 5×. → shared `ui_common.h` (inline fn + `constexpr int PANEL`).
- **`uiNotifyShow` Long Method** — `src/ui_notify.cpp:331-387`. → Extract `applyKindPresentation`, `applyTexts`, `playEntrance`.

---

## Low (selection — full detail in swarm archive)

**Relay bugs:** operator `disconnect` drops queued frames where `reclaim` drains (`DeviceHub.scala:239-249`); over-limit connections refused silently — no log/counter (`DeviceLinkServer.scala:50-52`); failed PONG write discarded (`DeviceSession.scala:263`); oversize-drop keeps link after EVENT drop (latent, `DeviceSession.scala:185-191`); dead refresh token retried forever with no terminal state (`TwitchAuth.scala:113,224-227`); CAS-lost refresh leaves an unrevoked rotated token at Twitch (`TwitchAuth.scala:128-130`); spurious "recovered" on first health observation (`TwitchRuntimeHealth.scala:28`); link-down card discards the failure reason (`TwitchRuntimeHealth.scala:49`); absent webhook `event` masked by all-None payload (`EventSubWebhookApi.scala:120`); chat/WS path doesn't null-normalize channel-update fields (RLY-21 residual, `TwitchEventHandlers.scala:99-101`); callback URL validation case-sensitive (`Config.scala:112-113`); `twitch.oauth.scopes` never validated in live mode (`Config.scala:82-107`); sub-millisecond intervals admitted (`Config.scala:67,83,148,165`); memory-sizing knobs unbounded (`Config.scala:143-174`); `/status` blocks on an unbounded hub ask (`StatusApi.scala:59`); readiness/auth-config default bypasses validation (`Config.scala:10`); alert message freezes elapsed duration at first raise (`AlertMonitor.scala:53`); alert monitor runs with zero rules (`AlertMonitor.scala:27-38`); EventBus subscriber overflow is silent (`EventBus.scala:68-71`); Basic-auth timing fingerprint (`ManagementAuth.scala:64`); Basic-permit exhaustion → 503 for legit logins, undocumented at site (`ManagementAuth.scala:22,65-66`); case-sensitive Host/Origin compare (`ManagementAuth.scala:90`); TokenFile: stale `.tmp` copy, no re-check of permissions, unbounded load (`TokenFile.scala:23-40`); freshness checked before HMAC (`EventSubWebhookApi.scala:58-59`).

**Relay smells:** `AttachedDevice` Data Class + Feature Envy (`DeviceHub.scala:343-352`); replay-ring merge duplicated (`DeviceHub.scala:227,297-299`); `FrameReader.read` Long Method (`FrameReader.scala:68-106`); version-mismatch Refusal duplicated (`DeviceSession.scala:124-133,247-255`); `protocolVersion: Int` Primitive Obsession (`DeviceHub.scala:28`); boolean blindness in write loop (`DeviceSession.scala:181-207`) and in `WebhookDeduplication.claim`/`ManagementAuth.checkBasic`; six never-read `EventSubPayload` fields (`EventSubMessage.scala:23-30`); `awaitCredential` test-only dead code (`TwitchAuth.scala:70-72`); webhook API constructed unconditionally (`LiveTwitchSource.scala:31-32`); stringly-typed `Either[String, _]` OAuth errors; bus-fold scaffolding duplicated (`AlertMonitor` vs `StatsAggregator`); rule knowledge shotgun (`AlertRule`+`MonitorState`); three parallel `RelayEvent` classifications; bounded-append ring ×3; `Otel` appender install unscoped (`Otel.scala:36`); `Dependencies.scala:26` global resize in composition root; `ConfigApi.resolved()` does a second independent config load (`ConfigApi.scala:46`); `Sensitive.Empty` sentinel instead of `Option`; section registries restated 4× (`ConfigApi` Shotgun Surgery); triplicated enum config readers.

**Firmware bugs:** backoff burned while DNS pending / close-worker busy (`link_transport_esp32.cpp:95` + `link_client.cpp:425`); WELCOME.caps trusted verbatim as `effectiveCaps` — mask with `DEVICE_CAPS` (`link_client.cpp:214`); backoff reset contradicts PROTOCOL.md §12 — update the spec (`link_client.cpp:500-501`); `xTaskNotifyGive(closeTask)` unguarded null-handle if worker creation failed (`link_transport_esp32.cpp:147-149`); replayed cards still play slide-in despite comment/§6.4 (`ui_notify.cpp:364-370`); `initDsc` trusts generator size with no `static_assert` (`ui_idle.cpp:60-71`); LVGL render stack headroom on the 8 KB loop task unmeasured (`main.cpp:106-119`); LVGL boot allocations unchecked → panic boot loop (`lv_port.cpp:45`); two overlapping WiFi reconnect policies (`link_transport_esp32.cpp:74,79-82`).

**Firmware smells/perf:** header extraction duplicated (`proto_codec.cpp:120-124` vs `436-439`); encode→check→write boilerplate ×4 (`link_client.cpp:162-209`); `byeFloorMs`/`byeForceMax` temporary-field clump; `static const` in header instead of `constexpr`; magic numbers in the otherwise-disciplined files (`3072`, `15000`, shift caps `6/5`, watchdog `10`); uptime/viewer labels realloc every tick — use `lv_label_set_text_static` (`ui_idle.cpp:143-149`); every live card pays two full-screen animations (`ui_notify.cpp:370-386`); infinite animations run on hidden groups (`ui_idle.cpp:210,313-325`); `LV_USE_FLOAT 1` unused (`lv_conf.h:61`); TX FIFO O(n) memmove per partial write (`link_client.cpp:418-419`); WiFi PSK baked into flash without flash encryption (document or plan secure boot, `credentials.h`).

**Tests/tools:** fuzz harness is differential-only — add an oracle arm (`test_parser_fuzz.cpp:42-68`); relay greet predicate transcribed into C++ test (`test_link_wire.cpp:373-385`); real-sleep timing assertion (`FrameReaderSuite.scala:147-157`); host test builds lack `-Werror` (`platformio.ini:81-88`); ttl clamp boundary 6000/6001 untested on both sides; magic timing literals in session tests; PONG-overflow probe can block for minutes (`DeviceLinkSuite.scala:205-219`); crash before summary leaves no test record (`test_custom_runner.py:37-59`); `formatCount` rounding boundaries untested; `smoke_container.py` has a third hard-coded copy of vector V1 and never asserts STATS/EVENT delivery; `check_protocol_vectors.py` brittle anchors fail with bare tracebacks.

**Performance (relay):** `LogBuffer` redaction regex compiled per log line ×3-4 fields — hoist the `Pattern` (`LogBuffer.scala:38`); `WireStrings.fold` compiles a regex + allocates per character on the hottest encode path — hoist pattern + ASCII fast path (`WireStrings.scala:75,95-106`); suppressed-bot log builds `event.summary` eagerly at INFO (`BotFilter.scala:51`); per-skipped-frame DEBUG builds `describe` eagerly (`DeviceSession.scala:281`); identical frames re-encoded per session (`DeviceHub.scala:303`); dedup claim is O(n) per CAS retry (`WebhookDeduplication.scala:14-20`); 50 ms deadline watcher per session (`DeviceSession.scala:49-52`).

---

## Nit (representative)

Unreachable `WrongDirection` fallbacks (`Tsb3Decoder.scala:37,54`); dead `EventValue.toInt` truncation hazard (`EventRecord.scala:27`); absent wire timestamp surfaces as 1970 in the HTTP API (`Notification.scala:42`); `WireStrings.truncate` breaks its cap contract for width ≤ 3 (`WireStrings.scala:62`); folding gaps for `ẞ`/ligatures (`WireStrings.scala:105,181`); `Deadline` overflow on absurd budgets (`FrameReader.scala:154`); accept-failure logging goes silent at the cap (`DeviceLinkServer.scala:59-60`); "attached as #N" logged on refused attach (`DeviceSession.scala:158`); write watchdog uses idle timeout during handshake (`DeviceSession.scala:49-52`); manual lifecycle card re-broadcasts STATS (`DeviceHub.scala:207-211`); heartbeat PING drops uncounted (`DeviceSession.scala:214`); duplicate subscriber names allowed (`EventBus.scala:53`); `SubscriberStats.delivered` overstates delivery; actor mailboxes implicitly bounded at 16 — undocumented load-bearing invariant; `lowerCaseEnums` dead with stale comment (`ApiJson.scala:20-27`); `WWW-Authenticate` on 403/503 (`ManagementAuth.scala:33-37`); redundant `endsWith(CallbackPath)` require (`Config.scala:93-96`); `Hostname` accepts embedded whitespace; scalar fallback silently coerces non-lists (`StringListReader.scala:9-12`); negative uptime on backward clock step (`StatusApi.scala:63`); pipeline insertion couples to Netty internal handler name (`HttpApi.scala:50`); unreachable terminal throw + no jitter (`TwitchRetry.scala:15-17`); `Optional.orElse(null)` round-trips (`TwitchEventHandlers.scala:114,122`); raw-hex `kindIsKnown` duplicating the enum (`notification.h:66-81`); `== 0` instead of `nullptr` throughout `proto_codec.cpp`; unused includes (`notification.h:4`, `ui_idle.h:3`); chained ternary for CONNECTING dots; stale "protocol v2" comment in local `credentials.h`; unseeded `random()` jitter (`link_transport_esp32.cpp:85`); two counters lack descriptions (`RelayMetrics.scala:43-44`); redundant ACK test case (`Tsb3DecoderSuite.scala:181-182`); `asInstanceOf` instead of named failure (`ProtocolBoundarySuite.scala:21,27`); single-case `HealthStatus` enum.

---

## Cross-cutting themes

1. **Trust-boundary sanitization is inconsistent.** LogBuffer sanitizes its records; the activity export, on-screen alert cards, and twitch4j error text paths don't share the policy (`TwitchOAuthClient.scala:86-88` → device display; `ActivityApi.scala:40`). Apply `WireStrings.sanitise` (or a shared policy) at every exit boundary.
2. **Two error philosophies coexist.** The protocol package is total (`Either`), but exceptions leak at its edges (FrameReader timeout, Ox fork semantics). Pick per-module contracts and enforce them at the boundary.
3. **Duplication across languages is the real drift risk.** §6 layouts exist 3× (Tsb3.scala, proto_codec, hand-written test builders); greet predicate 2×; golden vector V1 3×; spec prose vs code disagreed twice (§12 backoff, §6.7 BYE codes). `gen_vectors.py`/`check_protocol_vectors.py` are the anchor — harden them first (fail-loud on zero cross-checks, named anchors).
4. **Silent-degradation pattern.** Several safety mechanisms fail quietly: watchdog init no-op, over-limit refusals unlogged, EventBus drops unlogged, dedup-before-dispatch, health "recovered" spam. The fixes are all "fail/signal visibly at the boundary".
5. **Known-and-deferred items remain valid:** BYE `retry_after` uncapped floor (FW-10, ~18 h suppression — revisit jointly with H2), no OTA (FW-18), RLY-13 unauthenticated device protocol, dependency exceptions expiring **2026-10-25** (RLY-41 — schedule the twitch4j/Hystrix/Archaius replacement or re-assessment before then).

## Recommended fix order

1. **H1** — scope gating desync (`LiveTwitchSource.scala:106-134`): valid configs silently lose all stream events.
2. **Watchdog misconfig** (`main.cpp:102`): every hang-recovery guarantee is unverifiable until fixed.
3. **Hub actor `tell` kill path** (`DeviceHub.scala:59,93-94`) + **flow-consumer isolation** (`StatsAggregator`/`AlertMonitor`): one bad frame of work currently cascades to full relay shutdown.
4. **Credential-handle publication race** (`TwitchAuth.scala:139-165`) — undermines the token-freshness design.
5. **DNS-pending wedge + eventPaused unbounded pause + duplicate-WELCOME rewind** (firmware link trio) — the three remaining "device silently stuck" modes.
6. **`/config` unmasked unknown keys + HTTP idle timeout + cleartext-auth warning** — cheap, concrete security wins.
7. **`gen_vectors.py` fail-loud + quiescence assertions** — keep the parity gate honest.
8. **Extract `EventSubTransportStrategy` + `LinkSession` struct + split `DeviceHubState`** — the three structural extractions that unlock the upcoming PROTO-01/02/03 work.
9. **LogBuffer/WireStrings regex hoisting + LVGL heap churn guards** — the only findings with steady-state runtime cost.
10. **H2** — decide and document the device-link threat model (plaintext trusted-LAN vs TLS/PSK).

## Verified strengths (checked, no defect)

- Both codecs: every read bounds-checked, offsets pinned by `static_assert`, no unbounded allocation on any path, SeqNo cannot wrap, UTF-8 truncation matches §9.2 exactly, resync budgets match spec on both sides. 20/20 golden vectors machine-verified in CI; fuzz + ASan/UBSan suites green.
- Relay auth: fail-closed endpoint classification, constant-time token compare, PBKDF2 bounded by semaphore, security logic runs before body decoding, no secrets in logs, OAuth state single-use/bounded/expiring.
- Concurrency: hub actor never blocks, EventBus copy-on-write publish, `InterruptedException` not swallowed (verified against Ox/jox), shutdown ordering tested and sound.
- Firmware: zero dynamic allocation in codec/queue/transport hot paths, assets in flash, LVGL threading model clean (all calls on loop task), flush path race-free.
