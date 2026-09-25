package twitchscreen.relay.twitch

import java.time.{Clock, Instant, ZoneOffset}
import ox.supervised
import scala.concurrent.duration.DurationInt
import sttp.client4.*
import sttp.model.StatusCode
import sttp.tapir.server.stub4.TapirSyncStubInterpreter
import ox.channels.Source
import twitchscreen.relay.bus.{BusEvent, EventBus, RelayEvent}
import twitchscreen.relay.config.*

/** The webhook endpoint is the relay's only unauthenticated entry point, so its signature check is the thing most worth testing. The HMAC
  * here is computed independently of the implementation.
  */
class EventSubWebhookSuite extends munit.FunSuite:
  private val ids = java.util.concurrent.atomic.AtomicInteger()
  private val secret = "shared-secret"
  private val now = Instant.ofEpochSecond(1790309000L)
  private val clock = Clock.fixed(now, ZoneOffset.UTC)
  private val url = uri"http://localhost:8080/api/v1/twitch/eventsub"
  private val notifications = NotificationsConfig(30.seconds, ChatNotifications.Hide)

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

  private def sign(messageId: String, timestamp: String, body: String): String =
    EventSubSigning.sign(secret, messageId, timestamp, body)

  private def post(
      messageType: String,
      body: String,
      timestamp: String = now.toString,
      signature: Option[String] = None,
      messageId: String = s"msg-${ids.incrementAndGet()}"
  )(using
      backend: SyncBackend
  ) =
    basicRequest
      .post(url)
      .header("Twitch-Eventsub-Message-Id", messageId)
      .header("Twitch-Eventsub-Message-Timestamp", timestamp)
      .header("Twitch-Eventsub-Message-Signature", signature.getOrElse(sign(messageId, timestamp, body)))
      .header("Twitch-Eventsub-Message-Type", messageType)
      .body(body)
      .send(backend)

  /** Subscribes inside the scope and hands the test the channel, so each test body needs no Ox capability of its own. */
  private def withWebhook(body: (SyncBackend, Source[BusEvent]) => Unit): Unit =
    supervised:
      val bus = EventBus(clock, queueCapacity = 16)
      val health = TwitchRuntimeHealth(config, bus)
      health.observe(HealthComponent.Startup, None)
      val api = EventSubWebhookApi.create(
        config,
        bus,
        ChannelStateTracker(config.channel, clock),
        BotFilter.from(notifications),
        clock,
        (kind, failure) => health.observeSubscription(kind, failure)
      )
      body(TapirSyncStubInterpreter().whenServerEndpointsRunLogic(api.endpoints).backend(), bus.subscribe("test"))

  test("a correctly signed verification request is answered with the challenge"):
    withWebhook: (backend, _) =>
      given SyncBackend = backend
      val body = """{"subscription":{"type":"stream.online","status":"webhook_callback_verification_pending"},"challenge":"pogchamp"}"""
      assertEquals(post("webhook_callback_verification", body).body, Right("pogchamp"))

  test("a request with a forged signature is rejected"):
    withWebhook: (backend, _) =>
      given SyncBackend = backend
      val body = """{"subscription":{"type":"stream.online"},"challenge":"pogchamp"}"""
      assertEquals(post("webhook_callback_verification", body, signature = Some("sha256=deadbeef")).code, StatusCode.Unauthorized)

  test("a replayed request with a stale timestamp is rejected even though its signature is valid"):
    withWebhook: (backend, _) =>
      given SyncBackend = backend
      val body = """{"subscription":{"type":"stream.online"},"challenge":"pogchamp"}"""
      assertEquals(post("webhook_callback_verification", body, timestamp = now.minusSeconds(3600).toString).code, StatusCode.Unauthorized)

  test("an unparseable timestamp is a bad request, not a crash"):
    withWebhook: (backend, _) =>
      given SyncBackend = backend
      assertEquals(post("notification", "{}", timestamp = "yesterday").code, StatusCode.BadRequest)

  test("a signed follow notification reaches the bus"):
    withWebhook: (backend, events) =>
      given SyncBackend = backend
      val body = """{"subscription":{"type":"channel.follow"},"event":{"user_name":"pixelpainter"}}"""
      assertEquals(post("notification", body).code, StatusCode.Ok)
      assertEquals(events.receive().event, RelayEvent.Followed("pixelpainter"))

  test("a signed stream.online notification reaches the bus, carrying Twitch's own start time"):
    withWebhook: (backend, events) =>
      given SyncBackend = backend
      // §6.4.1 puts the stream's start in STREAM_START.value, and the moment a webhook happened to arrive is not
      // it: a redelivery would otherwise move the stream's start time.
      val body =
        """{"subscription":{"type":"stream.online"},"event":{"title":"Soldering","category_name":"Science & Technology","started_at":"2026-09-25T09:02:20Z"}}"""
      assertEquals(post("notification", body).code, StatusCode.Ok)
      assertEquals(
        events.receive().event,
        RelayEvent.StreamStarted("somechannel", "Soldering", "Science & Technology", Some(Instant.parse("2026-09-25T09:02:20Z")))
      )

  test("a bot-authored follow never reaches the bus, whichever EventSub transport delivered it"):
    withWebhook: (backend, events) =>
      given SyncBackend = backend
      // §13.1 filters at the source on BOTH transports, not only on the one wired to twitch4j.
      val bot = """{"subscription":{"type":"channel.follow"},"event":{"user_name":"Nightbot"}}"""
      val human = """{"subscription":{"type":"channel.follow"},"event":{"user_name":"pixelpainter"}}"""
      assertEquals(post("notification", bot).code, StatusCode.Ok)
      assertEquals(post("notification", human).code, StatusCode.Ok)
      assertEquals(events.receive().event, RelayEvent.Followed("pixelpainter"))

  test("a revoked subscription is reported as the Twitch link going down"):
    withWebhook: (backend, events) =>
      given SyncBackend = backend
      val body = """{"subscription":{"type":"channel.follow","status":"authorization_revoked"}}"""
      assertEquals(post("revocation", body).code, StatusCode.Ok)
      assert(events.receive().event.isInstanceOf[RelayEvent.RelayFailure])
      assert(events.receive().event.isInstanceOf[RelayEvent.TwitchLinkDown], "expected the shared health model to report revocation")
      assert(events.tryReceive().isEmpty, "revocation must publish one shared link transition")

  test("a body that is not JSON is a bad request"):
    withWebhook: (backend, _) =>
      given SyncBackend = backend
      assertEquals(post("notification", "not json").code, StatusCode.BadRequest)

  test("an unknown subscription type is accepted and ignored rather than failing Twitch's delivery"):
    withWebhook: (backend, _) =>
      given SyncBackend = backend
      assertEquals(post("notification", """{"subscription":{"type":"channel.hype_train.begin"}}""").code, StatusCode.Ok)

  test("signed redelivery is acknowledged without publishing again"):
    withWebhook: (backend, events) =>
      given SyncBackend = backend
      val body = """{"subscription":{"type":"channel.follow"},"event":{"user_name":"once"}}"""
      assertEquals(post("notification", body, messageId = "duplicate").code, StatusCode.Ok)
      assertEquals(post("notification", body, messageId = "duplicate").code, StatusCode.Ok)
      assertEquals(events.receive().event, RelayEvent.Followed("once"))
      assert(events.tryReceive().isEmpty)

  test("channel update supplies metadata to the later online event"):
    withWebhook: (backend, events) =>
      given SyncBackend = backend
      val update = """{"subscription":{"type":"channel.update"},"event":{"title":"Build night","category_name":"Science"}}"""
      assertEquals(post("notification", update).code, StatusCode.Ok)
      assert(events.receive().event.isInstanceOf[RelayEvent.ChannelUpdated])
      assertEquals(post("notification", """{"subscription":{"type":"stream.online"},"event":{}}""").code, StatusCode.Ok)
      assertEquals(events.receive().event, RelayEvent.StreamStarted("somechannel", "Build night", "Science", Some(now)))

  test("future signed timestamp cannot outlive its deduplication entry while still fresh"):
    class Movable extends Clock:
      var current = now
      override def instant(): Instant = current
      override def getZone = ZoneOffset.UTC
      override def withZone(zone: java.time.ZoneId): Clock = this
    supervised:
      val moving = Movable()
      val bus = EventBus(moving, queueCapacity = 16)
      val api = EventSubWebhookApi.create(config, bus, ChannelStateTracker(config.channel, moving), BotFilter.from(notifications), moving)
      given SyncBackend = TapirSyncStubInterpreter().whenServerEndpointsRunLogic(api.endpoints).backend()
      val events = bus.subscribe("skew-test")
      val body = """{"subscription":{"type":"channel.follow"},"event":{"user_name":"once"}}"""
      val sent = now.plusSeconds(600).toString
      assertEquals(post("notification", body, timestamp = sent, messageId = "future").code, StatusCode.Ok)
      assertEquals(events.receive().event, RelayEvent.Followed("once"))
      moving.current = now.plusSeconds(600)
      assertEquals(post("notification", body, timestamp = sent, messageId = "future").code, StatusCode.Ok)
      moving.current = now.plusSeconds(1200)
      assertEquals(post("notification", body, timestamp = sent, messageId = "future").code, StatusCode.Ok)
      assert(events.tryReceive().isEmpty)

  test("a known notification without an event is refused and its id remains retryable"):
    withWebhook: (backend, events) =>
      given SyncBackend = backend
      assertEquals(
        post("notification", """{"subscription":{"type":"channel.follow"}}""", messageId = "missing").code,
        StatusCode.BadRequest
      )
      val fixed = """{"subscription":{"type":"channel.follow"},"event":{"user_name":"retried"}}"""
      assertEquals(post("notification", fixed, messageId = "missing").code, StatusCode.Ok)
      assertEquals(events.receive().event, RelayEvent.Followed("retried"))

  test("a failed dispatch allows the same signed message to be redelivered"):
    supervised:
      val bus = EventBus(clock, queueCapacity = 16)
      var attempts = 0
      val api = EventSubWebhookApi.create(
        config,
        bus,
        ChannelStateTracker(config.channel, clock),
        BotFilter.from(notifications),
        clock,
        (_, _) =>
          attempts += 1
          if attempts == 1 then throw IllegalStateException("temporary failure")
      )
      given SyncBackend = TapirSyncStubInterpreter().whenServerEndpointsRunLogic(api.endpoints).backend()
      val body = """{"subscription":{"type":"channel.follow","status":"authorization_revoked"}}"""
      assertEquals(post("revocation", body, messageId = "retry").code, StatusCode.ServiceUnavailable)
      assertEquals(post("revocation", body, messageId = "retry").code, StatusCode.Ok)
      assertEquals(post("revocation", body, messageId = "retry").code, StatusCode.Ok)
      assertEquals(attempts, 2)

  test("signature authentication runs before timestamp diagnostics"):
    withWebhook: (backend, _) =>
      given SyncBackend = backend
      assertEquals(post("notification", "{}", timestamp = "not-a-date", signature = Some("forged")).code, StatusCode.Unauthorized)
