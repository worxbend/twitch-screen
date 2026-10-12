# ADR-0002: Make the TSB/3 deployment trust boundary explicit

## Status

Accepted for the Kimi review remediation. Reaffirmed by the owner on
2026-09-25: no device-link authentication (API key/HMAC) or TLS will be added;
the trusted-home-LAN boundary is the deployment requirement.

## Date

2026-09-25

## Context

TSB/3 sends display events over plaintext TCP and identifies a device by its
self-declared name. Review finding H2 correctly identifies impersonation and
replacement attacks. Adding a token to HELLO would change its frozen layout and
would not authenticate a rogue relay or hide credentials on the LAN.

## Decision

Use H2's explicit trusted-home-LAN option for the existing protocol. Document the
trust boundary and impersonation consequences in [PROTOCOL §2](../../twitch-screen-firmware/docs/PROTOCOL.md#2-transport-and-endianness).
Operators must restrict both listeners to a segment whose hosts they trust. The
2026 desk-update decision removes HTTP management authentication: every reachable
peer can read and control the relay. Exact Host/Origin/Fetch-Metadata checks are
independent browser defenses, not authentication. Provider OAuth/callback and
webhook verification remain mandatory. Native/Compose loopback is the default;
LAN publishing requires explicit settings and firewall/VPN isolation. Trusted
private HTTPS proxy/mobile consent is planned, not yet verified. Do not expose
management or plain device TCP to the internet.

Retain the protocol's uncapped `retry_after_s` minimum, including its maximum
65535-second suppression, as a trusted-relay instruction. Bound local queue
backpressure separately so a lost relay does not leave a device apparently
online indefinitely. Document that firmware credentials in unencrypted flash
also assume trusted physical access.

## Alternatives considered

Pinned-certificate TLS could authenticate the relay and protect traffic. It
requires provisioning, certificate rotation and device recovery design, plus
physical validation. A pre-shared HELLO token alone protects neither secrecy nor
relay identity and requires a versioned wire migration. These remain options for
deployments outside the documented trust boundary.

## Consequences

Existing devices retain byte-compatible TSB/3. This change does not add network
authentication, encrypted flash, secure boot or OTA. A malicious reachable LAN
host can still impersonate a peer. Such networks need an authenticated encrypted
transport before this deployment assumption is valid.
