package twitchscreen.relay.device

import java.time.Clock
import ox.*
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.{ChatNotifications, NotificationsConfig, StatsConfig}
import twitchscreen.relay.protocol.*
import twitchscreen.relay.stats.StatsAggregator

class LifecycleOrderingSuite extends munit.FunSuite:
  test("each lifecycle EVENT is followed by its post-transition STATS in the running pipeline"):
    supervised:
      val clock = Clock.systemUTC()
      val bus = EventBus(clock, 128)
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, bus)
      NotificationRouter.start(NotificationsConfig(30.seconds, ChatNotifications.Show), bus, hub)
      StatsAggregator.start(StatsConfig(1.hour, 1.minute), bus, hub, clock)
      val listener = DeviceLinkServer.startOnPort(TestRelay.config, hub, clock, 0)
      val device = useCloseableInScope(TestDevice(listener.getLocalPort))
      device.hello("test", 0)
      device.receiveMany(2).discard
      bus.publish(RelayEvent.StreamStarted("channel", "title", "game", Some(clock.instant())))
      expectTransition(device, NotificationKind.StreamStart, StreamState.Live)
      bus.publish(RelayEvent.StreamEnded("channel", 30.seconds))
      expectTransition(device, NotificationKind.StreamEnd, StreamState.Offline)

  private def expectTransition(device: TestDevice, kind: NotificationKind, state: StreamState): Unit =
    // Flow.tick emits an initial snapshot on its own scheduling turn. It can legitimately precede either lifecycle card.
    // Once the card arrives, its post-transition STATS must still be the immediately following frame.
    val next = timeoutOption(2.seconds):
      LazyList
        .continually(device.receiveMessage())
        .take(8)
        .dropWhile {
          case Some(_: RelayMessage.Stats) => true
          case _                           => false
        }
        .headOption
        .flatten
    .flatten
    assertEquals(next.collect { case RelayMessage.Event(record) => record.kind }, Some(kind))
    assertEquals(device.receiveMessage().collect { case RelayMessage.Stats(stats, _) => stats.state }, Some(state))
