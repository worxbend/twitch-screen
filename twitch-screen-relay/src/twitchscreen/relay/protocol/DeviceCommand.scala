package twitchscreen.relay.protocol

/** A validated message from a device: the wire frame's primitives have become domain values. */
private[relay] enum DeviceCommand:
  case Hello(device: DeviceId, protocolVersion: Int, lastSeq: SeqNo)
  case Ping(clientTimestamp: Long)
  case Pong(clientTimestamp: Long)

private[relay] object DeviceCommand:
  def fromFrame(frame: ClientFrame): Either[ProtocolError, DeviceCommand] = frame match
    case ClientFrame.Hello(device, proto, lastSeq) =>
      for
        id <- DeviceId(device).left.map(ProtocolError.InvalidDeviceId.apply)
        seq <- SeqNo(lastSeq).left.map(ProtocolError.InvalidSequence.apply)
      yield Hello(id, proto, seq)
    case ClientFrame.Ping(t) => Right(Ping(t))
    case ClientFrame.Pong(t) => Right(Pong(t))
