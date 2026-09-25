package twitchscreen.relay.twitch

import org.slf4j.LoggerFactory
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.config.TwitchConfig

/** No Twitch connection. The relay still accepts devices and still pushes whatever arrives through its HTTP API, which is what makes it
  * useful as a plain notification relay.
  */
private[twitch] final class DisabledTwitchSource(config: TwitchConfig) extends TwitchSource:
  LoggerFactory.getLogger(getClass).info("Twitch integration is disabled; notifications can still be pushed over HTTP")

  override val status: TwitchStatus =
    TwitchStatus(config.mode, TwitchHealth.Disabled, config.channel, "twitch.mode = disabled")

  override val endpoints: List[ServerEndpoint[Any, Identity]] = Nil
