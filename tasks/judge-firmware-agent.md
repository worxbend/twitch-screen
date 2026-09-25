# Independent judge: management security and Twitch lifecycle

Reviewer: firmware/protocol implementer, reviewing another agent's relay changes.
Baseline: `e0fd7b4`; reviewed security stack `e36f04e`, `ef48615`, `1dc5efa`,
`dd76b36`. Lifecycle code was reviewed while the owner completed its validation
slice and final refinements were checked at `dd76b36`.

Verdict: **APPROVE the reviewed security/Twitch slice for integration.** All six
concrete source findings below are corrected and the independent focused run
passed. The root agent still owns the final integrated build and complete suite.

## Scope inspected

- Management endpoint classification and pre-handler authorization, public health,
  aggregate stats/docs, and separate OAuth/EventSub callback validation.
- Basic/Bearer alternatives, salted PBKDF2 verifier, token comparison, startup
  configuration validation, secret masking, browser cross-site checks.
- Netty body limits for declared-length and streamed request content; exception
  response mapping and endpoint tests.
- OAuth token/state handling, shared Twitch4J credential identity and persistence;
  startup listener ordering, retries and bounded delays; subscription reconciliation,
  ongoing health, Helix polling and concurrent stream observations.

## Required findings and closure

1. **Refresh/sign-out persistence race.** A successful token CAS followed by
   `afterInstall` could race sign-out or newer consent: the old refresh then
   overwrote the shared credential and token file despite the new in-memory
   state. **Resolved:** `CredentialTransitions` owns accepted changes and their
   handle/file effects in one actor call. OAuth tests cover stale remote refresh
   completion racing sign-out and new consent, including handle/file results.
   Those tests pause before the CAS; closure of the exact post-CAS schedule rests
   on inspection of the actor boundary, not a claim that tests force that schedule.
2. **Stream transition publication order.** `ChannelStateTracker` serialized CAS
   state transitions but returned an event for callers to publish later. Concurrent
   online/offline callbacks could publish END before START while tracker state was
   Offline. **Resolved:** tracker actor calls now include publication for webhook,
   WebSocket and polling paths; the concurrent test blocks START publication while
   an END transition attempts to enter and confirms ordered output.
3. **Unusable static token configuration.** A 32-byte token containing CR/LF or
   non-header characters passed validation yet could not be transmitted faithfully
   as Bearer authorization. **Resolved:** startup accepts only the ASCII Bearer
   token alphabet and the minimum length, without trimming/mutating secrets;
   configuration tests cover rejected controls/non-ASCII values.
4. **Partial WebSocket subscription recovery.** An unrelated subscription success
   cleared the aggregate retry signal for another subscription's failure.
   Twitch4J 1.27.0 can also discard failed subscriptions when `willRetry` is false,
   so reconnect alone does not restore the desired set. **Resolved:** each kind
   retains its own health and failure sets a sticky rebuild request. The owner
   tears down the scoped client and rebuilds the complete desired set; grant
   identity includes token rotation. A partial-failure/unrelated-success test
   verifies the request survives; resource/re-registration wiring was inspected.
5. **Wrong exception type for Helix 401 recovery.** The pinned Twitch4J
   `TwitchHelixErrorDecoder` maps HTTP 401 to its `UnauthorizedException`, which
   extends `ContextedRuntimeException`, commonly wrapped by Hystrix. Checking only
   `FeignException` never rejects the actual failed token. **Resolved:** the
   classifier recognizes the Twitch4J exception through the cause chain. The
   regression uses the real pinned error decoder and a Hystrix command wrapper.
6. **Duplicate webhook link events bypass health ownership.** Revocation called
   the shared health observer and separately published `TwitchLinkDown`. The latter
   could follow a concurrent recovery `TwitchLinkUp` and leave alert state down
   while health was connected. **Resolved:** revocation uses the shared health
   observer exclusively; health transitions/publication share an actor and the
   concurrent-health test verifies ordered recovery and failure deduplication.

Root's independently identified route/rotation/docs coverage, complete timestamp
dedup expiration and health publication ordering requirements are also addressed;
this report does not count them as new findings.

## Confirmed design choices

Explicit endpoint access metadata keeps callback verification independent from
management credentials and rejects an unclassified endpoint during assembly.
Basic username mismatch still computes the password verifier. Tokens are compared
via constant-time digest comparison. Password verification concurrency is bounded
and excess requests receive 503. The per-connection body counter runs before Tapir
buffers bodies, including chunked bodies; the cap is 65,536 bytes.

The two optional authentication inputs are compatible with the requested OpenAPI
OR shape: Tapir documents that multiple optional auth inputs become separate
required alternatives without an empty requirement. The real server's generated
schema now has its own assertion for all 16 protected operations and four public
operations, beyond this documentation inference.
[Tapir authentication mapping](https://github.com/softwaremill/tapir/blob/master/generated-doc/out/docs/openapi.md).

## Validation

Executed independently in `twitch-screen-review-security/twitch-screen-relay`:

```sh
./mill test.testOnly \
  twitchscreen.relay.config.ConfigSuite \
  twitchscreen.relay.http.ManagementAuthSuite \
  twitchscreen.relay.http.ManagementRoutesSuite \
  twitchscreen.relay.twitch.TwitchRecoverySuite \
  twitchscreen.relay.twitch.TwitchAuthSuite \
  twitchscreen.relay.twitch.EventSubWebhookSuite \
  twitchscreen.relay.twitch.ChannelStateTrackerSuite \
  twitchscreen.relay.twitch.WebhookDeduplicationSuite
```

Result: **74 tests passed, zero failed or ignored** (19 configuration, 7 auth,
4 actual routes, 6 recovery, 12 OAuth, 13 webhook, 11 tracker, 2 dedup).
Mill exited 0; the run emitted JDK warnings for Netty native access and Scala's
`sun.misc.Unsafe` usage, not compiler warnings. The local log is
`/tmp/judge-security-focused.log`; `git diff --check`
also passed. The route fixture calls production `Config.log` and asserts secret
absence from config responses, captured logs and activity exports after rotation.

After final source refinements, independently reran `ConfigSuite`,
`ManagementAuthSuite` and `ManagementRoutesSuite` at `dd76b36`: **30 tests passed**,
zero failed or ignored, Mill exited 0. This covers the explicit shared auth gate,
Basic username validation and callback reachability from the after-bind hook.
Local log: `/tmp/judge-security-final-auth.log`. The security worktree included the
root-owned `ApplicationLifetime` integration helper and its adapted older
`ApiSuite` fixture; those are integrated by the root, outside the security commits.

Live Twitch credentials and an external callback deployment are not available in
this review. Local adapter/HTTP tests and source inspection verify the recovery
orchestration but do not establish a live Twitch authorization grant, provider
subscription acceptance or public TLS/proxy reachability. Token maintenance may
run before listeners bind; ingestion/subscription registration starts only after
the HTTP listener is reachable, following the device listener bind.
