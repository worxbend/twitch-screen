package twitchscreen.relay.device

import java.time.Clock
import ox.*
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.{BusEvent, EventBus, RelayEvent}
import twitchscreen.relay.config.{ChatNotifications, StatsConfig}
import twitchscreen.relay.protocol.*
import twitchscreen.relay.settings.*

class EpochResetSuite extends munit.FunSuite:
  private val stats = StatsConfig(1.hour, 1.minute)
  private val request = EventRequest.card(NotificationRequest(NotificationKind.Info, "synthetic reset", "", 1.second))
  private def nextEpoch(epoch: Long): Long = if epoch == 42L then 43L else 42L

  test("wall corrections and suspend discrepancies invalidate, but the exact two-second boundary does not"):
    var wall = 10000L
    var nanos = 0L
    val detector = ClockDiscontinuity(() => wall, () => nanos)
    wall += 3000L
    nanos += 1000000000L
    assert(!detector.sample())
    wall += 3001L
    nanos += 1000000000L
    assert(detector.sample())
    wall -= 1001L
    nanos += 1000000000L
    assert(detector.sample())
    wall += 1000L
    nanos += 1000000000L
    assert(!detector.sample())
    wall += 5000L
    assert(detector.sample())

  test("signed nanoTime wrap is ordinary elapsed time and extreme wall deltas cannot overflow"):
    var wall = 10000L
    var nanos = Long.MaxValue - 500000000L
    val detector = ClockDiscontinuity(() => wall, () => nanos)
    wall += 1000L
    nanos += 1000000000L
    assert(!detector.sample())
    wall = Long.MinValue
    assert(detector.sample())
    wall = Long.MaxValue
    assert(detector.sample())
    nanos -= 1L
    assert(detector.sample())

  test("hub reset preserves committed intent while invalidating sequence, replay, telemetry, stats and old callbacks"):
    supervised:
      val clock = Clock.systemUTC()
      val bus = EventBus(clock, 1)
      bus.subscribe("blocked-reset-observer").discard
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, bus)
      val desired =
        SettingsSnapshot(RuntimeSettings.firstRun("simulated", "synthetic").copy(display = DisplaySettings(true, "cpu")), 1L, "startup")
      hub.installSettings(desired)
      val source = SourceIngestion.start(bus, hub, stats)
      val oldFence = hub.sourceFence
      source.publish(RelayEvent.ChatMessaged("old", "synthetic", None))
      assert(hub.publish(request).isRight)
      assert(hub.publishCpu(Cpu_IN("synthetic", "host", 42)).isRight)
      assert(hub.acceptWeather(Weather_IN("synthetic", "desk", 21, "clear")).isRight)
      val before = hub.publicationSnapshot
      assert(hub.resetEpoch(before.relayEpoch, nextEpoch(before.relayEpoch)))
      val after = hub.publicationSnapshot
      assertEquals(after.latestSeq, SeqNo.Zero)
      assertEquals(after.retained, Vector.empty)
      assertEquals(after.settings, before.settings)
      assertEquals(after.control.map(_.display), before.control.map(_.display))
      assertEquals(after.control.map(value => (value.revision, value.sourceGeneration, value.twitchEpisode)), Some((1L, 1L, 1L)))
      assertEquals(after.cpu.get.status, "missing")
      assertEquals(after.weather.get.status, "missing")
      assertEquals(after.cpu.get.data, None)
      assertEquals(after.weather.get.data, None)
      assertEquals(after.stats, StreamStats.Unknown)
      assertEquals(after.statsRevision, 1L)
      source.publish(RelayEvent.ChatMessaged("late-old", "synthetic", None))
      assert(!hub.refreshSourceStats(oldFence, stats))
      assertEquals(hub.snapshot.latestSeq, SeqNo.Zero)
      val oldDeadline = Some(RelayClock(before.relayEpoch, before.sampledRelayMs + 60000))
      assertEquals(hub.publishExternal(request, oldDeadline), Left(IngressFailure.EpochChanged))
      assert(hub.acceptSource(hub.sourceFence, BusEvent(clock.instant(), RelayEvent.ChatMessaged("new", "synthetic", None)), stats))
      assertEquals(hub.latestStats.messagesTotal.value, 1)
      assertEquals(hub.retainedPublications.head.relayEpoch, after.relayEpoch)
      assertEquals(hub.retainedPublications.head.record.seq.value, 1L)
      assert(bus.subscriberStats.head.dropped > 0)

  test("obsolete, zero and equal replacement epochs cannot mutate the current domain"):
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 16))
      assert(hub.publish(request).isRight)
      val before = hub.publicationSnapshot
      assert(!hub.resetEpoch(nextEpoch(before.relayEpoch), 0L))
      intercept[IllegalArgumentException](hub.resetEpoch(before.relayEpoch, 0L)).discard
      intercept[IllegalArgumentException](hub.resetEpoch(before.relayEpoch, before.relayEpoch)).discard
      assertEquals(hub.publicationSnapshot.relayEpoch, before.relayEpoch)
      assertEquals(hub.retainedPublications, before.retained)
      assertEquals(hub.snapshot.latestSeq, before.latestSeq)

  test("actual TCP session closes on reset and late old detach cannot remove replacement connection"):
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 64))
      val listener = DeviceLinkServer.startOnPort(TestRelay.config, hub, clock, 0)
      val old = useCloseableInScope(TestDevice(listener.getLocalPort))
      old.hello("epoch-reset", 0)
      old.receiveMany(2).discard
      val connection = hub.links.head.connection
      val epoch = hub.relayClock.relayEpoch
      assert(hub.resetEpoch(epoch, nextEpoch(epoch)))
      assertEquals(old.receive(), None)
      val replacement = useCloseableInScope(TestDevice(listener.getLocalPort))
      replacement.hello("epoch-reset", 0)
      val greeting = replacement.receiveMany(2)
      assertEquals(greeting.map(_.header.typeCode.value), List(0x20, 0x22))
      assert(hub.links.head.connection != connection)
      hub.detach(connection, DisconnectReason.ListenerStopped)
      assertEquals(hub.connectedCount, 1)
      assert(hub.publish(request).isRight)
      assertEquals(replacement.receiveMessage().collect { case RelayMessage.Event(record) => record.seq.value }, Some(1L))

  test("reset clears exhausted sequence authority and does not block later durable settings handoff"):
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.startingAt(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 64), SeqNo.Max)
      val desired = SettingsSnapshot(RuntimeSettings.firstRun("disabled", "synthetic"), 1L, "startup")
      hub.installSettings(desired)
      assert(hub.publish(request).isLeft)
      val epoch = hub.relayClock.relayEpoch
      assert(hub.resetEpoch(epoch, nextEpoch(epoch)))
      hub.installSettings(desired.copy(revision = 2L, confirmed = desired.confirmed.copy(display = DisplaySettings(false, "weather"))))
      assertEquals(hub.publicationSnapshot.control.get.revision, 2L)
      assert(!hub.snapshot.sequenceExhausted)
      assertEquals(hub.publish(request).toOption.map(_.seq.value), Some(1L))
