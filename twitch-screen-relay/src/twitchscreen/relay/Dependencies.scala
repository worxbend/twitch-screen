package twitchscreen.relay

import com.softwaremill.macwire.{autowire, autowireMembersOf}
import java.time.Clock
import ox.{Ox, discard}
import twitchscreen.relay.activity.ActivityLog
import twitchscreen.relay.alerts.{AlertMonitor, AlertRule, AlertStore}
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.{Config, ConfigApi}
import twitchscreen.relay.device.{DeviceHub, DeviceLinkServer, NotificationRouter}
import twitchscreen.relay.http.HttpApi
import twitchscreen.relay.observability.{LogBuffer, Otel, RelayMetrics}
import twitchscreen.relay.stats.StatsAggregator
import twitchscreen.relay.twitch.TwitchSource

/** What [[Main]] holds on to once the relay is assembled. */
private[relay] final case class Dependencies(httpApi: HttpApi, hub: DeviceHub, twitch: TwitchSource)

/** The relay's assembly, in one place and in dependency order.
  *
  * Everything is started into the caller's scope, which is the application scope: when it ends — on SIGTERM, through `OxApp` — the listener
  * stops accepting, every device session is interrupted, the Twitch client is closed and the background folds unwind, in reverse order of
  * construction. There is no shutdown flag anywhere in the codebase.
  */
private[relay] object Dependencies:
  def create(config: Config, clock: Clock)(using Ox): Dependencies =
    LogBuffer.resize(config.observability.logBufferSize)
    val otel = Otel.initialize()

    // The bus first: everything below either publishes to it or subscribes to it, and nothing talks to anything else.
    val bus = EventBus(clock, config.bus.subscriberQueueCapacity)
    val hub = DeviceHub.start(config.deviceLink, clock, bus)

    val activityLog = ActivityLog.start(config.activity, bus)
    val alertStore = AlertMonitor.start(config.alerts, config.twitch.mode, bus, hub, clock)
    val alertRules = AlertRule.from(config.alerts, config.twitch.mode)
    RelayMetrics.start(otel, bus, hub).discard

    // Consumers before producers, so no event is published into a bus nobody is listening to yet.
    NotificationRouter.start(config.notifications, bus, hub)
    StatsAggregator.start(config.stats, bus, hub, clock)
    val twitch = TwitchSource.start(config.twitch, bus, clock)

    DeviceLinkServer.start(config.deviceLink, hub, clock).discard

    val apis = autowire[Apis](
      autowireMembersOf(config),
      clock,
      bus,
      hub,
      activityLog,
      alertStore,
      alertRules,
      twitch,
      ConfigApi.resolved()
    )

    Dependencies(HttpApi(apis.all, config.http, otel), hub, twitch)
