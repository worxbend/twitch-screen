package twitchscreen.relay.config

import org.slf4j.LoggerFactory
import java.net.URI
import scala.util.Try
import pureconfig.{ConfigReader, ConfigSource}
import scala.concurrent.duration.FiniteDuration

/** Where the management/monitoring HTTP API listens. */
final case class HttpConfig(host: Hostname, port: Port, auth: HttpAuthConfig = HttpAuthConfig()) derives ConfigReader

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
):
  validate()

  private def validate(): Unit =
    // §7: the check is exact equality, and this build has exactly one encoder. A configuration claiming another
    // version would have the relay stamp a BYE with a version it cannot actually speak, which is the one frame
    // that has to be right when the two sides disagree.
    require(protocolVersion == 3, "device-link.protocol-version must be 3; this relay speaks TSB/3 and nothing else")
    require(replayBufferSize > 0 && replayBufferSize <= 65535, "device-link.replay-buffer-size must be 1..65535")
    require(
      outboundQueueCapacity >= replayBufferSize + 18,
      "device-link.outbound-queue-capacity must hold replay-buffer-size + 18 greeting frames"
    )
    require(acceptBacklog > 0, "device-link.accept-backlog must be positive")
    require(handshakeTimeout.toNanos > 0, "device-link.handshake-timeout must be positive")
    require(idleTimeout.toSeconds >= 1 && idleTimeout.toSeconds <= 65535, "device-link.idle-timeout must be 1..65535 seconds")
    require(pingInterval.toSeconds >= 1 && pingInterval.toSeconds <= 65535, "device-link.ping-interval must be 1..65535 seconds")
    // §5: counted in bytes, header included, and every v3 peer must be able to accept 256 of them. A smaller
    // value here would have the relay refuse frames its own encoder is entitled to produce.
    require(maxFrameLength == 256, "device-link.max-frame-length must be exactly 256 bytes")
    // The firmware gives up after `idleTimeout` of silence, so the server must ping well inside that window.
    require(pingInterval < idleTimeout, "device-link.ping-interval must be shorter than device-link.idle-timeout")

/** EventSub delivery settings; only the fields of the selected `transport` are read. */
final case class EventSubConfig(transport: EventSubTransport, callbackUrl: String, secret: Sensitive) derives ConfigReader

/** The browser consent flow that obtains the broadcaster's user token at runtime, so no Twitch token is ever configured.
  *
  * `GET /api/v1/twitch/authorize` sends the operator to Twitch; Twitch sends them back to `redirectUrl` with a code, which the relay
  * exchanges for an access and refresh token pair. The pair is kept in `tokenFile` and refreshed `refreshBefore` it expires, so consent is
  * given once per deployment rather than once per process.
  */
final case class TwitchOAuthConfig(redirectUrl: String, scopes: List[String], tokenFile: String, refreshBefore: FiniteDuration)
    derives ConfigReader

object TwitchOAuthConfig:
  /** The path the relay serves its callback on. Twitch redirects to `redirectUrl` verbatim, so it has to land here. */
  val CallbackPath: String = "/api/v1/twitch/callback"

  private given ConfigReader[List[String]] = StringListReader.listOrCommaSeparated

/** Pacing of the synthetic event generator used by [[TwitchMode.Simulated]]. */
final case class SimulationConfig(interval: FiniteDuration, chatInterval: FiniteDuration):
  require(interval.toNanos > 0 && chatInterval.toNanos > 0, "twitch.simulation intervals must be positive")

