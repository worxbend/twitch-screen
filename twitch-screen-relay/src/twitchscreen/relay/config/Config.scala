package twitchscreen.relay.config

import org.slf4j.LoggerFactory
import pureconfig.{ConfigReader, ConfigSource}
import scala.concurrent.duration.FiniteDuration

/** Where the management/monitoring HTTP API listens. */
final case class HttpConfig(host: Hostname, port: Port) derives ConfigReader

/** The TCP listener the firmware connects to, and the parameters of TSB/3 (see `twitch-screen-firmware/docs/PROTOCOL.md`). */
final case class DeviceLinkConfig(
    host: Hostname,
    port: Port,
    protocolVersion: Int,
    acceptBacklog: Int,
    handshakeTimeout: FiniteDuration,
    idleTimeout: FiniteDuration,
    pingInterval: FiniteDuration,
    outboundQueueCapacity: Int,
    replayBufferSize: Int,
    maxFrameLength: Int
) derives ConfigReader:
  validate()

  private def validate(): Unit =
    // §7: the check is exact equality, and this build has exactly one encoder. A configuration claiming another
    // version would have the relay stamp a BYE with a version it cannot actually speak, which is the one frame
    // that has to be right when the two sides disagree.
    require(protocolVersion == 3, "device-link.protocol-version must be 3; this relay speaks TSB/3 and nothing else")
    require(replayBufferSize > 0, "device-link.replay-buffer-size must be positive")
    require(outboundQueueCapacity > 0, "device-link.outbound-queue-capacity must be positive")
    // §5: counted in bytes, header included, and every v3 peer must be able to accept 256 of them. A smaller
    // value here would have the relay refuse frames its own encoder is entitled to produce.
    require(maxFrameLength >= 256, "device-link.max-frame-length must be at least 256 bytes")
    // The firmware gives up after `idleTimeout` of silence, so the server must ping well inside that window.
    require(pingInterval < idleTimeout, "device-link.ping-interval must be shorter than device-link.idle-timeout")

/** EventSub delivery settings; only the fields of the selected `transport` are read. */
final case class EventSubConfig(transport: EventSubTransport, callbackUrl: String, secret: Sensitive) derives ConfigReader

/** Pacing of the synthetic event generator used by [[TwitchMode.Simulated]]. */
final case class SimulationConfig(interval: FiniteDuration, chatInterval: FiniteDuration) derives ConfigReader

final case class TwitchConfig(
    mode: TwitchMode,
    channel: String,
    clientId: String,
    clientSecret: Sensitive,
    userAccessToken: Sensitive,
    chatAccessToken: Sensitive,
    eventSub: EventSubConfig,
    pollInterval: FiniteDuration,
    simulation: SimulationConfig
) derives ConfigReader:
  validate()

  /** Fails startup rather than at the first Helix call, so a half-configured relay never looks healthy. */
  private def validate(): Unit =
    if mode == TwitchMode.Live then
      require(channel.trim.nonEmpty, "twitch.channel is required when twitch.mode = live")
      require(clientId.trim.nonEmpty, "twitch.client-id is required when twitch.mode = live")
      require(clientSecret.isSet, "twitch.client-secret is required when twitch.mode = live")
      if eventSub.transport == EventSubTransport.Webhook then
        require(eventSub.callbackUrl.trim.nonEmpty, "twitch.event-sub.callback-url is required for the webhook transport")
        require(eventSub.secret.isSet, "twitch.event-sub.secret is required for the webhook transport")

/** How Twitch events are turned into what the screen shows.
  *
  * `ignoredDisplayNames` is §13.1's bot list. It is named for what it actually matches — the lowercased, trimmed **display name** — because
  * the relay carries no Twitch login and cannot match on one. §13.1 states the weakness plainly: a bot that changes its display name stops
  * being matched until this list is updated, and a human who sets their display name to `Nightbot` is silently suppressed.
  */
final case class NotificationsConfig(
    defaultTtl: FiniteDuration,
    chat: ChatNotifications,
    ignoredDisplayNames: List[String] = NotificationsConfig.DefaultIgnoredDisplayNames
) derives ConfigReader

object NotificationsConfig:
  /** §13.1: the list is overridable by environment variable, and HOCON's `${?VAR}` yields a STRING, so accept either a HOCON list or a
    * comma-separated string (split on `,`, trimmed, empties dropped). In lexical scope of the derived reader, so it applies here only.
    */
  private given ConfigReader[List[String]] =
    ConfigReader[Vector[String]]
      .map(_.toList)
      .orElse(ConfigReader[String].map(_.split(',').iterator.map(_.trim).filter(_.nonEmpty).toList))

  /** §13.1's default list. Each of these ships with its display name equal to its login apart from capitalisation, which is the whole basis
    * on which display-name matching works for them — a property of those particular accounts, not of Twitch.
    */
  val DefaultIgnoredDisplayNames: List[String] =
    List("streamelements", "nightbot", "moobot", "streamlabs", "fossabot", "sery_bot")

/** Per-subscriber queue depth on the internal event bus. A subscriber that falls this far behind starts losing events. */
final case class BusConfig(subscriberQueueCapacity: Int) derives ConfigReader:
  require(subscriberQueueCapacity > 0, "bus.subscriber-queue-capacity must be positive")

/** How often the aggregated dashboard figures are recomputed and pushed to every device. */
final case class StatsConfig(broadcastInterval: FiniteDuration, chatRateWindow: FiniteDuration) derives ConfigReader

/** Size of the in-memory activity ring buffer served by `GET /api/v1/activity`. */
final case class ActivityConfig(bufferSize: Int) derives ConfigReader:
  require(bufferSize > 0, "activity.buffer-size must be positive")

/** Thresholds for the built-in alert rules. A threshold left unset disables its rule. */
final case class AlertsConfig(
    evaluationInterval: FiniteDuration,
    bufferSize: Int,
    noDevicesConnectedFor: Option[FiniteDuration],
    twitchDisconnectedFor: Option[FiniteDuration],
    streamOfflineFor: Option[FiniteDuration],
    errorRateThreshold: Int,
    errorRateWindow: FiniteDuration
) derives ConfigReader:
  require(bufferSize > 0, "alerts.buffer-size must be positive")

/** Size of the in-memory log ring buffer served by `GET /api/v1/logs`. */
final case class ObservabilityConfig(logBufferSize: Int) derives ConfigReader:
  require(logBufferSize > 0, "observability.log-buffer-size must be positive")

final case class Config(
    http: HttpConfig,
    deviceLink: DeviceLinkConfig,
    twitch: TwitchConfig,
    notifications: NotificationsConfig,
    bus: BusConfig,
    stats: StatsConfig,
    activity: ActivityConfig,
    alerts: AlertsConfig,
    observability: ObservabilityConfig
) derives ConfigReader

object Config:
  private val logger = LoggerFactory.getLogger(getClass)

  def read: Config = ConfigSource.default.loadOrThrow[Config]

  /** `Sensitive` masks itself in `toString`, so the whole tree is safe to log. */
  def log(config: Config): Unit =
    logger.info(
      s"""Relay configuration:
         |  HTTP:          ${config.http}
         |  Device link:   ${config.deviceLink}
         |  Twitch:        ${config.twitch}
         |  Notifications: ${config.notifications}
         |  Bus:           ${config.bus}
         |  Stats:         ${config.stats}
         |  Activity:      ${config.activity}
         |  Alerts:        ${config.alerts}
         |  Observability: ${config.observability}""".stripMargin
    )
