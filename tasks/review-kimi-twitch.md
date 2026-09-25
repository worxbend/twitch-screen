# Kimi review remediation — Twitch integration

Reviewed against `REVIEW_SUMMARY.kimi.md` and baseline `d7863f1`. This register covers every finding naming the relay `twitch/` package; configuration, HTTP infrastructure, bus consumers and device/firmware findings are recorded by their owners. Finding names below are the review's identifiers where it supplied no numbered ID.

## Findings and dispositions

| Finding | Disposition and evidence |
| --- | --- |
| **H1: hardcoded scopes disable unscoped WebSocket subscriptions** | Fixed. `SubscriptionPlan` derives requirements from requested scopes and one `AuthorizationView`. Both transports receive the same eligible kinds. WebSocket stream online/offline/update register with a usable broadcaster grant even without follow scope; unscoped webhooks require no user grant. `SubscriptionPlanSuite` and `WebSocketRegistrationSuite` cover reduced, missing, wrong-account and fully granted cases. |
| Medium: credential-handle publication race | Fixed. No mutable foreign credential fields are republished. `TwitchAuth` exposes a volatile-backed view; registration receives an immutable credential snapshot from the same grant used by the plan and rebuilds after grant changes. `TwitchAuthSuite` covers refresh/sign-out/new-consent races and credential visibility. |
| Medium: authorization endpoint accepts expired/rejected grants | Fixed. The response's `authorized` flag comes from the atomic view's usable grant; held metadata remains available for diagnosis. Endpoint regression covers a retained rejected grant. |
| Medium: ingestion restarts publish false failures | Fixed. `resetSession` records unknown observations without failure events. Expected subscription components are initialized before polling, preventing premature connected/recovered cards. The regression verifies base services alone cannot announce readiness. |
| Medium: compound auth reads mix grants | Fixed. `AuthorizationView` makes account, usability and scopes one atomic query. `TokenProvider` also rejects only the access token actually used by a failed Helix request. |
| Medium: dedup consumes IDs before successful dispatch | Fixed. IDs have explicit in-flight/completed states; completion follows successful dispatch, and failure releases the claim. Concurrent in-flight deliveries receive a retryable response, not a premature acknowledgement. Channel lifecycle publication rolls back its transition if publication throws. Dedup, webhook redelivery and tracker regression tests cover these paths. |
| Medium: reconciliation only reads one page/current callback | Fixed. Lists through `pagination.cursor`, preserves one healthy current subscription and removes stale/duplicate callbacks. Ownership is constrained to this application's listing, the requested kind, exact condition/broadcaster and webhook transport; unrelated broadcasters and transports survive. Pagination has a 100-page bound and rejects repeated cursors. The two-page regression includes a stale tunnel, current duplicate and unrelated broadcaster. |
| Medium: TokenFile single write can truncate | Fixed. `writeFully` loops through short writes before the atomic replacement. A deliberately short-writing channel verifies all bytes are written. |
| Structural: maintainSubscriptions/transport conditionals | Fixed. `EventSubTransportStrategy` separates webhook reconciliation from WebSocket registration; `SubscriptionPlan` contains shared grant/scope policy. The maintenance loop observes health and applies the chosen transport. |
| Structural: scattered scope literals | Fixed in runtime code. `TwitchScopes` supplies the follower/subscriber names to planning and polling. HOCON retains explicit external configuration strings, whose syntax/default validation belongs to the config owner. |
| Structural: string health component keys | Fixed. `HealthComponent` enumerates every component and converts recognized external subscription names at the boundary; unknown provider names cannot mint components. |
| Structural: three foreign-call attempt variants | Fixed. `TwitchCall.attempt` owns exception classification and safe typed failure details. Startup/OAuth/poller adapters retain only their different recovery policies. OAuth token HTTP status is intercepted before credentialmanager 0.5.0 flattens it into exception text. |
| Structural: duplicate EventSub domain mapping (RLY-20) | Fixed. `EventSubMapping` owns both transports' stream/follow/channel-update mapping. Adapters only normalize Java/JSON inputs. Existing handler/webhook/lifecycle suites remain green. |
| Structural: HelixPoller parameter clump and auth closures | Fixed. `PollingContext` carries session inputs and the narrow `TokenProvider` handles grants/rejection. Production does not provide closures independently reading auth fields. |
| Structural: TwitchAuth constructor starts actor/mutates handle | Fixed. A private constructor receives its actor and atomic state; `create` acquires the actor in the caller's Ox scope. |
| Structural: TwitchAuth owns the CSRF jar | Fixed. `PendingAuthorizations` encapsulates random states, expiration, one-time consumption and atomic capacity enforcement. Existing authorization tests cover replay/expiry. |
| Low: dead refresh token retried forever | Fixed. Token-endpoint 400/401 marks that grant terminally rejected until new consent. Network/server failures retain retry behavior. Tests cover the terminal transition and actual OAuth response-status adapter. |
| Low: losing refresh leaves rotated token unrevoked | Fixed. A refresh superseded by sign-out/new consent revokes the obsolete issued token. A rejection-metadata change on the same grant does **not** discard a successful refresh: replacement CAS checks grant identity and retries metadata races. Both scenarios have regressions. |
| Low: first observation logs recovered | Fixed. Recovery logging requires a previously failed component. Unknown startup observations do not count as failures. |
| Low: link-down card loses failure reason | Fixed. Link-down details retain the reason after shared `DiagnosticText` control sanitization, credential redaction and length limiting. Regression verifies sanitized details. |
| Low: absent webhook event becomes an all-None payload | Fixed. A known notification without `event` returns bad input and releases its ID, allowing corrected redelivery. Unknown future subscription types still acknowledge without dispatch. |
| Low: channel-update Java fields not null-normalized (RLY-21) | Stale at baseline: `TwitchEventHandlers.channelUpdated` already normalized all three fields. Preserved and consolidated in shared mapping; existing `TwitchEventHandlersSuite` verifies null/blank semantics. |
| Low: TokenFile stale staging, permissions, unbounded load | Fixed. Unique owner-only staging files are cleaned after failed replacements; startup/sign-out remove abandoned and legacy staging files. Loads reject nonregular/symlink files, broadened POSIX permissions and files larger than 64 KiB. Dedicated persistence tests cover each operational case. |
| Low: freshness checked before HMAC | Fixed. Authentication precedes timestamp parsing/diagnostics. A forged signature with an invalid timestamp is rejected as unauthorized. |
| Low smell: dedup Boolean blindness | Fixed. `WebhookClaim.Fresh`/`Duplicate` replaces positional booleans, with retryable `Fail.Unavailable` for in-flight work or saturation. |
| Low smell: six unused EventSubPayload fields | Fixed. Removed unused fields; JSON still ignores future fields. |
| Low smell: awaitCredential exists only for tests | Fixed. Removed the latch and blocking helper; tests inspect the current credential/view. |
| Low smell: webhook API always constructed | Fixed. It is created only for webhook transport. |
| Low smell: stringly OAuth errors | Fixed. `AuthorizationFailure` and `TwitchCallFailure` distinguish state rejection/provider failures and carry bounded safe details. HTTP response content remains compatible. |
| Performance: suppressed-bot summaries built with DEBUG disabled | Fixed. Summary construction is behind `isDebugEnabled`. |
| Performance: dedup scans the full map per CAS | Fixed in steady state. The atomic state tracks the earliest expiry; pruning occurs only when due, not on every insertion/retry. The capacity remains bounded and live entries are never evicted. |
| Nit: retry's unreachable terminal throw/no jitter | Fixed. A tail-recursive retry returns directly on success and uses bounded half-to-full exponential jitter. Deterministic jitter tests preserve cancellation and the 60-second cap. |
| Nit: Optional→null→Option round-trips | Fixed. IRC display-name/color adapters use `OptionConverters.toScala`. |
| Cross-cutting: provider error text reaches displays/logs | Fixed. Foreign exceptions expose only fixed operation/class/status details; raw exception text is never placed in auth/health events. External health/callback descriptions also use the shared `DiagnosticText` policy. |

