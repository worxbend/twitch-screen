package twitchscreen.relay.bus

import sttp.tapir.{Codec, CodecFormat, Schema}

/** Coarse grouping of [[RelayEvent]]s, used to filter the activity log and to scope alert rules. */
enum EventCategory:
  case Channel, Audience, Twitch, Device, Notification, Failure

object EventCategory:
  given Schema[EventCategory] = Schema.derivedEnumeration[EventCategory].defaultStringBased
  given Codec[String, EventCategory, CodecFormat.TextPlain] = Codec.derivedEnumeration[String, EventCategory].defaultStringBased
