package twitchscreen.relay.alerts

import com.typesafe.config.ConfigFactory
import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import ox.*
import pureconfig.ConfigSource
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.{BusEvent, EventBus, RelayEvent, SourceIdentity}
import twitchscreen.relay.config.{Config, TwitchMode}
import twitchscreen.relay.device.DeviceHub

class RuntimeAlertMonitorSuite extends munit.FunSuite:
  private final class TestClock extends Clock:
    var now = Instant.parse("2026-10-12T00:00:00Z")
    override def getZone: ZoneId = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant = now

  test("disabled startup retains Twitch rules for later activation and source reset retires only Twitch timers and alerts"):
    supervised:
      val clock = TestClock()
      val defaults = ConfigSource.fromConfig(ConfigFactory.load()).loadOrThrow[Config]
      val config = defaults.alerts.copy(
        noDevicesConnectedFor = Some(1.second),
        twitchDisconnectedFor = Some(1.second),
        streamOfflineFor = Some(1.second),
        errorRateThreshold = 1,
        errorRateWindow = 1.minute,
        evaluationInterval = 1.hour
      )
      val bus = EventBus(clock, 32)
      val hub = DeviceHub.start(defaults.deviceLink, defaults.notifications.chat, clock, bus)
      val monitor = RuntimeAlertMonitor.start(config, bus, hub, clock, TwitchMode.Disabled)
      val epoch = hub.relayClock.relayEpoch
      monitor.observe(BusEvent(clock.instant(), RelayEvent.RelayFailure("local", "synthetic")))
      clock.now = clock.now.plusSeconds(2)
      monitor.evaluate()
      assertEquals(monitor.store.recent(10, None, true).map(_.rule).toSet, Set("no-devices-connected", "failure-rate"))
      monitor.activate(hub.sourceFence, TwitchMode.Live)
      clock.now = clock.now.plusSeconds(2)
      monitor.evaluate()
      val old = monitor.store.recent(10, None, true).filter(_.sourceGeneration.isDefined)
      assertEquals(old.map(_.rule).toSet, Set("twitch-disconnected", "stream-offline"))
      assert(old.forall(_.relayEpoch.contains(f"$epoch%016x")))
      val independent = monitor.store.recent(10, None, true).filter(_.sourceGeneration.isEmpty).map(_.id).toSet
      assert(hub.resetEpoch(epoch, if epoch == 42L then 43L else 42L))
      monitor.activate(hub.sourceFence, TwitchMode.Live)
      monitor.evaluate()
      assertEquals(monitor.store.recent(10, None, true).map(_.id).toSet, independent)
      assert(
        old.forall(alert => monitor.store.recent(10, None, false).find(_.id == alert.id).get.status.isInstanceOf[AlertStatus.Resolved])
      )
      // An old queued source observation must not mark the new Twitch presence healthy.
      monitor.observe(BusEvent(clock.instant(), RelayEvent.TwitchLinkUp("obsolete"), Some(SourceIdentity(epoch, 1L))))
      clock.now = clock.now.plusSeconds(2)
      monitor.evaluate()
      val fresh = monitor.store.recent(10, None, true).filter(_.sourceGeneration.isDefined)
      assertEquals(fresh.map(_.rule).toSet, Set("twitch-disconnected", "stream-offline"))
      assert(fresh.forall(_.relayEpoch.contains(f"${hub.relayClock.relayEpoch}%016x")))
      monitor.activate(hub.sourceFence, TwitchMode.Disabled)
      monitor.evaluate()
      assertEquals(monitor.store.recent(10, None, true).map(_.id).toSet, independent)

  test("current source observations are fenced against hub authority and old failures cannot pollute independent failure state"):
    supervised:
      val clock = TestClock()
      val defaults = ConfigSource.fromConfig(ConfigFactory.load()).loadOrThrow[Config]
      val config = defaults.alerts.copy(
        noDevicesConnectedFor = None,
        twitchDisconnectedFor = Some(1.second),
        streamOfflineFor = Some(1.second),
        errorRateThreshold = 1,
        evaluationInterval = 1.hour
      )
      val bus = EventBus(clock, 32)
      val hub = DeviceHub.start(defaults.deviceLink, defaults.notifications.chat, clock, bus)
      val monitor = RuntimeAlertMonitor.start(config, bus, hub, clock, TwitchMode.Live)
      val fence = hub.sourceFence
      val identity = SourceIdentity(fence.relayEpoch, fence.generation)
      monitor.observe(BusEvent(clock.instant(), RelayEvent.TwitchLinkUp("current"), Some(identity)))
      monitor.observe(BusEvent(clock.instant(), RelayEvent.StreamStarted("synthetic", "title", "game", None), Some(identity)))
      clock.now = clock.now.plusSeconds(2)
      monitor.evaluate()
      assertEquals(monitor.store.activeCount, 0)
      assert(hub.resetEpoch(fence.relayEpoch, if fence.relayEpoch == 42L then 43L else 42L))
      // The hub fence commits before alert activation; queued failures must already be obsolete in this gap.
      monitor.observe(BusEvent(clock.instant(), RelayEvent.RelayFailure("old-before-activation", "synthetic"), Some(identity)))
      monitor.activate(hub.sourceFence, TwitchMode.Disabled)
      monitor.observe(BusEvent(clock.instant(), RelayEvent.RelayFailure("old", "synthetic"), Some(identity)))
      monitor.evaluate()
      assertEquals(monitor.store.activeCount, 0)
      monitor.observe(BusEvent(clock.instant(), RelayEvent.RelayFailure("application", "synthetic")))
      monitor.evaluate()
      assertEquals(monitor.store.recent(10, None, true).map(_.rule), List("failure-rate"))

  test("current source failures remain admissible and accepted independent failure history survives activation"):
    supervised:
      val clock = TestClock()
      val defaults = ConfigSource.fromConfig(ConfigFactory.load()).loadOrThrow[Config]
      val config = defaults.alerts.copy(
        noDevicesConnectedFor = None,
        twitchDisconnectedFor = None,
        streamOfflineFor = None,
        errorRateThreshold = 1,
        evaluationInterval = 1.hour
      )
      val hub = DeviceHub.start(defaults.deviceLink, defaults.notifications.chat, clock, EventBus(clock, 16))
      val monitor = new RuntimeAlertMonitor(config, hub, clock, AlertStore(config.bufferSize))
      val fence = hub.sourceFence
      monitor.activate(fence, TwitchMode.Live)
      monitor.observe(
        BusEvent(clock.instant(), RelayEvent.RelayFailure("current", "synthetic"), Some(SourceIdentity(fence.relayEpoch, fence.generation)))
      )
      monitor.evaluate()
      val accepted = monitor.store.recent(10, None, true)
      assertEquals(accepted.map(_.rule), List("failure-rate"))
      assert(hub.resetEpoch(fence.relayEpoch, if fence.relayEpoch == 42L then 43L else 42L))
      monitor.activate(hub.sourceFence, TwitchMode.Disabled)
      monitor.evaluate()
      assertEquals(monitor.store.recent(10, None, true).map(_.id), accepted.map(_.id))

  test("late lifecycle activation cannot enable Twitch alerts under a replacement authority"):
    supervised:
      val clock = TestClock()
      val defaults = ConfigSource.fromConfig(ConfigFactory.load()).loadOrThrow[Config]
      val config = defaults.alerts.copy(
        noDevicesConnectedFor = None,
        twitchDisconnectedFor = Some(1.second),
        streamOfflineFor = Some(1.second),
        errorRateThreshold = 0,
        evaluationInterval = 1.hour
      )
      val hub = DeviceHub.start(defaults.deviceLink, defaults.notifications.chat, clock, EventBus(clock, 16))
      val monitor = new RuntimeAlertMonitor(config, hub, clock, AlertStore(config.bufferSize))
      val old = hub.sourceFence
      assert(hub.resetEpoch(old.relayEpoch, if old.relayEpoch == 42L then 43L else 42L))
      monitor.activate(hub.sourceFence, TwitchMode.Disabled)
      monitor.activate(old, TwitchMode.Live)
      clock.now = clock.now.plusSeconds(2)
      monitor.evaluate()
      assertEquals(monitor.store.activeCount, 0)
      monitor.activate(hub.sourceFence, TwitchMode.Live)
      clock.now = clock.now.plusSeconds(2)
      monitor.evaluate()
      assertEquals(monitor.store.activeCount, 2)
