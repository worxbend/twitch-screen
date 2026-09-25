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

Implementation pending.
