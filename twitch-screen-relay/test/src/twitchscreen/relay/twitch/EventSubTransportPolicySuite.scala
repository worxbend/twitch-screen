package twitchscreen.relay.twitch

import com.github.twitch4j.TwitchClientBuilder
import com.github.twitch4j.client.websocket.domain.WebsocketConnectionState
import com.github.twitch4j.eventsub.socket.events.{
  EventSocketConnectionStateEvent,
  EventSocketSubscriptionFailureEvent,
  EventSocketSubscriptionSuccessEvent
}
import com.github.twitch4j.eventsub.subscriptions.SubscriptionTypes
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import ox.supervised
import scala.concurrent.duration.*
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.*

/** K-038: every transport-dependent decision is read from the one policy resolved from `config.eventSub.transport`. */
class EventSubTransportPolicySuite extends munit.FunSuite:
  private def config(transport: EventSubTransport): TwitchConfig = TwitchConfig(
    TwitchMode.Live,
    "channel",
    "client",
    Sensitive("secret"),
    TwitchOAuthConfig("http://localhost:8080/api/v1/twitch/callback", Nil, "unused.json", 15.minutes),
    EventSubConfig(transport, "https://relay.example/api/v1/twitch/eventsub", Sensitive("long-enough-secret")),
    30.seconds,
    SimulationConfig(10.seconds, 2.seconds)
  )
  private val clock = Clock.systemUTC()
  private val filter = BotFilter.from(NotificationsConfig(30.seconds, ChatNotifications.Hide))

  test("the WebSocket policy needs a grant, enables the event socket and expects a connection component"):
    val policy = EventSubTransportPolicy.of(EventSubTransport.WebSocket)
    assert(policy.requiresGrant)
    assert(policy.enablesEventSocket)
    assertEquals(policy.expectedHealth, List(HealthComponent.EventSubConnection))

  test("the webhook policy needs no grant, keeps the event socket off and expects no extra component"):
    val policy = EventSubTransportPolicy.of(EventSubTransport.Webhook)
    assert(!policy.requiresGrant)
    assert(!policy.enablesEventSocket)
    assertEquals(policy.expectedHealth, Nil)

  test("only the webhook policy exposes callback endpoints"):
    supervised:
      EventSubTransport.values.foreach: transport =>
        val bus = EventBus(clock, 16)
        val api = EventSubTransportPolicy
          .of(transport)
          .webhookApi(config(transport), bus, ChannelStateTracker("channel", clock), filter, clock, (_, _) => ())
        assertEquals(api.isDefined, transport == EventSubTransport.Webhook, clue(transport))
        api.foreach(webhook => assertEquals(webhook.endpoints.size, 1))

  test("each policy starts the session strategy of its own transport"):
    supervised:
      EventSubTransport.values.foreach: transport =>
        // Helix only: nothing here connects, and the socket strategy only registers listeners on the event manager.
        val client = TwitchClientBuilder.builder().withClientId("client").withClientSecret("secret").withEnableHelix(true).build()
        try
          val health = TwitchRuntimeHealth(config(transport), EventBus(clock, 16))
          val strategy = EventSubTransportPolicy
            .of(transport)
            .session(client, config(transport), health, AtomicBoolean(false), AtomicBoolean(true), () => ())
          transport match
            case EventSubTransport.Webhook   => assert(strategy.isInstanceOf[EventSubTransportStrategy.WebhookTransport], clue(strategy))
            case EventSubTransport.WebSocket => assert(strategy.isInstanceOf[EventSubTransportStrategy.SocketTransport], clue(strategy))
        finally client.close()

  private val follow =
    EventSubFactory.webSocketSubscription(SubscriptionTypes.CHANNEL_FOLLOW_V2, EventSubFactory.follow("123", "123"))
  private val online = EventSubFactory.webSocketSubscription(SubscriptionTypes.STREAM_ONLINE, EventSubFactory.streamOnline("123"))

  test("a socket subscription failure requests a rebuild and records the kind as failed"):
    supervised:
      val events = TwitchEventSubFixtures.eventManager()
      val health = TwitchRuntimeHealth(config(EventSubTransport.WebSocket), EventBus(clock, 16))
      val restart = AtomicBoolean(false)
      EventSubTransportStrategy.listenToSocket(events, health, restart, AtomicBoolean(true))
      events.publish(EventSocketSubscriptionSuccessEvent(online, null))
      events.publish(EventSocketSubscriptionFailureEvent(follow, null, IllegalStateException("rejected"), false))
      assert(restart.get())
      assertEquals(health.failure(HealthComponent.EventSubFollow), Some("subscription rejected; rebuilding connection"))
      assertEquals(health.failure(HealthComponent.EventSubOnline), None)

  test("socket connection state is reported as the EventSub connection's health"):
    supervised:
      val events = TwitchEventSubFixtures.eventManager()
      val health = TwitchRuntimeHealth(config(EventSubTransport.WebSocket), EventBus(clock, 16))
      EventSubTransportStrategy.listenToSocket(events, health, AtomicBoolean(false), AtomicBoolean(true))
      events.publish(EventSocketConnectionStateEvent(WebsocketConnectionState.CONNECTED, WebsocketConnectionState.RECONNECTING, null))
      assertEquals(health.failure(HealthComponent.EventSubConnection), Some("disconnected; reconnecting"))
      events.publish(EventSocketConnectionStateEvent(WebsocketConnectionState.RECONNECTING, WebsocketConnectionState.CONNECTED, null))
      assertEquals(health.failure(HealthComponent.EventSubConnection), None)

  test("socket callbacks after the session stopped accepting them change nothing"):
    supervised:
      val events = TwitchEventSubFixtures.eventManager()
      val health = TwitchRuntimeHealth(config(EventSubTransport.WebSocket), EventBus(clock, 16))
      val restart = AtomicBoolean(false)
      EventSubTransportStrategy.listenToSocket(events, health, restart, AtomicBoolean(false))
      val before = health.status
      events.publish(EventSocketSubscriptionFailureEvent(follow, null, IllegalStateException("rejected"), false))
      events.publish(EventSocketConnectionStateEvent(WebsocketConnectionState.CONNECTED, WebsocketConnectionState.LOST, null))
      assert(!restart.get())
      assertEquals(health.status, before)
