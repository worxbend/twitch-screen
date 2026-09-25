package twitchscreen.relay

import com.softwaremill.macwire.{autowire, autowireMembersOf}
import java.time.{Clock, Instant}
import ox.{Ox, discard}
import sttp.tapir.server.netty.sync.NettySyncServerBinding
import twitchscreen.relay.activity.ActivityLog
import twitchscreen.relay.alerts.{AlertMonitor, AlertRule, AlertStore}
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.Config
import com.typesafe.config.{Config as HoconConfig}
import twitchscreen.relay.device.{DeviceHub, DeviceLinkServer, NotificationRouter}
import twitchscreen.relay.http.HttpApi
import twitchscreen.relay.observability.{Otel, RelayMetrics}
import twitchscreen.relay.stats.StatsAggregator
import twitchscreen.relay.twitch.{BotFilter, TwitchSource}

/** What [[Main]] holds on to once the relay is assembled. */
private[relay] final case class Dependencies(httpApi: HttpApi, hub: DeviceHub, twitch: TwitchSource):
  /** Binds HTTP (webhook and OAuth callbacks), then starts Twitch ingestion, whose webhook registration Twitch verifies by calling back
    * (RLY-43).
    */
  def serve()(using Ox): NettySyncServerBinding = httpApi.start(_ => twitch.startIngestion())

/** The relay's assembly, in one place and in dependency order.
  *
  * Workers belong to the caller's application scope. Main binds HTTP before starting ingestion; ApplicationLifetime drains device shutdown
  * messages before scope cancellation closes the listeners, Twitch client and background consumers. It only wires objects; process-global
  * state such as the [[twitchscreen.relay.observability.LogBuffer]] capacity is applied by [[Main]] before this runs.
  */
private[relay] object Dependencies:
  def create(config: Config, source: HoconConfig, clock: Clock, startedAt: Instant)(using Ox): Dependencies =
    val otel = Otel.initialize()

    // The bus first: everything below either publishes to it or subscribes to it, and nothing talks to anything else.
    val bus = EventBus(clock, config.bus.subscriberQueueCapacity)
    val hub = DeviceHub.start(config.deviceLink, config.notifications.chat, clock, bus)

    val activityLog = ActivityLog.start(config.activity, bus)
    val alertRules = AlertRule.from(config.alerts, config.twitch.mode)
    val alertStore = AlertMonitor.start(config.alerts, alertRules, bus, hub, clock)
    RelayMetrics.start(otel, bus, hub).discard

    // Consumers before producers, so no event is published into a bus nobody is listening to yet.
    NotificationRouter.start(config.notifications, bus, hub)
    StatsAggregator.start(config.stats, bus, hub, clock)
    // §13.1: one filter, built once from `notifications.ignored-display-names`, handed to whichever source is running.
    val botFilter = BotFilter.from(config.notifications)
    val twitch = TwitchSource.start(config.twitch, bus, botFilter, clock)

    DeviceLinkServer.start(config.deviceLink, hub, clock).discard

    val apis = autowire[Apis](
      autowireMembersOf(config),
      clock,
      startedAt,
      bus,
      hub,
      activityLog,
      alertStore,
      alertRules,
      twitch,
      source
    )

    Dependencies(HttpApi(apis.all, config.http, otel), hub, twitch)
