package twitchscreen.relay.twitch

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.typesafe.config.ConfigFactory
import java.nio.file.{Files, Path}
import java.time.Clock
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import ox.*
import pureconfig.ConfigSource
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.util.Using
import sttp.client4.*
import sttp.model.StatusCode
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.stub4.TapirSyncStubInterpreter
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.*
import twitchscreen.relay.device.{ClockDiscontinuity, DeviceHub, DisplayApi}
import twitchscreen.relay.settings.*

class MonitoringApiSuite extends munit.FunSuite:
  private val defaults = ConfigSource.fromConfig(ConfigFactory.load()).loadOrThrow[Config]
  private val fixture = FunFixture[Path](
    _ => Files.createTempDirectory(Path.of(sys.env.getOrElse("TMPDIR", System.getProperty("java.io.tmpdir"))), "relay-monitoring-"),
    directory => Using.resource(Files.walk(directory))(_.iterator().asScala.toList.reverse.foreach(Files.delete(_)))
  )
  private def fake(config: TwitchConfig): TwitchSource = new TwitchSource:
    override val status = TwitchStatus(config.mode, TwitchHealth.Connected, config.channel, "synthetic")
    override val endpoints: List[ServerEndpoint[Any, Identity]] = Nil

  private def withController(
      directory: Path,
      grant: Option[UserToken] = None,
      app: Boolean = true,
      resolver: Option[String => Either[String, ResolvedChannel_OUT]] = None
  )(
      run: (MonitoringController, RuntimeSettingsWorker, DeviceHub, RuntimeSource, AtomicInteger, SyncBackend) => Ox ?=> Unit
  ): Unit = supervised:
    val clock = Clock.systemUTC()
    val bus = EventBus(clock, 32)
    val hub = DeviceHub.start(defaults.deviceLink, defaults.notifications.chat, clock, bus)
    val config = defaults.twitch.copy(clientId = if app then "synthetic" else "", clientSecret = Sensitive(if app then "synthetic" else ""))
    val settings = RuntimeSettingsWorker
      .start(RuntimeSettingsFile(directory.resolve("state.json")), RuntimeSettingsStartup.defaults(config), hub.installSettings)
      .toOption
      .get
    val source = RuntimeSource.startWith(config, hub, fake)
    val provider = new TwitchOAuthClient:
      override def exchange(code: String): Either[TwitchCallFailure, UserToken] = throw IllegalStateException("unused")
      override def refresh(token: UserToken): Either[TwitchCallFailure, UserToken] = throw IllegalStateException("unused")
      override def isValid(token: UserToken): Option[Boolean] = Some(true)
      override def revoke(token: UserToken): Boolean = false
    val oauth = TwitchOAuthOwner.create(
      config,
      () => TwitchAuth.create(config, provider, TokenFile(directory.resolve("token.json")), bus, clock, grant)
    )
    val lookups = AtomicInteger()
    val controller = MonitoringController(
      config,
      settings,
      hub,
      source,
      oauth,
      login =>
        lookups.incrementAndGet().discard
        resolver.fold[Either[String, ResolvedChannel_OUT]](
          if login == "missing" then Left("channel_not_found") else Right(ResolvedChannel_OUT("42", login, "Synthetic"))
        )(_(login))
    )
    val backend = TapirSyncStubInterpreter()
      .whenServerEndpointsRunLogic(MonitoringApi(controller).endpoints ++ DisplayApi(hub, settings).endpoints)
      .backend()
    run(controller, settings, hub, source, lookups, backend)

  private def patch(backend: SyncBackend, body: String) =
    basicRequest.patch(uri"http://localhost/api/v1/twitch/monitoring").contentType("application/json").body(body).send(backend)

  fixture.test("admin consumer capture holds monitoring acceptance until the local hub join finishes") { directory =>
    withController(directory): (controller, _, hub, _, _, _) =>
      val entered = CountDownLatch(1)
      val patchStarted = CountDownLatch(1)
      val outcome = new java.util.concurrent.atomic.AtomicReference[Boolean](false)
      val changed = Thread
        .ofVirtual()
        .start(() =>
          if entered.await(2, TimeUnit.SECONDS) then
            patchStarted.countDown()
            outcome.set(controller.patch(Monitoring_IN(Some("simulated"), Some("next"))).isRight)
        )
      try
        val captured = controller.captureWithin: selected =>
          entered.countDown()
          assert(patchStarted.await(2, TimeUnit.SECONDS))
          val observation = hub.latestObservation.get
          (selected, observation.publication.control.get.sourceGeneration)
        val joined = captured.toOption.get
        assertEquals(joined._1.sourceGeneration, joined._2)
        assertEquals(joined._2, 1L)
        changed.join(3000L)
        assert(!changed.isAlive)
        assert(outcome.get())
        val latest = controller.captureWithin(selected =>
          (selected.sourceGeneration, hub.latestObservation.get.publication.control.get.sourceGeneration)
        )
        assertEquals(latest, Right((2L, 2L)))
      finally
        entered.countDown()
        changed.interrupt()
        changed.join(3000L)
  }

  fixture.test("admin capture cannot join a queued pre-switch observation as the new channel") { directory =>
    withController(directory): (controller, _, hub, _, _, _) =>
      val previous = hub.latestObservation.get
      assert(controller.patch(Monitoring_IN(Some("simulated"), Some("next"))).isRight)
      val stale = controller.captureWithin(selected => twitchscreen.relay.device.AdminSnapshot.project(previous, selected))
      assert(stale.toOption.get.isLeft)
      val current =
        controller.captureWithin(selected => twitchscreen.relay.device.AdminSnapshot.project(hub.latestObservation.get, selected))
      val snapshot = current.toOption.get.toOption.get
      assertEquals(snapshot.monitoring.sourceGeneration, 2L)
      assertEquals(snapshot.monitoring.desired.channel, Some("next"))
      assertEquals(snapshot.charts.sourceGeneration, 2L)
  }

  fixture.test("slow live resolution never holds display, reads or disable; excess lookup fails without queued work") { directory =>
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    withController(
      directory,
      resolver = Some(login =>
        entered.countDown()
        if !release.await(3, TimeUnit.SECONDS) then throw IllegalStateException("synthetic lookup release timeout")
        Right(ResolvedChannel_OUT("42", login, "Synthetic"))
      )
    ): (controller, settings, _, _, lookups, _) =>
      val slow = fork(controller.patch(Monitoring_IN(Some("live"), Some("synthetic"))))
      try
        assert(entered.await(1, TimeUnit.SECONDS))
        val stage = AtomicInteger()
        val independent = timeoutOption(2.seconds):
          assertEquals(controller.read().desired.mode, "disabled")
          stage.set(1)
          assert(settings.patch(SettingsPatch(preferredDashboard = Some("cpu"))).isRight)
          stage.set(2)
          assert(controller.patch(Monitoring_IN(Some("disabled"), Some("newer"))).isRight)
          stage.set(3)
          assert(controller.patch(Monitoring_IN(Some("live"), Some("second"))).isLeft)
          stage.set(4)
          true
        assertEquals(independent, Some(true), s"last completed independent stage: ${stage.get()}")
        assertEquals(lookups.get(), 1)
      finally release.countDown()
      assert(slow.join().isRight)
      assertEquals(settings.snapshot.confirmed.display.preferredDashboard, "cpu")
  }

  fixture.test("partial live resolution is revalidated against merged channel before commit") { directory =>
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    withController(
      directory,
      resolver = Some(login =>
        entered.countDown()
        if !release.await(3, TimeUnit.SECONDS) then throw IllegalStateException("synthetic lookup release timeout")
        Right(ResolvedChannel_OUT("42", login, "Synthetic"))
      )
    ): (_, settings, _, _, _, backend) =>
      assert(settings.patch(SettingsPatch(channel = Some("first"))).isRight)
      val slow = fork(patch(backend, """{"mode":"live"}"""))
      try
        assert(entered.await(1, TimeUnit.SECONDS))
        assertEquals(patch(backend, """{"channel":"second"}""").code, StatusCode.Ok)
      finally release.countDown()
      assertEquals(slow.join().code, StatusCode.ServiceUnavailable)
      assertEquals(settings.snapshot.confirmed.monitoring, MonitoringSettings("disabled", ChannelSetting(Some("second"))))
  }

  fixture.test("startup lookup stays outside settings and cannot restore resolution after monitoring supersession") { directory =>
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    withController(
      directory,
      resolver = Some(login =>
        entered.countDown()
        if !release.await(3, TimeUnit.SECONDS) then throw IllegalStateException("synthetic lookup release timeout")
        Right(ResolvedChannel_OUT("42", login, "Synthetic"))
      )
    ): (controller, settings, _, _, lookups, _) =>
      assert(settings.patch(SettingsPatch(mode = Some("live"), channel = Some("first"))).isRight)
      controller.start()
      try
        assert(entered.await(1, TimeUnit.SECONDS))
        val independent = timeoutOption(2.seconds):
          val display = settings.patch(SettingsPatch(preferredDashboard = Some("weather")))
          val disabled = controller.patch(Monitoring_IN(mode = Some("disabled")))
          val read = controller.readWithin()
          (display.isRight, disabled.isRight, read.map(_.desired.mode))
        assertEquals(independent, Some((true, true, Right("disabled"))))
        assertEquals(lookups.get(), 1)
      finally release.countDown()
      assertEquals(controller.read().resolvedChannel, None)
      assertEquals(controller.read().desired.mode, "disabled")
  }

  fixture.test("mounted canonical monitoring persists partial intent, resets fences, preserves display and applies asynchronously") {
    directory =>
      withController(directory): (controller, settings, hub, source, lookups, backend) =>
        val before = controller.read()
        assertEquals((before.desired.mode, before.transition, before.revision), ("disabled", "ready", 1L))
        assert(writeToString(before).contains("\"channel\":null"))
        settings.patch(SettingsPatch(twitchDisplayEnabled = Some(true), preferredDashboard = Some("cpu"))).discard
        val response = patch(backend, """{"mode":"simulated","channel":"Worxbend"}""")
        assertEquals(response.code, StatusCode.Ok)
        val accepted = readFromString[Monitoring_OUT](response.body.fold(identity, identity))
        assertEquals(accepted.desired, MonitoringIntent_OUT("simulated", Some("worxbend")))
        assertEquals((accepted.sourceGeneration, accepted.transition), (2L, "applying"))
        assertEquals(hub.sourceFence.generation, 2L)
        assertEquals(lookups.get(), 0)
        assertEquals(settings.snapshot.confirmed.display, DisplaySettings(true, "cpu"))
        assertEquals(RuntimeSettingsFile(directory.resolve("state.json")).load(), Right(Some(settings.snapshot.confirmed)))
        assertEquals(source.recover(), Right(()))
        assertEquals(controller.read().effective, MonitoringIntent_OUT("simulated", Some("worxbend")))
        assertEquals(controller.read().transition, "ready")
        val revision = settings.snapshot.revision
        assertEquals(patch(backend, """{"channel":"worxbend"}""").code, StatusCode.Ok)
        assertEquals(settings.snapshot.revision, revision)
        assertEquals(controller.read().sourceGeneration, 2L)
        assertEquals(lookups.get(), 0)
  }

  fixture.test("strict malformed updates and missing app config preserve settings, source and storage") { directory =>
    withController(directory, app = false): (controller, settings, _, _, lookups, backend) =>
      val before = controller.read()
      List(
        "{}",
        "null",
        """{"mode":null}""",
        """{"channel":null}""",
        """{"mode":"LIVE"}""",
        """{"channel":"https://twitch.tv/foo"}""",
        """{"channel":""}""",
        """{"unknown":1}""",
        """{"mode":"disabled","mode":"live"}""",
        """{"channel":"\ud800"}"""
      ).foreach: body =>
        assertEquals(patch(backend, body).code, StatusCode.BadRequest, body)
      val rejected = patch(backend, """{"mode":"live","channel":"synthetic"}""")
      assertEquals(rejected.code, StatusCode.Conflict)
      assert(rejected.body.fold(identity, identity).contains("configuration_missing"))
      assertEquals(controller.read(), before)
      assertEquals(settings.snapshot.revision, 1L)
      assertEquals(lookups.get(), 0)
      assert(!Files.exists(directory.resolve("state.json")))
  }

  fixture.test("live lookup occurs before commit; missing grant does not block public monitoring") { directory =>
    withController(directory): (controller, settings, hub, source, lookups, backend) =>
      val before = controller.read()
      assertEquals(patch(backend, """{"mode":"live","channel":"missing"}""").code, StatusCode.ServiceUnavailable)
      assertEquals(controller.read(), before)
      assertEquals(settings.snapshot.revision, 1L)
      assertEquals(patch(backend, """{"mode":"live","channel":"synthetic"}""").code, StatusCode.Ok)
      val value = controller.read()
      assertEquals(value.resolvedChannel.map(_.id), Some("42"))
      assertEquals(value.grantCompatibility.status, "missing")
      assert(value.grantCompatibility.features.find(_.feature == "public_stream").get.eligible)
      assert(!value.grantCompatibility.features.find(_.feature == "followers").get.eligible)
      assertEquals(source.recover(), Right(()))
      assertEquals(controller.read().effective.mode, "live")
      assertEquals(hub.sourceFence.generation, 2L)
      assertEquals(patch(backend, """{"mode":"live","channel":"synthetic"}""").code, StatusCode.Ok)
      assertEquals(lookups.get(), 2)
  }

  fixture.test("resolved identity not channel text determines grant compatibility; wrong grant is retained") { directory =>
    val grant = UserToken(
      Sensitive("synthetic"),
      Sensitive("synthetic"),
      Clock.systemUTC().instant().plusSeconds(3600),
      TwitchScopes.Default,
      "99",
      "synthetic"
    )
    withController(directory, Some(grant)): (controller, _, _, _, _, backend) =>
      assertEquals(patch(backend, """{"mode":"live","channel":"synthetic"}""").code, StatusCode.Ok)
      assertEquals(controller.read().grantCompatibility.status, "mismatched")
      assert(!controller.read().grantCompatibility.features.find(_.feature == "followers").get.eligible)
      assert(!Files.exists(directory.resolve("token.json")))
  }

  fixture.test("concurrent partial monitoring and display commits do not lose omitted fields") { directory =>
    withController(directory): (controller, settings, _, source, _, backend) =>
      val first = fork(patch(backend, """{"mode":"simulated"}"""))
      val second = fork(patch(backend, """{"channel":"other"}"""))
      val display = fork(settings.patch(SettingsPatch(preferredDashboard = Some("weather"))))
      assertEquals(first.join().code, StatusCode.Ok)
      assertEquals(second.join().code, StatusCode.Ok)
      assert(display.join().isRight)
      assertEquals(settings.snapshot.confirmed.monitoring, MonitoringSettings("simulated", ChannelSetting(Some("other"))))
      assertEquals(settings.snapshot.confirmed.display.preferredDashboard, "weather")
      assertEquals(source.recover(), Right(()))
      assertEquals(controller.read().effective, MonitoringIntent_OUT("simulated", Some("other")))
  }

  fixture.test("accepted request does not wait for blocked cleanup; scheduled application consumes latest desired config") { directory =>
    supervised:
      val clock = Clock.systemUTC()
      val bus = EventBus(clock, 32)
      val hub = DeviceHub.start(defaults.deviceLink, defaults.notifications.chat, clock, bus)
      val settings = RuntimeSettingsWorker
        .start(RuntimeSettingsFile(directory.resolve("state.json")), RuntimeSettingsStartup.defaults(defaults.twitch), hub.installSettings)
        .toOption
        .get
      val release = CountDownLatch(1)
      val closing = CountDownLatch(1)
      val ready = CountDownLatch(1)
      val count = AtomicInteger()
      val source = RuntimeSource.startWith(
        defaults.twitch,
        hub,
        config =>
          if count.incrementAndGet() == 1 then
            useInScope(()): _ =>
              closing.countDown()
              var stopped = false
              while !stopped do
                try
                  release.await()
                  stopped = true
                catch case _: InterruptedException => ()
          else if config.channel == "latest" then ready.countDown()
          fake(config)
        ,
        teardownBudget = 100.millis
      )
      val controller = MonitoringController(defaults.twitch, settings, hub, source, new TwitchOAuthOwner(None), _ => Left("unused"))
      RuntimeRecovery(hub, source, ClockDiscontinuity(() => clock.millis())).start()
      try
        assert(controller.patch(Monitoring_IN(Some("simulated"), Some("first"))).isRight)
        assert(closing.await(2, TimeUnit.SECONDS))
        val at = System.nanoTime()
        assert(controller.patch(Monitoring_IN(channel = Some("latest"))).isRight)
        assert(System.nanoTime() - at < 500.millis.toNanos)
        assertEquals(count.get(), 1)
        assertEquals(controller.read().desired.channel, Some("latest"))
        release.countDown()
        assert(ready.await(4, TimeUnit.SECONDS))
        val eventual = timeoutOption(4.seconds):
          while controller.read().effective.channel != Some("latest") do sleep(10.millis)
          true
        assertEquals(eventual, Some(true))
        assertEquals(controller.read().effective.channel, Some("latest"))
      finally release.countDown()
  }

  fixture.test("recovery in commit-to-request gap cannot assign new authority to the obsolete configuration") { directory =>
    withController(directory): (_, settings, hub, source, _, _) =>
      val before = hub.sourceFence
      assert(settings.patch(SettingsPatch(mode = Some("simulated"), channel = Some("next"))).isRight)
      assertEquals(hub.sourceFence.generation, before.generation + 1L)
      assertEquals(source.recover(), Left("source_superseded"))
      assertEquals(source.applied(hub.sourceFence), None)
      source.request(
        defaults.twitch.copy(clientId = "synthetic", clientSecret = Sensitive("synthetic"), mode = TwitchMode.Simulated, channel = "next")
      )
      assertEquals(source.recover(), Right(()))
      assertEquals(source.applied(hub.sourceFence).map(_.channel), Some("next"))
  }

  fixture.test("saved live resolution is one bounded startup action; equivalent unresolved PATCH does not rebuild source") { directory =>
    withController(directory): (_, settings, hub, source, lookups, _) =>
      assert(settings.patch(SettingsPatch(mode = Some("live"), channel = Some("synthetic"))).isRight)
      val selected =
        defaults.twitch.copy(clientId = "synthetic", clientSecret = Sensitive("synthetic"), mode = TwitchMode.Live, channel = "synthetic")
      source.request(selected)
      assertEquals(source.recover(), Right(()))
      val controller = MonitoringController(
        selected,
        settings,
        hub,
        source,
        new TwitchOAuthOwner(None),
        login =>
          lookups.incrementAndGet().discard
          Right(ResolvedChannel_OUT("42", login, "Synthetic"))
      )
      assertEquals(controller.read().transition, "applying")
      controller.start()
      controller.start()
      val eventual = timeoutOption(2.seconds):
        while controller.read().resolvedChannel.isEmpty do sleep(10.millis)
        true
      assertEquals(eventual, Some(true))
      assertEquals(controller.read().transition, "ready")
      assertEquals(lookups.get(), 1)
      val revision = settings.snapshot.revision
      assert(controller.patch(Monitoring_IN(channel = Some("synthetic"))).isRight)
      assertEquals(settings.snapshot.revision, revision)
      assert(source.applied(hub.sourceFence).isDefined)
      assertEquals(lookups.get(), 1)
  }
