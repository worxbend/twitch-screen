package twitchscreen.relay.protocol

import java.nio.{ByteBuffer, ByteOrder}

/** Complete HELLO/WELCOME profile tails. This codec is not session negotiation and must not enable a partial DESK implementation. */
private[relay] object DeskGreetingCodec:
  val CapabilityBit: Long = 0x10L
  private val Profile = 1
  private val HelloLength = 92
  private val WelcomeLength = 60

  final case class HelloTail(previousRelayEpoch: Long, processedSeq: Long, deviceBootId: Long, probeDeviceMs: Long)
  final case class WelcomeTail(relayEpoch: Long, connectionId: Long, sampledRelayMs: Long, echoedProbeDeviceMs: Long)

  /** Classic validation remains authoritative. Invalid or missing profile tails select classic fallback, not a bad HELLO. */
  def decodeHello(frame: Frame): Either[String, Option[HelloTail]] =
    validate(frame, MessageType.Hello).flatMap: _ =>
      Tsb3Decoder
        .fromDevice(frame)
        .left
        .map(_.toString)
        .map: _ =>
          val bytes = buffer(frame.payload)
          if !hasCapability(bytes, Tsb3.Hello.Caps) || frame.payload.length < HelloLength || !validProfile(bytes, 60) then None
          else
            val tail = HelloTail(bytes.getLong(64), Integer.toUnsignedLong(bytes.getInt(72)), bytes.getLong(76), bytes.getLong(84))
            Option.when(tail.deviceBootId != 0)(tail)

  /** Once WELCOME selects DESK, a malformed tail or mismatched probe is a session error, never classic fallback. */
  def decodeWelcome(frame: Frame, expectedProbeDeviceMs: Long): Either[String, Option[WelcomeTail]] =
    validate(frame, MessageType.Welcome).flatMap: _ =>
      val bytes = buffer(frame.payload)
      if !hasCapability(bytes, Tsb3.Welcome.Caps) then Right(None)
      else if frame.payload.length < WelcomeLength || !validProfile(bytes, 24) then Left("invalid DESK/1 WELCOME profile")
      else
        val tail = WelcomeTail(bytes.getLong(28), bytes.getLong(36), bytes.getLong(44), bytes.getLong(52))
        if tail.relayEpoch == 0 || tail.connectionId == 0 || tail.echoedProbeDeviceMs != expectedProbeDeviceMs then
          Left("invalid DESK/1 WELCOME identity or probe")
        else Right(Some(tail))

  def hello(base: DeviceMessage.Hello, tail: HelloTail): Array[Byte] =
    require(tail.deviceBootId != 0, "device boot identity must be nonzero")
    require(tail.processedSeq >= 0 && tail.processedSeq <= 0xffffffffL, "processed cursor must fit u32")
    require((base.caps.unsigned & CapabilityBit) != 0, "HELLO must request DESK")
    val result = extend(Tsb3Encoder.toRelay(base), HelloLength)
    val bytes = buffer(result)
    val start = Tsb3.HeaderSize + 60
    writeProfile(bytes, start)
    val _ = bytes.putLong(start + 4, tail.previousRelayEpoch)
    val _ = bytes.putInt(start + 12, tail.processedSeq.toInt)
    val _ = bytes.putLong(start + 16, tail.deviceBootId)
    val _ = bytes.putLong(start + 24, tail.probeDeviceMs)
    result

  def welcome(base: RelayMessage.Welcome, tail: WelcomeTail): Array[Byte] =
    require(tail.relayEpoch != 0 && tail.connectionId != 0, "relay and connection identities must be nonzero")
    require((base.caps.unsigned & CapabilityBit) != 0, "WELCOME must select DESK")
    val result = extend(Tsb3Encoder.toDevice(base), WelcomeLength)
    val bytes = buffer(result)
    val start = Tsb3.HeaderSize + 24
    writeProfile(bytes, start)
    val _ = bytes.putLong(start + 4, tail.relayEpoch)
    val _ = bytes.putLong(start + 12, tail.connectionId)
    val _ = bytes.putLong(start + 20, tail.sampledRelayMs)
    val _ = bytes.putLong(start + 28, tail.echoedProbeDeviceMs)
    result

  private def validate(frame: Frame, kind: MessageType): Either[String, Unit] =
    if !frame.header.version.isCurrent || !frame.header.typeCode.known.contains(kind) then Left("wrong greeting record")
    else if frame.header.length != frame.payload.length || frame.payload.length < kind.baseLength || frame.payload.length > Tsb3.MaxPayload
    then Left("invalid greeting record length")
    else Right(())

  private def buffer(bytes: Array[Byte]): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
  private def hasCapability(bytes: ByteBuffer, offset: Int): Boolean = (Integer.toUnsignedLong(bytes.getInt(offset)) & CapabilityBit) != 0
  private def validProfile(bytes: ByteBuffer, offset: Int): Boolean = bytes.getShort(offset) == Profile && bytes.getShort(offset + 2) == 0

  private def writeProfile(bytes: ByteBuffer, offset: Int): Unit =
    val _ = bytes.putShort(offset, Profile.toShort)
    val _ = bytes.putShort(offset + 2, 0.toShort)

  private def extend(base: Array[Byte], length: Int): Array[Byte] =
    val result = java.util.Arrays.copyOf(base, Tsb3.HeaderSize + length)
    result(4) = length.toByte
    result(5) = (length >>> 8).toByte
    result(7) = Tsb3.headerCheck(result)
    result
