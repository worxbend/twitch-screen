# Relay runtime review workstream

Baseline: `e0fd7b4`, inspected 2026-09-25. Source inspection confirms the findings below; historical reproductions are not represented as fresh tests. Implementation and independent review evidence is recorded per slice.

| Findings | Grooming disposition | Planned verification |
|---|---|---|
| RLY-07, RLY-51 | Confirmed: blocked sink owns PONG lock; malformed full duplicate HELLO skips teardown | Stalled socket deadline, valid/short/invalid-field duplicate HELLO |
| RLY-09, RLY-22 | Confirmed: acknowledged condition reopens; read/replace transitions race | Persistent acknowledged condition, resolution, competing transitions |
| RLY-13, RLY-14 | Confirmed: unbounded sessions and tight accept retry | Bounded admission and retry policy |
| RLY-15, RLY-17 | Confirmed: shutdown only queues; operator reason lost | Wire shutdown drain and disconnect event reason |
| RLY-16, RLY-18, PROTO-03 | Confirmed: greeting capacity truncates and EVENT drop can be crossed | Capacity validation, overflow removes session before later events |
| RLY-27 | Confirmed: impossible framing length classified as skippable payload | Header resync classification and reader conformance |
| RLY-28, RLY-33 | Confirmed: DEBUG evicts useful logs; causes absent, text unlimited | Memory severity threshold, bounded text/cause retention |
| RLY-29, RLY-31, RLY-34 | Confirmed: catch-all metric, callback asks actor, gauge/SDK unclosed, version literal | Exhaustive classification, scoped instruments, nonblocking snapshot |
| RLY-30, RLY-36 | Confirmed: routine observations consume history; eviction identity untested | Retention policy and exact newest retained IDs |
| RLY-32, RLY-52 | Confirmed: unchecked page sizes and overflow on huge TTL | HTTP boundaries, no side effects on invalid TTL |
| RLY-12, RLY-37 | Coverage gaps confirmed | Focused regression assertions and correct device-event test |
| RLY-45, RLY-46, RLY-47 | Confirmed: raw firmware diagnostics, inconsistent IDs, duplicate branch | Wire/HTTP identity boundaries and escaped diagnostics |
| RLY-50 | Confirmed: probe/rebind test ports | Server-owned ephemeral listener |
| PROTO-05, PROTO-06, PROTO-07 | Confirmed: pre-transition STATS, u16 rate wrap, generic-title fallback | Ordered lifecycle state, saturation and kind-specific folding |
| RLY-35, RLY-56 | Confirmed; startup integration delegated to security workstream | Inject application start and shared rules |
| RLY-38, RLY-40, RLY-41, RLY-44 | Build/packaging ownership delegated to orchestrator | Build/format/CI/package evidence |
| RLY-39 | Optional: static analysis tooling absence is not a defect | No broad tooling migration required |
| RLY-55 | Optional: randomness injection only if deterministic restart test needs it | Preserve opaque per-process session IDs |
| RLY-58 | Optional API enhancement, security owns HTTP error contract | Preserve existing compatible shape |
| RLY-59 | Optional pagination; bounded admission limits size | Document maximum live sessions |
| RLY-60 | Documentation change warranted | State 200 result and no request deduplication |

## Progress

Implementation complete; independent review and combined integration remain with the orchestrator.

## Implementation evidence

