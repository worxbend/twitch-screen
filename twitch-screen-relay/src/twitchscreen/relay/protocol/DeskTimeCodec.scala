package twitchscreen.relay.protocol

import java.nio.{ByteBuffer, ByteOrder}

/** DESK/1 time records only. Session negotiation must gate use; legacy dispatch remains unchanged. Long values preserve all unsigned wire
  * bits, including epochs above signed Long.MaxValue.
  */
private[relay] object DeskTimeCodec:
  val RequestCode: Int = 0x05
  val ResponseCode: Int = 0x26

  final case class Response(relayEpoch: Long, connectionId: Long, echoedProbeDeviceMs: Long, sampledRelayMs: Long)

  def request(probeDeviceMs: Long): Array[Byte] = encode(RequestCode, List(probeDeviceMs))

  def response(value: Response): Array[Byte] =
    encode(ResponseCode, List(value.relayEpoch, value.connectionId, value.echoedProbeDeviceMs, value.sampledRelayMs))

  def decodeRequest(frame: Frame): Either[String, Long] =
    validate(frame, RequestCode, 8).map(_ => buffer(frame.payload).getLong())

  def decodeResponse(frame: Frame): Either[String, Response] =
    validate(frame, ResponseCode, 32).map: _ =>
      val bytes = buffer(frame.payload)
      Response(bytes.getLong(), bytes.getLong(), bytes.getLong(), bytes.getLong())

  private def buffer(bytes: Array[Byte]): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

  private def validate(frame: Frame, code: Int, length: Int): Either[String, Unit] =
    if !frame.header.version.isCurrent || frame.header.typeCode.value != code then Left("wrong time record")
    else if frame.header.length != frame.payload.length || frame.payload.length < length || frame.payload.length > Tsb3.MaxPayload then
      Left("invalid DESK/1 time record length")
    else Right(())

  private def encode(code: Int, values: List[Long]): Array[Byte] =
    val length = values.size * java.lang.Long.BYTES
    val bytes = new Array[Byte](Tsb3.HeaderSize + length)
    bytes(0) = Tsb3.Magic0
    bytes(1) = Tsb3.Magic1
    bytes(2) = Tsb3.Version.toByte
    bytes(3) = code.toByte
    bytes(4) = length.toByte
    bytes(5) = (length >>> 8).toByte
    bytes(7) = Tsb3.headerCheck(bytes)
    val payload = buffer(bytes)
    val _ = payload.position(Tsb3.HeaderSize)
    values.foreach: value =>
      val _ = payload.putLong(value)
    bytes
