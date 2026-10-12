package twitchscreen.relay.device

import com.typesafe.config.ConfigFactory
import java.net.{ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import java.time.Clock
import ox.{discard, supervised}
import pureconfig.ConfigSource
import scala.jdk.CollectionConverters.*
import scala.util.Using
import twitchscreen.relay.Dependencies
import twitchscreen.relay.config.{Config, TwitchMode}
import twitchscreen.relay.settings.*
import twitchscreen.relay.protocol.{MessageType, RelayMessage}

/** Actual production assembly, HTTP publication/readback and TCP delivery; no providers, hardware or personal content. */
class RuntimeSettingsDegradedSuite extends munit.FunSuite:
  ox.logback.InheritableMDC.init
  private def port(): Int = Using.resource(ServerSocket(0))(_.getLocalPort)
  private val fixture = FunFixture[Path](
    _ => Files.createTempDirectory(Path.of(sys.env.getOrElse("TMPDIR", System.getProperty("java.io.tmpdir"))), "settings-degraded-"),
    directory => Using.resource(Files.walk(directory))(_.iterator().asScala.toList.reverse.foreach(Files.delete(_)))
  )

  private def source(path: Path, httpPort: Int, devicePort: Int, startupMode: String) =
    ConfigFactory
      .parseString(s"""
      runtime.settings-file = "$path"
      twitch.mode = $startupMode
      twitch.channel = ""
      twitch.client-id = ""
      twitch.client-secret = ""
      twitch.simulation.interval = 1 hour
      twitch.simulation.chat-interval = 1 hour
      http.port = $httpPort
      http.allowed-hosts = ["127.0.0.1:$httpPort"]
      http.allowed-origins = ["http://127.0.0.1:$httpPort"]
      device-link.port = $devicePort
    """)
      .withFallback(ConfigFactory.load())

  private def send(client: HttpClient, port: Int, path: String, body: Option[String] = None): HttpResponse[String] =
    val builder = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/api/v1/$path"))
    body.foreach(value => builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(value)).discard)
    client.send(builder.build(), HttpResponse.BodyHandlers.ofString())

  for desiredMode <- List("live", "disabled", "simulated") do
    fixture.test(s"saved $desiredMode overrides incomplete live defaults and keeps real generic publication working across restarts") {
      directory =>
        val path = directory.resolve("runtime-state.json")
        val desired = RuntimeSettings(1, DisplaySettings(false, "cpu"), MonitoringSettings(desiredMode, ChannelSetting(Some("worxbend"))))
        val file = RuntimeSettingsFile(path)
        assertEquals(file.save(desired), Right(()))
        val original = Files.readAllBytes(path).toVector
        for _ <- 1 to 2 do
          val httpPort = port()
          val devicePort = port()
          val hocon = source(path, httpPort, devicePort, "live")
          val config = ConfigSource.fromConfig(hocon).loadOrThrow[Config]
          assertEquals(config.twitch.mode, TwitchMode.Live, "structural decoding must not discard startup intent")
          supervised:
            val clock = Clock.systemUTC()
            val runtime = Dependencies.create(config, hocon, clock, clock.instant())
            assertEquals(runtime.twitch.status.mode, if desiredMode == "simulated" then TwitchMode.Simulated else TwitchMode.Disabled)
            assertEquals(runtime.hub.publicationSnapshot.settings.map(_.confirmed), Some(desired))
            val binding = runtime.serve()
            try
              Using.resource(HttpClient.newHttpClient()): client =>
                val status = send(client, httpPort, "status")
                assertEquals(status.statusCode(), 200)
                assert(status.body().contains("\"settingsSource\":\"persisted\""), status.body())
                assert(status.body().contains(s"\"desired\":{\"mode\":\"$desiredMode\",\"channel\":\"worxbend\"}"), status.body())
                assertEquals(status.body().contains("configuration_missing"), desiredMode == "live")
                if desiredMode == "live" then
                  assert(status.body().contains("\"transition\":\"failed\""), status.body())
                  assert(status.body().contains("\"effective\":{\"mode\":\"disabled\""), status.body())
                val calibration = send(client, httpPort, "clock")
                assertEquals(calibration.statusCode(), 200)
                assertEquals(calibration.headers().firstValue("Cache-Control").orElse(""), "no-store")
                val sample = com.github.plokhotnyuk.jsoniter_scala.core.readFromString[Clock_OUT](calibration.body())
                assertEquals(sample.relayEpoch, runtime.hub.relayClock.epochHex)
                assert(sample.monotonicMs <= runtime.hub.relayClock.monotonicMs)
                val startup = send(client, httpPort, "config")
                assertEquals(startup.statusCode(), 200)
                assert(startup.body().contains("live"), "config remains startup configuration, not effective state")
                Using.resource(TestDevice(devicePort)): device =>
                  device.hello("startup-repair-05", lastSeq = 0)
                  assertEquals(device.receiveMany(2).map(_.header.typeCode.known), List(Some(MessageType.Welcome), Some(MessageType.Stats)))
                  val published = send(
                    client,
                    httpPort,
                    "notifications",
                    Some("""{"type":"message","title":"Synthetic startup repair","body":"independent","ttlMs":1000}""")
                  )
                  assertEquals(published.statusCode(), 200)
                  assert(device.receiveMessage().exists {
                    case RelayMessage.Event(record) => record.actor == "Synthetic startup repair"
                    case _                          => false
                  })
                  val readback = send(client, httpPort, "notifications")
                  assertEquals(readback.statusCode(), 200)
                  assert(readback.body().contains("Synthetic startup repair"))
            finally binding.stop()
          Using.resource(ServerSocket(httpPort))(_.getLocalPort).discard
          Using.resource(ServerSocket(devicePort))(_.getLocalPort).discard
          assertEquals(file.load(), Right(Some(desired)))
          assertEquals(Files.readAllBytes(path).toVector, original)
    }

  fixture.test("first-run live still rejects missing prerequisites before either listener binds") { directory =>
    val httpPort = port()
    val devicePort = port()
    val path = directory.resolve("runtime-state.json")
    val hocon = source(path, httpPort, devicePort, "live")
    val config = ConfigSource.fromConfig(hocon).loadOrThrow[Config]
    supervised:
      val clock = Clock.systemUTC()
      val failure = intercept[IllegalArgumentException](Dependencies.create(config, hocon, clock, clock.instant()))
      assert(failure.getMessage.contains("twitch.channel"))
    assert(!Files.exists(path))
    Using.resource(ServerSocket(httpPort))(_.getLocalPort).discard
    Using.resource(ServerSocket(devicePort))(_.getLocalPort).discard
  }

  fixture.test("selected live ingestion retains callback validation while saved disabled skips obsolete live prerequisites") { directory =>
    val config = ConfigSource.fromConfig(source(directory.resolve("state.json"), port(), port(), "disabled")).loadOrThrow[Config]
    val desired = RuntimeSettings.firstRun("live", "worxbend")
    val deployment = config.twitch.copy(
      clientId = "fixture",
      clientSecret = twitchscreen.relay.config.Sensitive("fixture"),
      oauth = config.twitch.oauth.copy(redirectUrl = "http://private-lan/api/v1/twitch/callback")
    )
    val _ = intercept[IllegalArgumentException](RuntimeSettingsStartup.select(deployment, SettingsSnapshot(desired, 1L, "persisted")))
    val disabled = desired.copy(monitoring = desired.monitoring.copy(mode = "disabled"))
    assertEquals(RuntimeSettingsStartup.select(deployment, SettingsSnapshot(disabled, 1L, "persisted")).config.mode, TwitchMode.Disabled)
  }
