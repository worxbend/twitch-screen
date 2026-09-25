package twitchscreen.relay.twitch

import com.github.philippheuer.events4j.core.EventManager
import com.github.twitch4j.chat.events.channel.{
  ChannelMessageEvent,
  CheerEvent,
  GiftSubscriptionsEvent,
  IRCMessageEvent,
  RaidEvent,
  SubscriptionEvent
}
import com.github.twitch4j.common.events.domain.EventUser
import com.github.twitch4j.eventsub.events.{ChannelFollowEvent, ChannelUpdateV2Event, StreamOfflineEvent, StreamOnlineEvent}
import java.util.function.Consumer
import org.slf4j.LoggerFactory
import ox.discard
import scala.reflect.ClassTag
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.protocol.{ChatColour, SubTier}

/** Bridges twitch4j's callback API onto the relay's event bus.
  *
  * Each transport has exactly one job, so no event is published twice:
  *   - chat (IRC) carries what chat sees: messages, subscriptions, gifted subs, cheers and raids;
  *   - EventSub carries what only Twitch can push: individual follows, stream start/stop and channel updates;
  *   - the Helix poll ([[HelixPoller]]) carries the totals nobody pushes: viewers, followers, subscribers.
  *
  * §13.2 fixes that split: `SUB`, `GIFT`, `BITS` and `RAID` arrive over IRC **only**, and no EventSub subscription is registered for them
  * on either transport. A webhook deployment with no chat connection therefore emits stream lifecycle and follow events and nothing else,
  * which is an accepted limitation of v3 rather than a gap to be worked around.
  *
  * Everything an account authored is published through [[BotFilter]] rather than straight to the bus, so §13.1's filtering happens at the
  * source: a bot costs no bus capacity, no sequence number, no replay slot and — because the statistics fold from this same bus — no
  * `msg_total` and no `chat_rate`.
  *
  * Handlers run on twitch4j's own threads. Publishing is non-blocking, so they never hold those threads up.
  */
private[twitch] object TwitchEventHandlers:
  private val logger = LoggerFactory.getLogger(getClass)

  def registerChat(events: EventManager, bus: EventBus, filter: BotFilter): Unit =
    on[ChannelMessageEvent](events): event =>
      filter.publish(
        bus,
        RelayEvent.ChatMessaged(displayName(event.getMessageEvent, event.getUser), event.getMessage, chatColour(event))
      )

    on[SubscriptionEvent](events): event =>
      // A gifted sub also raises GiftSubscriptionsEvent for the gifter; only the gift is worth showing.
      if !java.lang.Boolean.TRUE.equals(event.getGifted) then
        filter.publish(
          bus,
          RelayEvent.Subscribed(
            user = displayName(event.getMessageEvent, event.getUser),
            tier = SubTier.fromTwitch(String.valueOf(event.getSubscriptionPlan)),
            cumulativeMonths = intOr(event.getMonths, 1),
            message = event.getMessage.orElse("")
          )
        )

    on[GiftSubscriptionsEvent](events): event =>
      // Twitch attributes an anonymous gift to the placeholder account `AnAnonymousGifter`, and twitch4j leaves the
      // user null when the tag is missing entirely. Both are what §6.4's ANONYMOUS eflag exists to carry, and §6.4
      // lets `actor` be empty for it rather than putting a fake name on the screen.
      val gifter = if event.getUser == null then "" else displayName(event.getMessageEvent, event.getUser)
      val anonymous = gifter.isEmpty || gifter.equalsIgnoreCase(AnonymousGifter)
      filter.publish(
        bus,
        RelayEvent.SubscriptionGifted(
          user = if anonymous then "" else gifter,
          count = math.max(1, event.getCount),
          tier = SubTier.fromTwitch(String.valueOf(event.getTier)),
          anonymous = anonymous
        )
      )

    on[CheerEvent](events): event =>
      filter.publish(
        bus,
        RelayEvent.BitsCheered(displayName(event.getMessageEvent, event.getUser), intOr(event.getBits, 0), textOr(event.getMessage))
      )

    on[RaidEvent](events): event =>
      filter.publish(bus, RelayEvent.Raided(displayName(event.getMessageEvent, event.getRaider), intOr(event.getViewers, 0)))

    logger.debug("Chat event handlers registered")

  def registerEventSub(events: EventManager, bus: EventBus, tracker: ChannelStateTracker, filter: BotFilter): Unit =
    on[ChannelFollowEvent](events)(event => filter.publish(bus, RelayEvent.Followed(event.getUserName)))

    on[StreamOnlineEvent](events): event =>
      tracker
        // stream.online carries no title or category (event.getType is "live", not a game); the tracker fills both in.
        .wentLive(title = "", game = "", startedAt = Option(event.getStartedAt), publish = bus.publish)
        .discard

    on[StreamOfflineEvent](events)(_ => tracker.wentOffline(bus.publish).discard)

    on[ChannelUpdateV2Event](events): event =>
      tracker.channelInfo(event.getTitle, event.getCategoryName)
      bus.publish(RelayEvent.ChannelUpdated(event.getBroadcasterUserName, event.getTitle, event.getCategoryName))

    logger.debug("EventSub handlers registered")

  private def on[E](events: EventManager)(handle: E => Unit)(using tag: ClassTag[E]): Unit =
    val eventClass = tag.runtimeClass.asInstanceOf[Class[E]]
    val consumer: Consumer[E] = event => handle(event)
    events.onEvent(eventClass, consumer).discard

  /** IRC reports the chatter's colour as `#RRGGBB`, and as an empty tag for a user who never picked one. §6.4 keeps the two apart with the
    * `CHAT_COLOUR_PRESENT` eflag, because black is a legal colour and would otherwise read as "not reported".
    */
  private def chatColour(event: ChannelMessageEvent): Option[ChatColour] =
    Option(event.getMessageEvent).flatMap(raw => Option(raw.getUserChatColor.orElse(null))).flatMap(ChatColour.fromHex)

  /** §1/§13.1: the wire carries the Twitch DISPLAY NAME, and bot matching is on it. twitch4j's `EventUser.getName` is the IRC `login` tag
    * (lowercase, ASCII), so the `display-name` tag is read off the raw message; on a USERNOTICE it belongs to the notice's author — the
    * subscriber, cheerer, raider or gifter. The login is only the fallback for a message that omits the tag.
    */
  private def displayName(raw: IRCMessageEvent, user: EventUser): String =
    Option(raw)
      .flatMap(r => Option(r.getUserDisplayName.orElse(null)))
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(Option(user).map(_.getName).getOrElse(""))

  /** The account Twitch attributes an anonymous gift to. */
  private val AnonymousGifter = "AnAnonymousGifter"

  /** twitch4j boxes its numbers and leaves them null when Twitch omits the tag. */
  private def intOr(value: Integer, fallback: Int): Int = if value == null then fallback else value.intValue

  /** A resub or cheer with no attached message is the common case, not an error; §6.4.1 leaves `text` empty for it. */
  private def textOr(value: String): String = if value == null then "" else value
