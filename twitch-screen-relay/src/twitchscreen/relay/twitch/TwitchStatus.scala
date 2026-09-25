package twitchscreen.relay.twitch

import sttp.tapir.Schema
import twitchscreen.relay.config.TwitchMode

/** How the relay's connection to Twitch is doing. */
enum TwitchHealth:
  /** No Twitch connection was asked for. */
  case Disabled

  /** Credentials accepted, still opening chat and EventSub. */
  case Connecting

  case Connected

  /** Some integration paths are usable, while others await consent or recovery. */
  case Degraded

  /** Configured, but not currently usable — the detail says why. */
  case Disconnected

object TwitchHealth:
  given Schema[TwitchHealth] = Schema.derivedEnumeration[TwitchHealth].defaultStringBased

final case class TwitchStatus(mode: TwitchMode, health: TwitchHealth, channel: String, detail: String) derives Schema
