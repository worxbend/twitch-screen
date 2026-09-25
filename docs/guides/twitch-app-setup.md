# 🎮 Connect your Twitch channel

[Guidebook](../README.md) · [Relay setup](relay-setup.md) · [Configuration reference](../reference/relay-configuration.md)

Turn your desk display into a little witness to your stream's main-character moments. This guide registers your Twitch application, connects the **broadcaster's account**, and checks that the relay can receive real events.

Start with a working [simulated stream](first-simulated-stream.md). You need a Twitch account, a browser, and a relay host with internet access. No Twitch credentials belong on the ESP32; it only connects to your relay.

> [!NOTE]
> The default setup uses an outbound EventSub **WebSocket**. You do not need to publish a webhook or open a router port to Twitch. The browser still needs to reach the OAuth callback during consent.

## 1. Understand the three credentials 🔑

| Credential | What it unlocks | Where it belongs |
|---|---|---|
| Relay management token or Basic password | Your relay's management API | Your terminal, browser, or deployment secret store |
| Twitch application Client ID and Client Secret | The relay's connection to Twitch | Relay environment only |
| Broadcaster access and refresh tokens | The broadcaster's approved Twitch data | Created by consent; saved by the relay in its token file |

The relay implements Twitch's **authorization code grant**. You start consent on the relay, Twitch redirects your browser back with a code, and the relay exchanges that code for tokens. This is why the Twitch application must support a server-held client secret. Choose **Confidential** if the Developer Console asks for a client type; a Public client is limited to Twitch's device-code flow. [Twitch OAuth documentation](https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/)

## 2. Register the application 📝

