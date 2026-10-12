package twitchscreen.relay.device

import com.typesafe.config.ConfigFactory
import java.net.ServerSocket
import java.nio.file.{Files, Path}
import java.time.Clock
import ox.{discard, supervised}
import pureconfig.ConfigSource
import scala.jdk.CollectionConverters.*
import scala.util.Using
import twitchscreen.relay.Dependencies
import twitchscreen.relay.config.{Config, TwitchMode}
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.settings.*

class RuntimeSettingsStartupSuite extends munit.FunSuite:
  private val fixture = FunFixture[Path](
    _ => Files.createTempDirectory(Path.of(sys.env.getOrElse("TMPDIR", System.getProperty("java.io.tmpdir"))), "settings-startup-"),
    directory => Using.resource(Files.walk(directory))(_.iterator().asScala.toList.reverse.foreach(Files.delete(_)))
  )
  private def port(): Int = Using.resource(ServerSocket(0))(_.getLocalPort)

  fixture.test("production assembly applies persisted monitoring and display before serving, across restarts") { directory =>
    val path = directory.resolve("runtime-state.json")
    val desired = RuntimeSettings(1, DisplaySettings(true, "cpu"), MonitoringSettings("simulated", ChannelSetting(Some("worxbend"))))
    assertEquals(RuntimeSettingsFile(path).save(desired), Right(()))
    (1 to 2).foreach: _ =>
      val source = ConfigFactory
        .parseString(s"""
        runtime.settings-file = "$path"
        twitch.mode = disabled
        device-link.port = ${port()}
        http.port = ${port()}
      """)
        .withFallback(ConfigFactory.load())
      val config = ConfigSource.fromConfig(source).loadOrThrow[Config]
      supervised:
        val clock = Clock.systemUTC()
        val runtime = Dependencies.create(config, source, clock, clock.instant())
        assertEquals(runtime.twitch.status.mode, TwitchMode.Simulated)
        assertEquals(runtime.hub.publicationSnapshot.settings.map(_.confirmed), Some(desired))
        assertEquals(runtime.hub.publicationSnapshot.settings.map(_.settingsSource), Some("persisted"))
        runtime.serve().discard
    assertEquals(RuntimeSettingsFile(path).load(), Right(Some(desired)))
  }

  fixture.test("real storage worker joins successful commits to the actual hub and skips uncertain writes") { directory =>
    val config = ConfigSource.fromConfig(ConfigFactory.load()).loadOrThrow[Config]
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(config.deviceLink, config.notifications.chat, clock, EventBus(clock, 64))
      val file = RuntimeSettingsFile(directory.resolve("runtime-state.json"))
      val worker = RuntimeSettingsWorker.start(file, RuntimeSettingsStartup.defaults(config.twitch), hub.installSettings).toOption.get
      assertEquals(hub.publicationSnapshot.settings, Some(worker.snapshot))
      val committed = worker.patch(SettingsPatch(twitchDisplayEnabled = Some(true))).toOption.get
      assertEquals(hub.publicationSnapshot.settings, Some(committed))
      assertEquals(file.load(), Right(Some(committed.confirmed)))
  }

  fixture.test("committed control keeps cumulative override and source fences in the real hub without resetting generic history") {
    directory =>
      val config = ConfigSource.fromConfig(ConfigFactory.load()).loadOrThrow[Config]
      supervised:
        val clock = Clock.systemUTC()
        val hub = DeviceHub.start(config.deviceLink, config.notifications.chat, clock, EventBus(clock, 64))
        val worker = RuntimeSettingsWorker
          .start(
            RuntimeSettingsFile(directory.resolve("runtime-state.json")),
            RuntimeSettingsStartup.defaults(config.twitch),
            hub.installSettings
          )
          .toOption
          .get
        val original = hub.publicationSnapshot
        assertEquals(original.control, Some(PublicationControl(1L, 1L, 1L, DisplaySettings(false, "generic"))))
        val generic = hub
          .publish(
            twitchscreen.relay.protocol.NotificationRequest(
              twitchscreen.relay.protocol.NotificationKind.Message,
              "Synthetic control fence",
              "retained",
              scala.concurrent.duration.DurationInt(1).second
            )
          )
          .toOption
          .get
        val retained = hub.retainedPublications
        val enabled = worker.patch(SettingsPatch(twitchDisplayEnabled = Some(true))).toOption.get
        assertEquals(hub.publicationSnapshot.control, Some(PublicationControl(2L, 1L, 2L, enabled.confirmed.display)))
        val disabled = worker.patch(SettingsPatch(twitchDisplayEnabled = Some(false))).toOption.get
        assertEquals(hub.publicationSnapshot.control, Some(PublicationControl(3L, 1L, 3L, disabled.confirmed.display)))
        val reenabled = worker.patch(SettingsPatch(twitchDisplayEnabled = Some(true))).toOption.get
        assertEquals(hub.publicationSnapshot.control, Some(PublicationControl(4L, 1L, 4L, reenabled.confirmed.display)))
        val preferred = worker.patch(SettingsPatch(preferredDashboard = Some("cpu"))).toOption.get
        assertEquals(hub.publicationSnapshot.control, Some(PublicationControl(5L, 1L, 4L, preferred.confirmed.display)))
        assertEquals(worker.patch(SettingsPatch(preferredDashboard = Some("cpu"))), Right(preferred))
        assertEquals(hub.publicationSnapshot.control.map(_.revision), Some(5L))
        val switched = worker.patch(SettingsPatch(mode = Some("simulated"), channel = Some("worxbend"))).toOption.get
        val snapshot = hub.publicationSnapshot
        assertEquals(snapshot.control, Some(PublicationControl(6L, 2L, 5L, switched.confirmed.display)))
        assertEquals(snapshot.settings, Some(switched))
        assertEquals(snapshot.relayEpoch, original.relayEpoch)
        assertEquals(snapshot.latestSeq, generic.seq)
        assertEquals(snapshot.retained, retained)
        val _ = intercept[IllegalArgumentException](hub.installSettings(switched.copy(revision = switched.revision + 2L)))
        assertEquals(hub.publicationSnapshot.control, snapshot.control)
  }

  fixture.test("invalid stored state aborts production assembly before the TCP listener binds") { directory =>
    val path = directory.resolve("runtime-state.json")
    Files.writeString(path, "{invalid-state").discard
    val devicePort = port()
    val source = ConfigFactory
      .parseString(s"""
      runtime.settings-file = "$path"
      device-link.port = $devicePort
      http.port = ${port()}
    """)
      .withFallback(ConfigFactory.load())
    val config = ConfigSource.fromConfig(source).loadOrThrow[Config]
    supervised:
      val clock = Clock.systemUTC()
      val _ = intercept[IllegalStateException](Dependencies.create(config, source, clock, clock.instant()))
      Using.resource(ServerSocket(devicePort))(_.getLocalPort).discard
    assertEquals(Files.readString(path), "{invalid-state")
  }

  test("hub rejects conflicting equal revisions, gaps and failed projections without changing authority"):
    val config = ConfigSource.fromConfig(ConfigFactory.load()).loadOrThrow[Config]
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(config.deviceLink, config.notifications.chat, clock, EventBus(clock, 64))
      val initial = SettingsSnapshot(RuntimeSettingsStartup.defaults(config.twitch), 1L, "startup")
      hub.installSettings(initial)
      hub.installSettings(initial)
      List(
        initial.copy(revision = 3L),
        initial.copy(confirmed = initial.confirmed.copy(display = DisplaySettings(true, "cpu"))),
        initial.copy(revision = 2L, projectionFailed = true)
      ).foreach: invalid =>
        val _ = intercept[IllegalArgumentException](hub.installSettings(invalid))
        assertEquals(hub.publicationSnapshot.settings, Some(initial))

  test("blank startup channel is null; saved live intent degrades while first-run live still validates"):
    val config = ConfigSource.fromConfig(ConfigFactory.load()).loadOrThrow[Config]
    val initial = RuntimeSettingsStartup.defaults(config.twitch)
    assertEquals(initial.monitoring.channel.value, None)
    val live = initial.copy(monitoring = MonitoringSettings("live", ChannelSetting(Some("worxbend"))))
    val selected = RuntimeSettingsStartup.select(config.twitch, SettingsSnapshot(live, 1L, "persisted"))
    assertEquals(selected.config.mode, TwitchMode.Disabled)
    assertEquals(selected.status.desired.mode, "live")
    assertEquals(selected.status.error, Some("configuration_missing"))
    intercept[IllegalArgumentException](RuntimeSettingsStartup.select(config.twitch, SettingsSnapshot(live, 1L, "startup")))
