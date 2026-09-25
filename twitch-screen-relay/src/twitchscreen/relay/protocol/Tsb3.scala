package twitchscreen.relay.protocol

/** The constants of TSB/3 — Twitch Screen Binary Protocol, version 3, specified in `twitch-screen-firmware/docs/PROTOCOL.md`.
  *
  * Every number here is normative and none of them may be guessed: the firmware is written from the same document by someone who cannot
  * ask. Where the spec states a number, this object is the only place the relay repeats it.
  */
private[relay] object Tsb3:
  /** The version this relay speaks. §7 makes the check exact equality, not a range. */
  val Version: ProtocolVersion = ProtocolVersion.Three

  val Magic0: Byte = 0xa7.toByte
  val Magic1: Byte = 0x53.toByte

  val HeaderSize: Int = 8

  /** §5. `length` is counted in bytes — never characters, never UTF-16 units. */
  val MaxPayload: Int = 248
  val MaxFrame: Int = HeaderSize + MaxPayload

  /** Every v3 peer MUST be able to accept a 256-byte frame (§5). */
  val MinRxMax: Int = 256

  // String field widths (§5). The last byte of a field is always the NUL, so the content budget is one less.
  val DeviceIdWidth: Int = 32
  val FwVersionWidth: Int = 16
  val ActorWidth: Int = 48
  val TextWidth: Int = 96
  val ReasonWidth: Int = 24

  // §4.5 resync budget, per connection, reset on every frame that was decoded or deliberately skipped.
  val MaxRejectedCandidates: Int = 16
  val MaxDiscardedBytes: Int = 4096

  /** Payload field offsets, §6, relative to the first payload byte. They are named once, here, because the encoder and the decoder must
    * agree and because the firmware is written from the same tables by someone who cannot ask. A field of width *W* starts at an offset
    * that is a multiple of *W*, so every scalar lands naturally aligned behind the 8-byte header.
    */
  object Hello:
    val LastSeq: Int = 0
    val Caps: Int = 4
    val RxMax: Int = 8
    val Reserved0: Int = 10
    val DeviceId: Int = 12
    val FwVersion: Int = 44

  object Welcome:
    val LatestSeq: Int = 0
    val ServerTime: Int = 4
    val SessionId: Int = 8
    val MaxFrame: Int = 12
    val PingInterval: Int = 14
    val IdleTimeout: Int = 16
    val ReplayWindow: Int = 18
    val Caps: Int = 20

  object Event:
    val Seq: Int = 0
    val Ts: Int = 4
    val Value: Int = 8

    /** §8.1. Four bytes held open for a future monetary amount. Senders write zero; receivers ignore the content entirely, because blanking
      * bytes a later version is entitled to use defeats the forward compatibility they exist to provide.
      */
    val Reserved1: Int = 12
    val Months: Int = 16
    val TtlDs: Int = 18
    val Kind: Int = 20
    val Tier: Int = 21
    val EFlags: Int = 22

    /** §8.1. Held open as the decimal exponent of a future monetary amount. Written zero, ignored on receipt. */
    val Reserved2: Int = 23
    val Actor: Int = 24
    val Text: Int = 72

  object Stats:
    val Viewers: Int = 0
    val MsgTotal: Int = 4
    val UptimeS: Int = 8
    val Followers: Int = 12
    val Subs: Int = 16
    val ServerTime: Int = 20
    val StreamStartedAt: Int = 24
    val ChatRate: Int = 28
    val Live: Int = 30
    val SFlags: Int = 31

  object Heartbeat:
    val Token: Int = 0

  object Ack:
    val Seq: Int = 0

  object Bye:
    val Code: Int = 0
    val Detail: Int = 2
    val RetryAfter: Int = 4
    val Reserved0: Int = 6
    val Reason: Int = 8

  /** §3.1: `hchk = 0xFF XOR ((1*b0 + 2*b1 + … + 7*b6) mod 256)`.
    *
    * The weights are positional on purpose. A plain additive sum is invariant under the one-byte stream shift that moves `type` into
    * `length` and back — exactly the corruption this check exists to catch — and is therefore forbidden. `hchk` makes no integrity claim
    * about the payload; its only job is to make `length` trustworthy enough to skip by while resynchronising.
    */
  def headerCheck(bytes: Array[Byte], offset: Int = 0): Byte =
    var sum = 0
    var i = 0
    while i < HeaderSize - 1 do
      sum += (i + 1) * (bytes(offset + i) & 0xff)
      i += 1
    (0xff ^ (sum & 0xff)).toByte

/** The version byte carried at offset 2 of every frame (§3), frozen at that offset for all versions.
  *
  * Kept as a value rather than a constant because a `BYE` is always encoded with the version of the frame that provoked it: that is what
  * lets a peer read a refusal from a peer whose version it does not speak.
  */
private[relay] opaque type ProtocolVersion = Int

private[relay] object ProtocolVersion:
  val Three: ProtocolVersion = 3

  def fromWire(raw: Byte): ProtocolVersion = raw & 0xff

  extension (version: ProtocolVersion)
    def value: Int = version
    def toByte: Byte = version.toByte
    def isCurrent: Boolean = version == 3
