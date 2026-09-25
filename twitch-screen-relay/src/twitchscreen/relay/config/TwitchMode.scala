package twitchscreen.relay.config

import pureconfig.ConfigReader
import sttp.tapir.Schema

/** Which Twitch event source the relay runs. */
enum TwitchMode:
  /** No Twitch connection at all; the relay only serves whatever is pushed through its HTTP API. */
  case Disabled

  /** Synthetic events on a timer, so firmware and enclosure work can proceed without Twitch credentials. */
  case Simulated

  /** The real thing: Helix, chat and EventSub through twitch4j. */
  case Live

object TwitchMode:
  given ConfigReader[TwitchMode] = EnumConfigReader("twitch mode", values)

  given Schema[TwitchMode] = Schema.derivedEnumeration[TwitchMode].defaultStringBased
