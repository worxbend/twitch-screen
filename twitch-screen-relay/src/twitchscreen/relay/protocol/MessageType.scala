package twitchscreen.relay.protocol

/** Which way a frame travels. §6 partitions the type-code space by direction so that a confused peer is told apart from a corrupt stream: a
  * frame carrying the receiver's own outbound code is a misrouted frame, not garbage.
  */
private[relay] enum WireDirection:
  case DeviceToRelay, RelayToDevice

private[relay] object WireDirection:
  /** The ranges are frozen: `0x01`…`0x1f` device to relay, `0x20`…`0x3f` relay to device, everything else reserved. */
  def ofCode(code: Int): Option[WireDirection] =
    if code >= 0x01 && code <= 0x1f then Some(DeviceToRelay)
    else if code >= 0x20 && code <= 0x3f then Some(RelayToDevice)
    else None

/** The ten message types of TSB/3 (§6). `baseLength` is the payload length this version defines; a receiver rejects anything shorter as a
  * frame error and accepts anything longer, decoding the first `baseLength` bytes and ignoring the tail (§16 rule 2).
  */
private[relay] enum MessageType(val code: Int, val direction: WireDirection, val baseLength: Int):
  case Hello extends MessageType(0x01, WireDirection.DeviceToRelay, 60)
  case DevicePing extends MessageType(0x02, WireDirection.DeviceToRelay, 4)
  case DevicePong extends MessageType(0x03, WireDirection.DeviceToRelay, 4)
  case Ack extends MessageType(0x04, WireDirection.DeviceToRelay, 4)
  case Welcome extends MessageType(0x20, WireDirection.RelayToDevice, 24)
  case Event extends MessageType(0x21, WireDirection.RelayToDevice, 168)
  case Stats extends MessageType(0x22, WireDirection.RelayToDevice, 32)
  case RelayPing extends MessageType(0x23, WireDirection.RelayToDevice, 4)
  case RelayPong extends MessageType(0x24, WireDirection.RelayToDevice, 4)
  case Bye extends MessageType(0x25, WireDirection.RelayToDevice, 32)

private[relay] object MessageType:
  def fromCode(code: Int): Option[MessageType] = values.find(_.code == code)

/** The raw `type` byte of a frame header. It is not a [[MessageType]] because a peer of a later version may legitimately send a code this
  * build does not know, which §4.3 requires be skipped and counted rather than treated as corruption.
  */
private[relay] opaque type TypeCode = Int

private[relay] object TypeCode:
  def fromWire(raw: Byte): TypeCode = raw & 0xff

  def of(messageType: MessageType): TypeCode = messageType.code

  extension (code: TypeCode)
    def value: Int = code
    def toByte: Byte = code.toByte
    def known: Option[MessageType] = MessageType.fromCode(code)
    def direction: Option[WireDirection] = WireDirection.ofCode(code)
