package twitchscreen.relay.twitch

import java.time.Instant
import java.time.format.DateTimeParseException
import ox.discard
import ox.either.catching
import twitchscreen.relay.bus.RelayEvent

/** The single transport-independent mapping for the four EventSub subscriptions supported by the relay. */
private[twitch] final class EventSubMapping(tracker: ChannelStateTracker, channel: String, publish: RelayEvent => Unit):
  def dispatch(kind: String, payload: EventSubPayload): Unit = kind match
    case "stream.online" =>
      tracker
        .wentLive(
          payload.title.getOrElse(""),
          payload.categoryName.getOrElse(""),
          payload.startedAt.flatMap(raw => Instant.parse(raw).catching[DateTimeParseException].toOption),
          publish
        )
        .discard
    case "stream.offline" => tracker.wentOffline(publish).discard
    case "channel.follow" => payload.userName.foreach(user => publish(RelayEvent.Followed(user)))
    case "channel.update" =>
      val update = EventSubMapping.channelUpdated(payload.broadcasterUserName, payload.title, payload.categoryName, channel)
      tracker.channelInfo(update.title, update.game)
      publish(update)
    case _ => ()

private[twitch] object EventSubMapping:
  def channelUpdated(
      broadcaster: Option[String],
      title: Option[String],
      category: Option[String],
      fallback: String
  ): RelayEvent.ChannelUpdated =
    RelayEvent.ChannelUpdated(broadcaster.getOrElse(fallback), title.getOrElse(""), category.getOrElse(""))
