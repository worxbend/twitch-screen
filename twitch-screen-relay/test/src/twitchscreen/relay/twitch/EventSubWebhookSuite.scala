package twitchscreen.relay.twitch

import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Clock, Instant, ZoneOffset}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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
    userAccessToken = Sensitive("token"),
    chatAccessToken = Sensitive.Empty,
    eventSub = EventSubConfig(EventSubTransport.Webhook, "https://relay.example/api/v1/twitch/eventsub", Sensitive(secret)),
    pollInterval = 30.seconds,
    simulation = SimulationConfig(10.seconds, 2.seconds)
  )

  private def sign(messageId: String, timestamp: String, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    "sha256=" + mac.doFinal((messageId + timestamp + body).getBytes(UTF_8)).map(byte => f"$byte%02x").mkString

  private def post(messageType: String, body: String, timestamp: String = now.toString, signature: Option[String] = None)(using
      backend: SyncBackend
  ) =
    val messageId = "msg-1"
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
      val api = EventSubWebhookApi.create(config, bus, ChannelStateTracker(config.channel, clock), BotFilter.from(notifications), clock)
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
      assert(events.receive().event.isInstanceOf[RelayEvent.TwitchLinkDown], "expected the revocation to be reported")

  test("a body that is not JSON is a bad request"):
    withWebhook: (backend, _) =>
      given SyncBackend = backend
      assertEquals(post("notification", "not json").code, StatusCode.BadRequest)

  test("an unknown subscription type is accepted and ignored rather than failing Twitch's delivery"):
    withWebhook: (backend, _) =>
      given SyncBackend = backend
      assertEquals(post("notification", """{"subscription":{"type":"channel.hype_train.begin"}}""").code, StatusCode.Ok)
