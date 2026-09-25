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

  /** Endpoints this source needs mounted. Only the EventSub webhook transport has any; everything else returns none. */
  override def endpoints: List[ServerEndpoint[Any, Identity]]

private[relay] object TwitchSource:
  /** Starts the configured source. Everything it forks or opens stops when the enclosing scope ends. */
  def start(config: TwitchConfig, bus: EventBus, filter: BotFilter, clock: Clock)(using Ox): TwitchSource = config.mode match
    case TwitchMode.Disabled  => DisabledTwitchSource(config)
    case TwitchMode.Simulated => SimulatedTwitchSource.start(config, bus, filter, clock)
    case TwitchMode.Live      => LiveTwitchSource.start(config, bus, filter, clock)
