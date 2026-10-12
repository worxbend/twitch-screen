package twitchscreen.relay.twitch

import java.time.Clock
import ox.Ox
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.{TwitchConfig, TwitchMode}
import twitchscreen.relay.http.ServerEndpoints

/** Where [[twitchscreen.relay.bus.RelayEvent]]s about the channel come from.
  *
  * Three implementations, chosen by configuration: none at all, a deterministic simulator for working on the firmware and the enclosure
  * without Twitch credentials, and the real twitch4j integration. Everything downstream — the notification routing, the statistics, the
  * alerts — is written against the bus and cannot tell which one is running.
  */
private[relay] trait TwitchSource extends ServerEndpoints:
  def status: TwitchStatus

  /** Called after the HTTP callback and device listeners are bound. */
  def startIngestion()(using Ox): Unit = ()

  /** The application supplies stable replay-window ownership before exposing a replaceable callback. */
  private[twitch] def webhookOwner: Option[EventSubWebhookApi] = None

  /** Replaceable webhook logic. The application owner mounts one stable dispatch route. */
  def webhookCallback: Option[(String, String, String, String, String) => Either[twitchscreen.relay.http.Fail, String]] = None

  /** Ingestion-only routes; OAuth belongs to the application owner. */
  override def endpoints: List[ServerEndpoint[Any, Identity]]

private[relay] object TwitchSource:
  /** Starts the configured source. Everything it forks or opens stops when the enclosing scope ends. */
  def start(config: TwitchConfig, bus: EventBus, filter: BotFilter, clock: Clock)(using Ox): TwitchSource =
    val oauth = TwitchOAuthOwner.start(config, bus, clock)
    val source = create(config, bus, filter, clock, oauth)
    new TwitchSource:
      override private[twitch] def webhookOwner: Option[EventSubWebhookApi] = source.webhookOwner
      override def status: TwitchStatus = source.status
      override def endpoints: List[ServerEndpoint[Any, Identity]] = oauth.endpoints ++ source.endpoints
      override def startIngestion()(using Ox): Unit = source.startIngestion()

  /** Production passes the application owner explicitly; replacement creates ingestion only, not another grant owner. */
  def create(config: TwitchConfig, bus: EventBus, filter: BotFilter, clock: Clock, oauth: TwitchOAuthOwner)(using Ox): TwitchSource =
    config.mode match
      case TwitchMode.Disabled  => DisabledTwitchSource(config)
      case TwitchMode.Simulated => SimulatedTwitchSource.start(config, bus, filter, clock)
      case TwitchMode.Live =>
        config.validateLive()
        LiveTwitchSource.create(config, bus, filter, clock, oauth.auth.getOrElse(throw IllegalStateException("OAuth owner unavailable")))
