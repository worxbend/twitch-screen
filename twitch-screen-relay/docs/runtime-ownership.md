# Runtime ownership and reset boundary

## Installed application-scoped OAuth

`Dependencies` creates one `TwitchOAuthOwner` outside ingestion. With valid
application/OAuth configuration, its authorization, authorize, callback and revoke
routes are mounted in disabled, simulated and live modes. Grant storage, refresh
and pending single-use consent belong to that application owner. Replacing a live
source must receive the same owner; it must not reload or delete the grant.

OAuth admission requires the configured application ID/secret, validated redirect,
scopes and token-file location. It does not require a monitored channel or EventSub
transport. Missing or invalid OAuth configuration leaves these routes unmounted,
without blocking independent notifications/display/telemetry. Selecting live
monitoring still validates its complete provider prerequisites. No grant authorizes
a different broadcaster merely because channel text changes; existing subscription
and token-provider checks retain that responsibility.

`TwitchOAuthOwnerSuite` exercises held consent/grant/refresh across cancellation of
a separate source-construction scope. Actual assembled HTTP tests exercise disabled
and simulated routes without following the consent redirect or contacting Twitch.
These are software tests, not real grant, live-provider or mobile consent acceptance.

Application-owned OAuth diagnostics use a separate reliable hub-admitted bus view.
Refresh and token-storage failures retain Twitch provenance. Each publication captures
current epoch, source generation, episode and display eligibility inside the hub actor;
the stable owner does not capture a replaceable source fence. Admission precedes optional
lossy activity/alert fan-out, and the source-owned notification router does not publish a
second card. Diagnostics do not create an observed Twitch measurement or refresh STATS.
Source callbacks retain their original fences and cannot use this application path.

`TwitchOAuthDiagnosticsSuite` exercises actual refresh and storage failures before and
after committed source-fence changes and explicit epoch reset in disabled/live intent
with both display states. The installed dependency graph is also exercised with a
synthetic OAuth provider, real HTTP routes and canonical history readback; it never
starts live ingestion. `ApplicationDiagnosticSuite` checks original provenance and
eligibility, observer overflow, stopped admission, old-source rejection and actual
classic TCP delivery. These tests do not install automatic source replacement or reset.

## Implemented explicit hub reset

`DeviceHub.resetEpoch(expectedEpoch, replacementEpoch)` performs the hub half of a
reliable discontinuity transaction. It compares the expected epoch, validates a
nonzero different replacement, closes every old transport and DESK owner, and rotates
clock/sequence authority. It clears waiting replay, latest CPU/weather, Twitch
statistics, rate buckets and reclaim state. It preserves exact confirmed settings
and rebases control/source/episode revisions within the new epoch. It performs no
disk write, provider request or socket-drain wait in the actor.

Connection IDs remain process-unique, so late old-session detach cannot remove a
replacement session. Old source callback fences and calibrated producer deadlines
are invalid in the new epoch. Each old DESK connection retains its original clock
object, never a mutable reference to a replacement domain. Already admitted device
cards remain firmware-owned with original identity and conservative deadline;
clearing relay replay does not relabel or cancel them.

`EpochResetSuite` covers old/invalid reset rejection, state invalidation, committed
intent preservation, old callback/deadline rejection, sequence-exhaustion recovery,
actual TCP closure and replacement delivery. The native application fixture performs
reset during hold, preserves one active and one waiting old-epoch card, and completes
both before a new-epoch sequence-1 card. Raw report validation checks exact fixture
credit conservation and telemetry invalidation. See [reproduction commands](desk-session-pairing.md).

## Installed bounded ingestion ownership

`RuntimeSource` is the stable `TwitchSource` supplied to the production HTTP/status
assembly. Each producer and its admitted statistics timer belongs to a dedicated
Ox structured scope. Replacement first removes current webhook dispatch, requests
cancellation, and waits at most five seconds for completion after child joins and
resource cleanup. A timeout retains the old owner and refuses to create another.
Source creation has a fifteen-second admission budget. OAuth is not recreated.

