package twitchscreen.relay.twitch

import com.typesafe.config.ConfigFactory
import io.opentelemetry.api.OpenTelemetry
import java.net.{ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Clock
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import ox.*
import pureconfig.ConfigSource
import scala.concurrent.duration.DurationInt
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.*
import twitchscreen.relay.device.{ClockDiscontinuity, DeviceHub, SourceIngestion}
import twitchscreen.relay.http.HttpApi

class RuntimeSourceFailureSuite extends munit.FunSuite:
  ox.logback.InheritableMDC.init
  private val defaults = ConfigSource.fromConfig(ConfigFactory.load()).loadOrThrow[Config]
  private val clock = Clock.systemUTC()
  private def fake(selected: TwitchConfig): TwitchSource = new TwitchSource:
    override val status = TwitchStatus(selected.mode, TwitchHealth.Connected, selected.channel, "synthetic")
    override val endpoints: List[ServerEndpoint[Any, Identity]] = Nil

  test("scheduled detection keeps invalidating fresh epochs while prior source cleanup remains blocked"):
    supervised:
      val bus = EventBus(clock, 16)
      val hub = DeviceHub.start(defaults.deviceLink, defaults.notifications.chat, clock, bus)
      val closing = CountDownLatch(1)
      val release = CountDownLatch(1)
      val creates = AtomicInteger()
      def create(selected: TwitchConfig)(using Ox): TwitchSource =
        creates.incrementAndGet().discard
        useInScope(()): _ =>
          closing.countDown()
          var finished = false
          while !finished do
            try
              release.await()
              finished = true
            catch case _: InterruptedException => ()
        fake(selected)
      val source = RuntimeSource.startWith(defaults.twitch, hub, selected => create(selected))
      val correction = AtomicLong()
      val detector = ClockDiscontinuity(() => System.currentTimeMillis() + correction.get())
      val recovery = RuntimeRecovery(hub, source, detector)
      val old = hub.relayClock.relayEpoch
      recovery.start()
      recovery.start() // once only
      try
        correction.set(10000L)
        timeout(3.seconds):
          repeatUntil:
            hub.relayClock.relayEpoch != old || { sleep(10.millis); false }
        assert(closing.await(1, TimeUnit.SECONDS))
        val intermediate = hub.relayClock.relayEpoch
        correction.set(20000L)
        timeout(3.seconds):
          repeatUntil:
            hub.relayClock.relayEpoch != intermediate || { sleep(10.millis); false }
        assertEquals(creates.get(), 1)
        assertEquals(source.status.health, TwitchHealth.Disconnected)
      finally release.countDown()

  test("failed source construction stays isolated and runtime alerts deactivate instead of claiming effective live ownership"):
    supervised:
      val bus = EventBus(clock, 16)
      val hub = DeviceHub.start(defaults.deviceLink, defaults.notifications.chat, clock, bus)
      var activation = TwitchMode.Disabled
      val creates = AtomicInteger()
      val source = RuntimeSource.startWith(
        defaults.twitch,
        hub,
        selected => if creates.incrementAndGet() == 1 then fake(selected) else throw IllegalStateException("synthetic failure"),
        activate = mode => activation = mode
      )
      assertEquals(source.replace(defaults.twitch.copy(mode = TwitchMode.Live)), Left("source_application_failed"))
      assertEquals(activation, TwitchMode.Disabled)
      assertEquals(source.status.health, TwitchHealth.Disconnected)

  test("actual mounted stable webhook retains HMAC checks and dispatches to the replacement source"):
    val reservation = ServerSocket(0)
    val port = reservation.getLocalPort
    reservation.close()
    val config = defaults.twitch.copy(
      channel = "first",
      eventSub =
        EventSubConfig(EventSubTransport.Webhook, "https://synthetic.invalid/api/v1/twitch/eventsub", Sensitive("synthetic-shared-secret"))
    )
    val http = defaults.http.copy(
      port = Port(port).toOption.get,
      allowedHosts = List(s"127.0.0.1:$port"),
      allowedOrigins = List(s"http://127.0.0.1:$port")
    )
    val client = HttpClient.newHttpClient()
    try
      supervised:
        val bus = EventBus(clock, 16)
        val hub = DeviceHub.start(defaults.deviceLink, defaults.notifications.chat, clock, bus)
        var oldCallback = Option.empty[EventSubWebhookApi]
        val source = RuntimeSource.startWith(
          config,
          hub,
          selected =>
            val api = EventSubWebhookApi.create(
              selected,
              SourceIngestion.start(bus, hub, defaults.stats),
              ChannelStateTracker(selected.channel, clock),
              BotFilter(Set.empty),
              clock
            )
            WebhookIdentityFixtures.bind(api, if selected.channel == "first" then "111" else "222")
            if oldCallback.isEmpty then oldCallback = Some(api)
            new TwitchSource:
              override private[twitch] def webhookOwner: Option[EventSubWebhookApi] = Some(api)
              override val status = fake(selected).status
              override val endpoints: List[ServerEndpoint[Any, Identity]] = Nil
              override val webhookCallback = Some(api.handleCallback)
        )
        HttpApi(List(source), http, OpenTelemetry.noop()).start().discard
        val raw = """{"subscription":{"type":"channel.follow"},"event":{"user_name":"synthetic"}}"""
        val body = WebhookIdentityFixtures.body(raw, "111", "first")
        def post(id: String, forged: Boolean = false, login: String = "first", kind: String = "notification"): HttpResponse[String] =
          val payload = WebhookIdentityFixtures.body(raw, if login == "first" then "111" else "222", login)
          val at = clock.instant().toString
          val signature = if forged then "sha256=forged" else EventSubSigning.sign("synthetic-shared-secret", id, at, payload)
          client.send(
            HttpRequest
              .newBuilder(URI.create(s"http://127.0.0.1:$port/api/v1/twitch/eventsub"))
              .header("Content-Type", "application/json")
              .header("Twitch-Eventsub-Message-Id", id)
              .header("Twitch-Eventsub-Message-Timestamp", at)
              .header("Twitch-Eventsub-Message-Signature", signature)
              .header("Twitch-Eventsub-Message-Type", kind)
              .POST(HttpRequest.BodyPublishers.ofString(payload))
              .build(),
            HttpResponse.BodyHandlers.ofString()
          )
        assertEquals(post("forged", forged = true).statusCode(), 401)
        assertEquals(hub.snapshot.latestSeq.value, 0L)
        assertEquals(post("first").statusCode(), 200)
        assertEquals(hub.snapshot.latestSeq.value, 1L)
        val epoch = hub.relayClock.relayEpoch
        assert(hub.resetEpoch(epoch, if epoch == 42L then 43L else 42L))
        assertEquals(source.replace(config), Right(()))
        assertEquals(post("first").statusCode(), 200) // stable dedup survives same-channel epoch recovery
        assertEquals(hub.snapshot.latestSeq.value, 0L)
        assertEquals(source.replace(config.copy(channel = "second")), Right(()))
        assertEquals(post("first").statusCode(), 401) // identical signed old-channel delivery cannot acquire current authority
        assertEquals(post("old-revocation", kind = "revocation").statusCode(), 401)
        assertEquals(post("old-challenge", kind = "webhook_callback_verification").statusCode(), 401)
        val at = clock.instant().toString
        assert(
          oldCallback.get
            .handleCallback("old-late", at, EventSubSigning.sign("synthetic-shared-secret", "old-late", at, body), "notification", body)
            .isRight
        )
        assertEquals(hub.snapshot.latestSeq.value, 0L)
        assertEquals(post("second", login = "second").statusCode(), 200)
        assertEquals(post("second", login = "second").statusCode(), 200)
        assertEquals(hub.snapshot.latestSeq.value, 1L)
        assertEquals(source.status.channel, "second")
    finally client.close()
