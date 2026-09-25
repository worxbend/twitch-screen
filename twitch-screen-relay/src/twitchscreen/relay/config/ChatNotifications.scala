package twitchscreen.relay.config

import pureconfig.ConfigReader
import sttp.tapir.Schema

/** Whether individual chat messages become on-screen notifications. A busy chat would otherwise bury every other event. */
enum ChatNotifications:
  case Show, Hide

object ChatNotifications:
  given ConfigReader[ChatNotifications] = ConfigReader[String].emap: raw =>
    values
      .find(_.toString.equalsIgnoreCase(raw))
      .toRight(ConfigReaderFailures.reason(s"Unknown chat notification setting '$raw', expected one of: ${values.mkString(", ")}"))

  given Schema[ChatNotifications] = Schema.derivedEnumeration[ChatNotifications].defaultStringBased