## Validation and review

- `./mill test`: full relay suite passed in the isolated worktree after the implementation changes (including actual socket/HTTP suites).
- After the independent review added expected EventSub startup components, `./mill test.testOnly 'twitchscreen.relay.twitch.*'` passed all 102 Twitch tests.
- `./mill mill.scalalib.scalafmt/` formatted changed Scala sources. Compilation uses the existing `-Werror` policy with no source warnings. Runtime retains the pinned Scala/JDK `sun.misc.Unsafe` deprecation notice and Twitch4J's missing-URL configuration warning; neither was suppressed.
- Independent device-agent review identified/confirmed the same-grant refresh CAS race and premature readiness race; both are fixed with regression coverage.
- Root agent performs final integrated rebase, formatting and full-suite checks against the current main branch. Its shared `DiagnosticText` implementation was copied only for isolated compilation and is not duplicated in these commits.
- No production Twitch account or credentials were used. Remote calls are scripted at adapter boundaries; live Twitch service behavior was not claimed as tested.

Pagination/transport policy was checked against Twitch's [API reference](https://dev.twitch.tv/docs/api/reference/#get-eventsub-subscriptions) and [subscription management documentation](https://dev.twitch.tv/docs/eventsub/manage-subscriptions/). Pinned Twitch4J/credentialmanager bytecode was inspected for exact pagination parameter order, credential getters, and OAuth error handling.
