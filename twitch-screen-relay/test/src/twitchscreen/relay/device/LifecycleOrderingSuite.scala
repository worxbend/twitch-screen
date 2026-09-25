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
      assertEquals(device.receiveMessage().collect { case RelayMessage.Event(record) => record.kind }, Some(NotificationKind.StreamStart))
      assertEquals(device.receiveMessage().collect { case RelayMessage.Stats(stats, _) => stats.state }, Some(StreamState.Live))
      bus.publish(RelayEvent.StreamEnded("channel", 30.seconds))
      assertEquals(device.receiveMessage().collect { case RelayMessage.Event(record) => record.kind }, Some(NotificationKind.StreamEnd))
      assertEquals(device.receiveMessage().collect { case RelayMessage.Stats(stats, _) => stats.state }, Some(StreamState.Offline))
