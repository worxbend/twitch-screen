package twitchscreen.relay.twitch

import com.typesafe.config.ConfigFactory
import java.net.{ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import java.util.concurrent.atomic.AtomicLong
import ox.*
import pureconfig.ConfigSource
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.util.Using
import twitchscreen.relay.Dependencies
import twitchscreen.relay.config.Config

class RuntimeRecoverySuite extends munit.FunSuite:
  ox.logback.InheritableMDC.init
  private final class WallClock extends Clock:
    val correction = AtomicLong(0)
    override def getZone: ZoneId = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant = Instant.now().plusMillis(correction.get())
  private def port(): Int = Using.resource(ServerSocket(0))(_.getLocalPort)

  test(
    "installed Dependencies automatically rotates clock domain and recreates simulated ingestion without rebinding HTTP or losing desired state"
  ):
    val directory =
      Files.createTempDirectory(Path.of(sys.env.getOrElse("TMPDIR", System.getProperty("java.io.tmpdir"))), "automatic-runtime-")
    val httpPort = port()
    val devicePort = port()
    val source = ConfigFactory
      .parseString(s"""
      runtime.settings-file = "${directory.resolve("runtime.json")}"
      twitch.mode = simulated
      twitch.channel = synthetic
      twitch.simulation.interval = 1 hour
      twitch.simulation.chat-interval = 1 hour
      twitch.poll-interval = 1 hour
      http.port = $httpPort
      http.allowed-hosts = ["127.0.0.1:$httpPort"]
      http.allowed-origins = ["http://127.0.0.1:$httpPort"]
      device-link.port = $devicePort
    """)
      .withFallback(ConfigFactory.load())
    val config = ConfigSource.fromConfig(source).loadOrThrow[Config]
    val client = HttpClient.newHttpClient()
    def read(path: String): HttpResponse[String] = client.send(
      HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$httpPort/api/v1/$path")).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    )
    def write(path: String, method: String, body: String): HttpResponse[String] = client.send(
      HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:$httpPort/api/v1/$path"))
        .header("Content-Type", "application/json")
        .method(method, HttpRequest.BodyPublishers.ofString(body))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    try
      supervised:
        val clock = WallClock()
        val runtime = Dependencies.create(config, source, clock, clock.instant())
        runtime.serve().discard
        val displayResponse = write("display", "PATCH", """{"preferredDashboard":"cpu"}""")
        assertEquals(displayResponse.statusCode(), 200, displayResponse.body())
        assertEquals(write("cpu", "PUT", """{"sourceId":"synthetic","host":"Synthetic host","cpuPercent":42}""").statusCode(), 200)
        assertEquals(
          write("weather", "PUT", """{"sourceId":"synthetic","location":"Synthetic desk","temperature":21,"condition":"clear"}""")
            .statusCode(),
          200
        )
        assertEquals(write("notifications", "POST", """{"type":"info","title":"Old epoch","body":"synthetic"}""").statusCode(), 200)
        val old = runtime.hub.relayClock.relayEpoch
        val started = System.nanoTime()
        clock.correction.set(10000L)
        timeout(4.seconds):
          repeatUntil:
            runtime.hub.relayClock.relayEpoch != old || { sleep(10.millis); false }
        timeout(4.seconds):
          repeatUntil:
            runtime.twitch.status.mode == config.twitch.mode || { sleep(10.millis); false }
        assert(System.nanoTime() - started < 4.seconds.toNanos)
        assertEquals(read("cpu").statusCode(), 200)
        assert(read("cpu").body().contains("\"status\":\"missing\""))
        assert(read("weather").body().contains("\"status\":\"missing\""))
        assert(read("display").body().contains("\"preferredDashboard\":\"cpu\""))
        val history = read("notifications")
        assertEquals(history.statusCode(), 200)
        assert(!history.body().contains("Old epoch"))
        assert(history.body().contains("synthetic")) // recreated simulator emits its initial lifecycle through the new admitted view
        assertEquals(write("notifications", "POST", """{"type":"info","title":"New epoch","body":"synthetic"}""").statusCode(), 200)
        assert(read("notifications").body().contains("New epoch"))
        assertEquals(runtime.twitch.status.channel, "synthetic")
      client.close()
      for closedPort <- List(httpPort, devicePort) do
        timeout(3.seconds):
          repeatUntil:
            val socket = new java.net.Socket()
            val refused =
              try
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", closedPort), 100)
                false
              catch case _: java.io.IOException => true
              finally socket.close()
            refused || { sleep(10.millis); false }
    finally
      client.close()
      Using.resource(Files.walk(directory))(_.iterator().asScala.toList.reverse.foreach(Files.delete(_)))
