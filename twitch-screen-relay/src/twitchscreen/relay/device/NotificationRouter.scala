package twitchscreen.relay.device

import org.slf4j.LoggerFactory
import ox.{Ox, discard}
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.{ChatNotifications, NotificationsConfig}
import twitchscreen.relay.protocol.{NotificationKind, NotificationRequest}

/** The policy layer between "something happened on Twitch" and "the screen lights up".
  *
  * Only audience events become notifications. Channel telemetry (viewer counts, follower totals) belongs on the idle dashboard instead, and
  * relay-internal events — including the notifications this router itself publishes — are deliberately not mapped, so there is no path back
  * into the hub.
  */
private[relay] object NotificationRouter:
  private val logger = LoggerFactory.getLogger(getClass)

  def start(config: NotificationsConfig, bus: EventBus, hub: DeviceHub)(using Ox): Unit =
    logger.info(s"Routing Twitch events to devices (ttl ${config.defaultTtl}, chat ${config.chat})")
    bus.consume("device-notifications"): message =>
      toRequest(message.event, config).foreach(hub.publish(_).discard)

  private[device] def toRequest(event: RelayEvent, config: NotificationsConfig): Option[NotificationRequest] =
    val ttl = config.defaultTtl
    event match
      case RelayEvent.Followed(user) =>
        Some(NotificationRequest(NotificationKind.Follow, "New follower", user, ttl))
      case RelayEvent.Subscribed(user, tier, months) =>
        Some(NotificationRequest(NotificationKind.Sub, "New subscriber", s"$user, tier $tier, $months months", ttl))
      case RelayEvent.SubscriptionGifted(user, count, tier) =>
        Some(NotificationRequest(NotificationKind.Gift, s"$count gifted subs", s"$user, tier $tier", ttl))
      case RelayEvent.Raided(from, viewers) =>
        Some(NotificationRequest(NotificationKind.Raid, "Raid incoming", s"$from with $viewers viewers", ttl))
      case RelayEvent.BitsCheered(user, bits) =>
        Some(NotificationRequest(NotificationKind.Bits, s"$bits bits", user, ttl))
      case RelayEvent.ChatMessaged(user, text) if config.chat == ChatNotifications.Show =>
        Some(NotificationRequest(NotificationKind.Chat, user, text, ttl))
      case RelayEvent.StreamStarted(channel, title, _) =>
        Some(NotificationRequest(NotificationKind.Info, s"$channel is live", title, ttl))
      case RelayEvent.StreamEnded(channel) =>
        Some(NotificationRequest(NotificationKind.Info, "Stream ended", channel, ttl))
      case RelayEvent.TwitchLinkDown(reason) =>
        Some(NotificationRequest(NotificationKind.Warning, "Twitch link lost", reason, ttl))
      case RelayEvent.RelayFailure(source, message) =>
        Some(NotificationRequest(NotificationKind.Alert, s"$source failed", message, ttl))
      case _ => None
