package twitchscreen.relay.config

import org.slf4j.LoggerFactory
import pureconfig.{ConfigReader, ConfigSource}
import com.typesafe.config.{Config as HoconConfig, ConfigFactory}
import scala.concurrent.duration.FiniteDuration

/** Where the management/monitoring HTTP API listens. */
final case class HttpConfig(host: Hostname, port: Port, auth: HttpAuthConfig):
  auth.validate()

object HttpConfig:
  given ConfigReader[HttpConfig] = ValidatedConfigReader.derivedValidated[HttpConfig]

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
      outboundQueueCapacity >= replayBufferSize + DeviceLinkConfig.GreetingFrames,
      s"device-link.outbound-queue-capacity must hold replay-buffer-size + ${DeviceLinkConfig.GreetingFrames} greeting frames " +
        s"(WELCOME, ${DeviceLinkConfig.ChatReplaySize} chat replays, STATS)"
    )
    require(acceptBacklog > 0 && acceptBacklog <= 1024, "device-link.accept-backlog must be 1..1024")
    require(outboundQueueCapacity <= 65535 + DeviceLinkConfig.GreetingFrames, "device-link.outbound-queue-capacity is too large")
    // Not a wire value: it only bounds how long the relay waits for HELLO, so sub-second is fine.
    require(handshakeTimeout.toMillis >= 1, "device-link.handshake-timeout must be at least 1 ms")
    // §6.2: WELCOME carries both as u16 seconds, so anything the device cannot be told exactly is refused.
    require(pingInterval.toSeconds >= 1 && pingInterval.toSeconds <= 65535, "device-link.ping-interval must be 1..65535 seconds")
    require(wholeSeconds(pingInterval), "device-link.ping-interval must be a whole number of seconds (WELCOME carries u16 seconds)")
    require(idleTimeout.toSeconds >= 1 && idleTimeout.toSeconds <= 65535, "device-link.idle-timeout must be 1..65535 seconds")
    require(wholeSeconds(idleTimeout), "device-link.idle-timeout must be a whole number of seconds (WELCOME carries u16 seconds)")
    // §5: counted in bytes, header included, and every v3 peer must be able to accept 256 of them. A smaller
    // value here would have the relay refuse frames its own encoder is entitled to produce.
    require(maxFrameLength == 256, "device-link.max-frame-length must be exactly 256 bytes")
    // The firmware gives up after `idleTimeout` of silence, so the server must ping well inside that window. §12 says
    // this MUST be enforced at startup, and it is compared in the wire's seconds: that is what the device is told.
    require(
      pingInterval.toSeconds < idleTimeout.toSeconds,
      "device-link.ping-interval must be shorter than device-link.idle-timeout"
    )

  private def wholeSeconds(duration: FiniteDuration): Boolean = duration.toNanos % 1_000_000_000L == 0

/** EventSub delivery settings; only the fields of the selected `transport` are read. */
final case class EventSubConfig(transport: EventSubTransport, callbackUrl: String, secret: Sensitive):
  private[config] def validateLive(): Unit =
    if transport == EventSubTransport.Webhook then
      require(
        CallbackUrl.valid(callbackUrl, "/api/v1/twitch/eventsub", webhook = true),
        "twitch.event-sub.callback-url must be HTTPS on port 443 with path /api/v1/twitch/eventsub"
      )
      require(
        secret.isSet && secret.value.length >= 10 && secret.value.length <= 100 && secret.value.forall(_ <= 127),
        "twitch.event-sub.secret must contain 10..100 ASCII characters"
      )

object EventSubConfig:
  given ConfigReader[EventSubConfig] = ValidatedConfigReader.derivedValidated[EventSubConfig]

/** The browser consent flow that obtains the broadcaster's user token at runtime, so no Twitch token is ever configured.
  *
  * `GET /api/v1/twitch/authorize` sends the operator to Twitch; Twitch sends them back to `redirectUrl` with a code, which the relay
  * exchanges for an access and refresh token pair. The pair is kept in `tokenFile` and refreshed `refreshBefore` it expires, so consent is
  * given once per deployment rather than once per process.
  */
