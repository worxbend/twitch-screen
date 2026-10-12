# Runtime monitoring API

The production relay mounts `GET` and `PATCH /api/v1/twitch/monitoring` through the
same private Host/Origin boundary as display and producer APIs. It needs no
management password. Anyone with network access can change monitoring; use a
trusted LAN/VPN and firewall, never public wildcard proxying.

## Desired state and source application

PATCH accepts `mode` (`disabled`, `simulated`, `live`) and/or a Twitch login in
`channel`. It rejects URLs, empty/null fields, invalid modes, unknown fields and
duplicate keys. Logins normalize ASCII case to lowercase. Omitted fields preserve
the current state in the same sixteen-command storage actor used by display PATCH.
Changing monitoring never changes display preference, override, OAuth grant,
weather/CPU slots, notification sequence or listeners.

Example from a trusted local terminal:

```sh
curl --fail --silent --show-error -X PATCH \
  -H 'Content-Type: application/json' \
  --data '{"mode":"simulated","channel":"demo"}' \
  http://localhost:8080/api/v1/twitch/monitoring
```

For a new live preference the relay validates application/OAuth/transport
configuration and resolves the login before writing storage. Application-token
acquisition plus Helix lookup share one three-second budget, with disabled redirects
and at most 64 KiB per response. Resolution runs outside the shared storage actor.
One lookup may be active; excess lookup requests fail immediately with 503
`channel_lookup_busy`, without queuing provider work. Display updates, monitoring
reads and disable requests do not wait for that lookup. Before storage, the actor
revalidates the prepared identity against the exact merged live intent; a changed
implicit channel returns 503 `monitoring_superseded` without mutation.

Settings writes and monitoring reads have a five-second application budget,
including queue residence. Expired or abandoned queued requests cannot enter
storage later. A 503 `application_timeout` after storage admission is ambiguous:
read canonical state before retrying an idempotent update. Startup identity lookup
also runs outside storage and cannot overwrite a superseding preference.

A missing application setup returns 409
`configuration_missing`. A lookup failure returns 503 with a safe code, without
changing the old preference, source or disk. A missing/mismatched broadcaster grant
does not prohibit public monitoring.

After confirmed atomic persistence, the hub installs the new source-generation and
Twitch-episode fence and invalidates old source statistics. Only then does the
controller enqueue source application. The response does not wait for teardown,
provider connection or a device. Old scopes must finish cancellation/cleanup before
a replacement is created. Teardown is bounded at five seconds; construction at
fifteen seconds. Failures remain visible and recovery uses the existing bounded
one-to-thirty-second backoff. New desired state supersedes older application work.
A reliable activation callback carries its original fence, not a newly read fence.
Recovery during the storage-to-request handoff rejects an obsolete configuration
rather than assigning it the newly committed generation.

GET returns desired/effective mode/channel, settingsSource, epoch, monitoring
revision/sourceGeneration, transition, resolved identity, grant compatibility and
safe error. The revision is the authoritative monitoring generation within its
epoch; display-only writes do not increment it. Ready means source scope admission
and (for live) resolved identity, not healthy provider measurements or visible
pixels. Provider health remains in status. During replacement or failure effective
mode is disabled with null channel; saved desired intent is never silently reverted.
Simulated mode with no configured login reports effective `demo` without storing a
fake desired identity.

An equivalent resolved PATCH performs no disk write or source rebuild. A saved live
preference receives one finite startup identity lookup. If that lookup fails, GET
reports failure/unresolved identity; an explicit equivalent PATCH can retry resolution
without rebuilding a successfully admitted source. There is no monitoring health
polling loop. Storage uncertainty remains a failure requiring canonical/disk
reconciliation, not proof that an attempted durable mutation did not happen.

## Grant compatibility

Diagnostics compare the resolved numeric broadcaster ID with the actual usable
stored grant ID, not channel text. Compatibility is not_required, missing, expired,
mismatched, partial or usable, with up to five reason-coded feature observations
(public_stream, public_chat, followers, subscribers, eventsub) and missing scopes.
No access/refresh token or client secret is returned. Wrong-user grants remain
stored/refreshed and cannot enable broadcaster-scoped capabilities. Actual scoped
Helix token selection and both EventSub subscription plans enforce the resolved
numeric broadcaster ID and requested scopes from the current usable grant.
Feature eligibility is not evidence of a current provider connection or delivered event.

## Verification limits

`MonitoringApiSuite` exercises strict mounted JSON, durable partial merges,
no-ops, concurrent display/monitoring writes, failed lookup nonmutation, missing and
wrong grants, asynchronous blocked-cleanup supersession, startup resolution and
the commit-to-request race. `ChannelResolverSuite` uses real isolated HTTP exchanges
to check resolution, bounded responses and the shared deadline. These are software
fixtures, not actual Twitch account acceptance.

Relay now negotiates complete DESK sessions by default for a valid capability/tail.
Actual final assembled Main and the isolated documented Compose path pass mandatory
native application synchronization, queue-pressure and capable reconnect pairing.
Full old/new peer-matrix acceptance and firmware default advertisement remain pending.
Classic peers retain their existing fallback.
The admin snapshot/feed/panel are not yet mounted. Actual provider, supported
mobile HTTPS consent, DESK device application and physical/live acceptance remain
separate required gates.
