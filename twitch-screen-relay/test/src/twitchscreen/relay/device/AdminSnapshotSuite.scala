package twitchscreen.relay.device

import java.time.{Clock, Instant}
import ox.{discard, supervised}
import ox.channels.Channel
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.ChatNotifications
import twitchscreen.relay.protocol.*
import twitchscreen.relay.settings.*
import twitchscreen.relay.twitch.*

class AdminSnapshotSuite extends munit.FunSuite:
  private def monitoring(value: HubObservation): Monitoring_OUT =
    val publication = value.publication
    val control = publication.control.get
    val settings = publication.settings.get
    Monitoring_OUT(
      AdminSnapshot.hex(publication.relayEpoch),
      control.sourceGeneration,
      settings.settingsSource,
      MonitoringIntent_OUT(settings.confirmed.monitoring.mode, settings.confirmed.monitoring.channel.value),
      MonitoringIntent_OUT("disabled", None),
      "ready",
      control.sourceGeneration,
      None,
      GrantCompatibility_OUT("not_required", Nil, Nil),
      None
    )

  private def withHub(run: DeviceHub => Unit): Unit = supervised:
    val clock = Clock.systemUTC()
    val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 1))
    hub.installSettings(SettingsSnapshot(RuntimeSettings.firstRun("disabled", "synthetic"), 1L, "startup"))
    run(hub)

  test("canonical capture joins the exact generation and bounds supplementary previews without losing scalar validity"):
    withHub: hub =>
      val text = "😀" * 200
      hub.publish(NotificationRequest(NotificationKind.Info, text, text, 8.seconds)).discard
      val capture = hub.latestObservation.get
      val dto = AdminSnapshot.project(capture, monitoring(capture)).toOption.get
      assertEquals(dto.notifications.head.title.codePointCount(0, dto.notifications.head.title.length), 160)
      assertEquals(dto.notifications.head.body.codePointCount(0, dto.notifications.head.body.length), 160)
      assertEquals(dto.notifications.head.identity.origin, "external")
      assertEquals(dto.sources.cpu.status, "missing")
      assertEquals(dto.charts.notificationArrivals.values.last, Some(1.0))
      assert(AdminSnapshot.encoded(dto).toOption.get.length < 524288)
      assert(AdminSnapshot.project(capture, monitoring(capture).copy(sourceGeneration = 2L)).isLeft)
      assert(AdminSnapshot.project(capture, monitoring(capture).copy(relayEpoch = "0000000000000001")).isLeft)
      assert(AdminSnapshot.project(capture, monitoring(capture).copy(desired = MonitoringIntent_OUT("live", Some("other")))).isLeft)

  test("canonical encoded limit refuses oversized output instead of silently truncating a complete snapshot"):
    withHub: hub =>
      val capture = hub.latestObservation.get
      val dto = AdminSnapshot.project(capture, monitoring(capture)).toOption.get
      val large = dto.copy(monitoring = dto.monitoring.copy(resolvedChannel = Some(ResolvedChannel_OUT("1", "synthetic", "x" * 524288))))
      assert(AdminSnapshot.encoded(large).isLeft)

  test("actual classic admissions distinguish unsupported FIFO from tracked writer backlog"):
    withHub: hub =>
      val clock = Clock.systemUTC()
      val counters = LinkCounters(clock)
      val outbound = Channel.buffered[Outbound](128)
      val request = AttachRequest(
        DeviceId("classic").toOption.get,
        "127.0.0.1",
        ProtocolVersion.Three,
        SeqNo.Zero,
        Capabilities.RelaySupported,
        outbound,
        counters,
        () => (),
        Channel.buffered[Unit](1),
        firmwareVersion = "test"
      )
      val connection = hub.attach(request)
      val initial = hub.latestObservation.get
      val dto = AdminSnapshot.project(initial, monitoring(initial)).toOption.get.devices.head
      assertEquals(dto.reportStatus, "unsupported")
      assertEquals(dto.pending, None)
      assertEquals(dto.active, None)
      assertEquals(dto.appliedDisplayRevision, None)
      assert(dto.transportQueuedFrames > 0)
      val size = counters.queuedFrames
      outbound.receive().discard
      counters.dequeued()
      hub.refreshObservation()
      assertEquals(hub.latestObservation.get.devices.head.classicQueuedFrames, size - 1)
      hub.detach(connection, DisconnectReason.PeerClosed)
      val historical = hub.latestObservation.get
      val row = AdminSnapshot.project(historical, monitoring(historical)).toOption.get.devices.head
      assertEquals(row.reportStatus, "historical")
      assertEquals(row.transportQueuedFrames, 0)
      assertEquals(row.pending, None)

  test("chart ring records actual sequence changes once, preserves gaps and resets Twitch generation only"):
    withHub: hub =>
      val initial = hub.latestObservation.get
      val history = new AdminChartHistory
      val first = history.capture(initial)
      assertEquals(first.notificationArrivals.values.last, Some(0.0))
      val changed = initial.copy(publication = initial.publication.copy(sampledRelayMs = 10000L, latestSeq = SeqNo.fromWire(3L)))
      val next = history.capture(changed)
      assertEquals(next.notificationArrivals.values, Vector(Some(0.0), None, Some(3.0)))
      assertEquals(history.capture(changed).notificationArrivals.values.last, Some(3.0))
      val observed = changed.copy(publication =
        changed.publication.copy(
          statsAvailability = DeskMessageCodec.Availability.Observed,
          statsObservedAt = Some(changed.capturedAt),
          stats = StreamStats.Unknown.copy(viewers = Count.clamp(42), chatRate = MessagesPerMinute.clamp(7))
        )
      )
      assertEquals(history.capture(observed).viewers.values.last, Some(42.0))
      val switched = observed.copy(publication =
        observed.publication.copy(
          control = observed.publication.control.map(_.copy(sourceGeneration = 2L)),
          statsAvailability = DeskMessageCodec.Availability.Unknown
        )
      )
      val reset = history.capture(switched)
      assert(reset.viewers.values.forall(_.isEmpty))
      assertEquals(reset.notificationArrivals.values.last, Some(3.0))
      val late =
        switched.copy(capturedAt = Instant.EPOCH.plusMillis(1000000L), publication = switched.publication.copy(sampledRelayMs = 1000000L))
      val bounded = history.capture(late)
      assertEquals(bounded.notificationArrivals.values.size, 1)
      (201L to 381L).foreach: bucket =>
        history.capture(late.copy(publication = late.publication.copy(sampledRelayMs = bucket * 5000L))).discard
      assertEquals(
        history.capture(late.copy(publication = late.publication.copy(sampledRelayMs = 381L * 5000L))).notificationArrivals.values.size,
        180
      )

  test("same chart identity on a new session creates a gap and cannot manufacture counter deltas"):
    withHub: hub =>
      val clock = Clock.systemUTC()
      val link = DeviceLink(ConnectionId(1L), DeviceId("desk").toOption.get, "127.0.0.1", Instant.EPOCH, 3, 0L, LinkCounters(clock).traffic)
      val initial = hub.latestObservation.get
      val epoch = initial.publication.relayEpoch
      def observation(now: Long, connection: Long, revision: Long, expired: Long): HubObservation =
        val report = DeskReportCodec.Report(
          DeskControlCodec.Identity(epoch, connection),
          3L,
          revision,
          now,
          now,
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
          expired,
          0L,
          0L,
          None,
          Vector.empty
        )
        initial.copy(
          publication = initial.publication.copy(sampledRelayMs = now),
          devices = Vector(
            HubDeviceObservation(link, 16L, "test", Some(DeskObservation(Some(report), Some(now), "fresh", 0)), lastSeenRelayMs = now)
          )
        )
      val history = new AdminChartHistory
      history.capture(observation(1L, 1L, 1L, 10L)).discard
      val change = history.capture(observation(1001L, 1L, 2L, 12L))
      assertEquals(change.devices.head.expired.values.last, Some(2.0))
      assertEquals(history.capture(observation(2001L, 1L, 2L, 12L)).devices.head.expired.values.last, Some(2.0))
      val boundary = history.capture(observation(3001L, 2L, 1L, 20L))
      assertEquals(boundary.devices.head.queueDepth.values.last, None)
      assertEquals(boundary.devices.head.expired.values.last, None)
      assertEquals(history.capture(observation(5001L, 2L, 2L, 21L)).devices.head.expired.values.last, Some(1.0))
      val stale = history.capture(observation(5001L, 2L, 2L, 21L).copy(publication = initial.publication.copy(sampledRelayMs = 21002L)))
      assertEquals(stale.devices.head.queueDepth.values.last, None)
