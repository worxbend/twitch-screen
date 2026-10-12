package twitchscreen.relay.device

import java.net.InetAddress
import java.time.{Clock, Instant}
import ox.{fork, supervised, timeoutOption}
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.ChatNotifications
import twitchscreen.relay.protocol.*

class ObservationDeadlineSuite extends munit.FunSuite:
  private def publication(now: Long, epoch: Long = 1L): PublicationSnapshot =
    PublicationSnapshot(epoch, now, SeqNo.Zero, StreamStats.Unknown, Vector.empty)

  private def device(id: String, connection: Long = 1L): HubDeviceObservation =
    val clock = Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC)
    HubDeviceObservation(
      DeviceLink(ConnectionId(connection), DeviceId(id).toOption.get, "synthetic", Instant.EPOCH, 3, 0, LinkCounters(clock).traffic),
      7L,
      "synthetic",
      None
    )

  test("disconnect becomes historical immediately and expires at original fifteen-minute deadline"):
    val owner = new HubObservers
    owner.refresh(Instant.EPOCH, publication(1000), Nil, Vector(device("one")))
    owner.refresh(Instant.EPOCH, publication(2000), Nil)
    val historical = owner.snapshot.get.devices.head
    assert(!historical.connected)
    assertEquals(historical.lastSeenRelayMs, 1000L)
    assertEquals(historical.disconnectedRelayMs, Some(2000L))
    assertEquals(owner.nextDeadline, Some(902000L))
    owner.refresh(Instant.EPOCH, publication(901999), Nil)
    assertEquals(owner.snapshot.get.devices.size, 1)
    assertEquals(owner.snapshot.get.devices.head.disconnectedRelayMs, Some(2000L))
    owner.refresh(Instant.EPOCH, publication(902000), Nil)
    assertEquals(owner.snapshot.get.devices.size, 0)
    assertEquals(owner.nextDeadline, None)
    owner.stop()

  test("reconnect replaces historical identity and epoch reset discards old-domain history"):
    val owner = new HubObservers
    owner.refresh(Instant.EPOCH, publication(1000), Nil, Vector(device("one")))
    owner.refresh(Instant.EPOCH, publication(2000), Nil)
    owner.refresh(Instant.EPOCH, publication(3000), Nil, Vector(device("one", 2)))
    val live = owner.snapshot.get.devices
    assertEquals(live.size, 1)
    assert(live.head.connected)
    assertEquals(live.head.link.connection.value, 2L)
    assertEquals(live.head.disconnectedRelayMs, None)
    owner.refresh(Instant.EPOCH, publication(0, 2), Nil)
    assertEquals(owner.snapshot.get.devices.size, 0)
    owner.stop()

  test("bounded history prioritizes all live entries then deterministic recent identities"):
    val owner = new HubObservers
    val first = (1 to 64).map(index => device(f"old-$index%02d")).toVector
    owner.refresh(Instant.EPOCH, publication(1000), Nil, first)
    owner.refresh(Instant.EPOCH, publication(2000), Nil, Vector(device("current")))
    val bounded = owner.snapshot.get.devices
    assertEquals(bounded.size, 64)
    assertEquals(bounded.head.link.device.value, "current")
    assertEquals(bounded.tail.map(_.link.device.value), first.take(63).map(_.link.device.value))
    assert(bounded.tail.forall(!_.connected))
    owner.stop()

  test("report deadline uses calibrated observation age, never receipt time or unknown calibration"):
    val owner = new HubObservers
    val report = DeskReportCodec.Report(
      DeskControlCodec.Identity(1L, 1L),
      1L,
      1L,
      100L,
      1L,
      8L,
      0L,
      0L,
      1L,
      1L,
      1L,
      1L,
      1L,
      DeskReportCodec.Base.ConnectedOrbit,
      DeskReportCodec.Reason.GenericSelected,
      DeskReportCodec.Phase.None,
      0L,
      0L,
      0L,
      0L,
      None,
      Vector.empty
    )
    val observed = device("reported").copy(desk = Some(DeskObservation(Some(report), Some(15000L), "fresh", 0)))
    owner.refresh(Instant.EPOCH, publication(15000), Nil, Vector(observed))
    assertEquals(owner.nextDeadline, Some(15002L))
    owner.refresh(Instant.EPOCH, publication(15001), Nil, Vector(observed))
    assertEquals(owner.nextDeadline, Some(15002L))
    owner.refresh(Instant.EPOCH, publication(15002), Nil, Vector(observed.copy(desk = observed.desk.map(_.copy(status = "stale")))))
    assertEquals(owner.nextDeadline, None)
    owner.refresh(
      Instant.EPOCH,
      publication(15003),
      Nil,
      Vector(observed.copy(desk = observed.desk.map(_.copy(report = Some(report.copy(observedRelayLowerMs = 0))))))
    )
    assertEquals(owner.nextDeadline, None)
    owner.stop()

  test("deadline waiting is woken by an earlier replacement and stop without a polling interval"):
    supervised:
      val owner = new HubObservers
      owner.refresh(Instant.EPOCH, publication(1000).copy(cpuDeadlineRelayMs = Some(61000)), Nil)
      val waiting = fork(owner.awaitDeadline())
      owner.refresh(Instant.EPOCH, publication(1000).copy(cpuDeadlineRelayMs = Some(1010)), Nil)
      assertEquals(timeoutOption(2.seconds)(waiting.join()), Some(true))
      owner.refresh(Instant.EPOCH, publication(1010), Nil)
      val stopped = fork(owner.awaitDeadline())
      owner.stop()
      assertEquals(timeoutOption(2.seconds)(stopped.join()), Some(false))

  test("installed hub publishes source expiration without another write or bus observation"):
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 1))
      val accepted = hub.publishCpu(Cpu_IN("synthetic", "host", 42, freshnessMs = 1000)).toOption.get
      val (initial, subscription) = hub.observeHub(InetAddress.getLoopbackAddress).toOption.get
      try
        assertEquals(initial.publication.cpu.get.status, "fresh")
        val expired = timeoutOption(3.seconds):
          var value = subscription.take().get
          while value.publication.cpu.get.status != "stale" do value = subscription.take().get
          value
        assert(expired.isDefined, "source expiry must reliably refresh idle observers")
        assertEquals(expired.get.publication.cpu.get.revision, accepted.revision)
        assertEquals(expired.get.publication.cpu.get.data, accepted.data)
        assertEquals(expired.get.publication.cpu.get.remainingFreshnessMs, 0L)
        assertEquals(expired.get.publication.latestSeq, SeqNo.Zero)
        assert(expired.get.revision > initial.revision)
      finally subscription.close()
