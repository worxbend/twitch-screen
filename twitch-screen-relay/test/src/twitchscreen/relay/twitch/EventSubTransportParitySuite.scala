package twitchscreen.relay.twitch

import java.time.{Clock, Duration as JDuration, Instant, ZoneOffset}
import ox.supervised
import ox.channels.Source
import scala.concurrent.duration.{DurationInt, FiniteDuration, SECONDS}
import sttp.client4.*
import sttp.model.StatusCode
import sttp.tapir.server.stub4.TapirSyncStubInterpreter
import twitchscreen.relay.bus.{BusEvent, EventBus, RelayEvent}
import twitchscreen.relay.config.*

/** K-042: both EventSub transports build an [[EventSubPayload]] and hand it to the one [[EventSubMapping]]. This suite sends the same
  * scenario through the WebSocket adapters (twitch4j events on an `EventManager` wired by `registerEventSub`) and through the signed
  * webhook, and requires the bus to see the same events, so a regression in either adapter fails here.
  */
class EventSubTransportParitySuite extends munit.FunSuite:
  import TwitchEventSubFixtures.*

  private val secret = "shared-secret"
  private val now = Instant.ofEpochSecond(1790309000L)
  private val clock = Clock.fixed(now, ZoneOffset.UTC)
  private val url = uri"http://localhost:8080/api/v1/twitch/eventsub"
  private val notifications = NotificationsConfig(30.seconds, ChatNotifications.Hide)
  private val startedAt = "2026-09-25T09:02:20Z"

  private val config = TwitchConfig(
    mode = TwitchMode.Live,
    channel = "somechannel",
    clientId = "client",
    clientSecret = Sensitive("secret"),
    oauth = TwitchOAuthConfig("http://localhost:8080/api/v1/twitch/callback", Nil, "data/twitch-token.json", 15.minutes),
    eventSub = EventSubConfig(EventSubTransport.Webhook, "https://relay.example/api/v1/twitch/eventsub", Sensitive(secret)),
    pollInterval = 30.seconds,
    simulation = SimulationConfig(10.seconds, 2.seconds)
  )

  /** The scenario as Twitch's webhook delivers it; [[webSocketEvents]] publishes the same five notifications as twitch4j objects. */
  private val webhookBodies = List(
    """{"subscription":{"type":"channel.update"},"event":{}}""",
    """{"subscription":{"type":"channel.follow"},"event":{"user_name":"pixelpainter"}}""",
    """{"subscription":{"type":"channel.follow"},"event":{"user_name":"Nightbot"}}""",
    s"""{"subscription":{"type":"stream.online"},"event":{"started_at":"$startedAt"}}""",
    """{"subscription":{"type":"stream.offline"},"event":{}}"""
  )

  private def drain(events: Source[BusEvent]): List[RelayEvent] =
    Iterator.continually(events.tryReceive()).takeWhile(_.nonEmpty).flatten.map(_.event).toList

  private def webSocketEvents(): List[RelayEvent] =
    supervised:
      val bus = EventBus(clock, queueCapacity = 16)
      val received = bus.subscribe("websocket")
      val events = eventManager()
      try
        TwitchEventHandlers.registerEventSub(
          events,
          bus,
          ChannelStateTracker(config.channel, clock),
          BotFilter.from(notifications),
          config.channel
        )
        events.publish(channelUpdate(None, None, None))
        events.publish(follow(Some("pixelpainter")))
        events.publish(follow(Some("Nightbot")))
        events.publish(streamOnline(Some(startedAt)))
        events.publish(streamOffline())
        drain(received)
      finally events.close()

  private def webhookEvents(): List[RelayEvent] =
    supervised:
      val bus = EventBus(clock, queueCapacity = 16)
      val received = bus.subscribe("webhook")
      val api =
        EventSubWebhookApi.create(config, bus, ChannelStateTracker(config.channel, clock), BotFilter.from(notifications), clock)
      val backend = TapirSyncStubInterpreter().whenServerEndpointsRunLogic(api.endpoints).backend()
      webhookBodies.zipWithIndex.foreach: (body, index) =>
        val messageId = s"parity-$index"
        val timestamp = now.toString
        val response = basicRequest
          .post(url)
          .header("Twitch-Eventsub-Message-Id", messageId)
          .header("Twitch-Eventsub-Message-Timestamp", timestamp)
          .header("Twitch-Eventsub-Message-Signature", EventSubSigning.sign(secret, messageId, timestamp, body))
          .header("Twitch-Eventsub-Message-Type", "notification")
          .body(body)
          .send(backend)
        assertEquals(response.code, StatusCode.Ok, body)
      drain(received)

  test("K-042: the WebSocket and webhook transports publish identical events for the same notifications"):
    val start = Instant.parse(startedAt)
    val expected = List(
      RelayEvent.ChannelUpdated("somechannel", "", ""),
      RelayEvent.Followed("pixelpainter"),
      RelayEvent.StreamStarted("somechannel", "", "", Some(start)),
      RelayEvent.StreamEnded("somechannel", FiniteDuration(math.max(0L, JDuration.between(start, now).toSeconds), SECONDS))
    )
    val webhook = webhookEvents()
    assertEquals(webhook, expected)
    assertEquals(webSocketEvents(), webhook)
