package twitchscreen.relay.protocol

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

/** The `EVENT` payload's primary number (§6.4). One `u32` whose meaning is fixed per kind: a stream start, a stream duration, a gifted-sub
  * count, a raider's viewer count, a chat colour or a bits count. There is no monetary reading of this field and there is no money on this
  * wire at all (§8). A kind that does not use it MUST write 0.
  */
private[relay] opaque type EventValue = Long

private[relay] object EventValue:
  val Zero: EventValue = 0L
  val Max: EventValue = 0xffffffffL

  def apply(value: Long): Either[String, EventValue] =
    if value >= 0 && value <= 0xffffffffL then Right(value) else Left(s"Event value must fit in a u32: $value")

  /** For figures from Twitch, where a negative or oversized number should degrade rather than fail an event. */
  def clamp(value: Long): EventValue = if value <= 0 then 0L else if value > 0xffffffffL then 0xffffffffL else value

  def fromWire(raw: Long): EventValue = raw & 0xffffffffL

  extension (amount: EventValue) def value: Long = amount

/** How long the device should hold the card, in units of 100 ms (§6.4). Zero means "use the receiver's per-kind default". */
private[relay] opaque type DisplayTtl = Int

private[relay] object DisplayTtl:
  /** Let the device pick, per kind. */
  val Default: DisplayTtl = 0

  /** §6.4: ten minutes. Values above this are clamped by the receiver, so a sender's mistake costs one long card rather than an hour and
    * forty-nine minutes of a 240×240 display showing one follow.
    */
  val Max: DisplayTtl = 6000

  def fromDuration(ttl: FiniteDuration): DisplayTtl =
    val deciseconds = ttl.toMillis / 100
    if deciseconds <= 0 then 0 else if deciseconds > 0xffff then 0xffff else deciseconds.toInt

  /** §6.4's receiver clamp, applied here because this is the relay's receiving edge. The relay is only ever an `EVENT` sender in
    * production, but a decoder that is not a conformant receiver cannot be trusted to catch a sender that is not a conformant sender, and
    * catching that is what the round trip through §18's vectors is for.
    */
  def fromWire(raw: Int): DisplayTtl = math.min(raw & 0xffff, Max)

  /** §6.4.1's per-kind display time, pinned frame by frame by vectors V6–V15.
    *
    * Every Twitch-sourced event takes its time from here. One uniform value across all twelve kinds is legal and wrong: it gives a follow
    * the same share of the display as a stream transition, and a large one turns §10.3's reconnect burst into a slideshow that keeps the
    * idle dashboard off the screen for minutes while the device's 8-entry queue drains one card at a time.
    */
  def defaultFor(kind: NotificationKind): FiniteDuration = kind.defaultTtl

  extension (ttl: DisplayTtl)
    def deciseconds: Int = ttl
    def duration: FiniteDuration = FiniteDuration(ttl.toLong * 100, TimeUnit.MILLISECONDS)

/** A chat name colour, `0x00rrggbb`. Only meaningful when `eflags.CHAT_COLOUR_PRESENT` is set, because black is a legal colour and would
  * otherwise be indistinguishable from "not reported".
  */
private[relay] opaque type ChatColour = Int

private[relay] object ChatColour:
  def apply(rgb: Int): ChatColour = rgb & 0x00ffffff

  /** Twitch reports the colour as `#RRGGBB`, and as an empty string for a user who never picked one. */
  def fromHex(raw: String): Option[ChatColour] =
    val hex = raw.trim.stripPrefix("#")
    if hex.length == 6 && hex.forall(c => Character.digit(c, 16) >= 0) then Some(Integer.parseInt(hex, 16) & 0x00ffffff) else None

  extension (colour: ChatColour) def rgb: Int = colour

/** The `eflags` byte of an `EVENT` (§6.4). Unknown bits MUST be ignored. */
private[relay] opaque type EventFlags = Int

private[relay] object EventFlags:
  val Empty: EventFlags = 0x00
  val TextTruncated: EventFlags = 0x01
  val ActorTruncated: EventFlags = 0x02
  val Anonymous: EventFlags = 0x08
  val ChatColourPresent: EventFlags = 0x10

  def fromWire(raw: Int): EventFlags = raw & 0xff

  extension (flags: EventFlags)
    def value: Int = flags
    def contains(other: EventFlags): Boolean = (flags & other) == other
    def withFlag(other: EventFlags): EventFlags = flags | other

/** The one record behind every notification and every stream event: `EVENT`, 168 bytes, one layout and one decode path for all twelve kinds
  * (§6.4.1). Field meanings are fixed per kind by §6.4.1, which the notification router maps from incoming events.
  *
  * `actor` and `text` are the values *before* truncation: the encoder truncates them to the field widths per §9.2 and sets the matching
  * `eflags` bit, so a caller can never produce a record whose flags disagree with its bytes.
  */
private[relay] final case class EventRecord(
    seq: SeqNo,
    at: Option[Instant],
    value: EventValue,
    months: SubMonths,
    ttl: DisplayTtl,
    kind: NotificationKind,
    tier: SubTier,
    flags: EventFlags,
    actor: String,
    text: String
)
