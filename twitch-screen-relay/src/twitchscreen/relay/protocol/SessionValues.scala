package twitchscreen.relay.protocol

/** The values the two peers exchange in `HELLO` and `WELCOME` (§6.1, §6.2). They exist only to describe those two records, so they live
  * beside them rather than in files of their own.
  */

/** The capability bitmap. A capability is in effect only if both peers have it; `WELCOME.caps` carries the intersection and is
  * authoritative for the connection. A missing capability MUST cause degradation, never a refusal, and unknown bits MUST be ignored.
  */
private[relay] opaque type Capabilities = Int

private[relay] object Capabilities:
  val Empty: Capabilities = 0x00

  /** The device will send `ACK` frames (§6.6). */
  val Ack: Capabilities = 0x01

  /** The device renders chat events and wants them. ANDed with the relay's own `notifications.chat` setting: the device's bit can only
    * narrow, never widen. Neither switch affects `msg_total` or `chat_rate` (§13).
    */
  val Chat: Capabilities = 0x02

  /** The device renders the generic kinds `0x00`…`0x03`. */
  val Generic: Capabilities = 0x04

  /** The device has a font beyond US-ASCII, so strings are sent verbatim instead of transliterated (§9.3). */
  val Utf8Text: Capabilities = 0x08

  /** Everything this relay implements. The `WELCOME` value is this intersected with what the device asked for. */
  val RelaySupported: Capabilities = Ack | Chat | Generic | Utf8Text

  def fromWire(raw: Long): Capabilities = (raw & 0xffffffffL).toInt

  extension (caps: Capabilities)
    def value: Int = caps
    def unsigned: Long = caps.toLong & 0xffffffffL
    def contains(other: Capabilities): Boolean = (caps & other) == other
    def |(other: Capabilities): Capabilities = caps | other
    def intersect(other: Capabilities): Capabilities = caps & other

/** The largest frame a peer accepts, in bytes, including the header (`HELLO.rx_max`, `WELCOME.max_frame`). Every v3 peer must accept at
  * least 256; a device declaring less is refused with `BYE(11 INVALID_PARAMETER)`, while a relay declaring less is only logged by the
  * device, so the type must be able to hold an unacceptable value.
  */
private[relay] opaque type FrameSize = Int

private[relay] object FrameSize:
  val Default: FrameSize = Tsb3.MaxFrame

  def fromWire(raw: Int): FrameSize = raw & 0xffff

  extension (size: FrameSize)
    def value: Int = size
    def isAcceptable: Boolean = size >= Tsb3.MinRxMax

/** Opaque handle on the relay's sequence space, changing once per relay process start. The device re-baselines whenever it changes, which
  * closes the v2 hole where a restarted relay that happened to reach a higher seq silently skipped the gap (§10.2).
  */
private[relay] opaque type SessionId = Long

private[relay] object SessionId:
  def apply(value: Long): Either[String, SessionId] =
    if value >= 0 && value <= 0xffffffffL then Right(value) else Left(s"Session id must fit in a u32: $value")

  def fromWire(raw: Long): SessionId = raw & 0xffffffffL

  extension (id: SessionId) def value: Long = id

/** How many durable events the relay retains for replay, as advertised in `WELCOME` (§10.3). */
private[relay] opaque type ReplayWindow = Int

private[relay] object ReplayWindow:
  /** §5's default: 64 durable events, plus a separate ring of 16 chat events. */
  val Durable: ReplayWindow = ReplayWindow.of(64)

  /** §6.2: what the relay advertises must be what it actually retains, so this is built from `device-link.replay-buffer-size` rather than
    * from the constant. A device that is told 64 and served 8 has no way to notice, and `last_seq` arithmetic is the only thing standing
    * between it and a silently lost card.
    */
  def of(size: Int): ReplayWindow = math.max(0, math.min(size, 0xffff))

  def fromWire(raw: Int): ReplayWindow = raw & 0xffff

  extension (window: ReplayWindow) def value: Int = window

/** The firmware version string a device reports. Diagnostics only: never parsed, never compared, never acted on. */
private[relay] opaque type FirmwareVersion = String

private[relay] object FirmwareVersion:
  val Unknown: FirmwareVersion = ""

  def fromWire(raw: String): FirmwareVersion = raw

  extension (version: FirmwareVersion) def value: String = version

/** The opaque `PING`/`PONG` payload (§6.3). A responder echoes it byte for byte and never interprets it; the device uses `millis() / 1000`
  * and the relay uses the epoch second.
  */
private[relay] opaque type Token = Long

private[relay] object Token:
  def apply(value: Long): Either[String, Token] =
    if value >= 0 && value <= 0xffffffffL then Right(value) else Left(s"Token must fit in a u32: $value")

  def fromWire(raw: Long): Token = raw & 0xffffffffL

  extension (token: Token) def value: Long = token