final case class TwitchConfig(
    mode: TwitchMode,
    channel: String,
    clientId: String,
    clientSecret: Sensitive,
    oauth: TwitchOAuthConfig,
    eventSub: EventSubConfig,
    pollInterval: FiniteDuration,
    simulation: SimulationConfig
):
  validate()

  /** Fails startup rather than at the first Helix call, so a half-configured relay never looks healthy. */
  private def validate(): Unit =
    require(pollInterval.toNanos > 0, "twitch.poll-interval must be positive")
    require(oauth.refreshBefore.toNanos > 0, "twitch.oauth.refresh-before must be positive")
    if mode == TwitchMode.Live then
      require(channel.trim.nonEmpty, "twitch.channel is required when twitch.mode = live")
      require(clientId.trim.nonEmpty, "twitch.client-id is required when twitch.mode = live")
      require(clientSecret.isSet, "twitch.client-secret is required when twitch.mode = live")
      require(
        validCallback(oauth.redirectUrl, TwitchOAuthConfig.CallbackPath, webhook = false),
        "twitch.oauth.redirect-url must be an absolute http(s) URL"
      )
      require(
        oauth.redirectUrl.endsWith(TwitchOAuthConfig.CallbackPath),
        s"twitch.oauth.redirect-url must end in ${TwitchOAuthConfig.CallbackPath}, where the relay serves the callback"
      )
      require(oauth.tokenFile.trim.nonEmpty, "twitch.oauth.token-file is required when twitch.mode = live")
      if eventSub.transport == EventSubTransport.Webhook then
        require(
          validCallback(eventSub.callbackUrl, "/api/v1/twitch/eventsub", webhook = true),
          "twitch.event-sub.callback-url must be HTTPS on port 443 with path /api/v1/twitch/eventsub"
        )
        require(
          eventSub.secret.isSet && eventSub.secret.value.length >= 10 && eventSub.secret.value.length <= 100 &&
            eventSub.secret.value.forall(_ <= 127),
          "twitch.event-sub.secret must contain 10..100 ASCII characters"
        )

  private def validCallback(raw: String, path: String, webhook: Boolean): Boolean =
    Try(URI(raw)).toOption.exists: uri =>
      val transport =
        if webhook then uri.getScheme == "https" && (uri.getPort == -1 || uri.getPort == 443)
        else uri.getScheme == "https" || (uri.getScheme == "http" && Set("localhost", "127.0.0.1", "[::1]").contains(uri.getHost))
      transport && Option(uri.getHost).exists(_.nonEmpty) && uri.getPath == path && uri.getRawQuery == null &&
      uri.getRawFragment == null && uri.getRawUserInfo == null

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
):
  require(defaultTtl.toMillis > 0 && defaultTtl.toMillis <= 6553500, "notifications.default-ttl must be 1..6553500 milliseconds")

object NotificationsConfig:
  given ConfigReader[NotificationsConfig] = ValidatedConfigReader(ConfigReader.derived[NotificationsConfig])

  /** §13.1: the list is overridable by environment variable. In lexical scope of the derived reader, so it applies here only. */
  private given ConfigReader[List[String]] = StringListReader.listOrCommaSeparated

  /** §13.1's default list. Each of these ships with its display name equal to its login apart from capitalisation, which is the whole basis
    * on which display-name matching works for them — a property of those particular accounts, not of Twitch.
    */
  val DefaultIgnoredDisplayNames: List[String] =
    List("streamelements", "nightbot", "moobot", "streamlabs", "fossabot", "sery_bot")

/** Per-subscriber queue depth on the internal event bus. A subscriber that falls this far behind starts losing events. */
final case class BusConfig(subscriberQueueCapacity: Int):
  require(subscriberQueueCapacity > 0, "bus.subscriber-queue-capacity must be positive")

/** How often the aggregated dashboard figures are recomputed and pushed to every device. */
final case class StatsConfig(broadcastInterval: FiniteDuration, chatRateWindow: FiniteDuration):
  require(broadcastInterval.toNanos > 0 && chatRateWindow.toNanos > 0, "stats intervals must be positive")

/** Size of the in-memory activity ring buffer served by `GET /api/v1/activity`. */
final case class ActivityConfig(bufferSize: Int):
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
):
  require(bufferSize > 0, "alerts.buffer-size must be positive")
  require(evaluationInterval.toNanos > 0 && errorRateWindow.toNanos > 0, "alerts intervals must be positive")
  require(
    List(noDevicesConnectedFor, twitchDisconnectedFor, streamOfflineFor).flatten.forall(_.toNanos > 0),
    "alerts thresholds must be positive when enabled"
  )
  require(errorRateThreshold >= 0, "alerts.error-rate-threshold must be nonnegative")

/** Size of the in-memory log ring buffer served by `GET /api/v1/logs`. */
final case class ObservabilityConfig(logBufferSize: Int):
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

object DeviceLinkConfig:
  given ConfigReader[DeviceLinkConfig] = ValidatedConfigReader(ConfigReader.derived[DeviceLinkConfig])

object SimulationConfig:
  given ConfigReader[SimulationConfig] = ValidatedConfigReader(ConfigReader.derived[SimulationConfig])

object TwitchConfig:
  given ConfigReader[TwitchConfig] = ValidatedConfigReader(ConfigReader.derived[TwitchConfig])

object BusConfig:
  given ConfigReader[BusConfig] = ValidatedConfigReader(ConfigReader.derived[BusConfig])

object StatsConfig:
  given ConfigReader[StatsConfig] = ValidatedConfigReader(ConfigReader.derived[StatsConfig])

object ActivityConfig:
  given ConfigReader[ActivityConfig] = ValidatedConfigReader(ConfigReader.derived[ActivityConfig])

object AlertsConfig:
  given ConfigReader[AlertsConfig] = ValidatedConfigReader(ConfigReader.derived[AlertsConfig])

object ObservabilityConfig:
  given ConfigReader[ObservabilityConfig] = ValidatedConfigReader(ConfigReader.derived[ObservabilityConfig])
