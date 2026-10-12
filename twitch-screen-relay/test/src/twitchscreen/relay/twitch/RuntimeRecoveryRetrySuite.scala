package twitchscreen.relay.twitch

import com.typesafe.config.ConfigFactory
import java.time.Clock
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import ox.*
import pureconfig.ConfigSource
import scala.concurrent.duration.DurationInt
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.{Config, TwitchConfig, TwitchMode}
import twitchscreen.relay.device.{ClockDiscontinuity, DeviceHub}

class RuntimeRecoveryRetrySuite extends munit.FunSuite:
  ox.logback.InheritableMDC.init
  private val defaults = ConfigSource.fromConfig(ConfigFactory.load()).loadOrThrow[Config]
  private val clock = Clock.systemUTC()
  private def fake(config: TwitchConfig): TwitchSource = new TwitchSource:
    override val status = TwitchStatus(config.mode, TwitchHealth.Connected, config.channel, "synthetic")
    override val endpoints: List[ServerEndpoint[Any, Identity]] = Nil

  private def await(check: => Boolean): Unit = timeout(8.seconds):
    repeatUntil:
      check || { sleep(10.millis); false }

  test("scheduled recovery retries a fail-once constructor without rotating the recovered epoch again"):
    supervised:
      val bus = EventBus(clock, 16)
      val hub = DeviceHub.start(defaults.deviceLink, defaults.notifications.chat, clock, bus)
      val creates = AtomicInteger()
      val source = RuntimeSource.startWith(
        defaults.twitch,
        hub,
        selected => if creates.incrementAndGet() == 2 then throw IllegalStateException("synthetic once") else fake(selected)
      )
      val correction = AtomicLong()
      RuntimeRecovery(hub, source, ClockDiscontinuity(() => System.currentTimeMillis() + correction.get())).start()
      val old = hub.relayClock.relayEpoch
      correction.set(10000L)
      await(creates.get() >= 2)
      val recovered = hub.relayClock.relayEpoch
      assert(recovered != old)
      await(creates.get() == 3 && source.status.health == TwitchHealth.Connected)
      assertEquals(hub.relayClock.relayEpoch, recovered)

  test("scheduled recovery waits for late cleanup then starts exactly one replacement automatically"):
    supervised:
      val bus = EventBus(clock, 16)
      val hub = DeviceHub.start(defaults.deviceLink, defaults.notifications.chat, clock, bus)
      val creates = AtomicInteger()
      val closing = CountDownLatch(1)
      val release = CountDownLatch(1)
      def create(config: TwitchConfig)(using Ox): TwitchSource =
        if creates.incrementAndGet() == 1 then
          useInScope(()): _ =>
            closing.countDown()
            var complete = false
            while !complete do
              try
                release.await()
                complete = true
              catch case _: InterruptedException => ()
        fake(config)
      val source = RuntimeSource.startWith(defaults.twitch, hub, selected => create(selected), teardownBudget = 100.millis)
      val correction = AtomicLong()
      RuntimeRecovery(hub, source, ClockDiscontinuity(() => System.currentTimeMillis() + correction.get())).start()
      try
        correction.set(10000L)
        assert(closing.await(3, TimeUnit.SECONDS))
        await(source.status.detail == "source_teardown_timeout")
        assertEquals(creates.get(), 1)
        val recovered = hub.relayClock.relayEpoch
        release.countDown()
        await(creates.get() == 2 && source.status.health == TwitchHealth.Connected)
        assertEquals(hub.relayClock.relayEpoch, recovered)
      finally release.countDown()

  test("unexpected source-scope exit triggers recovery without a clock correction"):
    supervised:
      val bus = EventBus(clock, 16)
      val hub = DeviceHub.start(defaults.deviceLink, defaults.notifications.chat, clock, bus)
      val fail = CountDownLatch(1)
      val creates = AtomicInteger()
      def create(config: TwitchConfig)(using Ox): TwitchSource =
        if creates.incrementAndGet() == 1 then
          forkDiscard:
            fail.await()
            throw IllegalStateException("synthetic child failure")
        fake(config)
      val source = RuntimeSource.startWith(defaults.twitch, hub, selected => create(selected))
      RuntimeRecovery(hub, source, ClockDiscontinuity(() => System.currentTimeMillis())).start()
      val epoch = hub.relayClock.relayEpoch
      fail.countDown()
      await(creates.get() == 2 && source.status.health == TwitchHealth.Connected)
      assertEquals(hub.relayClock.relayEpoch, epoch)

  test("a newer desired source supersedes a failed retry and never rebuilds the retired configuration"):
    supervised:
      val bus = EventBus(clock, 16)
      val hub = DeviceHub.start(defaults.deviceLink, defaults.notifications.chat, clock, bus)
      val creates = AtomicInteger()
      val source = RuntimeSource.startWith(
        defaults.twitch.copy(channel = "old"),
        hub,
        selected => if creates.incrementAndGet() == 2 then throw IllegalStateException("synthetic once") else fake(selected)
      )
      val correction = AtomicLong()
      RuntimeRecovery(hub, source, ClockDiscontinuity(() => System.currentTimeMillis() + correction.get())).start()
      correction.set(10000L)
      await(source.status.detail == "source_application_failed")
      assertEquals(source.replace(defaults.twitch.copy(mode = TwitchMode.Simulated, channel = "new")), Right(()))
      sleep(1500.millis)
      assertEquals(creates.get(), 3)
      assertEquals(source.status.channel, "new")

  test("repeated construction failures keep bounded backoff and eventually recover in the same epoch"):
    supervised:
      val bus = EventBus(clock, 16)
      val hub = DeviceHub.start(defaults.deviceLink, defaults.notifications.chat, clock, bus)
      val creates = AtomicInteger()
      val times = new java.util.concurrent.CopyOnWriteArrayList[Long]()
      val source = RuntimeSource.startWith(
        defaults.twitch,
        hub,
        selected =>
          val count = creates.incrementAndGet()
          times.add(System.nanoTime()).discard
          if count == 2 || count == 3 then throw IllegalStateException("synthetic transient") else fake(selected)
      )
      val correction = AtomicLong()
      RuntimeRecovery(hub, source, ClockDiscontinuity(() => System.currentTimeMillis() + correction.get())).start()
      correction.set(10000L)
      await(creates.get() >= 2)
      val epoch = hub.relayClock.relayEpoch
      await(creates.get() == 4 && source.status.health == TwitchHealth.Connected)
      assert(times.get(2) - times.get(1) >= 1.second.toNanos)
      assert(times.get(3) - times.get(2) >= 2.seconds.toNanos)
      assertEquals(hub.relayClock.relayEpoch, epoch)

  test("a new discontinuity supersedes recovery backoff and old admitted callbacks cannot enter the latest epoch"):
    supervised:
      val bus = EventBus(clock, 16)
      val hub = DeviceHub.start(defaults.deviceLink, defaults.notifications.chat, clock, bus)
      val creates = AtomicInteger()
      val source = RuntimeSource.startWith(
        defaults.twitch,
        hub,
        selected => if creates.incrementAndGet() == 2 then throw IllegalStateException("synthetic once") else fake(selected)
      )
      val correction = AtomicLong()
      RuntimeRecovery(hub, source, ClockDiscontinuity(() => System.currentTimeMillis() + correction.get())).start()
      correction.set(10000L)
      await(creates.get() >= 2)
      val intermediate = hub.relayClock.relayEpoch
      val obsolete = twitchscreen.relay.device.SourceIngestion.start(bus, hub, defaults.stats)
      correction.set(20000L)
      await(hub.relayClock.relayEpoch != intermediate)
      await(source.status.health == TwitchHealth.Connected)
      assert(creates.get() >= 3)
      obsolete.publish(twitchscreen.relay.bus.RelayEvent.Followed("obsolete"))
      assertEquals(hub.snapshot.latestSeq.value, 0L)

  test("reset racing provider construction cannot relabel an obsolete producer with replacement authority"):
    supervised:
      val bus = EventBus(clock, 16)
      val hub = DeviceHub.start(defaults.deviceLink, defaults.notifications.chat, clock, bus)
      val creates = AtomicInteger()
      val source = RuntimeSource.startFencedWith(
        defaults.twitch,
        hub,
        (selected, fence) =>
          if creates.incrementAndGet() == 2 then
            val epoch = hub.relayClock.relayEpoch
            assert(hub.resetEpoch(epoch, if epoch == 42L then 43L else 42L))
            val obsolete = twitchscreen.relay.device.SourceIngestion.startAtFence(bus, hub, defaults.stats, fence)
            obsolete.publish(twitchscreen.relay.bus.RelayEvent.Followed("obsolete-constructor"))
          fake(selected)
      )
      val correction = AtomicLong()
      RuntimeRecovery(hub, source, ClockDiscontinuity(() => System.currentTimeMillis() + correction.get())).start()
      correction.set(10000L)
      await(creates.get() == 3 && source.status.health == TwitchHealth.Connected)
      assertEquals(hub.snapshot.latestSeq.value, 0L)

  test("a bounded application timeout cancels construction and automatically retries after confirmed cleanup"):
    supervised:
      val bus = EventBus(clock, 16)
      val hub = DeviceHub.start(defaults.deviceLink, defaults.notifications.chat, clock, bus)
      val creates = AtomicInteger()
      val source = RuntimeSource.startWith(
        defaults.twitch,
        hub,
        selected =>
          if creates.incrementAndGet() == 2 then sleep(1.second)
          fake(selected)
        ,
        applicationBudget = 100.millis
      )
      val correction = AtomicLong()
      RuntimeRecovery(hub, source, ClockDiscontinuity(() => System.currentTimeMillis() + correction.get())).start()
      correction.set(10000L)
      await(source.status.detail == "source_application_timeout")
      val epoch = hub.relayClock.relayEpoch
      await(creates.get() == 3 && source.status.health == TwitchHealth.Connected)
      assertEquals(hub.relayClock.relayEpoch, epoch)
