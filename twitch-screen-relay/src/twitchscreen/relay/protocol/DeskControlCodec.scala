package twitchscreen.relay.protocol

import java.nio.{ByteBuffer, ByteOrder}

/** Complete DESK/1 display, synchronization and ordered-progress records. Session negotiation and current identity checks remain the
  * caller's responsibility; this codec alone must not enable the profile.
  */
private[relay] object DeskControlCodec:
  final case class Identity(relayEpoch: Long, connectionId: Long):
    require(relayEpoch != 0 && connectionId != 0, "DESK identities must be nonzero")

  enum Preferred(val code: Int):
    case Generic extends Preferred(0)
    case Weather extends Preferred(1)
    case Cpu extends Preferred(2)

  final case class Display(
      identity: Identity,
      revision: Long,
      sourceGeneration: Long,
      twitchEpisode: Long,
      enabled: Boolean,
      preferred: Preferred
  ):
    List(revision, sourceGeneration, twitchEpisode).foreach(requireRevision)

  final case class Sync(
      identity: Identity,
      displayRevision: Long,
      weatherRevision: Long,
      cpuRevision: Long,
      statsRevision: Long,
      sourceGeneration: Long,
      twitchEpisode: Long
  ):
    List(displayRevision, weatherRevision, cpuRevision, statsRevision, sourceGeneration, twitchEpisode).foreach(requireRevision)

  final case class Barrier(identity: Identity, throughSeq: Long):
    require(throughSeq >= 0 && throughSeq <= 0xffffffffL, "progress must fit u32")

  def display(value: Display): Array[Byte] = encode(0x27, 32, value.identity): bytes =>
    putRevisions(bytes, List(value.revision, value.sourceGeneration, value.twitchEpisode))
    val _ = bytes.put(if value.enabled then 1.toByte else 0.toByte)
    val _ = bytes.put(value.preferred.code.toByte)
    val _ = bytes.putShort(0.toShort)

  def sync(value: Sync): Array[Byte] = encode(0x2a, 40, value.identity): bytes =>
    putRevisions(
      bytes,
      List(
        value.displayRevision,
        value.weatherRevision,
        value.cpuRevision,
        value.statsRevision,
        value.sourceGeneration,
        value.twitchEpisode
      )
    )

  def barrier(value: Barrier): Array[Byte] = encode(0x2c, 20, value.identity): bytes =>
    val _ = bytes.putInt(value.throughSeq.toInt)

  def decodeDisplay(frame: Frame): Either[String, Display] = decode(frame, 0x27, 32): bytes =>
    val identity = getIdentity(bytes)
    val revision = u32(bytes)
    val generation = u32(bytes)
    val episode = u32(bytes)
    val enabled = bytes.get() & 0xff
    val preferred = bytes.get() & 0xff
    require(enabled <= 1, "invalid enabled value")
    require(bytes.getShort() == 0, "invalid control reserved field")
    val selection = Preferred.values.find(_.code == preferred).getOrElse(throw IllegalArgumentException("invalid preferred dashboard"))
    Display(identity, revision, generation, episode, enabled == 1, selection)

  def decodeSync(frame: Frame): Either[String, Sync] = decode(frame, 0x2a, 40): bytes =>
    Sync(getIdentity(bytes), u32(bytes), u32(bytes), u32(bytes), u32(bytes), u32(bytes), u32(bytes))

  def decodeBarrier(frame: Frame): Either[String, Barrier] = decode(frame, 0x2c, 20): bytes =>
    Barrier(getIdentity(bytes), u32(bytes))

  private def requireRevision(value: Long): Unit = require(value > 0 && value <= 0xffffffffL, "revision must be nonzero u32")
  private def u32(bytes: ByteBuffer): Long = Integer.toUnsignedLong(bytes.getInt())
  private def getIdentity(bytes: ByteBuffer): Identity = Identity(bytes.getLong(), bytes.getLong())
  private def putRevisions(bytes: ByteBuffer, values: List[Long]): Unit = values.foreach: value =>
    val _ = bytes.putInt(value.toInt)

  private def decode[A](frame: Frame, code: Int, length: Int)(read: ByteBuffer => A): Either[String, A] =
    if !frame.header.version.isCurrent || frame.header.typeCode.value != code then Left("wrong DESK control record")
    else if frame.header.length != frame.payload.length || frame.payload.length < length || frame.payload.length > Tsb3.MaxPayload then
      Left("invalid DESK control record length")
    else
      try Right(read(ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)))
      catch case error: IllegalArgumentException => Left(error.getMessage)

  private def encode(code: Int, length: Int, identity: Identity)(write: ByteBuffer => Unit): Array[Byte] =
    val result = new Array[Byte](Tsb3.HeaderSize + length)
    result(0) = Tsb3.Magic0
    result(1) = Tsb3.Magic1
    result(2) = Tsb3.Version.toByte
    result(3) = code.toByte
    result(4) = length.toByte
    result(5) = (length >>> 8).toByte
    result(7) = Tsb3.headerCheck(result)
    val bytes = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
    val _ = bytes.position(Tsb3.HeaderSize)
    val _ = bytes.putLong(identity.relayEpoch)
    val _ = bytes.putLong(identity.connectionId)
    write(bytes)
    result
