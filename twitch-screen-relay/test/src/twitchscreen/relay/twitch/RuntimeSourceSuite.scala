package twitchscreen.relay.twitch

import com.typesafe.config.ConfigFactory
import java.time.Clock
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import ox.*
import pureconfig.ConfigSource
import scala.concurrent.duration.DurationInt
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.*
import twitchscreen.relay.device.{DeviceHub, SourceIngestion}
import twitchscreen.relay.http.Fail
import twitchscreen.relay.protocol.NotificationRequest

class RuntimeSourceSuite extends munit.FunSuite:
  private val config = ConfigSource.fromConfig(ConfigFactory.load()).loadOrThrow[Config]
  private val clock = Clock.systemUTC()
  private def withHub(body: (DeviceHub, EventBus) => Ox ?=> Unit): Unit = supervised:
    val bus = EventBus(clock, 32)
    val hub = DeviceHub.start(config.deviceLink, config.notifications.chat, clock, bus)
    body(hub, bus)

  private def fake(selected: TwitchConfig, callback: String = "current"): TwitchSource = new TwitchSource:
    override val status = TwitchStatus(selected.mode, TwitchHealth.Connected, selected.channel, callback)
    override val endpoints: List[ServerEndpoint[Any, Identity]] = Nil
    override val webhookCallback: Option[(String, String, String, String, String) => Either[Fail, String]] =
      Some((_, _, _, _, _) => Right(callback))

  test("source scopes join cleanup before a replacement starts and old admitted callbacks remain fenced"):
    withHub: (hub, bus) =>
      val created = AtomicInteger()
      val closed = AtomicInteger()
      var oldBus: Option[EventBus] = None
      def create(selected: TwitchConfig)(using Ox): TwitchSource =
        val number = created.incrementAndGet()
        assertEquals(closed.get(), number - 1)
        val admitted = SourceIngestion.start(bus, hub, config.stats)
        if number == 1 then oldBus = Some(admitted)
        useInScope(())(_ => closed.incrementAndGet().discard)
        admitted.publish(RelayEvent.ChatMessaged("synthetic", "current", None))
        fake(selected, s"owner-$number")
      val owner = RuntimeSource.startWith(config.twitch, hub, selected => create(selected))
      val before = hub.relayClock.relayEpoch
      assertEquals(owner.reset(before, if before == 42L then 43L else 42L), Right(true))
      assertEquals(created.get(), 2)
      assertEquals(closed.get(), 1)
      val sequence = hub.snapshot.latestSeq
      oldBus.get.publish(RelayEvent.ChatMessaged("obsolete", "late", None))
      assertEquals(hub.snapshot.latestSeq, sequence)
      assertEquals(owner.status.detail, "owner-2")
      assertEquals(owner.reset(before, 99L), Right(false))
      assertEquals(created.get(), 2)

  test("teardown timeout is bounded and never creates a competing source; generic admission remains available"):
    withHub: (hub, _) =>
      val closing = CountDownLatch(1)
      val release = CountDownLatch(1)
      val creates = AtomicInteger()
      def create(selected: TwitchConfig)(using Ox): TwitchSource =
        creates.incrementAndGet().discard
        useInScope(()): _ =>
          closing.countDown()
          var complete = false
          while !complete do
            try
              release.await()
              complete = true
            catch case _: InterruptedException => ()
        fake(selected)
      val owner = RuntimeSource.startWith(config.twitch, hub, selected => create(selected), teardownBudget = 100.millis)
      try
        val epoch = hub.relayClock.relayEpoch
        val started = System.nanoTime()
        assertEquals(owner.reset(epoch, if epoch == 42L then 43L else 42L), Left("source_teardown_timeout"))
        assert((System.nanoTime() - started) < 2.seconds.toNanos)
        assert(closing.await(1, TimeUnit.SECONDS))
        assertEquals(owner.replace(config.twitch), Left("source_teardown_timeout"))
        assertEquals(creates.get(), 1)
        assert(
          hub.publish(NotificationRequest(twitchscreen.relay.protocol.NotificationKind.Info, "Independent", "synthetic", 1.second)).isRight
        )
        assertEquals(owner.status.health, TwitchHealth.Disconnected)
      finally release.countDown()

  test("one mounted webhook dispatch follows current source and fails closed during missing ownership"):
    withHub: (hub, _) =>
      val selected = config.twitch.copy(eventSub = config.twitch.eventSub.copy(transport = EventSubTransport.Webhook))
      val created = AtomicInteger()
      val owner = RuntimeSource.startWith(selected, hub, value => fake(value, s"owner-${created.incrementAndGet()}"))
      val routes = owner.endpoints
      assertEquals(routes.size, 1)
      assertEquals(owner.dispatchWebhook("id", "at", "signature", "type", "synthetic"), Right("owner-1"))
      assertEquals(owner.replace(selected), Right(()))
      assertEquals(owner.endpoints, routes)
      assertEquals(owner.dispatchWebhook("id", "at", "signature", "type", "synthetic"), Right("owner-2"))

  test("ingestion runs once in the owned scope after application listener readiness"):
    withHub: (hub, _) =>
      val calls = AtomicInteger()
      val called = CountDownLatch(1)
      val owner = RuntimeSource.startWith(
        config.twitch,
        hub,
        selected =>
          new TwitchSource:
            override val status = fake(selected).status
            override val endpoints: List[ServerEndpoint[Any, Identity]] = Nil
            override def startIngestion()(using Ox): Unit =
              calls.incrementAndGet().discard
              called.countDown()
      )
      assertEquals(calls.get(), 0)
      owner.startIngestion()
      owner.startIngestion()
      assert(called.await(1, TimeUnit.SECONDS))
      assertEquals(calls.get(), 1)
