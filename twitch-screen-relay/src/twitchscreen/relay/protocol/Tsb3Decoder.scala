package twitchscreen.relay.protocol

import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}

/** Reads the payload of one well-framed TSB/3 frame back into a message (§6).
  *
  * **Nothing here throws.** Every problem is a [[ProtocolError]] value, because this code sits on a socket exposed to a device that may be
  * running any firmware, and because §4.6's two-tier rule — a malformed frame must not kill the link, a malformed stream must — can only be
  * applied by a caller that was handed the problem rather than an exception. [[ProtocolError.disposition]] carries which tier each one is
  * in, so no call site has to remember.
  *
  * A `length` above the type's base length is not an error: §16 rule 2 obliges a receiver to decode the first `base` bytes and ignore the
  * tail, which is what lets a later version append a field without breaking a peer flashed today.
  */
private[relay] object Tsb3Decoder:
  /** Decodes a frame arriving from a device (§6, `0x01`…`0x1f`). This is the relay's inbound path.
    *
    * `version` is checked here only for `HELLO`, which is where §7 puts the check: the handshake is exact equality and a mismatch owes the
    * device a `BYE(1 UNSUPPORTED_VERSION)`. §4.2 deliberately keeps `version` out of header validation, so that two peers of different
    * versions can still exchange that refusal.
    */
  def fromDevice(frame: Frame): Either[ProtocolError, DeviceMessage] =
    // §7: the version byte sits in the frozen header, and a peer on another version may lay its payload out differently, so a HELLO's
    // version is checked before any payload-length check — a short v2 HELLO is owed BYE(1), not BYE(2).
    if frame.header.typeCode.known.contains(MessageType.Hello) && !frame.header.version.isCurrent then
      Left(ProtocolError.UnsupportedVersion(frame.header.version.value, Tsb3.Version.value))
    else fromDeviceCurrent(frame)

  private def fromDeviceCurrent(frame: Frame): Either[ProtocolError, DeviceMessage] =
    expect(frame, WireDirection.DeviceToRelay).flatMap {
      case MessageType.Hello =>
        if frame.header.version.isCurrent then hello(frame.payload)
        else Left(ProtocolError.UnsupportedVersion(frame.header.version.value, Tsb3.Version.value))
      case MessageType.DevicePing => Right(DeviceMessage.Ping(Token.fromWire(u32(frame.payload, Tsb3.Heartbeat.Token))))
      case MessageType.DevicePong => Right(DeviceMessage.Pong(Token.fromWire(u32(frame.payload, Tsb3.Heartbeat.Token))))
      case MessageType.Ack        => Right(DeviceMessage.Ack(SeqNo.fromWire(u32(frame.payload, Tsb3.Ack.Seq))))
      case other                  => Left(ProtocolError.WrongDirection(other.code))
    }

  /** Decodes a frame the relay sends to a device (§6, `0x20`…`0x3f`).
    *
    * The relay never receives one of these. It is the mirror of [[Tsb3Encoder.toDevice]] and exists so that the golden vectors of §18 can
    * be asserted in both directions — bytes in, field values out — which is the mechanism by which this codec and the firmware's stay
    * aligned.
    */
  def fromRelay(frame: Frame): Either[ProtocolError, RelayMessage] =
    expect(frame, WireDirection.RelayToDevice).flatMap {
      case MessageType.Welcome   => Right(welcome(frame.payload))
      case MessageType.Event     => event(frame.payload).map(RelayMessage.Event.apply)
      case MessageType.Stats     => Right(stats(frame.payload))
      case MessageType.RelayPing => Right(RelayMessage.Ping(Token.fromWire(u32(frame.payload, Tsb3.Heartbeat.Token))))
      case MessageType.RelayPong => Right(RelayMessage.Pong(Token.fromWire(u32(frame.payload, Tsb3.Heartbeat.Token))))
      case MessageType.Bye       => Right(bye(frame.payload))
      case other                 => Left(ProtocolError.WrongDirection(other.code))
    }

  /** §4.3's three framing dispositions, in the order the specification lists them: a type this build does not implement is unknown, a type
    * from the receiver's own outbound range is a confused peer rather than a corrupt stream, and a payload below the base length is short.
    */
  private def expect(frame: Frame, inbound: WireDirection): Either[ProtocolError, MessageType] =
    val code = frame.header.typeCode.value
    frame.header.typeCode.known match
      case Some(messageType) if messageType.direction == inbound =>
        if frame.payload.length < messageType.baseLength then
          Left(ProtocolError.ShortPayload(messageType.toString, messageType.baseLength, frame.payload.length))
        else Right(messageType)
      case Some(_) => Left(ProtocolError.WrongDirection(code))
      case None =>
        frame.header.typeCode.direction match
          case Some(direction) if direction != inbound => Left(ProtocolError.WrongDirection(code))
          case _                                       => Left(ProtocolError.UnknownType(code))

  private def hello(payload: Array[Byte]): Either[ProtocolError, DeviceMessage] =
    val rxMax = FrameSize.fromWire(u16(payload, Tsb3.Hello.RxMax))
    if !rxMax.isAcceptable then Left(ProtocolError.InvalidField("rx_max", Tsb3.Hello.RxMax))
    else
      deviceId(payload).map: id =>
        DeviceMessage.Hello(
          deviceId = id,
          lastSeq = SeqNo.fromWire(u32(payload, Tsb3.Hello.LastSeq)),
          caps = Capabilities.fromWire(u32(payload, Tsb3.Hello.Caps)),
          rxMax = rxMax,
          fwVersion = FirmwareVersion.fromWire(WireStrings.read(payload, Tsb3.Hello.FwVersion, Tsb3.FwVersionWidth))
        )

  /** §6.1. Blank after trimming ASCII whitespace, no NUL anywhere in the field, or any byte outside `0x20`…`0x7e` before the first NUL, all
    * mean `BYE(3 INVALID_DEVICE_ID)` and a close — this is the one string on this wire that is validated rather than rendered, because it
    * names a row in the relay's device table.
    */
  private def deviceId(payload: Array[Byte]): Either[ProtocolError, DeviceId] =
    val start = Tsb3.Hello.DeviceId
    val limit = start + Tsb3.DeviceIdWidth
    (start until limit).find(index => payload(index) == 0) match
      case None => Left(ProtocolError.InvalidDeviceId(s"no NUL in the ${Tsb3.DeviceIdWidth} byte field"))
      case Some(end) =>
        val raw = payload.slice(start, end)
        if raw.exists(byte => (byte & 0xff) < 0x20 || (byte & 0xff) > 0x7e) then
          Left(ProtocolError.InvalidDeviceId("byte outside 0x20…0x7e before the terminator"))
        else DeviceId(String(raw, StandardCharsets.US_ASCII)).left.map(ProtocolError.InvalidDeviceId.apply)

  private def welcome(payload: Array[Byte]): RelayMessage.Welcome =
    RelayMessage.Welcome(
      latestSeq = SeqNo.fromWire(u32(payload, Tsb3.Welcome.LatestSeq)),
      serverTime = instant(u32(payload, Tsb3.Welcome.ServerTime)),
      sessionId = SessionId.fromWire(u32(payload, Tsb3.Welcome.SessionId)),
      maxFrame = FrameSize.fromWire(u16(payload, Tsb3.Welcome.MaxFrame)),
      pingInterval = u16(payload, Tsb3.Welcome.PingInterval).seconds,
      idleTimeout = u16(payload, Tsb3.Welcome.IdleTimeout).seconds,
      replayWindow = ReplayWindow.fromWire(u16(payload, Tsb3.Welcome.ReplayWindow)),
      caps = Capabilities.fromWire(u32(payload, Tsb3.Welcome.Caps))
    )

  /** `reserved1` at +12 and `reserved2` at +23 are not read and not checked. §8.1 holds them open for a future version; rejecting a frame
    * because they are non-zero, or blanking them on receipt, would defeat the forward compatibility they exist to provide.
    */
  private def event(payload: Array[Byte]): Either[ProtocolError, EventRecord] =
    val seq = u32(payload, Tsb3.Event.Seq)
    if seq == 0 then Left(ProtocolError.InvalidField("seq", Tsb3.Event.Seq))
    else
      Right(
        EventRecord(
          seq = SeqNo.fromWire(seq),
          at = instant(u32(payload, Tsb3.Event.Ts)),
          value = EventValue.fromWire(u32(payload, Tsb3.Event.Value)),
          months = SubMonths.fromWire(u16(payload, Tsb3.Event.Months)),
          ttl = DisplayTtl.fromWire(u16(payload, Tsb3.Event.TtlDs)),
          kind = NotificationKind.fromCode(u8(payload, Tsb3.Event.Kind)),
          tier = SubTier.fromWire(u8(payload, Tsb3.Event.Tier)),
          flags = EventFlags.fromWire(u8(payload, Tsb3.Event.EFlags)),
          actor = WireStrings.read(payload, Tsb3.Event.Actor, Tsb3.ActorWidth),
          text = WireStrings.read(payload, Tsb3.Event.Text, Tsb3.TextWidth)
        )
      )

  private def stats(payload: Array[Byte]): RelayMessage.Stats =
    val figures = StreamStats(
      state = StreamState.fromLive(u8(payload, Tsb3.Stats.Live) != 0),
      viewers = count(u32(payload, Tsb3.Stats.Viewers)),
      followers = count(u32(payload, Tsb3.Stats.Followers)),
      subscribers = count(u32(payload, Tsb3.Stats.Subs)),
      uptime = seconds(u32(payload, Tsb3.Stats.UptimeS)),
      chatRate = MessagesPerMinute.clamp(u16(payload, Tsb3.Stats.ChatRate)),
      messagesTotal = count(u32(payload, Tsb3.Stats.MsgTotal)),
      streamStartedAt = instant(u32(payload, Tsb3.Stats.StreamStartedAt))
    )
    RelayMessage.Stats(figures, instant(u32(payload, Tsb3.Stats.ServerTime)))

  private def bye(payload: Array[Byte]): RelayMessage.Bye =
    RelayMessage.Bye(
      code = ByeCode.fromWire(u16(payload, Tsb3.Bye.Code)),
      detail = ByeDetail.fromWire(u16(payload, Tsb3.Bye.Detail)),
      retryAfter = u16(payload, Tsb3.Bye.RetryAfter).seconds,
      reason = WireStrings.read(payload, Tsb3.Bye.Reason, Tsb3.ReasonWidth)
    )

  private def u8(payload: Array[Byte], offset: Int): Int = payload(offset) & 0xff

  private def u16(payload: Array[Byte], offset: Int): Int =
    (payload(offset) & 0xff) | ((payload(offset + 1) & 0xff) << 8)

  private def u32(payload: Array[Byte], offset: Int): Long =
    (payload(offset) & 0xffL) |
      ((payload(offset + 1) & 0xffL) << 8) |
      ((payload(offset + 2) & 0xffL) << 16) |
      ((payload(offset + 3) & 0xffL) << 24)

  /** 0 means "unknown" in every timestamp field of this protocol. */
  private def instant(epochSeconds: Long): Option[Instant] =
    if epochSeconds == 0 then None else Some(Instant.ofEpochSecond(epochSeconds))

  /** `Count` is a JVM `Int`; the wire field is a `u32`. Saturating is the only honest narrowing, and no real figure comes near it. */
  private def count(value: Long): Count = Count.clamp(if value > Int.MaxValue then Int.MaxValue else value.toInt)

  private def seconds(value: Long): FiniteDuration = value.seconds
