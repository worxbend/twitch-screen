package twitchscreen.relay.device

import com.typesafe.config.ConfigFactory
import java.time.Clock
import ox.*
import pureconfig.ConfigSource
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.Config
import twitchscreen.relay.settings.{RuntimeSettings, SettingsSnapshot}

class SourceFenceObservationSuite extends munit.FunSuite:
  test("actor-committed source fence advances reliably and remains readable after scope shutdown"):
    val defaults = ConfigSource.fromConfig(ConfigFactory.load()).loadOrThrow[Config]
    val captured = supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(defaults.deviceLink, defaults.notifications.chat, clock, EventBus(clock, 16))
      val initial = hub.sourceFence
      assert(initial.relayEpoch != 0L)
      assertEquals(initial.generation, 1L)
      val settings = RuntimeSettings.firstRun("disabled", "first")
      hub.installSettings(SettingsSnapshot(settings, 1L, "startup"))
      hub.installSettings(SettingsSnapshot(RuntimeSettings.firstRun("simulated", "second"), 2L, "persisted"))
      assertEquals(hub.sourceFence.generation, 2L)
      val epoch = if initial.relayEpoch == 42L then 43L else 42L
      assert(hub.resetEpoch(initial.relayEpoch, epoch))
      assertEquals(hub.sourceFence, SourceFence(epoch, 1L))
      (hub, hub.sourceFence)
    assertEquals(captured._1.sourceFence, captured._2)
