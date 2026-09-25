package twitchscreen.relay.protocol

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import sttp.tapir.{Codec, CodecFormat, DecodeResult, Schema}

/** The `kind` byte of a TSB/3 `EVENT` (§6.4.1), and the `type` field of the HTTP notification API.
  *
  * The `code` travels on the wire; the `wire` string stays the HTTP API's vocabulary, which is why both live here. The numbering is the
  * spec's and is frozen: `0x00`–`0x0f` generic severities, `0x10`–`0x3f` stream and audience events. The firmware picks an icon and accent
  * colour per kind and renders anything it does not recognise as [[Info]], using `actor` as the title and `text` as the body.
  *
  * `isLifecycle` marks the stream lifecycle kinds: the hub follows their `EVENT` with a `STATS` snapshot whenever the stats state is known.
  */
enum NotificationKind(
    val wire: String,
    val code: Int,
    val defaultTtl: FiniteDuration = 8.seconds,
    val foldsToPlaceholder: Boolean = false,
    val isGeneric: Boolean = false,
    val isDurable: Boolean = true,
    val isLifecycle: Boolean = false
):
  case Follow extends NotificationKind("follow", 0x12, 6.seconds, foldsToPlaceholder = true)
  case Sub extends NotificationKind("sub", 0x13, foldsToPlaceholder = true)
  case Gift extends NotificationKind("gift", 0x14, foldsToPlaceholder = true)
  case Raid extends NotificationKind("raid", 0x15, 10.seconds, foldsToPlaceholder = true)
  case Chat extends NotificationKind("chat", 0x16, 6.seconds, foldsToPlaceholder = true, isDurable = false)
  case Bits extends NotificationKind("bits", 0x17, foldsToPlaceholder = true)
  case Info extends NotificationKind("info", 0x00, isGeneric = true)
  case Message extends NotificationKind("message", 0x01, isGeneric = true)
  case Warning extends NotificationKind("warning", 0x02, isGeneric = true)
  case Alert extends NotificationKind("alert", 0x03, isGeneric = true)
  case StreamStart extends NotificationKind("stream_start", 0x10, 10.seconds, isLifecycle = true)
  case StreamEnd extends NotificationKind("stream_end", 0x11, 10.seconds, isLifecycle = true)

object NotificationKind:
  /** What an unrecognised wire value degrades to, matching the firmware's own fallback. */
  val Fallback: NotificationKind = Info

  /** Lenient: used for values arriving from a device, which must never fail a connection. */
  def fromWire(raw: String): NotificationKind = parse(raw).getOrElse(Fallback)

  /** §6.4.2: a kind this build does not know is rendered as [[Info]] from `actor`/`text`, never dropped and never fatal. */
  def fromCode(raw: Int): NotificationKind = values.find(_.code == raw).getOrElse(Fallback)

  /** Strict: used for values arriving through the HTTP API, where a typo deserves a 400. */
  def parse(raw: String): Either[String, NotificationKind] =
    values.find(_.wire == raw).toRight(s"Unknown notification type '$raw', expected one of: ${values.map(_.wire).mkString(", ")}")

  given Schema[NotificationKind] = Schema.derivedEnumeration[NotificationKind](encode = Some(_.wire))
  given Codec[String, NotificationKind, CodecFormat.TextPlain] =
    Codec.string.mapDecode(raw => DecodeResult.fromEitherString(raw, parse(raw)))(_.wire)
