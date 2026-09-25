package twitchscreen.relay.config

import pureconfig.ConfigReader
import sttp.tapir.Schema

/** Whether individual chat messages become on-screen notifications. A busy chat would otherwise bury every other event. */
enum ChatNotifications:
  case Show, Hide

object ChatNotifications:
  given ConfigReader[ChatNotifications] = EnumConfigReader("chat notification setting", values)

  given Schema[ChatNotifications] = Schema.derivedEnumeration[ChatNotifications].defaultStringBased
