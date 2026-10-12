package twitchscreen.relay.twitch

import com.typesafe.config.ConfigFactory
import java.time.Clock
import ox.{fork, sleep, supervised}
import scala.concurrent.duration.DurationInt
import pureconfig.ConfigSource
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.Config

class WebhookIdentitySuite extends munit.FunSuite:
  private val defaults = ConfigSource.fromConfig(ConfigFactory.load()).loadOrThrow[Config]
  private val clock = Clock.systemUTC()
  private val secret = "synthetic-identity-secret"
  private val selected = defaults.twitch.copy(
    channel = "current",
    eventSub = defaults.twitch.eventSub.copy(secret = twitchscreen.relay.config.Sensitive(secret))
  )
  private def envelope(
      id: String = "accepted",
      condition: String = "123",
      eventId: String = "123",
      login: String = "current"
  ): String =
    s"""{"subscription":{"id":"$id","type":"channel.follow","condition":{"broadcaster_user_id":"$condition"}},"event":{"broadcaster_user_id":"$eventId","broadcaster_user_login":"$login","user_name":"synthetic"},"challenge":"synthetic-challenge"}"""

  private def send(api: EventSubWebhookApi, body: String, kind: String = "notification") =
    val at = clock.instant().toString
    api.handleCallback("synthetic-message", at, EventSubSigning.sign(secret, "synthetic-message", at, body), kind, body)

  test("supported signed callbacks fail closed until the resolved broadcaster and provider subscription ID are bound"):
    supervised:
      val bus = EventBus(clock, 16)
      val received = bus.subscribe("identity")
      val api = EventSubWebhookApi.create(selected, bus, ChannelStateTracker(selected.channel, clock), BotFilter(Set.empty), clock)
      assert(send(api, envelope()).isLeft)
      api.bindBroadcaster("123")
      assert(send(api, envelope()).isLeft)
      api.bindSubscription("channel.follow", "accepted")
      assert(send(api, envelope()).isRight)
      assertEquals(received.receive().event, RelayEvent.Followed("synthetic"))
      assert(received.tryReceive().isEmpty)

  test("a signed wrong condition, retired subscription, wrong event broadcaster or wrong login never publishes"):
    supervised:
      val bus = EventBus(clock, 16)
      val received = bus.subscribe("identity")
      val api = EventSubWebhookApi.create(selected, bus, ChannelStateTracker(selected.channel, clock), BotFilter(Set.empty), clock)
      api.bindBroadcaster("123")
      api.bindSubscription("channel.follow", "accepted")
      List(envelope(condition = "111"), envelope(id = "retired"), envelope(eventId = "111"), envelope(login = "retired"))
        .foreach(body => assert(send(api, body).isLeft))
      assert(received.tryReceive().isEmpty)
      assert(send(api, envelope()).isRight) // rejected attempts must not claim the stable message ID
      assertEquals(received.receive().event, RelayEvent.Followed("synthetic"))

  test("retired same-broadcaster revocation and challenge cannot update current subscription health"):
    supervised:
      val bus = EventBus(clock, 16)
      var observations = List.empty[(String, Option[String])]
      val api = EventSubWebhookApi.create(
        selected,
        bus,
        ChannelStateTracker(selected.channel, clock),
        BotFilter(Set.empty),
        clock,
        (kind, failure) => observations = observations :+ (kind -> failure)
      )
      api.bindBroadcaster("123")
      api.bindSubscription("channel.follow", "accepted")
      assert(send(api, envelope(id = "retired"), "revocation").isLeft)
      assert(send(api, envelope(id = "retired"), "webhook_callback_verification").isLeft)
      assertEquals(observations, Nil)
      assertEquals(send(api, envelope(), "webhook_callback_verification"), Right("synthetic-challenge"))
      assertEquals(observations, List("channel.follow" -> None))

  test("an early signed challenge waits for accepted provider registration without learning identity from its body"):
    supervised:
      val bus = EventBus(clock, 16)
      val api = EventSubWebhookApi.create(selected, bus, ChannelStateTracker(selected.channel, clock), BotFilter(Set.empty), clock)
      api.bindBroadcaster("123")
      val completed = new java.util.concurrent.atomic.AtomicBoolean(false)
      val challenge = fork:
        val result = send(api, envelope(), "webhook_callback_verification")
        completed.set(true)
        result
      sleep(50.millis)
      assert(!completed.get())
      api.bindSubscription("channel.follow", "accepted")
      assertEquals(challenge.join(), Right("synthetic-challenge"))

  test("an unregistered signed challenge has a bounded failure and cannot bootstrap publication authority"):
    supervised:
      val bus = EventBus(clock, 16)
      val api = EventSubWebhookApi.create(selected, bus, ChannelStateTracker(selected.channel, clock), BotFilter(Set.empty), clock)
      api.bindBroadcaster("123")
      val started = System.nanoTime()
      assert(send(api, envelope(), "webhook_callback_verification").isLeft)
      assert(System.nanoTime() - started < 2.seconds.toNanos)
      assert(send(api, envelope()).isLeft)
