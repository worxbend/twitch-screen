package twitchscreen.relay.device

import java.time.Clock
import ox.*
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.{ChatNotifications, NotificationsConfig, StatsConfig}
import twitchscreen.relay.protocol.*
import twitchscreen.relay.settings.*

class ApplicationDiagnosticSuite extends munit.FunSuite:
  private val clock = Clock.systemUTC()
  private val failure = RelayEvent.RelayFailure("twitch-oauth", "synthetic diagnostic", DeskMessageCodec.Origin.Twitch)
  private val initial = SettingsSnapshot(RuntimeSettings.firstRun("disabled", "synthetic"), 1L, "startup")

  test("stable diagnostics capture current epoch, source and episode without inventing observed statistics"):
    supervised:
      val bus = EventBus(clock, 64)
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, bus)
      hub.installSettings(initial)
      val diagnostics = SourceIngestion.applicationDiagnostics(bus, hub)
      val oldSource = SourceIngestion.start(bus, hub, StatsConfig(1.hour, 1.minute))
      diagnostics.publish(failure)
      val first = hub.retainedPublications.last
      assertEquals(first.origin, DeskMessageCodec.Origin.Twitch)
      assertEquals((first.sourceGeneration, first.twitchEpisode), (1L, 1L))
      assert(!first.capableEligible)
      assertEquals(hub.publicationSnapshot.statsAvailability, DeskMessageCodec.Availability.Unknown)
      val changed = initial.copy(
        revision = 2L,
        confirmed = initial.confirmed
          .copy(display = DisplaySettings(true, "cpu"), monitoring = MonitoringSettings("live", ChannelSetting(Some("new"))))
      )
      hub.installSettings(changed)
      diagnostics.publish(failure)
      oldSource.publish(failure)
      val second = hub.retainedPublications.last
      assertEquals(hub.snapshot.latestSeq.value, 2L, "obsolete source diagnostics must not bypass their original fence")
      assertEquals((second.sourceGeneration, second.twitchEpisode), (2L, 2L))
      assert(second.capableEligible)
      assertEquals(hub.retainedPublications.head, first, "enabling display must not resurrect an ineligible diagnostic")
      val epoch = hub.relayClock.relayEpoch
      assert(hub.resetEpoch(epoch, if epoch == 42L then 43L else 42L))
      diagnostics.publish(failure)
      val replacement = hub.retainedPublications.last
      assert(replacement.relayEpoch != first.relayEpoch)
      assertEquals((replacement.record.seq.value, replacement.sourceGeneration, replacement.twitchEpisode), (1L, 1L, 1L))
      assert(replacement.capableEligible)
      assertEquals(hub.publicationSnapshot.statsAvailability, DeskMessageCodec.Availability.Unknown)
      assertEquals(hub.publicationSnapshot.statsObservedAt, None)
      assertEquals(hub.latestStats, StreamStats.Unknown)

  test("blocked optional observers cannot lose admitted diagnostics or accept source callbacks through the application path"):
    supervised:
      val bus = EventBus(clock, 1)
      bus.subscribe("blocked-observer").discard
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, bus)
      hub.installSettings(initial)
      val diagnostics = SourceIngestion.applicationDiagnostics(bus, hub)
      (1 to 100).foreach(_ => diagnostics.publish(failure))
      assertEquals(hub.snapshot.latestSeq.value, 100L)
      assert(bus.subscriberStats.head.dropped > 0)
      diagnostics.publish(RelayEvent.StreamStarted("obsolete", "title", "game", None))
      diagnostics.publish(RelayEvent.ChatMessaged("obsolete", "body", None))
      assertEquals(hub.snapshot.latestSeq.value, 100L)
      assertEquals(hub.latestStats, StreamStats.Unknown)
      hub.shutdown().discard
      diagnostics.publish(failure)
      assertEquals(hub.snapshot.latestSeq.value, 100L)

  test("actual classic TCP delivery admits each application diagnostic once without lossy router duplication"):
    supervised:
      val bus = EventBus(clock, 64)
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, bus)
      hub.installSettings(initial)
      NotificationRouter.start(NotificationsConfig(30.seconds, ChatNotifications.Show), bus, hub, sourceOwned = true)
      val diagnostics = SourceIngestion.applicationDiagnostics(bus, hub)
      val listener = DeviceLinkServer.startOnPort(TestRelay.config, hub, clock, 0)
      val device = useCloseableInScope(TestDevice(listener.getLocalPort))
      device.hello("oauth-diagnostics", 0)
      device.receiveMany(2).discard
      (1 to 3).foreach: expected =>
        diagnostics.publish(failure)
        assertEquals(
          device.receiveMessage().collect { case RelayMessage.Event(record) => (record.seq.value, record.actor) },
          Some((expected.toLong, "twitch-oauth failed"))
        )
      assert(timeoutOption(5.seconds) {
        while bus.subscriberStats.find(_.name == "device-notifications").exists(value => value.delivered < value.enqueued) do
          sleep(1.millis)
      }.isDefined)
      assertEquals(hub.snapshot.notificationsPublished, 3L)
      assertEquals(hub.latestStats, StreamStats.Unknown)