final case class TwitchOAuthConfig(redirectUrl: String, scopes: List[String], tokenFile: String, refreshBefore: FiniteDuration):
  require(refreshBefore.toMillis >= 1, "twitch.oauth.refresh-before must be at least 1 ms")

  private[config] def validateLive(): Unit =
    require(
      CallbackUrl.valid(redirectUrl, TwitchOAuthConfig.CallbackPath, webhook = false),
      s"twitch.oauth.redirect-url must be HTTPS (or HTTP loopback) with path ${TwitchOAuthConfig.CallbackPath}"
    )
    require(tokenFile.trim.nonEmpty, "twitch.oauth.token-file is required when twitch.mode = live")
    require(
      scopes.forall(_.matches("[a-z][a-z0-9]*(?::[a-z][a-z0-9_]*)*")) && scopes.distinct.size == scopes.size,
      "twitch.oauth.scopes must contain distinct nonblank scope names"
    )

object TwitchOAuthConfig:
  given ConfigReader[TwitchOAuthConfig] = ValidatedConfigReader.derivedValidated[TwitchOAuthConfig]

  /** The path the relay serves its callback on. Twitch redirects to `redirectUrl` verbatim, so it has to land here. */
  val CallbackPath: String = "/api/v1/twitch/callback"

  private given ConfigReader[List[String]] = StringListReader.listOrCommaSeparated

/** Pacing of the synthetic event generator used by [[TwitchMode.Simulated]]. */
final case class SimulationConfig(interval: FiniteDuration, chatInterval: FiniteDuration):
  require(interval.toMillis >= 1 && chatInterval.toMillis >= 1, "twitch.simulation intervals must be at least 1 ms")

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
    require(pollInterval.toMillis >= 1, "twitch.poll-interval must be at least 1 ms")
    if mode == TwitchMode.Live then
      require(channel.trim.nonEmpty, "twitch.channel is required when twitch.mode = live")
      require(clientId.trim.nonEmpty, "twitch.client-id is required when twitch.mode = live")
      require(clientSecret.isSet, "twitch.client-secret is required when twitch.mode = live")
      oauth.validateLive()
      eventSub.validateLive()

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
  require(
    defaultTtl.toNanos % 100_000_000L == 0 && defaultTtl.toMillis >= 100 && defaultTtl.toMillis <= 6553500,
    "notifications.default-ttl must be a multiple of 100 milliseconds in 100..6553500 ms " +
      "(EVENT carries u16 deciseconds; 0 would mean the device's per-kind default)"
  )

object NotificationsConfig:
  given ConfigReader[NotificationsConfig] = ValidatedConfigReader.derivedValidated[NotificationsConfig]

  /** §13.1: the list is overridable by environment variable. In lexical scope of the derived reader, so it applies here only. */
  private given ConfigReader[List[String]] = StringListReader.listOrCommaSeparated

  /** §13.1's default list. Each of these ships with its display name equal to its login apart from capitalisation, which is the whole basis
    * on which display-name matching works for them — a property of those particular accounts, not of Twitch.
    */
  val DefaultIgnoredDisplayNames: List[String] =
    List("streamelements", "nightbot", "moobot", "streamlabs", "fossabot", "sery_bot")

/** Per-subscriber queue depth on the internal event bus. A subscriber that falls this far behind starts losing events. */
final case class BusConfig(subscriberQueueCapacity: Int):
  ConfigLimits.buffer(subscriberQueueCapacity, "bus.subscriber-queue-capacity")

/** How often the aggregated dashboard figures are recomputed and pushed to every device. */
final case class StatsConfig(broadcastInterval: FiniteDuration, chatRateWindow: FiniteDuration):
  require(
    broadcastInterval.toMillis >= 1 && chatRateWindow.toSeconds >= 1,
    "stats broadcast interval must be at least 1 ms and rate window at least 1 s"
  )

