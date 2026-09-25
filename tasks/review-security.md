# Review security and Twitch remediation

Baseline: `a66a052` (implemented atop the orchestration commit).

| Finding | Grooming disposition | Implementation / evidence |
|---|---|---|
| RLY-01,05,06 | Confirmed: public management, wildcard CORS, unbounded bodies | Implemented: fail-closed Basic/Bearer access, explicit public routes, pre-buffer 64KiB body limit and 128 HTTP connections. Six real Netty tests pass, including chunked overflow. |
| RLY-02 | Token selection fixed by OAuth commit; registration reporting remains open | Preserve app-token fallback and surface outcomes |
| RLY-03,10,11,43 | Confirmed lifecycle/retry/health gaps despite OAuth refresh support | Pending |
| RLY-04 | Syntax fixed before this work | Root owns image build/smoke |
| RLY-08,19,20,21 | Confirmed conflicting observations, duplicate deliveries, stale metadata, nullable text | Pending |
| RLY-23,24,25,26,48 | Confirmed provider/config validation gaps | Implemented provider/wire/timer constraints, path-qualified reader failures and nonblank secrets. ConfigSuite passes. |
| RLY-35,56 | Confirmed startup timestamp and duplicated rule conversion | Pending integration |
| RLY-42 | Current first-live announcement explicitly specified in tests and initializes statistics | Retain intentional behavior; document rather than silently change event semantics |
| RLY-49 | Current two Twitch secrets correctly masked | Implemented current secret-field coverage including verifier/API token. |
| RLY-53 | Source report contradicted by pinned Tapir 1.13.31 defaultHandlers implementation | Refuted on current pinned dependency: real Netty regression verifies 500 application/json {"error":"Internal server error"}; private detail absent. |
| RLY-54 | Optional unused factory cleanup | Pending |
| RLY-57 | Order-sensitive definitions | Converted derived mappings to methods. |
| RLY-58 | Optional API expansion with no demonstrated client requirement | Deferred: retain compatible error envelope |

No live Twitch credentials are available; real-provider subscription acceptance cannot be verified locally. Tests will exercise adapters and state transitions with scripted clients.
