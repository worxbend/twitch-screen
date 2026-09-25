package twitchscreen.relay

import com.typesafe.config.ConfigFactory
import io.opentelemetry.api.OpenTelemetry
import java.net.{InetSocketAddress, ServerSocket, Socket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Clock, Instant, ZoneOffset}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import ox.{Ox, discard, supervised}
import pureconfig.ConfigSource
import scala.util.Using
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.*
import twitchscreen.relay.device.DeviceHub
import twitchscreen.relay.health.HealthApi
import twitchscreen.relay.http.HttpApi
import twitchscreen.relay.twitch.{TwitchHealth, TwitchSource, TwitchStatus}

/** RLY-43: Twitch calls the webhook back as soon as ingestion registers a subscription, so the relay must be serving HTTP first. */
class StartupOrderSuite extends munit.FunSuite:
  ox.logback.InheritableMDC.init
  private val clock = Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneOffset.UTC)

  /** What the recording source observed from inside `startIngestion`. */
  private final case class Observation(connected: Boolean, healthStatus: Option[Int])

  /** Records whether the relay's own port accepted a connection and served HTTP at the moment ingestion started. */
  private final class RecordingTwitchSource(port: Int) extends TwitchSource:
    val calls = AtomicInteger()
    val observed = AtomicReference[Option[Observation]](None)
    override val status: TwitchStatus = TwitchStatus(TwitchMode.Disabled, TwitchHealth.Disabled, "channel", "recording")
    override val endpoints: List[ServerEndpoint[Any, Identity]] = Nil
    override def startIngestion()(using Ox): Unit =
      calls.incrementAndGet().discard
      val connected =
        try
          Using.resource(Socket())(_.connect(InetSocketAddress("127.0.0.1", port), 1000))
          true
        catch case _: java.io.IOException => false
      val health = if connected then Some(healthStatus()) else None
      observed.set(Some(Observation(connected, health)))

    private def healthStatus(): Int =
      val client = HttpClient.newHttpClient()
      try
        client
          .send(HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/api/v1/health")).build(), HttpResponse.BodyHandlers.discarding())
          .statusCode()
      finally client.close()

  private def freePort(): Int = Using.resource(ServerSocket(0))(_.getLocalPort)

  test("Twitch ingestion starts exactly once, after the HTTP port accepts connections and serves requests"):
    val port = freePort()
    val source = ConfigFactory
      .parseString(s"""
        http.host = "127.0.0.1"
        http.port = $port
        http.auth.api-token = "startup-order-test-api-token-with-32-bytes"
      """)
      .withFallback(ConfigFactory.load())
    val config = ConfigSource.fromConfig(source).loadOrThrow[Config]
    val recorder = RecordingTwitchSource(port)
    supervised:
      val hub = DeviceHub.start(config.deviceLink, config.notifications.chat, clock, EventBus(clock, 16))
      Dependencies(HttpApi(List(HealthApi(), recorder), config.http, OpenTelemetry.noop()), hub, recorder).serve().discard
    assertEquals(recorder.calls.get(), 1)
    assertEquals(recorder.observed.get(), Some(Observation(connected = true, healthStatus = Some(200))))
