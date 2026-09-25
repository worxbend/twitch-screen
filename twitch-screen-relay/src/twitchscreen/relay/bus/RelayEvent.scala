package twitchscreen.relay.bus

import scala.concurrent.duration.FiniteDuration
import twitchscreen.relay.device.ConnectionId
import twitchscreen.relay.protocol.{Count, DeviceId, Notification}

/** Everything that happens in the relay, in one vocabulary. Producers (the Twitch source, the device link, the HTTP API) publish these;
  * consumers (notification routing, statistics, the activity log, alerting, metrics) each decide what to do with them. No consumer knows
  * about any producer.
  *
  * Events carry no timestamp: [[EventBus]] stamps them on publication from a single injected clock, so the ordering the subscribers see is
  * the ordering the relay recorded.
  */
private[relay] enum RelayEvent:
  // Channel state, observed either from EventSub or from a Helix poll.
  case StreamStarted(channel: String, title: String, game: String)
  case StreamEnded(channel: String)
  case ChannelUpdated(channel: String, title: String, game: String)
  case ViewersObserved(viewers: Count, uptime: FiniteDuration)
  case FollowersObserved(total: Count)
  case SubscribersObserved(total: Count)

  // Things the audience did.
  case Followed(user: String)
  case Subscribed(user: String, tier: String, cumulativeMonths: Int)
  case SubscriptionGifted(user: String, count: Int, tier: String)
  case Raided(from: String, viewers: Int)
  case BitsCheered(user: String, bits: Int)
  case ChatMessaged(user: String, text: String)

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
  extension (event: RelayEvent)
    /** Short human-readable form, used by the activity log and by log lines. */
    def summary: String = event match
      case StreamStarted(channel, title, game)            => s"$channel went live: $title ($game)"
      case StreamEnded(channel)                           => s"$channel went offline"
      case ChannelUpdated(channel, title, game)           => s"$channel updated: $title ($game)"
      case ViewersObserved(viewers, uptime)               => s"${viewers.value} viewers, up ${uptime.toMinutes} min"
      case FollowersObserved(total)                       => s"${total.value} followers"
      case SubscribersObserved(total)                     => s"${total.value} subscribers"
      case Followed(user)                                 => s"$user followed"
      case Subscribed(user, tier, months)                 => s"$user subscribed (tier $tier, $months months)"
      case SubscriptionGifted(user, count, tier)          => s"$user gifted $count tier $tier subs"
      case Raided(from, viewers)                          => s"$from raided with $viewers viewers"
      case BitsCheered(user, bits)                        => s"$user cheered $bits bits"
      case ChatMessaged(user, text)                       => s"$user: $text"
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
