package twitchscreen.relay.device

import org.slf4j.LoggerFactory
import ox.{Ox, discard}
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.NotificationsConfig
import twitchscreen.relay.protocol.*

/** The policy layer between "something happened on Twitch" and "the screen lights up".
  *
  * Only audience and stream-lifecycle events become events on the wire. Channel telemetry (viewer counts, follower totals) belongs on the
  * idle dashboard instead, and relay-internal events — including the notifications this router itself publishes — are deliberately not
  * mapped, so there is no path back into the hub.
  *
  * **The person goes in `actor` and what they said goes in `text`** (§6.4.1), which is the opposite of what v2 did. v2 put a constant
  * headline ("New subscriber", "Raid incoming") in the title slot and the person's name in small grey body text, and flattened every number
  * into that sentence — so the raider's viewer count, the bits amount, the sub tier and the month count existed only as English and never
  * reached the wire at all. Vectors V7, V10 and V11 pin the correct shape: `actor = "newfriend"`, `actor = "streamfriend"` with
  * `value = 128`, `actor = "bitsfan"` with `value = 1500` and `text = "take my bits"`.
  *
  * Bot filtering is **not** here. §13.1 puts it at the source, in the Twitch handlers, so that a bot costs no bus capacity, no sequence
  * number and no replay slot — and so that `STATS.msg_total` and `STATS.chat_rate`, which fold from the same bus, exclude it too. A filter
  * at this point would leave both of those numbers inflated, and they are what the idle screen shows.
  */
private[relay] object NotificationRouter:
  private val logger = LoggerFactory.getLogger(getClass)

  def start(config: NotificationsConfig, bus: EventBus, hub: DeviceHub)(using Ox): Unit =
    logger.info(s"Routing Twitch events to devices (chat ${config.chat}); display time is per kind, per §6.4.1")
    bus.consume("device-notifications"): message =>
      message.event match
        // The statistics fold owns these cards so EVENT and its resulting STATS share one actor operation.
        case _: (RelayEvent.StreamStarted | RelayEvent.StreamEnded) => ()
        case event                                                  => toRequest(event).foreach(hub.publish(_).discard)

  /** Chat is mapped unconditionally, `notifications.chat` notwithstanding. §10.3 requires every `EVENT` to be sequenced and replayable,
    * including chat: a kind skipped by the sequence space lets a later event hold the high-water mark past a lost durable one, which
    * silently defeats replay exactly when it matters. The relay's chat policy is ANDed with the device's `CAP_CHAT` where §6.1 puts it — at
    * the moment of sending, in [[DeviceHub]] — not by refusing to create the event.
    */
  private[relay] def toRequest(event: RelayEvent): Option[EventRequest] =
    event match
      case RelayEvent.Followed(user) =>
        // §6.4.1 fixes FOLLOW.value at 0: a follower total of 0 would be indistinguishable from "not reported",
        // so totals live in STATS.
        Some(EventRequest.of(NotificationKind.Follow, actor = user, text = ""))

      case RelayEvent.Subscribed(user, tier, months, message) =>
        Some(
          EventRequest.of(
            NotificationKind.Sub,
            actor = user,
            text = message,
            months = SubMonths.clamp(months),
            tier = tier
          )
        )

      case RelayEvent.SubscriptionGifted(user, count, tier, anonymous) =>
        Some(
          EventRequest.of(
            NotificationKind.Gift,
            actor = user,
            text = "",
            value = EventValue.clamp(count.toLong),
            tier = tier,
            flags = if anonymous then EventFlags.Anonymous else EventFlags.Empty
          )
        )

      case RelayEvent.Raided(from, viewers) =>
        Some(EventRequest.of(NotificationKind.Raid, actor = from, text = "", value = EventValue.clamp(viewers.toLong)))

      case RelayEvent.BitsCheered(user, bits, message) =>
        // Bits are a plain count. There is no money on this wire, in either direction, at any point (§8).
        Some(EventRequest.of(NotificationKind.Bits, actor = user, text = message, value = EventValue.clamp(bits.toLong)))

      case RelayEvent.ChatMessaged(user, text, colour) =>
        // CHAT.value is only meaningful when CHAT_COLOUR_PRESENT is set, because black is a legal colour and would
        // otherwise be indistinguishable from "this chatter never picked one".
        Some(
          EventRequest.of(
            NotificationKind.Chat,
            actor = user,
            text = text,
            value = EventValue.clamp(colour.fold(0L)(_.rgb.toLong)),
            flags = colour.fold(EventFlags.Empty)(_ => EventFlags.ChatColourPresent)
          )
        )

      case RelayEvent.StreamStarted(channel, title, _, startedAt) =>
        Some(
          EventRequest.of(
            NotificationKind.StreamStart,
            actor = channel,
            text = title,
            value = EventValue.clamp(startedAt.fold(0L)(_.getEpochSecond))
          )
        )

      case RelayEvent.StreamEnded(channel, duration) =>
        Some(EventRequest.of(NotificationKind.StreamEnd, actor = channel, text = "", value = EventValue.clamp(duration.toSeconds)))

      // The two relay-internal cards. §6.4.3's generic kinds: a title and a body, every numeric field 0.
      case RelayEvent.TwitchLinkDown(reason) =>
        Some(EventRequest.of(NotificationKind.Warning, actor = "Twitch link lost", text = reason))

      case RelayEvent.RelayFailure(source, message) =>
        Some(EventRequest.of(NotificationKind.Alert, actor = s"$source failed", text = message))

      case _ => None
