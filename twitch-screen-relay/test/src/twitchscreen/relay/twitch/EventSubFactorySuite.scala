package twitchscreen.relay.twitch

import com.github.twitch4j.common.util.TypeConvert
import com.github.twitch4j.eventsub.EventSubSubscription
import com.github.twitch4j.eventsub.condition.EventSubCondition
import com.github.twitch4j.eventsub.subscriptions.SubscriptionType

/** Twitch rejects a create request without `type` and `version` as "invalid subscription type and version". twitch4j serializes those two
  * fields from `rawType`/`rawVersion`, which its builder does not derive from `type`, so every factory must set them explicitly.
  */
class EventSubFactorySuite extends munit.FunSuite:
  private val subscriptions: List[(SubscriptionType[?, ?, ?], EventSubCondition)] =
    EventSubWebhookApi.unscopedSubscriptions("123") ++ EventSubWebhookApi.scopedSubscriptions("123")

  private def assertWireTypeAndVersion(kind: SubscriptionType[?, ?, ?], subscription: EventSubSubscription): Unit =
    val json = TypeConvert.getObjectMapper.readTree(TypeConvert.objectToJson(subscription))
    assertEquals(json.path("type").asText(null), kind.getName, clue(json))
    assertEquals(json.path("version").asText(null), kind.getVersion, clue(json))

  test("WebSocket subscriptions carry the type name and version on the wire"):
    subscriptions.foreach: (kind, condition) =>
      assertWireTypeAndVersion(kind, EventSubFactory.webSocketSubscription(kind, condition))

  test("webhook subscriptions carry the type name and version on the wire"):
    subscriptions.foreach: (kind, condition) =>
      assertWireTypeAndVersion(
        kind,
        EventSubFactory.webhookSubscription(kind, condition, "https://relay.example/api/v1/twitch/eventsub", "long-enough-secret")
      )