- `7f682b6`: RLY-09/RLY-22. Three regressions reproduced on the baseline, then all nine AlertStore tests passed. Acknowledged conditions remain open while `activeOnly` and active counts retain their existing unacknowledged meaning. Immutable CAS transitions also deduplicate simultaneous raises.
- `2face5a`: RLY-32/RLY-52/RLY-60 and notification field portion of RLY-06. All 19 API tests passed. TTL milliseconds are checked against the wire maximum before constructing a duration; invalid input has no publish side effect. List limits are 1–500, text fields at most 4096 characters. The endpoint documents 200, wire truncation and retry duplication.
- `4903f81`: RLY-07, RLY-13–18, RLY-27, RLY-45–47, RLY-50–51; PROTO-03/04/06/07/15. DeviceLink (37), DeviceBackpressure (5), reader (27), boundary (3), golden-vector (23) tests passed. The stalled-output fixture uses a real accepted socket with a blocking output adapter and a PING waiting for the sink; the independent deadline closes/detaches it. EVENT overflow removes the session before later fan-out. STATS remains lossy. Config greeting-capacity enforcement is in the security workstream. The 64-session cap includes pending handshakes; at most 16 reclaims are allowed per minute. IDs remain unauthenticated LAN claims. Root owns the process SIGTERM wire smoke for shutdown validation.
- Final lifecycle slice: PROTO-05 and PROTO-18. StatsAggregator owns lifecycle EVENT publication with the newly folded state in one hub operation, tested over a real socket for online and offline. A zero-valued offline state is emitted even when equal to the initial unknown sentinel. A pure SeqNo boundary test checks that successor returns None at the u32 maximum and construction rejects values above it. Source inspection confirms hub publication logs and throws until operator restart; there is no actor/application exhaustion fixture.
- Final diagnostics slice: RLY-12 (diagnostics portion), RLY-28–31/RLY-33–34/RLY-36–37. Real SDK collection checks separate event, observation and link counters and the instrumentation version; gauge lifetime is scoped and uses an atomic device-count snapshot. The SDK is scoped too. Stored text has UTF-8 byte bounds, credential-header redaction and safe cause class/call-site summaries. Logs default to INFO with configurable logger/memory thresholds. Poll observations and chat no longer evict lifecycle activity; retention asserts exact IDs/order. Device-category and overflow-accounting tests now test their names.

## Explicit dispositions and limitations

- RLY-12/RLY-37 status, JSON error and callback diagnostic tests are owned by the HTTP/security workstream. No claim is made that every diagnostic class now has complete coverage.
- RLY-35/RLY-56 startup clock/shared rule wiring are owned by the security workstream. RLY-38/40/41/44 build, CI, packaging and dependency policy are owned by the orchestrator.
- RLY-39 remains optional: no speculative static-analysis migration. RLY-55 remains optional: production session randomness is already isolated to hub construction; current tests need no prescribed random value.
- RLY-59 is addressed by a documented bounded collection, not a new paging contract: the listener admits at most 64 live sessions.
- RLY-50 removes the port allocation race; the existing trickle-input timeout regression still deliberately sleeps between bytes to model that protocol behavior. New race tests use completion conditions and generous outer deadlines.
- Replay is bounded best effort within the running sequence space. Closing on EVENT overflow prevents later high-water progress from hiding that relay-side gap; it cannot restore evicted records or recover a lost process sequence space. Protocol-policy dispositions are recorded by the firmware/spec workstream.
- The admission/reclaim limits limit resource use; they do not authenticate devices or make an Internet-exposed device listener safe. Keep this unauthenticated protocol on its intended trusted LAN.
- Full display/hardware behavior and authenticated Twitch-account integration are outside these host/runtime tests.

Final runtime validation: `./mill test` passed 280 tests, zero failures/errors. All changed Scala was formatted with the pinned Scalafmt. The runtime emitted the existing dependency `sun.misc.Unsafe` deprecation warning; no Scala compiler warnings were reported. Root verifies the combined stack with `-Werror`.

Follow-up RLY-15: the real container SIGTERM smoke exposed that OxApp cancels all sibling service forks before the old Main finally block. ApplicationLifetime now translates interruption into a stop signal while keeping the nested service scope alive for a graceful phase bounded at three seconds. Two ApplicationShutdownSuite tests pass: actual application-fork cancellation produces SERVER_SHUTDOWN BYE then EOF on a held socket, and a cleanup that never completes cannot prevent cancellation. All 37 DeviceLink tests also pass. The hub rejects new attaches during the drain. The orchestrator will rerun the real container SIGTERM smoke on the integrated sources.
