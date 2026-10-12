package twitchscreen.relay

import com.softwaremill.macwire.{autowire, autowireMembersOf}
import java.time.{Clock, Instant}
import ox.{Ox, discard, useInScope}
import sttp.tapir.server.netty.sync.NettySyncServerBinding
import twitchscreen.relay.activity.ActivityLog
import twitchscreen.relay.alerts.RuntimeAlertMonitor
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.{Config, TwitchConfig}
import com.typesafe.config.{Config as HoconConfig}
import twitchscreen.relay.device.{DeviceHub, DeviceLinkServer, NotificationRouter, SourceIngestion}
import twitchscreen.relay.http.HttpApi
import twitchscreen.relay.observability.{Otel, RelayMetrics}
import twitchscreen.relay.settings.{RuntimeSettingsFile, RuntimeSettingsWorker}
import java.nio.file.Path
import twitchscreen.relay.twitch.{BotFilter, RuntimeRecovery, RuntimeSource, TwitchOAuthOwner, TwitchSource}

/** What [[Main]] holds on to once the relay is assembled. */
private[relay] final case class Dependencies(
    httpApi: HttpApi,
    hub: DeviceHub,
    twitch: TwitchSource,
    recovery: Option[RuntimeRecovery] = None
):
  /** Binds HTTP (webhook and OAuth callbacks), then starts Twitch ingestion, whose webhook registration Twitch verifies by calling back
    * (RLY-43).
    */
  def serve()(using Ox): NettySyncServerBinding = httpApi.start: _ =>
    twitch.startIngestion()
    recovery.foreach(_.start())

/** The relay's assembly, in one place and in dependency order.
  *
  * Workers belong to the caller's application scope. Main binds HTTP before starting ingestion; ApplicationLifetime drains device shutdown
  * messages before scope cancellation closes the listeners, Twitch client and background consumers. It only wires objects; process-global
  * state such as the [[twitchscreen.relay.observability.LogBuffer]] capacity is applied by [[Main]] before this runs.
  */
private[relay] object Dependencies:
  def create(config: Config, source: HoconConfig, clock: Clock, startedAt: Instant)(using Ox): Dependencies =
    createWithOAuth(config, source, clock, startedAt, (selected, diagnostics, time) => TwitchOAuthOwner.start(selected, diagnostics, time))

  /** Exercise the installed assembly with a synthetic provider while keeping production's one OAuth owner and admission graph intact. */
  private[relay] def createWithOAuth(
      config: Config,
      source: HoconConfig,
      clock: Clock,
      startedAt: Instant,
      startOAuth: (TwitchConfig, EventBus, Clock) => TwitchOAuthOwner
  )(using Ox): Dependencies =
    val otel = Otel.initialize()

    // The bus first: everything below either publishes to it or subscribes to it, and nothing talks to anything else.
    val bus = EventBus(clock, config.bus.subscriberQueueCapacity)
    val hub = DeviceHub.start(config.deviceLink, config.notifications.chat, clock, bus)
    twitchscreen.relay.weather.WeatherWorker.start(config.weather, hub)

    val settings = RuntimeSettingsWorker
      .start(
        RuntimeSettingsFile(Path.of(config.runtime.settingsFile)),
        twitchscreen.relay.settings.RuntimeSettingsStartup.defaults(config.twitch),
        hub.installSettings
      )
      .fold(_ => throw IllegalStateException("runtime settings failed startup admission"), identity)
    val selectedMonitoring = twitchscreen.relay.settings.RuntimeSettingsStartup.select(config.twitch, settings.snapshot)
    val effectiveTwitch = selectedMonitoring.config
    val startupMonitoringStatus: Option[twitchscreen.relay.settings.StartupMonitoringStatus] = Some(selectedMonitoring.status)

    val activityLog = ActivityLog.start(config.activity, bus)
    val alertMonitor = RuntimeAlertMonitor.start(config.alerts, bus, hub, clock, effectiveTwitch.mode)
    val alertRules = alertMonitor.rules
    val alertStore = alertMonitor.store
    RelayMetrics.start(otel, bus, hub).discard

    // Source callbacks first enter the hub's reliable actor admission; optional bus consumers cannot lose authority.
    NotificationRouter.start(config.notifications, bus, hub, sourceOwned = true)
    // §13.1: one filter, built once from `notifications.ignored-display-names`, handed to whichever source is running.
    val botFilter = BotFilter.from(config.notifications)
    val oauth = startOAuth(config.twitch, SourceIngestion.applicationDiagnostics(bus, hub), clock)
    val twitch = RuntimeSource.start(
      effectiveTwitch,
      bus,
      hub,
      config.stats,
      botFilter,
      clock,
      oauth,
      activateFenced = Some(alertMonitor.activate)
    )

    val resolverClient = useInScope(twitchscreen.relay.twitch.ChannelResolver.client())(_.close())
    val resolver = twitchscreen.relay.twitch.ChannelResolver(config.twitch, resolverClient)
    val monitoring = twitchscreen.relay.twitch.MonitoringController(config.twitch, settings, hub, twitch, oauth, resolver.resolve)
    monitoring.start()

    DeviceLinkServer.start(config.deviceLink, hub, clock).discard

    val apis = autowire[Apis](
      autowireMembersOf(config),
      startupMonitoringStatus,
      clock,
      startedAt,
      bus,
      hub,
      settings,
      activityLog,
      alertStore,
      alertRules,
      oauth,
      twitch,
      monitoring,
      source
    )

    val recovery = RuntimeRecovery(hub, twitch, twitchscreen.relay.device.ClockDiscontinuity(() => clock.millis()))
    Dependencies(HttpApi(apis.all, config.http, otel), hub, twitch, Some(recovery))
