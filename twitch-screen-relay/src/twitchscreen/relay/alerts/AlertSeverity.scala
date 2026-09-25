package twitchscreen.relay.alerts

import sttp.tapir.{Codec, CodecFormat, Schema}

/** How much an alert should worry whoever is reading it. */
enum AlertSeverity:
  case Warning, Critical

object AlertSeverity:
  given Schema[AlertSeverity] = Schema.derivedEnumeration[AlertSeverity].defaultStringBased
  given Codec[String, AlertSeverity, CodecFormat.TextPlain] = Codec.derivedEnumeration[String, AlertSeverity].defaultStringBased
