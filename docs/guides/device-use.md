# 💜 Meet your tiny stream sidekick

[← Guidebook](../README.md) · [Firmware setup](firmware-setup.md) · [Troubleshooting](troubleshooting.md)

Once configured, the device is an automatic display: plug in USB power, keep the relay running and let it do its thing. No app on your phone, no daily pairing ritual. Just a tiny circle doing the most. ✨

The relay computer must stay awake and connected to the network. The ESP32 uses Wi-Fi to reach it; the USB cable supplies power and supports firmware updates/serial diagnostics. Twitch account authorization lives on the relay.

## Understand the three screen states

| Screen | What it means | Your next step |
| --- | --- | --- |
| **CONNECTING…** with purple spinner | The device has not completed its connection to the relay | Wait through a normal restart; investigate Wi-Fi, the relay or its LAN address if it persists |
| **OFFLINE** / “waiting to go live” | The device is connected and the relay's current stream state is offline | Go live or use simulated mode; check relay status if the channel is already live |
| **LIVE** with viewer count | The relay reports a live stream | Enjoy the dashboard; event cards appear automatically |

**OFFLINE is a stream state, not a network error.** A connected display does not by itself prove every Twitch integration is healthy. When data is missing, check the relay's status as described below.

Notification cards appear above the dashboard. Already queued cards can continue to show during a connection interruption, so the connecting screen can be temporarily covered by a card.

## Read the live dashboard

| Element | Meaning |
| --- | --- |
| Red LIVE pill | Relay reports the stream is live |
| `h:mm:ss` timer | Stream uptime; ticks locally every second and is corrected by incoming telemetry |
| Large VIEWERS number | Most recent viewer count delivered by the relay |
| `FLW` chip | Most recently observed total follower count |
| `MSG` chip | Chat messages observed by this relay since the most recent stream-start reset, after configured bot filtering |
| `SUB` chip | Most recently observed total subscriber count |
| Purple edge ring | Chat rate in messages per minute, visually capped at 100 |

The ring is an activity gauge; a full circle means 100 or more messages per minute. It is not a percentage of a follower goal.

Counts use compact `K`, `M` and `B` suffixes to fit the round screen. These are abbreviated display values, not changes to the underlying metrics. For example, 1,234 displays as `1.2K`.

Telemetry is normally broadcast every five seconds. Viewer/follower/subscriber observations can update less often because they depend on upstream polling and permissions. The uptime label still ticks between broadcasts.

On the offline screen, the `FLW`, `MSG` and `SUB` row remains visible. Stream end does not reset the message total; the next stream-start event does. The relay's counters are in memory, so restarting the relay can reset them, and starting it midway through a stream does not backfill earlier chat.

## Meet the notification cards

| Card | Accent | What it carries |
| --- | --- | --- |
| STREAM LIVE | Green | Channel going live |
| STREAM ENDED | Slate | Channel ending, with duration when supplied |
| FOLLOW | Purple | Follower name |
| SUB | Gold | Subscriber, tier and subscription months when available |
| GIFT | Pink | Gifter, quantity and tier when available |
| RAID | Red | Raider and audience size |
| CHAT | Cyan | Chatter and message; message text gets extra space |
| BITS | Light purple | Chatter and Bits amount |
| INFO / MESSAGE / WARNING / ALERT | Kind-specific | Custom notifications published through the relay |

The screen can render these cards when the relay supplies them. Which real Twitch events arrive depends on the relay mode, integrations and account permissions. Cash donations are not a built-in event kind; a third-party integration would need to create a supported generic notification.

Fresh events begin with a full-screen attention flash, then slide in. The relay supplies a hold of **6 seconds for follows and chat**, **8 seconds for subs, gifts and Bits**, and **10 seconds for raids and stream transitions**, plus entrance/exit animation. Manual HTTP cards default to eight seconds unless their request or relay configuration supplies another duration. Chat cards are hidden by the relay's default policy; their messages still count toward chat statistics.

For protocol senders that leave the duration unspecified, the firmware's fallback holds are 2.5 seconds for chat, 5 seconds for stream start/end and 3.5 seconds for other cards. These fallbacks are not the normal durations of cards from this relay.

Replayed cards skip the attention flash but still slide in. The small `#number` is the relay sequence number; it helps diagnose ordering, not viewer count.

Long titles and message bodies end with an ellipsis when they exceed the available space. The current firmware advertises printable-ASCII text support: the relay folds unsupported Unicode instead of promising full emoji or multilingual font coverage. Dark chat-name colors fall back to white for readability.

## Controls, power and settings

This build has **no touch input, navigation menu, notification-dismiss button, brightness control or on-device Wi-Fi setup**. The non-touch LCD and firmware operate as an always-on display.

The board's service buttons are:

- **EN/reset:** restart the ESP32.
- **BOOT:** used with reset/upload to enter the serial bootloader; it is not a screen control.

In the enclosure, remove the base to reach them. For ordinary use, connect or disconnect USB power. Give the relay computer a reliable network connection and keep it awake while streaming.

To change Wi-Fi, relay address or a custom device name, edit the private firmware configuration and [reflash over USB](firmware-setup.md). To change Twitch channel, grants or bot filtering, follow [relay setup](relay-setup.md) and [Twitch app setup](twitch-app-setup.md).

## What happens when the connection drops?

The firmware keeps trying automatically. Its retry delay grows from roughly one second to a 30-second base delay, with jitter; a relay-requested retry floor can extend that. A stable connection eventually resets the retry ramp.

Reconnection can replay retained notifications when the device still holds a usable sequence position. The default relay retention is **64 non-chat events plus 16 chat events**. This is bounded, in-memory recovery, not a durable notification archive.

Practical limits:

- Turning the device off or resetting it clears its replay position. A fresh boot starts from the relay's current sequence instead of requesting old cards.
- Restarting the relay loses its in-memory replay history and can reset its sequence space.
- A long or busy outage can exceed the retained history.
- A queued event is acknowledged before it has necessarily finished displaying.
- Retained replay has no age cutoff, so a recovered card can refer to an earlier stream.

The firmware queue holds eight waiting notifications and pauses incoming event consumption when full. A burst therefore takes time to display. This device is for awareness on your desk; Twitch's own dashboard remains the place to inspect a complete event history.

## Check the relay from your computer

From a shell with the same management token used to start the relay:

```sh
curl --fail-with-body http://127.0.0.1:8080/api/v1/status \
  -H "Authorization: Bearer $RELAY_HTTP_AUTH_API_TOKEN"
curl --fail-with-body http://127.0.0.1:8080/api/v1/devices \
  -H "Authorization: Bearer $RELAY_HTTP_AUTH_API_TOKEN"
```

The first request reports integration and relay readiness; the second lists connected device sessions and counters. An empty device list means the display has not registered a current connection. Basic-auth installations can use the Basic credentials described in [relay setup](relay-setup.md).

The public `/api/v1/health` endpoint checks that the relay process serves HTTP. It intentionally does not prove Twitch is connected. The built-in API documentation is at `http://127.0.0.1:8080/docs`.

## Your first-stream checklist

- [ ] The relay runs on a computer that will remain awake.
- [ ] The screen reaches OFFLINE or LIVE after power-up.
- [ ] A simulated or manual notification has displayed successfully.
- [ ] The correct Twitch channel is configured and the required authorization is granted.
- [ ] Relay status has no unexplained degraded integration.
- [ ] The real stream produces the expected LIVE state and updates.

For setup from scratch, start with [your first simulated stream](first-simulated-stream.md). For a screen that gets stuck, continue to [troubleshooting](troubleshooting.md).
