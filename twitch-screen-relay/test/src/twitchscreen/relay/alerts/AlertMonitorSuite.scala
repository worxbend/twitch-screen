package twitchscreen.relay.alerts

import java.time.{Clock, Instant, ZoneOffset}
import ox.supervised
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.{AlertsConfig, ChatNotifications, DeviceLinkConfig, Hostname, Port}
import twitchscreen.relay.device.DeviceHub

/** Whether the monitor attaches to the bus at all: with nothing to evaluate it must not take a subscriber slot or run a timer. */
class AlertMonitorSuite extends munit.FunSuite:
  private val clock = Clock.fixed(Instant.ofEpochSecond(1790309000L), ZoneOffset.UTC)

  // A long interval so the evaluation timer never fires while a test runs.
  private val config = AlertsConfig(
    evaluationInterval = 1.hour,
    bufferSize = 10,
    noDevicesConnectedFor = Some(2.minutes),
    twitchDisconnectedFor = Some(1.minute),
    streamOfflineFor = None,
    errorRateThreshold = 10,
    errorRateWindow = 5.minutes
  )

  private val deviceLinkConfig = DeviceLinkConfig(
    host = Hostname("127.0.0.1").toOption.get,
    port = Port(8099).toOption.get,
    protocolVersion = 3,
    acceptBacklog = 8,
    handshakeTimeout = 2.seconds,
    idleTimeout = 5.seconds,
    pingInterval = 4.seconds,
    outboundQueueCapacity = 32,
    replayBufferSize = 8,
    maxFrameLength = 256
  )

  private def alertsSubscribed(rules: List[AlertRule]): (Boolean, Int) =
    supervised:
      val bus = EventBus(clock, 16)
      val hub = DeviceHub.start(deviceLinkConfig, ChatNotifications.Hide, clock, bus)
      val store = AlertMonitor.start(config, rules, bus, hub, clock)
      (bus.subscriberStats.exists(_.name == "alerts"), store.activeCount)

  test("no configured rules starts no alerts subscriber"):
    assertEquals(alertsSubscribed(Nil), (false, 0))

  test("a configured rule subscribes the monitor to the bus"):
    assertEquals(alertsSubscribed(List(AlertRule.NoDevicesConnected(1.minute))), (true, 0))
