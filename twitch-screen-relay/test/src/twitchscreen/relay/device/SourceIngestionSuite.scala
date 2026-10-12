package twitchscreen.relay.device

import java.time.Clock
import ox.*
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.{BusEvent, EventBus, RelayEvent}
import twitchscreen.relay.config.{ChatNotifications, NotificationsConfig, StatsConfig}
import twitchscreen.relay.protocol.*
import twitchscreen.relay.settings.*
import twitchscreen.relay.twitch.BotFilter

class SourceIngestionSuite extends munit.FunSuite:
  private val config = StatsConfig(1.hour, 1.minute)
  private val settings = SettingsSnapshot(RuntimeSettings.firstRun("simulated", "synthetic"), 1L, "startup")

  test("lossy optional bus overflow cannot lose source cards or statistics"):
    supervised:
      val clock = Clock.systemUTC()
      val bus = EventBus(clock, 1)
      val unused = bus.subscribe("blocked-observer")
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, bus)
      hub.installSettings(settings)
      val source = SourceIngestion.start(bus, hub, config)
      (1 to 100).foreach(index => source.publish(RelayEvent.ChatMessaged(s"human$index", "synthetic", None)))
      val snapshot = hub.publicationSnapshot
      assertEquals(snapshot.latestSeq.value, 100L)
      assertEquals(snapshot.stats.messagesTotal.value, 100)
      assertEquals(snapshot.stats.chatRate.value, 100)
      assert(bus.subscriberStats.head.dropped > 0)
      assertEquals(unused.receive().event.category, twitchscreen.relay.bus.EventCategory.Notification)

  test("obsolete callbacks and timers cannot repopulate cleared state or telemetry fan-out"):
    supervised:
      val clock = Clock.systemUTC()
      val bus = EventBus(clock, 64)
      val observed = bus.subscribe("observer")
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, bus)
      hub.installSettings(settings)
      val old = hub.sourceFence
      val source = SourceIngestion.start(bus, hub, config)
      source.publish(RelayEvent.ViewersObserved(Count.clamp(99), 1.minute))
      assertEquals(observed.receive().event, RelayEvent.ViewersObserved(Count.clamp(99), 1.minute))
      hub.installSettings(
        settings.copy(
          revision = 2L,
          confirmed = settings.confirmed.copy(monitoring = MonitoringSettings("disabled", ChannelSetting(Some("new"))))
        )
      )
      val reset = hub.publicationSnapshot
      source.publish(RelayEvent.ChatMessaged("obsolete", "synthetic", None))
      assert(!hub.refreshSourceStats(old, config))
      assertEquals(hub.publicationSnapshot.stats, StreamStats.Unknown)
      assertEquals(hub.publicationSnapshot.latestSeq, reset.latestSeq)
      assertEquals(hub.publicationSnapshot.retained, reset.retained)
      assertEquals(bus.subscriberStats.head.enqueued, 1L)
      val otherEpoch = if old.relayEpoch == 1L then 2L else 1L
      assert(!hub.acceptSource(old.copy(relayEpoch = otherEpoch), BusEvent(clock.instant(), RelayEvent.Followed("wrong-epoch")), config))

  test("new source starts with empty fold and original retained fences survive settings changes"):
    supervised:
      val clock = Clock.systemUTC()
      val bus = EventBus(clock, 64)
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, bus)
      hub.installSettings(settings)
      val first = SourceIngestion.start(bus, hub, config)
      first.publish(RelayEvent.ChatMessaged("first", "synthetic", None))
      val retained = hub.retainedPublications.head
      assertEquals((retained.sourceGeneration, retained.twitchEpisode), (1L, 1L))
      val changed = settings.copy(
        revision = 2L,
        confirmed = settings.confirmed.copy(monitoring = MonitoringSettings("simulated", ChannelSetting(Some("new"))))
      )
      hub.installSettings(changed)
      val second = SourceIngestion.start(bus, hub, config)
      second.publish(RelayEvent.ChatMessaged("second", "synthetic", None))
      assertEquals(hub.latestStats.messagesTotal.value, 1)
      assertEquals(hub.retainedPublications.head, retained)
      assertEquals(hub.retainedPublications.last.sourceGeneration, 2L)
      assertEquals(hub.retainedPublications.last.twitchEpisode, 2L)
      assert(hub.publish(NotificationRequest(NotificationKind.Follow, "manual", "", 1.second)).isRight)
      assertEquals(hub.retainedPublications.last.origin, DeskMessageCodec.Origin.External)
      assertEquals((hub.retainedPublications.last.sourceGeneration, hub.retainedPublications.last.twitchEpisode), (0L, 0L))

  test("source-owned production pipeline preserves classic lifecycle EVENT then STATS without duplicate cards"):
    supervised:
      val clock = Clock.systemUTC()
      val bus = EventBus(clock, 64)
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, bus)
      hub.installSettings(settings)
      NotificationRouter.start(NotificationsConfig(30.seconds, ChatNotifications.Show), bus, hub, sourceOwned = true)
      val source = SourceIngestion.start(bus, hub, config)
      val listener = DeviceLinkServer.startOnPort(TestRelay.config, hub, clock, 0)
      val device = useCloseableInScope(TestDevice(listener.getLocalPort))
      device.hello("synthetic", 0)
      device.receiveMany(2).discard
      source.publish(RelayEvent.StreamStarted("synthetic", "title", "game", Some(clock.instant())))
      assertEquals(device.receiveMessage().collect { case RelayMessage.Event(record) => record.kind }, Some(NotificationKind.StreamStart))
      assertEquals(device.receiveMessage().collect { case RelayMessage.Stats(stats, _) => stats.state }, Some(StreamState.Live))
      source.publish(RelayEvent.StreamEnded("synthetic", 1.minute))
      assertEquals(device.receiveMessage().collect { case RelayMessage.Event(record) => record.kind }, Some(NotificationKind.StreamEnd))
      assertEquals(device.receiveMessage().collect { case RelayMessage.Stats(stats, _) => stats.state }, Some(StreamState.Offline))
      assertEquals(hub.snapshot.notificationsPublished, 2L)

  test("bot filtering precedes reliable admission and local failures remain independent"):
    supervised:
      val clock = Clock.systemUTC()
      val bus = EventBus(clock, 64)
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, bus)
      hub.installSettings(settings)
      val source = SourceIngestion.start(bus, hub, config)
      val filter = BotFilter(Set("nightbot"))
      filter.publish(source, RelayEvent.ChatMessaged("Nightbot", "synthetic", None))
      filter.publish(source, RelayEvent.ChatMessaged("human", "synthetic", None))
      assertEquals(hub.snapshot.notificationsPublished, 1L)
      assertEquals(hub.latestStats.messagesTotal.value, 1)
      val diagnostic = bus.subscribe("relay-diagnostics")
      val local = RelayEvent.RelayFailure("local", "synthetic")
      source.publish(local)
      assertEquals(diagnostic.receive().event, local)
      assertEquals(hub.latestStats.messagesTotal.value, 1)
      assert(hub.acceptSource(hub.sourceFence, BusEvent(clock.instant(), RelayEvent.RelayFailure("local", "synthetic")), config))
      assertEquals(hub.snapshot.notificationsPublished, 1L)

  test("sequence exhaustion does not prevent lifecycle stats or later independent source admission"):
    supervised:
      val clock = Clock.systemUTC()
      val bus = EventBus(clock, 64)
      val hub = DeviceHub.startingAt(TestRelay.config, ChatNotifications.Show, clock, bus, SeqNo.Max)
      hub.installSettings(settings)
      val source = SourceIngestion.start(bus, hub, config)
      source.publish(RelayEvent.StreamStarted("synthetic", "title", "game", Some(clock.instant())))
      assertEquals(hub.latestStats.state, StreamState.Live)
      source.publish(RelayEvent.StreamEnded("synthetic", 1.minute))
      assertEquals(hub.latestStats.state, StreamState.Offline)
      assertEquals(hub.snapshot.notificationsPublished, 0L)
      assertEquals(hub.snapshot.latestSeq, SeqNo.Max)
