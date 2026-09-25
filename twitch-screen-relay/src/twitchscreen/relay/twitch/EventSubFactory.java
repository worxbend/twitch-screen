package twitchscreen.relay.twitch;

import com.github.twitch4j.eventsub.EventSubSubscription;
import com.github.twitch4j.eventsub.EventSubTransport;
import com.github.twitch4j.eventsub.EventSubTransportMethod;
import com.github.twitch4j.eventsub.condition.ChannelCheerCondition;
import com.github.twitch4j.eventsub.condition.ChannelFollowV2Condition;
import com.github.twitch4j.eventsub.condition.ChannelRaidCondition;
import com.github.twitch4j.eventsub.condition.ChannelSubscribeCondition;
import com.github.twitch4j.eventsub.condition.ChannelSubscriptionGiftCondition;
import com.github.twitch4j.eventsub.condition.ChannelSubscriptionMessageCondition;
import com.github.twitch4j.eventsub.condition.ChannelUpdateCondition;
import com.github.twitch4j.eventsub.condition.StreamOfflineCondition;
import com.github.twitch4j.eventsub.condition.EventSubCondition;
import com.github.twitch4j.eventsub.condition.StreamOnlineCondition;
import com.github.twitch4j.eventsub.subscriptions.SubscriptionType;

/**
 * The one file in this project that is not Scala.
 *
 * <p>twitch4j generates its EventSub conditions with Lombok's {@code @SuperBuilder}, whose recursive generic
 * signature ({@code B extends Builder<C, B>}) does not survive Scala's wildcard capture: {@code builder()} returns
 * {@code Builder<?, ?>}, and Scala types the result of a setter as the unnameable {@code builder.B}, so neither
 * chaining another setter nor calling {@code build()} type-checks. In Java the same calls are the ordinary,
 * documented way to use the library.
 *
 * <p>Keeping the workaround to these one-line factories means the rest of the Twitch integration stays in Scala and
 * stays honest about its types.
 */
final class EventSubFactory {
  private EventSubFactory() {}

  static StreamOnlineCondition streamOnline(String broadcasterId) {
    return StreamOnlineCondition.builder().broadcasterUserId(broadcasterId).build();
  }

  static StreamOfflineCondition streamOffline(String broadcasterId) {
    return StreamOfflineCondition.builder().broadcasterUserId(broadcasterId).build();
  }

  /** Follow notifications are per-moderator; a broadcaster moderates their own channel. */
  static ChannelFollowV2Condition follow(String broadcasterId, String moderatorId) {
    return ChannelFollowV2Condition.builder().broadcasterUserId(broadcasterId).moderatorUserId(moderatorId).build();
  }

  static ChannelSubscribeCondition subscribe(String broadcasterId) {
    return ChannelSubscribeCondition.builder().broadcasterUserId(broadcasterId).build();
  }

  static ChannelSubscriptionGiftCondition subscriptionGift(String broadcasterId) {
    return ChannelSubscriptionGiftCondition.builder().broadcasterUserId(broadcasterId).build();
  }

  static ChannelSubscriptionMessageCondition subscriptionMessage(String broadcasterId) {
    return ChannelSubscriptionMessageCondition.builder().broadcasterUserId(broadcasterId).build();
  }

  static ChannelCheerCondition cheer(String broadcasterId) {
    return ChannelCheerCondition.builder().broadcasterUserId(broadcasterId).build();
  }

  /** Raids are subscribed to by destination: this channel being raided. */
  static ChannelRaidCondition raid(String broadcasterId) {
    return ChannelRaidCondition.builder().toBroadcasterUserId(broadcasterId).build();
  }

  static ChannelUpdateCondition channelUpdate(String broadcasterId) {
    return ChannelUpdateCondition.builder().broadcasterUserId(broadcasterId).build();
  }

  /**
   * A WebSocket subscription. The socket fills in its own session id before sending this to Twitch, which is why the
   * transport carries only the method.
   */
  static EventSubSubscription webSocketSubscription(SubscriptionType<?, ?, ?> type, EventSubCondition condition) {
    return EventSubSubscription.builder()
        .type(type)
        .condition(condition)
        .transport(EventSubTransport.builder().method(EventSubTransportMethod.WEBSOCKET).build())
        .build();
  }

  /** A webhook subscription for Twitch to create. {@code type} is a Scala keyword, which is the other reason this is Java. */
  static EventSubSubscription webhookSubscription(
      SubscriptionType<?, ?, ?> type, EventSubCondition condition, String callbackUrl, String secret) {
    return EventSubSubscription.builder()
        .type(type)
        .condition(condition)
        .transport(
            EventSubTransport.builder()
                .method(EventSubTransportMethod.WEBHOOK)
                .callback(callbackUrl)
                .secret(secret)
                .build())
        .build();
  }
}
