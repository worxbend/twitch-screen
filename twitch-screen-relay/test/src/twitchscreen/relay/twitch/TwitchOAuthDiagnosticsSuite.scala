package twitchscreen.relay.twitch

import com.typesafe.config.ConfigFactory
import java.net.{ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import java.time.Clock
import ox.*
import pureconfig.ConfigSource
import scala.jdk.CollectionConverters.*
import scala.util.Using
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.*
import twitchscreen.relay.device.{DeviceHub, NotificationRouter, SourceIngestion}
import twitchscreen.relay.protocol.DeskMessageCodec
import twitchscreen.relay.settings.*

class TwitchOAuthDiagnosticsSuite extends munit.FunSuite:
  ox.logback.InheritableMDC.init
  private val defaults = ConfigSource.fromConfig(ConfigFactory.load()).loadOrThrow[Config]
  private val clock = Clock.systemUTC()
  private val fixture = FunFixture[Path](
    _ => Files.createTempDirectory(Path.of(sys.env("TMPDIR")), "oauth-diagnostics-"),
    directory => Using.resource(Files.walk(directory))(_.iterator().asScala.toList.reverse.foreach(Files.delete(_)))
  )

  private final class Provider extends TwitchOAuthClient:
    var failRefresh = true
    val token =
      UserToken(Sensitive("synthetic-access"), Sensitive("synthetic-refresh"), clock.instant().plusSeconds(3600), Nil, "1", "owner")
    override def exchange(code: String): Either[TwitchCallFailure, UserToken] = Right(token)
    override def refresh(value: UserToken): Either[TwitchCallFailure, UserToken] =
      if failRefresh then Left(TwitchCallFailure("refresh", "SyntheticFailure", None)) else Right(token)
    override def isValid(value: UserToken): Option[Boolean] = Some(true)
    override def revoke(value: UserToken): Boolean = true

  for mode <- List("disabled", "live"); enabled <- List(false, true) do
    fixture.test(s"application OAuth diagnostics admit once across $mode/$enabled source changes and epoch reset") { directory =>
      supervised:
        val bus = EventBus(clock, 64)
        val hub = DeviceHub.start(defaults.deviceLink, defaults.notifications.chat, clock, bus)
        var settings =
          SettingsSnapshot(RuntimeSettings.firstRun(mode, "synthetic").copy(display = DisplaySettings(enabled, "cpu")), 1L, "startup")
        hub.installSettings(settings)
        NotificationRouter.start(defaults.notifications, bus, hub, sourceOwned = true)
        val observed = bus.subscribe("oauth-observer")
        val provider = Provider()
        val path = directory.resolve("token.json")
        // A directory at the token path forces the actual persistence path to fail without touching a grant.
        Files.createDirectory(path).discard
        val config = defaults.twitch.copy(
          mode = if mode == "live" then TwitchMode.Live else TwitchMode.Disabled,
          channel = "synthetic",
          clientId = "synthetic-client",
          clientSecret = Sensitive("synthetic-secret"),
          oauth = defaults.twitch.oauth.copy(tokenFile = path.toString)
        )
        val auth = TwitchAuth.create(
          config,
          provider,
          TokenFile(path),
          SourceIngestion.applicationDiagnostics(bus, hub),
          clock,
          Some(provider.token.copy(expiresAt = clock.instant()))
        )
        val owner = TwitchOAuthOwner.create(config, () => auth)
        val consent = sttp.model.Uri.unsafeParse(auth.beginAuthorization()).params.get("state").get
        val endpoints = owner.endpoints

        def refreshFailure(): Unit =
          provider.failRefresh = true
          auth.rejectAccessToken()
          auth.maintain()
        def persistenceFailure(): Unit =
          provider.failRefresh = false
          auth.maintain()
        def expectCard(before: Long): Unit =
          assertEquals(hub.snapshot.latestSeq.value, before + 1L)
          assertEquals(hub.recentNotifications(1).head.title, "twitch-oauth failed")
          assertEquals(hub.latestStats, twitchscreen.relay.protocol.StreamStats.Unknown)
          // The admission must precede optional diagnostics fan-out.
          var failureSeen = false
          for _ <- 1 to 2 if !failureSeen do
            observed.receive().event match
              case RelayEvent.RelayFailure("twitch-oauth", _, DeskMessageCodec.Origin.Twitch) => failureSeen = true
              case _: RelayEvent.NotificationPublished                                        => ()
              case other => fail(s"unexpected diagnostic observation: $other")
          assert(failureSeen)

        refreshFailure()
        expectCard(0L)
        persistenceFailure()
        expectCard(1L)
        settings = settings.copy(
          revision = 2L,
          confirmed = settings.confirmed.copy(monitoring = MonitoringSettings(mode, ChannelSetting(Some("replacement"))))
        )
        hub.installSettings(settings)
        refreshFailure()
        expectCard(2L)
        persistenceFailure()
        expectCard(3L)
        val epoch = hub.relayClock.relayEpoch
        assert(hub.resetEpoch(epoch, if epoch == 42L then 43L else 42L))
        refreshFailure()
        expectCard(0L)
        persistenceFailure()
        expectCard(1L)
        assertEquals(owner.endpoints, endpoints)
        assertEquals(auth.view.held.map(_.login), Some("owner"))
        assert(auth.completeAuthorization("synthetic-code", consent).isRight)
        expectCard(2L)
        assertEquals(hub.connectedCount, 0)
        assert(!Files.exists(directory.resolve("unrelated-token")))
    }

  for mode <- List("disabled", "live") do
    fixture.test(s"installed $mode assembly admits actual OAuth refresh/storage failures and serves canonical readback") { directory =>
      def port(): Int = Using.resource(ServerSocket(0))(_.getLocalPort)
      val httpPort = port()
      val devicePort = port()
      val tokenPath = directory.resolve("token.json")
      Files.createDirectory(tokenPath).discard
      val hocon = ConfigFactory
        .parseString(s"""
        runtime.settings-file = "${directory.resolve("state.json")}"
        twitch.mode = $mode
        twitch.channel = "synthetic"
        twitch.client-id = "synthetic-client"
        twitch.client-secret = "synthetic-secret"
        twitch.oauth.token-file = "$tokenPath"
        http.port = $httpPort
        http.allowed-hosts = ["127.0.0.1:$httpPort"]
        http.allowed-origins = ["http://127.0.0.1:$httpPort"]
        device-link.port = $devicePort
      """)
        .withFallback(ConfigFactory.load())
      val config = ConfigSource.fromConfig(hocon).loadOrThrow[Config]
      supervised:
        val provider = Provider()
        var held = Option.empty[TwitchOAuthOwner]
        val runtime = twitchscreen.relay.Dependencies.createWithOAuth(
          config,
          hocon,
          clock,
          clock.instant(),
          (selected, diagnostics, time) =>
            val auth = TwitchAuth.create(
              selected,
              provider,
              TokenFile(tokenPath),
              diagnostics,
              time,
              Some(provider.token.copy(expiresAt = time.instant()))
            )
            val owner = TwitchOAuthOwner.create(selected, () => auth)
            held = Some(owner)
            owner
        )
        // Bind real production routes without starting a live provider connection.
        val binding = runtime.httpApi.start()
        try
          val owner = held.get
          val auth = owner.auth.get
          val consent = sttp.model.Uri.unsafeParse(auth.beginAuthorization()).params.get("state").get
          Using.resource(HttpClient.newHttpClient()): client =>
            def get(path: String): HttpResponse[String] =
              client.send(
                HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$httpPort/api/v1/$path")).build(),
                HttpResponse.BodyHandlers.ofString()
              )
            assertEquals(get("twitch/authorization").statusCode(), 200)
            auth.maintain()
            assertEquals(runtime.hub.snapshot.latestSeq.value, 1L)
            val first = get("notifications")
            assertEquals(first.statusCode(), 200)
            assert(first.body().contains("\"origin\":\"twitch\""), first.body())
            assert(first.body().contains("\"sourceGeneration\":1"), first.body())
            assert(first.body().contains("twitch-oauth failed"), first.body())
            val changed = SettingsSnapshot(
              RuntimeSettings.firstRun(mode, "replacement").copy(display = DisplaySettings(true, "weather")),
              2L,
              "startup"
            )
            runtime.hub.installSettings(changed)
            provider.failRefresh = false
            auth.maintain()
            assertEquals(runtime.hub.snapshot.latestSeq.value, 2L)
            val switched = get("notifications")
            assert(switched.body().contains("\"sourceGeneration\":2"), switched.body())
            assert(switched.body().contains("\"twitchEpisode\":2"), switched.body())
            val before = runtime.hub.relayClock.relayEpoch
            assert(runtime.hub.resetEpoch(before, if before == 42L then 43L else 42L))
            assert(auth.completeAuthorization("synthetic-code", consent).isRight)
            assertEquals(runtime.hub.snapshot.latestSeq.value, 1L)
            val after = get("notifications")
            assert(after.body().contains(s"\"relayEpoch\":\"${runtime.hub.relayClock.epochHex}\""), after.body())
            assert(!after.body().contains(f"$before%016x"), after.body())
            assertEquals(get("twitch/authorization").statusCode(), 200)
            val external = HttpRequest
              .newBuilder(URI.create(s"http://127.0.0.1:$httpPort/api/v1/notifications"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString("""{"type":"info","title":"Synthetic independent card","body":""}"""))
              .build()
            assertEquals(client.send(external, HttpResponse.BodyHandlers.ofString()).statusCode(), 200)
            assertEquals(runtime.hub.snapshot.latestSeq.value, 2L)
            assertEquals(runtime.hub.latestStats, twitchscreen.relay.protocol.StreamStats.Unknown)
            assertEquals(get("cpu").statusCode(), 200)
            assertEquals(get("weather").statusCode(), 200)
            assertEquals(get("display").statusCode(), 200)
        finally binding.stop()
      Using.resource(ServerSocket(httpPort))(_.getLocalPort).discard
      Using.resource(ServerSocket(devicePort))(_.getLocalPort).discard
    }
