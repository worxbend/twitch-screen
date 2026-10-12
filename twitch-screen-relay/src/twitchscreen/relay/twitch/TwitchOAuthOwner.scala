package twitchscreen.relay.twitch

import java.time.Clock
import ox.Ox
import scala.util.Try
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.TwitchConfig
import twitchscreen.relay.http.ServerEndpoints

/** Owns grant storage, refresh and consent routes in application scope, never in a replaceable ingestion scope. Missing or invalid OAuth
  * configuration disables only consent; independent generic operation remains available. Live selection validates its prerequisites itself.
  */
private[relay] final class TwitchOAuthOwner private[twitch] (private[twitch] val auth: Option[TwitchAuth]) extends ServerEndpoints:
  override val endpoints: List[ServerEndpoint[Any, Identity]] = auth.toList.flatMap(value => TwitchAuthApi(value).endpoints)

private[relay] object TwitchOAuthOwner:
  def start(config: TwitchConfig, bus: EventBus, clock: Clock)(using Ox): TwitchOAuthOwner =
    create(config, () => TwitchAuth.start(config, TwitchOAuthClient.live(config, clock), bus, clock))

  private[twitch] def create(config: TwitchConfig, startAuth: () => TwitchAuth): TwitchOAuthOwner =
    new TwitchOAuthOwner(Option.when(Try(config.validateOAuth()).isSuccess)(startAuth()))
