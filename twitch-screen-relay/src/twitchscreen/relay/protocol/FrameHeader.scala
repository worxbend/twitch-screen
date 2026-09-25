package twitchscreen.relay.protocol

/** The header's flag byte (§3.2). A receiver MUST ignore bits it does not recognise and MUST NOT reject a frame because an unknown bit is
  * set, which is what makes this byte usable by a later version.
  */
private[relay] opaque type FrameFlags = Int

private[relay] object FrameFlags:
  val Empty: FrameFlags = 0x00

  /** Served from the replay buffer rather than live (§10.3). Setting it on a cached, pre-encoded frame rewrites exactly two bytes: the
    * flags byte at offset 6 and the header check at offset 7.
    */
  val Replay: FrameFlags = 0x01

  def fromWire(raw: Byte): FrameFlags = raw & 0xff

  extension (flags: FrameFlags)
    def value: Int = flags
    def toByte: Byte = flags.toByte
    def contains(other: FrameFlags): Boolean = (flags & other) == other
    def withFlag(other: FrameFlags): FrameFlags = flags | other
    def isReplay: Boolean = (flags & Replay) != 0

/** The eight bytes in front of every payload (§3): magic, version, type, length, flags and header check.
  *
  * `version` is deliberately not part of header validity: a header carrying an unknown version is a valid header with a version problem,
  * handled at the session layer (§7). That is exactly the mechanism that lets two peers of different versions exchange a `BYE`.
  */
private[relay] final case class FrameHeader(version: ProtocolVersion, typeCode: TypeCode, length: Int, flags: FrameFlags):
  def frameSize: Int = Tsb3.HeaderSize + length

private[relay] object FrameHeader:
  /** §4.2. A candidate is valid if and only if the magic matches, the header check matches, the type is not `0x00` and the length fits. */
  def decode(bytes: Array[Byte], offset: Int = 0): Either[ProtocolError, FrameHeader] =
    val magic0 = bytes(offset) & 0xff
    val magic1 = bytes(offset + 1) & 0xff
    val typeCode = bytes(offset + 3) & 0xff
    val length = (bytes(offset + 4) & 0xff) | ((bytes(offset + 5) & 0xff) << 8)
    val expected = Tsb3.headerCheck(bytes, offset) & 0xff
    val received = bytes(offset + 7) & 0xff
    if magic0 != (Tsb3.Magic0 & 0xff) || magic1 != (Tsb3.Magic1 & 0xff) then Left(ProtocolError.BadMagic(magic0, magic1))
    else if received != expected then Left(ProtocolError.HeaderCheckFailed(expected, received))
    else if typeCode == 0x00 then Left(ProtocolError.IllegalTypeCode)
    else if length > Tsb3.MaxPayload then Left(ProtocolError.LengthOutOfRange(length, Tsb3.MaxPayload))
    else
      Right(
        FrameHeader(
          version = ProtocolVersion.fromWire(bytes(offset + 2)),
          typeCode = TypeCode.fromWire(bytes(offset + 3)),
          length = length,
          flags = FrameFlags.fromWire(bytes(offset + 6))
        )
      )

  /** Writes the header of a frame whose payload occupies the rest of `frame`, including the header check over the seven bytes before it. */
  def encodeInto(frame: Array[Byte], version: ProtocolVersion, messageType: MessageType, flags: FrameFlags, length: Int): Unit =
    frame(0) = Tsb3.Magic0
    frame(1) = Tsb3.Magic1
    frame(2) = version.toByte
    frame(3) = messageType.code.toByte
    frame(4) = (length & 0xff).toByte
    frame(5) = ((length >>> 8) & 0xff).toByte
    frame(6) = flags.toByte
    frame(7) = Tsb3.headerCheck(frame)

/** One complete, well-framed TSB/3 frame: a valid header and exactly `header.length` payload bytes.
  *
  * Equality is identity on `payload`, as it is for any array; tests compare encoded hex rather than frames.
  */
private[relay] final case class Frame(header: FrameHeader, payload: Array[Byte])