/** Size of the in-memory activity ring buffer served by `GET /api/v1/activity`. */
final case class ActivityConfig(bufferSize: Int):
  ConfigLimits.buffer(bufferSize, "activity.buffer-size")

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
  ConfigLimits.buffer(bufferSize, "alerts.buffer-size")
  require(
    evaluationInterval.toMillis >= 1 && errorRateWindow.toSeconds >= 1,
    "alerts evaluation interval must be at least 1 ms and error window at least 1 s"
  )
  require(
    List(noDevicesConnectedFor, twitchDisconnectedFor, streamOfflineFor).flatten.forall(_.toMillis >= 1),
    "alerts thresholds must be at least 1 ms when enabled"
  )
  require(errorRateThreshold >= 0, "alerts.error-rate-threshold must be nonnegative")

/** Size of the in-memory log ring buffer served by `GET /api/v1/logs`. */
final case class ObservabilityConfig(logBufferSize: Int):
  ConfigLimits.buffer(logBufferSize, "observability.log-buffer-size")

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

  val Sections: List[String] = List("http", "device-link", "twitch", "notifications", "bus", "stats", "activity", "alerts", "observability")

  /** Every leaf path the typed configuration reads. `/config` masks anything else, so a key the relay never reads cannot leak its value. */
  val SchemaPaths: Set[String] =
    def leaves(prefix: String, keys: List[String], nested: String*): List[String] =
      keys.filterNot(nested.contains).map(key => s"$prefix.$key")
    Set.from(
      leaves("http", ConfigKeys.of[HttpConfig], "auth") ++ leaves("http.auth", ConfigKeys.of[HttpAuthConfig]) ++
        leaves("device-link", ConfigKeys.of[DeviceLinkConfig]) ++
        leaves("twitch", ConfigKeys.of[TwitchConfig], "oauth", "event-sub", "simulation") ++
        leaves("twitch.oauth", ConfigKeys.of[TwitchOAuthConfig]) ++ leaves("twitch.event-sub", ConfigKeys.of[EventSubConfig]) ++
        leaves("twitch.simulation", ConfigKeys.of[SimulationConfig]) ++ leaves("notifications", ConfigKeys.of[NotificationsConfig]) ++
        leaves("bus", ConfigKeys.of[BusConfig]) ++ leaves("stats", ConfigKeys.of[StatsConfig]) ++
        leaves("activity", ConfigKeys.of[ActivityConfig]) ++ leaves("alerts", ConfigKeys.of[AlertsConfig]) ++
        leaves("observability", ConfigKeys.of[ObservabilityConfig])
    )

  def load(): (Config, HoconConfig) =
    val source = ConfigFactory.load()
    (ConfigSource.fromConfig(source).loadOrThrow[Config], source)

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
  /** §5, §10.3: chat is replayed out of a ring of its own, so that a busy chat cannot evict a follow, a raid or a sub from the durable one.
    * The single source of truth for that ring's size; the device hub reads it from here.
    */
  private[relay] val ChatReplaySize: Int = 16

  /** §11.1: the frames a greeting burst adds on top of the durable replay — one WELCOME, the whole chat ring and one trailing STATS. */
  private[relay] val GreetingFrames: Int = ChatReplaySize + 2

  given ConfigReader[DeviceLinkConfig] = ValidatedConfigReader.derivedValidated[DeviceLinkConfig]

object SimulationConfig:
  given ConfigReader[SimulationConfig] = ValidatedConfigReader.derivedValidated[SimulationConfig]

object TwitchConfig:
  given ConfigReader[TwitchConfig] = ValidatedConfigReader.derivedValidated[TwitchConfig]

object BusConfig:
  given ConfigReader[BusConfig] = ValidatedConfigReader.derivedValidated[BusConfig]

object StatsConfig:
  given ConfigReader[StatsConfig] = ValidatedConfigReader.derivedValidated[StatsConfig]

object ActivityConfig:
  given ConfigReader[ActivityConfig] = ValidatedConfigReader.derivedValidated[ActivityConfig]

object AlertsConfig:
  given ConfigReader[AlertsConfig] = ValidatedConfigReader.derivedValidated[AlertsConfig]

object ObservabilityConfig:
  given ConfigReader[ObservabilityConfig] = ValidatedConfigReader.derivedValidated[ObservabilityConfig]