1. Verify your Twitch account's email and enable two-factor authentication.
2. Open the [Twitch Developer Console](https://dev.twitch.tv/console/apps), sign in, and choose **Applications → Register Your Application**.
3. Give it a unique name, such as your own variation of `Desk Stream Buddy`.
4. Add this **OAuth Redirect URL**, including its complete path:

   ```text
   http://localhost:8080/api/v1/twitch/callback
   ```

5. Choose the category that best describes your integration. Select **Confidential** if prompted for client type.
6. Complete the verification and create the application.
7. Open **Manage**, copy the **Client ID**, and use **New Secret** to generate the **Client Secret**. Save the secret privately; generating another invalidates the old one.

These are Twitch's registration requirements; console labels can evolve. The [official registration guide](https://dev.twitch.tv/docs/authentication/register-app/) is the current authority.

**The redirect URL is the callback, not `/authorize`.** The scheme, hostname, port, and path you register must match `RELAY_TWITCH_REDIRECT_URL`. There is no trailing slash, query string, or fragment in this project's callback URL.

## 3. Make the browser-to-relay route work 🌐

For a relay running on the same computer as your browser, keep the localhost callback above.

For a headless Linux box or Raspberry Pi, open an SSH tunnel **on the computer running the browser**. Replace `your-user@relay-host` with your SSH destination:

```sh
ssh -N -L 8080:127.0.0.1:8080 your-user@relay-host
```

Keep that terminal open. Your browser's `http://localhost:8080` now reaches port 8080 on the relay host. The callback remains the same, and the ESP32 continues using the relay's LAN address on port 8099.

When local port 8080 is occupied, use a different local tunnel port, then update **both** the registered redirect and `RELAY_TWITCH_REDIRECT_URL` to match. For example, a local tunnel on 18080 needs `http://localhost:18080/api/v1/twitch/callback` even when the relay itself still listens on 8080.

For a hosted relay, use an HTTPS reverse proxy and register `https://your-relay.example/api/v1/twitch/callback`. The relay validates HTTPS callbacks or HTTP loopback callbacks only. A plain `http://192.168.…` callback fails its startup validation. See [network boundaries](relay-setup.md#network-boundaries-and-https-).

## 4. Configure live mode ⚙️

Run these commands from `twitch-screen-relay/`. The placeholders below must be replaced before starting. Use your **channel login**, such as the `yourchannel` in `twitch.tv/yourchannel`; omit `https://`, `@`, and `#`.

```sh
export RELAY_TWITCH_MODE=live
export RELAY_TWITCH_CHANNEL='yourchannel'
export RELAY_TWITCH_CLIENT_ID='replace-with-your-client-id'
export RELAY_TWITCH_CLIENT_SECRET='replace-with-your-client-secret'
export RELAY_TWITCH_REDIRECT_URL='http://localhost:8080/api/v1/twitch/callback'
export RELAY_TWITCH_EVENTSUB_TRANSPORT=websocket
```

Use a private environment file or secret manager for ongoing operation; avoid putting a real secret in shared shell history. [Relay setup](relay-setup.md) explains local environment files and the Docker configuration.

The relay resolves the numeric **broadcaster ID** from the configured login through Twitch's Get Users API. There is no `RELAY_TWITCH_BROADCASTER_ID` setting and no manual account-ID lookup step.

Keep the default scopes:

| Requested scope | How this relay uses it |
|---|---|
| `moderator:read:followers` | Follow EventSub subscription and follower totals |
| `channel:read:subscriptions` | Subscriber totals |

These permissions are described in Twitch's [scope reference](https://dev.twitch.tv/docs/authentication/scopes/). The follow subscription uses the broadcaster ID as both broadcaster and moderator; the broadcaster moderates their own channel. [Follow subscription requirements](https://dev.twitch.tv/docs/eventsub/eventsub-subscription-types/#channel-follow)

The source reads chat anonymously. Subs, gifts, Bits, and raids arrive through that IRC connection; it does **not** subscribe to the corresponding EventSub event types. Adding `bits:read` or chat scopes does not switch transports or create new features.

## 5. Start the relay and grant consent ✅

Management authentication is required before startup. For convenient browser login, generate a Basic password verifier:

```sh
export RELAY_HTTP_AUTH_BASIC_USERNAME='admin'
export RELAY_HTTP_AUTH_BASIC_PASSWORD_HASH="$(python3 tools/hash_management_password.py)"
./mill run
```

The helper prompts twice without echoing the password and requires at least 12 characters. The relay stores a salted verifier, not that password. You can keep a management Bearer token configured too; either method is sufficient.

Open `http://localhost:8080/api/v1/twitch/authorize` directly in the browser address bar. When prompted by the browser, use your **relay Basic credentials**. Then sign in on Twitch as the **same broadcaster named in `RELAY_TWITCH_CHANNEL`** and approve the requested permissions.

The successful callback page says **Twitch connected** and identifies the authorized login. The token is saved automatically. No manual token copy and no firmware rebuild are involved.

> [!TIP]
> Already using Bearer authentication? Request the redirect without following it:
>
> ```sh
> curl --silent --show-error --dump-header - --output /dev/null \
>   -H "Authorization: Bearer $RELAY_HTTP_AUTH_API_TOKEN" \
>   http://localhost:8080/api/v1/twitch/authorize
> ```
>
> Copy the `Location:` value into your browser. It includes a temporary OAuth state value. Keep it private and complete consent within ten minutes. Do not add your relay token to the browser URL.

The relay retains up to 16 pending consent requests. Each state is single-use and expires after ten minutes; restarting the relay also discards pending states. Start a fresh request when the callback reports an unknown or expired request.

## 6. Check the account and event pipeline 🔍

In a second terminal, use Basic authentication; `curl` prompts for the management password:

```sh
curl --fail --silent --show-error --user admin \
  http://localhost:8080/api/v1/twitch/authorization
```

Check that `authorized` is `true`, `login` matches your configured channel, and `missingScopes` is empty. `userId` is the Twitch ID of the consenting account. This endpoint never returns the access or refresh token. `authorized: true` means a token is held; the following readiness check is needed to assess whether the integration is currently usable.

```sh
curl --fail --silent --show-error --user admin \
  http://localhost:8080/api/v1/status
curl --fail --silent --show-error \
  http://localhost:8080/api/v1/stats
```

EventSub notices a new grant on its next maintenance pass, approximately every 30 seconds. Scoped totals update on the configured Helix polling interval, 30 seconds by default. Check `twitch.health` and `twitch.detail` in status; a running HTTP server alone does not establish Twitch readiness.

What to expect:

| Source | Data | Useful observation |
|---|---|---|
| Helix polling | Stream state, viewers, followers, subscribers | Aggregate stats update after a successful poll |
| EventSub | Follows, stream online/offline, channel title/category updates | Activity records reflect incoming events |
| Anonymous IRC | Chat, subscriptions, gifted subs, Bits, raids | IRC must be connected for these notifications |

Chat cards are hidden by default, while chat still contributes to counters. Set `RELAY_CHAT_NOTIFICATIONS=show` and restart to display them. A single harmless manual notification can confirm the relay-to-device path without generating real Twitch activity; see the [HTTP API example](../reference/http-api.md#send-a-card-).

## 7. Keep consent across restarts 💾

The default token file is `data/twitch-token.json`, relative to the relay's working directory. Native runs from `twitch-screen-relay/` keep it beneath that directory. Docker uses `/home/relay/data/twitch-token.json`; the supplied Compose file persists `/home/relay/data` in a named volume.

The token file contains credentials in plaintext JSON. On POSIX filesystems, the relay creates token files with owner-only permissions and replaces them atomically. On other filesystems, set appropriate OS access controls yourself. Keep the directory private, include it only in protected backups, and do not commit it. The relay can keep a grant in memory after a persistence failure, so check logs when consent unexpectedly disappears after a restart.

Maintenance runs every minute, refreshing near expiry and validating at least hourly while the token is not in its refresh window. Twitch requires ongoing validation. [Token validation requirements](https://dev.twitch.tv/docs/authentication/validate-tokens/)

To disconnect the account intentionally:

```sh
curl --silent --show-error --user admin --request DELETE \
  --write-out '\nHTTP %{http_code}\n' \
  http://localhost:8080/api/v1/twitch/authorization
```

HTTP `204` means the relay cleared the in-memory grant and attempted saved-file deletion and revocation at Twitch; `404` means none was held. File-deletion and provider failures are logged; confirm that the saved token file is gone before relying on sign-out across a restart. Reauthorize after changing the broadcaster, application credentials, or requested scopes. Editing the scopes setting cannot add permissions to an existing grant.

## Optional: use EventSub webhooks 📬

Webhooks need a public HTTPS callback on port 443. Twitch must reach it without a browser login challenge. [Twitch webhook requirements](https://dev.twitch.tv/docs/eventsub/handling-webhook-events/)

Configure these settings alongside the live configuration:

```sh
export RELAY_TWITCH_EVENTSUB_TRANSPORT=webhook
export RELAY_TWITCH_EVENTSUB_CALLBACK_URL='https://your-relay.example/api/v1/twitch/eventsub'
export RELAY_TWITCH_EVENTSUB_SECRET="$(openssl rand -hex 32)"
```

Persist the generated secret privately. It must contain 10–100 ASCII characters. The OAuth callback `/api/v1/twitch/callback` and EventSub callback `/api/v1/twitch/eventsub` are separate endpoints with separate purposes.

Keep the raw webhook body and Twitch headers intact through your proxy. The relay verifies HMAC signatures, timestamps, and delivery IDs. Do not put proxy Basic authentication in front of these exact callbacks; management routes still require the relay's authentication. The relay creates and reconciles its own subscriptions; you do not manually register them in the Developer Console. Twitch uses app tokens for webhook subscription management and user tokens for WebSocket subscriptions. [Subscription management](https://dev.twitch.tv/docs/eventsub/manage-subscriptions/)

Changing an EventSub signing secret requires coordinating the existing provider subscriptions too: the current reconciliation finds an enabled subscription by condition and callback and does not compare its hidden signing secret. A fresh callback hostname with the same required path lets the relay create new subscriptions; retire the old subscriptions in your Twitch administration workflow. Simply changing the local secret can make existing deliveries fail HMAC verification.

Webhooks do not replace the IRC connection for subscription, gift, Bits, or raid notifications. When IRC is unavailable, those event types remain unavailable.

## When Twitch says “not today” 🛠️

| Symptom | Check |
|---|---|
| `/authorize` returns `401` | Supply relay management credentials; Twitch credentials do not authenticate this API |
| Twitch rejects the redirect | Compare the registered URL and relay URL character for character |
| Callback cannot connect on a headless host | Keep the SSH tunnel running on the browser's computer |
| Callback state expired | Start at `/authorize` again; do not reuse an old callback URL |
| Wrong account was authorized | Revoke, sign in as the configured broadcaster, and consent again |
| Missing follower/subscriber totals | Inspect `missingScopes`, authorized login, readiness detail, and provider errors |
| `/twitch/authorization` is `404` | These endpoints exist only in `live` mode |
| Follows work but subs/raids do not | Check the anonymous IRC connection; those events are not EventSub subscriptions here |
| Healthy container, disconnected Twitch | Health checks cover HTTP liveness; inspect authenticated `/status` |

The real-account path still needs operator acceptance with actual Twitch credentials and sustained reconnect testing. Source-level and simulated checks cannot prove provider acceptance. Continue with [troubleshooting](troubleshooting.md), [relay operations](relay-setup.md), or [firmware setup](firmware-setup.md).

Source trail: [OAuth implementation](../../twitch-screen-relay/src/twitchscreen/relay/twitch/TwitchAuth.scala), [live source](../../twitch-screen-relay/src/twitchscreen/relay/twitch/LiveTwitchSource.scala), [event routing](../../twitch-screen-relay/src/twitchscreen/relay/twitch/TwitchEventHandlers.scala), and [configuration validation](../../twitch-screen-relay/src/twitchscreen/relay/config/Config.scala). Official Twitch guidance checked on 2026-09-25.
