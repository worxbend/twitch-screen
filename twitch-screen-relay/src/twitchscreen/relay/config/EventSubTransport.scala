package twitchscreen.relay.config

import pureconfig.ConfigReader
import sttp.tapir.Schema

/** How Twitch delivers EventSub notifications to this relay. */
enum EventSubTransport:
  /** The relay opens an outbound WebSocket. Needs no inbound connectivity — the right choice on a home Raspberry Pi. */
  case WebSocket

  /** Twitch POSTs to `callback-url`. Needs a publicly reachable HTTPS endpoint and a shared secret. */
  case Webhook

object EventSubTransport:
  given ConfigReader[EventSubTransport] = EnumConfigReader("EventSub transport", values)

  given Schema[EventSubTransport] = Schema.derivedEnumeration[EventSubTransport].defaultStringBased
