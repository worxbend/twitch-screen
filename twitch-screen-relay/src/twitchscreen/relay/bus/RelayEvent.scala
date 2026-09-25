package twitchscreen.relay.bus

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import twitchscreen.relay.device.ConnectionId
import twitchscreen.relay.protocol.{ChatColour, Count, DeviceId, Notification, SubTier}

/** Everything that happens in the relay, in one vocabulary. Producers (the Twitch source, the device link, the HTTP API) publish these;
  * consumers (notification routing, statistics, the activity log, alerting, metrics) each decide what to do with them. No consumer knows
  * about any producer.
  *
  * Events carry no timestamp: [[EventBus]] stamps them on publication from a single injected clock, so the ordering the subscribers see is
  * the ordering the relay recorded.
  */
private[relay] enum RelayEvent:
  // Channel state, observed either from EventSub or from a Helix poll. `startedAt` and `duration` are carried
  // because TSB/3's STREAM_START and STREAM_END put them in `EVENT.value` (§6.4.1); neither is recoverable from
  // the moment the relay happened to notice.
  case StreamStarted(channel: String, title: String, game: String, startedAt: Option[Instant])
  case StreamEnded(channel: String, duration: FiniteDuration)
  case ChannelUpdated(channel: String, title: String, game: String)
  case ViewersObserved(viewers: Count, uptime: FiniteDuration)
  case FollowersObserved(total: Count)
  case SubscribersObserved(total: Count)

  // Things the audience did. The numbers are structured, not prose: §6.4's EVENT carries `value`, `months`,
  // `tier` and `eflags` as fields, and a router that flattened them into English would put the raider's viewer
  // count and the sub tier somewhere the device cannot read them.
  case Followed(user: String)
  case Subscribed(user: String, tier: SubTier, cumulativeMonths: Int, message: String)
  case SubscriptionGifted(user: String, count: Int, tier: SubTier, anonymous: Boolean)
  case Raided(from: String, viewers: Int)
  case BitsCheered(user: String, bits: Int, message: String)
  case ChatMessaged(user: String, text: String, colour: Option[ChatColour])

  // The relay's connection to Twitch.
  case TwitchLinkUp(detail: String)
  case TwitchLinkDown(reason: String)

  // The relay's connections to devices.
  case DeviceConnected(device: DeviceId, connection: ConnectionId, remoteAddress: String)
  case DeviceDisconnected(device: DeviceId, connection: ConnectionId, reason: String)

  // The relay itself.
  case NotificationPublished(notification: Notification)
  case RelayFailure(source: String, message: String)

private[relay] object RelayEvent:
  /** For log lines and the activity feed only. The wire carries the `tier` byte of §6.4, never this string: Twitch's own plan ids
    * (`"1000"`, `"2000"`) are exactly what blocker 15 found leaking onto the screen.
    */
  private def tierLabel(tier: SubTier): String = tier match
    case SubTier.NotApplicable => "no tier"
    case SubTier.Prime         => "Prime"
    case SubTier.Tier1         => "Tier 1"
    case SubTier.Tier2         => "Tier 2"
    case SubTier.Tier3         => "Tier 3"

  extension (event: RelayEvent)
    /** Short human-readable form, used by the activity log and by log lines. */
    def summary: String = event match
      case StreamStarted(channel, title, game, _)         => s"$channel went live: $title ($game)"
      case StreamEnded(channel, duration)                 => s"$channel went offline after ${duration.toMinutes} min"
      case ChannelUpdated(channel, title, game)           => s"$channel updated: $title ($game)"
      case ViewersObserved(viewers, uptime)               => s"${viewers.value} viewers, up ${uptime.toMinutes} min"
      case FollowersObserved(total)                       => s"${total.value} followers"
      case SubscribersObserved(total)                     => s"${total.value} subscribers"
      case Followed(user)                                 => s"$user followed"
      case Subscribed(user, tier, months, _)              => s"$user subscribed (${tierLabel(tier)}, $months months)"
      case SubscriptionGifted(user, count, tier, _)       => s"$user gifted $count ${tierLabel(tier)} subs"
      case Raided(from, viewers)                          => s"$from raided with $viewers viewers"
      case BitsCheered(user, bits, _)                     => s"$user cheered $bits bits"
      case ChatMessaged(user, text, _)                    => s"$user: $text"
      case TwitchLinkUp(detail)                           => s"Twitch link up: $detail"
      case TwitchLinkDown(reason)                         => s"Twitch link down: $reason"
      case DeviceConnected(device, connection, remote)    => s"device ${device.value} connected as #${connection.value} from $remote"
      case DeviceDisconnected(device, connection, reason) => s"device ${device.value} (#${connection.value}) disconnected: $reason"
      case NotificationPublished(notification) =>
        s"notification #${notification.seq.value} ${notification.kind.wire}: ${notification.title}"
      case RelayFailure(source, message) => s"$source failed: $message"

    /** Which part of the relay the event came from; the activity log and the alert rules filter on this. */
    def category: EventCategory = event match
      case _: (StreamStarted | StreamEnded | ChannelUpdated | ViewersObserved | FollowersObserved | SubscribersObserved) =>
        EventCategory.Channel
      case _: (Followed | Subscribed | SubscriptionGifted | Raided | BitsCheered | ChatMessaged) => EventCategory.Audience
      case _: (TwitchLinkUp | TwitchLinkDown)                                                    => EventCategory.Twitch
      case _: (DeviceConnected | DeviceDisconnected)                                             => EventCategory.Device
      case _: NotificationPublished                                                              => EventCategory.Notification
      case _: RelayFailure                                                                       => EventCategory.Failure
