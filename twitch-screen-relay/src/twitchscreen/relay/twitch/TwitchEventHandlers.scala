package twitchscreen.relay.twitch

import com.github.philippheuer.events4j.core.EventManager
import com.github.twitch4j.chat.events.channel.{ChannelMessageEvent, CheerEvent, GiftSubscriptionsEvent, RaidEvent, SubscriptionEvent}
import com.github.twitch4j.eventsub.events.{ChannelFollowEvent, ChannelUpdateV2Event, StreamOfflineEvent, StreamOnlineEvent}
import java.util.function.Consumer
import org.slf4j.LoggerFactory
import ox.discard
import scala.reflect.ClassTag
import twitchscreen.relay.bus.{EventBus, RelayEvent}

/** Bridges twitch4j's callback API onto the relay's event bus.
  *
  * Each transport has exactly one job, so no event is published twice:
  *   - chat (IRC) carries what chat sees: messages, subscriptions, gifted subs, cheers and raids;
  *   - EventSub carries what only Twitch can push: individual follows, stream start/stop and channel updates;
  *   - the Helix poll ([[HelixPoller]]) carries the totals nobody pushes: viewers, followers, subscribers.
  *
  * Handlers run on twitch4j's own threads. Publishing is non-blocking, so they never hold those threads up.
  */
private[twitch] object TwitchEventHandlers:
  private val logger = LoggerFactory.getLogger(getClass)

  def registerChat(events: EventManager, bus: EventBus): Unit =
    on[ChannelMessageEvent](events): event =>
      bus.publish(RelayEvent.ChatMessaged(event.getUser.getName, event.getMessage))

    on[SubscriptionEvent](events): event =>
      // A gifted sub also raises GiftSubscriptionsEvent for the gifter; only the gift is worth showing.
      if !java.lang.Boolean.TRUE.equals(event.getGifted) then
        bus.publish(RelayEvent.Subscribed(event.getUser.getName, event.getSubscriptionPlan, intOr(event.getMonths, 1)))

    on[GiftSubscriptionsEvent](events): event =>
      bus.publish(RelayEvent.SubscriptionGifted(event.getUser.getName, event.getCount, String.valueOf(event.getTier)))

    on[CheerEvent](events): event =>
      bus.publish(RelayEvent.BitsCheered(event.getUser.getName, intOr(event.getBits, 0)))

    on[RaidEvent](events): event =>
      bus.publish(RelayEvent.Raided(event.getRaider.getName, intOr(event.getViewers, 0)))

    logger.debug("Chat event handlers registered")

  def registerEventSub(events: EventManager, bus: EventBus, tracker: ChannelStateTracker): Unit =
    on[ChannelFollowEvent](events)(event => bus.publish(RelayEvent.Followed(event.getUserName)))

    on[StreamOnlineEvent](events): event =>
      tracker.wentLive(title = "", game = String.valueOf(event.getType)).foreach(bus.publish)

    on[StreamOfflineEvent](events)(_ => tracker.wentOffline().foreach(bus.publish))

    on[ChannelUpdateV2Event](events): event =>
      bus.publish(RelayEvent.ChannelUpdated(event.getBroadcasterUserName, event.getTitle, event.getCategoryName))

    logger.debug("EventSub handlers registered")

  private def on[E](events: EventManager)(handle: E => Unit)(using tag: ClassTag[E]): Unit =
    val eventClass = tag.runtimeClass.asInstanceOf[Class[E]]
    val consumer: Consumer[E] = event => handle(event)
    events.onEvent(eventClass, consumer).discard

  /** twitch4j boxes its numbers and leaves them null when Twitch omits the tag. */
  private def intOr(value: Integer, fallback: Int): Int = if value == null then fallback else value.intValue
