package twitchscreen.relay.device

import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import ox.{discard, supervised}
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.{BusEvent, EventBus, RelayEvent}
import twitchscreen.relay.config.{ChatNotifications, StatsConfig}
import twitchscreen.relay.protocol.*
import twitchscreen.relay.settings.*
import twitchscreen.relay.twitch.*

class AdminObservationRepairSuite extends munit.FunSuite:
  private final class TestClock extends Clock:
    var at: Instant = Instant.parse("2026-10-12T00:00:00Z")
    override def getZone(): ZoneId = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant = at

  private def monitoring(value: HubObservation): Monitoring_OUT =
    val control = value.publication.control.get
    val settings = value.publication.settings.get
    Monitoring_OUT(
      AdminSnapshot.hex(value.publication.relayEpoch),
      settings.revision,
      settings.settingsSource,
      MonitoringIntent_OUT(settings.confirmed.monitoring.mode, settings.confirmed.monitoring.channel.value),
      MonitoringIntent_OUT("disabled", None),
      "ready",
      control.sourceGeneration,
      None,
      GrantCompatibility_OUT("not_required", Nil, Nil),
      None
    )

  test("identical provider observations refresh chart authority without changing wire revision; timers and old sources cannot"):
    supervised:
      val clock = new TestClock
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 1))
      val settings = RuntimeSettings.firstRun("simulated", "synthetic")
      hub.installSettings(SettingsSnapshot(settings, 1L, "startup"))
      val config = StatsConfig(1.hour, 1.minute)
      val fence = hub.sourceFence
      def observe(): Boolean = hub.acceptSource(fence, BusEvent(clock.instant(), RelayEvent.FollowersObserved(Count.clamp(42))), config)
      assert(observe())
      val initial = hub.latestObservation.get
      assertEquals(initial.charts.get.viewers.values.last, Some(0.0))
      clock.at = clock.at.plusSeconds(20)
      assert(observe())
      assert(hub.refreshSourceStats(fence, config))
      val repeated = hub.latestObservation.get
      assertEquals(repeated.publication.statsObservedAt, Some(clock.instant()))
      assertEquals(repeated.publication.statsRevision, initial.publication.statsRevision)
      val identity = DeskControlCodec.Identity(initial.publication.relayEpoch, 1L)
      assertEquals(DeskProjection.stats(repeated.publication, identity), DeskProjection.stats(initial.publication, identity))
      assertEquals(repeated.charts.get.viewers.values.last, Some(0.0))
      assertEquals(repeated.charts.get.chatRate.values.last, Some(0.0))
      clock.at = clock.at.plusSeconds(16)
      assert(hub.refreshSourceStats(fence, config))
      val lost = hub.latestObservation.get
      assertEquals(lost.publication.statsObservedAt, repeated.publication.statsObservedAt)
      assertEquals(lost.charts.get.viewers.values.last, None)
      assertEquals(lost.charts.get.chatRate.values.last, None)
      hub.installSettings(SettingsSnapshot(settings.copy(monitoring = settings.monitoring.copy(mode = "disabled")), 2L, "startup"))
      assert(!observe())
      val switched = hub.latestObservation.get
      assertEquals(switched.publication.statsObservedAt, None)
      assert(switched.charts.get.viewers.values.forall(_.isEmpty))

  test("aggregation changes to live uptime cannot invent provider observation freshness"):
    supervised:
      val clock = new TestClock
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 1))
      hub.installSettings(SettingsSnapshot(RuntimeSettings.firstRun("simulated", "synthetic"), 1L, "startup"))
      val config = StatsConfig(1.hour, 1.minute)
      val fence = hub.sourceFence
      assert(hub.acceptSource(fence, BusEvent(clock.instant(), RelayEvent.ViewersObserved(Count.clamp(7), 1.minute)), config))
      val first = hub.latestObservation.get
      clock.at = clock.at.plusSeconds(20)
      assert(hub.refreshSourceStats(fence, config))
      val next = hub.latestObservation.get
      assert(next.publication.statsRevision > first.publication.statsRevision)
      assertEquals(next.publication.statsObservedAt, first.publication.statsObservedAt)
      assertEquals(next.charts.get.viewers.values.last, None)

  test("unknown calibration encodes no application or FIFO authority; fresh empty, stale and historical remain distinct"):
    supervised:
      val clock = new TestClock
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 1))
      hub.installSettings(SettingsSnapshot(RuntimeSettings.firstRun("disabled", "synthetic"), 1L, "startup"))
      val capture = hub.latestObservation.get
      val link =
        DeviceLink(ConnectionId(1L), DeviceId("desk").toOption.get, "127.0.0.1", clock.instant(), 3, 0L, LinkCounters(clock).traffic)
      val report = DeskReportCodec.Report(
        DeskControlCodec.Identity(capture.publication.relayEpoch, 1L),
        3L,
        1L,
        1L,
        0L,
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
      def project(lower: Long, now: Long, connected: Boolean = true): AdminDevice_OUT =
        val observation = capture.copy(
          publication = capture.publication.copy(sampledRelayMs = now),
          devices = Vector(
            HubDeviceObservation(
              link,
              16L,
              "test",
              Some(DeskObservation(Some(report.copy(observedRelayLowerMs = lower)), Some(1L), "unknown", 0)),
              connected = connected
            )
          )
        )
        AdminSnapshot.project(observation, monitoring(observation)).toOption.get.devices.head
      val unknown = project(0L, 1000L)
      assertEquals(unknown.reportStatus, "unknown")
      assertEquals(unknown.pending, None)
      assertEquals(unknown.active, None)
      assertEquals(unknown.baseScreen, None)
      assertEquals(unknown.reason, None)
      assertEquals(unknown.appliedDisplayRevision, None)
      assertEquals(unknown.expiredTotal, None)
      assertEquals(unknown.reportRevision, Some(1L))
      assertEquals(project(1L, 1000L).pending, Some(Vector.empty))
      assertEquals(project(1L, 16002L).reportStatus, "stale")
      assertEquals(project(1L, 16002L).pending, Some(Vector.empty))
      assertEquals(project(1L, 1000L, connected = false).reportStatus, "historical")
      assertEquals(project(1L, 1000L, connected = false).pending, Some(Vector.empty))
      assertEquals(project(0L, 1000L, connected = false).pending, None)
      hub.refreshObservation().discard