The EventSub endpoint is mounted once when the configured deployment transport is
webhook. Each request selects current source logic; missing ownership returns 503.
The selected source retains HMAC/freshness checks and its original admitted producer
fence. Broadcaster identity is resolved before callbacks become eligible. Supported
callbacks require the provider's accepted subscription ID and matching subscription
condition; notifications also require matching broadcaster ID/login in their payload.
Unknown or retired identities do not update current source state. A challenge racing
registration waits at most one second for the provider's accepted ID; it cannot register
itself from its signed body. Accepted message-ID deduplication belongs to the stable
application source owner and survives source recreation and clock-epoch recovery for
the complete bounded replay window. Old in-flight callbacks retain their original
producer fences. Ingestion starts once after listener readiness. The public monitoring
owner now commits hub authority before enqueueing replacement; this internal interface
alone is not proof of complete hot-switch/provider acceptance.

`RuntimeSourceSuite` exercises cleanup ordering, uncooperative teardown containment,
old callbacks, stable current dispatch and ingestion-start ordering. These are
software ownership tests, not provider or hardware acceptance.

## Installed scheduled coordinated recovery

`RuntimeRecovery` is started once after HTTP binding. Its detector compares
wall/monotonic deltas every second, with a discrepancy strictly over 2000 ms.
Detection immediately invalidates hub replay, telemetry, statistics and sessions,
rotates epoch/sequence space and retires current source dispatch/alerts. One bounded
pending signal hands recreation to a separate worker; slow cleanup cannot block
subsequent sampling or leave another corrected domain's telemetry valid.

`RuntimeSource` serializes cancellation and confirmed cleanup before recreating the
admitted producer and statistics timer. Its read/dispatch projection checks the
original source fence. A teardown timeout leaves effective ingestion unavailable
without creating a competing provider. Failed construction, application timeout and
unexpected source-scope exit retain recovery intent. One serialized worker retries
with exponential backoff from one to thirty seconds and at most 25 percent jitter;
retries do not rotate the epoch. Late completed cleanup permits a replacement on a
later attempt, while newer desired configuration or discontinuity supersedes obsolete
work. Producer authority is captured before construction, so a reset during creation
cannot relabel old callbacks as current. Desired settings, OAuth and HTTP/device
listeners are not replaced. Unknown source statistics stay unknown until a real
source observation; refresh ticks alone do not manufacture measured zeros.

`RuntimeAlertMonitor` initializes all configured rules independently of startup
mode. Reliable activation retires Twitch alerts and resets Twitch timers, while
preserving device/failure state. Optional source observations carry original epoch
and generation; queued old messages cannot restore retired state. Historical Twitch
alerts retain those labels. Application OAuth failures remain independent of a
replaceable source fence. Source-tagged failures must match both local monitor identity
and the authoritative hub fence, including the interval before replacement activation.
Previously accepted independent failures are retained.

The installed `RuntimeRecoverySuite` injects a wall correction and checks actual
HTTP invalidation, preserved intent, recreated simulated ingestion and listener
cleanup. `RuntimeSourceFailureSuite` proves detection remains independent of blocked
cleanup, isolates constructor failure and exercises actual signed stable webhook
requests across replacement. `NativeDeskReconnect automatic` injects a correction
during an actual native card hold, preserving old generic identities/lifecycles and
invalidating old telemetry. The native fixture still explicitly enables the internal
DeviceSession gate; it is not default production DESK or physical evidence.

Public monitoring resolution/desired-effective/grant APIs are mounted; see
[runtime monitoring](runtime-monitoring.md). Full live-provider application/recovery
and source/episode/blocked-writer acceptance remain pending.
Admin projection/feed/panel depend on those owners. Relay complete-profile negotiation
is enabled for valid capable HELLOs; final enabled-assembly pairing/Docker/Compose and
firmware default advertisement remain pending. Scheduled synthetic correction tests
do not establish actual system suspend, provider cleanup, physical pixels,
watchdog/resource budgets, supported desktop/service or product acceptance.
