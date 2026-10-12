# Private HTTPS termination (Linux)

This optional Caddy example terminates HTTPS only on an explicitly selected trusted LAN/VPN interface. It is not public hosting or application authentication. Anyone with network access can read and control the relay. Restrict the interface with your firewall; never port-forward either service or expose management through a public proxy.

Caddy 2.11.7-alpine is pinned to the official multi-platform index digest obtained through `docker buildx imagetools inspect caddy:2`. Access logging is disabled. Do not enable body/header logging containing OAuth codes or private cards.

## Docker Compose deployment

Use Docker Compose 2.24.4 or newer (`!override` support). Run from `twitch-screen-relay/`, not this directory. The HTTPS file is an overlay for the main Compose service, not a standalone deployment.

Choose a private hostname resolving to the selected host interface on every intended client. Replace the example hostname and interface. Use an unused private Docker subnet; inspect your network/VPN routes and Docker networks first. The defaults are `172.30.80.0/29`, Caddy `172.30.80.2`, relay `172.30.80.3`. If they conflict, change all three together using `RELAY_PROXY_SUBNET`, `RELAY_PROXY_IP` and `RELAY_UPSTREAM_IP`. Never trust an entire subnet.

Set these values in your private interpolation configuration. The example uses standard HTTPS port 443:

    RELAY_PRIVATE_HOST=desk.home.arpa
    CADDY_BIND_IP=192.168.1.10
    RELAY_HTTP_ALLOWED_HOSTS=localhost:8080,127.0.0.1:8080,desk.home.arpa
    RELAY_HTTP_ALLOWED_ORIGINS=http://localhost:8080,http://127.0.0.1:8080,https://desk.home.arpa
    RELAY_HTTP_PUBLIC_ORIGIN=https://desk.home.arpa
    RELAY_TWITCH_REDIRECT_URL=https://desk.home.arpa/api/v1/twitch/callback

The overlay assigns the exact Caddy container IP to `RELAY_HTTP_TRUSTED_PROXIES`. Do not set it to `127.0.0.1`: Docker loopback publishing does not preserve loopback as the relay's immediate peer. Caddy connects directly to `relay:8080` on the dedicated project network. The overlay always publishes upstream HTTP on host loopback, even when `RELAY_BIND_IP` selects a private device-link interface. Only Caddy is trusted to supply forwarded headers; other network containers and host-originated spoofed forwarding fail closed. Docker/root administrators remain inside the deployment trust boundary.

Validate and start with the guarded entry point. Replace `PRIVATE_ENV_FILE` with your private absolute env-file path; never print expanded configuration:

    python3 tools/compose.py --env-file "$PRIVATE_ENV_FILE" -f compose.yaml -f deploy/private-https/compose.yaml run --rm --no-deps private-https caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
    python3 tools/compose.py --env-file "$PRIVATE_ENV_FILE" -f compose.yaml -f deploy/private-https/compose.yaml up -d --build relay private-https

The guard rejects obsolete management-auth keys before deployment. Keep existing relay token and Caddy CA volumes. Changing the Compose project name selects different volumes; do not accidentally replace an established deployment's project name. The device interface is separate and must remain private. If changing `CADDY_HTTPS_PORT`, include that port in hostname/public-origin/allowed-origin/callback values; a port change alone is insufficient.

Caddy strips client-supplied ambiguous forwarding fields, preserves external Host, replaces scheme/client headers with its own observations, and flushes streaming responses immediately. Direct relay forwarding chains, untrusted hops, rewritten Host and hostile Origins fail before route/body handling. Provider callbacks retain Host and provider state/signature checks.

## Native relay alternative

For a native relay and native Caddy on the same Linux host, bind relay HTTP to loopback and set `RELAY_HTTP_TRUSTED_PROXIES=127.0.0.1`. Use the same allowed-host/origin/public-origin/callback values above. Set `RELAY_PROXY_UPSTREAM=127.0.0.1:8080`, `RELAY_PRIVATE_HOST` and `CADDY_BIND_IP` for the native Caddy process, then validate the supplied Caddyfile. Local host processes share this exact-hop trust boundary. Do not apply this loopback trust value to a bridged relay container.

## Trust and consent

Caddy creates a private CA in its named data volume. Copy only the public root certificate (`/data/caddy/pki/authorities/local/root.crt`) from your deployment-owned container, verify its fingerprint through a trusted channel, and explicitly trust it on consenting clients. Never distribute `root.key`, erase the CA volume during normal restart, or disable certificate verification. This configuration disables automatic host trust installation.

Register the exact HTTPS callback in your Twitch application. Open consent at the configured HTTPS origin. Hostname configuration does not establish DNS, client CA trust, callback registration or a usable grant. Without trusted private HTTPS, remote mobile consent remains unavailable; use desktop-local or SSH-tunneled loopback consent.

## Evidence boundary

`python3 tools/smoke_private_https.py` runs a separate credential-free Compose project with ephemeral loopback ports, the pinned Caddy proxy and current relay image. It uses only a test-owned CA certificate, never installs host trust, checks HTTPS health/docs/management and hostile requests, and removes only its own containers/network. It preserves named token/CA volumes. Native socket tests remain separate from container-topology evidence.

Real browser-trusted mobile/tablet consent, CA installation on another device and production SSE/panel acceptance remain pending. No configuration or synthetic HTTPS smoke proves those gates. Stop only deployment-owned services with the same guarded Compose files and `stop private-https`; never use `down -v`.
