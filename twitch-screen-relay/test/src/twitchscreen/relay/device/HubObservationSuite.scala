package twitchscreen.relay.device

import java.net.InetAddress
import java.time.Clock
import ox.{fork, supervised}
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.ChatNotifications
import twitchscreen.relay.protocol.*
import twitchscreen.relay.settings.*

class HubObservationSuite extends munit.FunSuite:
  private val remote = InetAddress.getLoopbackAddress
  private def request = NotificationRequest(NotificationKind.Info, "observer", "synthetic", 8.seconds)

  test("installed hub refresh does not depend on lossy bus and unread subscribers retain only latest"):
    supervised:
      val clock = Clock.systemUTC()
      val bus = EventBus(clock, queueCapacity = 1)
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, bus)
      val (initial, subscriber) = hub.observeHub(remote).toOption.get
      try
        (1 to 100).foreach(_ => assert(hub.publish(request).isRight))
        val latest = subscriber.take().get
        assert(latest.revision > initial.revision)
        assertEquals(latest.publication.latestSeq.value, 100L)
        assertEquals(latest, hub.latestObservation.get)
        assertEquals(subscriber.poll(), None)
      finally subscriber.close()

  test("registration concurrent with publication has no initial-to-latest gap"):
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 1))
      val writer = fork((1 to 100).foreach(_ => hub.publish(request)))
      val (initial, subscriber) = hub.observeHub(remote).toOption.get
      try
        writer.join()
        val latest = subscriber.poll().getOrElse(initial)
        assertEquals(latest.publication.latestSeq.value, 100L)
        assertEquals(latest.publication.retained.last.record.seq.value, 100L)
      finally subscriber.close()

  test("settings and epoch transitions reliably replace capture without another publication"):
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 1))
      val (_, subscriber) = hub.observeHub(remote).toOption.get
      try
        hub.installSettings(SettingsSnapshot(RuntimeSettings.firstRun("disabled", "synthetic"), 1L, "startup"))
        val committed = subscriber.take().get
        assertEquals(committed.publication.control.get.display.preferredDashboard, "generic")
        assert(hub.resetEpoch(committed.publication.relayEpoch, 42L))
        val reset = subscriber.take().get
        assertEquals(reset.publication.relayEpoch, 42L)
        assertEquals(reset.revision, 1L)
        assertEquals(reset.publication.retained.size, 0)
        assertEquals(reset.publication.cpu.get.status, "missing")
      finally subscriber.close()

  test("eight global and two normalized address slots release on idempotent close"):
    val observers = new HubObservers
    observers.refresh(java.time.Instant.EPOCH, PublicationSnapshot(1, 0, SeqNo.Zero, StreamStats.Unknown, Vector.empty), Nil)
    val subscriptions = (1 to 4).flatMap: suffix =>
      val address = InetAddress.getByAddress(Array[Byte](127, 0, 0, suffix.toByte))
      val first = observers.subscribe(address).toOption.get._2
      val second = observers.subscribe(address).toOption.get._2
      assertEquals(observers.subscribe(address), Left("observer_limit"))
      List(first, second)
    assertEquals(observers.size, 8)
    assertEquals(observers.subscribe(remote), Left("observer_limit"))
    subscriptions.head.close()
    subscriptions.head.close()
    assertEquals(observers.size, 7)
    observers.stop()
    assertEquals(observers.size, 0)
    subscriptions.foreach(value => assertEquals(value.take(), None))
    assertEquals(observers.subscribe(remote), Left("observer_stopped"))

  test("scope exit releases observers and wakes waiting consumers"):
    val subscription = supervised:
      val clock = Clock.systemUTC()
      DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 1)).observeHub(remote).toOption.get._2
    assertEquals(subscription.take(), None)

  test("projection capture clamps enlarged replay without changing delivery retention"):
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(
        TestRelay.config.copy(replayBufferSize = 100, outboundQueueCapacity = 118),
        ChatNotifications.Show,
        clock,
        EventBus(clock, 1)
      )
      (1 to 100).foreach(_ => assert(hub.publish(request).isRight))
      assertEquals(hub.retainedPublications.size, 100)
      assertEquals(hub.latestObservation.get.publication.retained.size, 80)
      assertEquals(hub.latestObservation.get.publication.retained.head.record.seq.value, 21L)
