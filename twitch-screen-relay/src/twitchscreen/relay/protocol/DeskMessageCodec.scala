package twitchscreen.relay.protocol

import java.nio.{ByteBuffer, ByteOrder}

/** Additive STATS and event envelopes. Immutable classic bytes preserve receiver-side clamping and text differences for revision checks.
  * These records do not negotiate DESK or establish current-session eligibility, freshness or event credit.
  */
private[relay] object DeskMessageCodec:
  import DeskControlCodec.Identity

  enum Origin(val code: Int):
    case External extends Origin(0)
    case Twitch extends Origin(1)
    case Relay extends Origin(2)
    case Unknown extends Origin(255)

  enum Availability(val code: Int):
    case Unknown extends Availability(0)
    case Observed extends Availability(1)
    case Unavailable extends Availability(2)

  final case class Stats(base: Vector[Byte], identity: Identity, sourceGeneration: Long, revision: Long, availability: Availability):
    require(base.size == 32, "STATS base must contain 32 wire bytes")
    requireRevision(sourceGeneration)
    requireRevision(revision)

  final case class Event(
      base: Vector[Byte],
      relayEpoch: Long,
      sourceGeneration: Long,
      twitchEpisode: Long,
      deadlineRelayMs: Long,
      origin: Origin
  ):
    require(base.size == 168, "EVENT base must contain 168 wire bytes")
    require(seq > 0 && relayEpoch != 0 && deadlineRelayMs != 0, "DESK event identity and deadline must be nonzero")
    require(origin != Origin.Unknown, "new DESK events require known provenance")
    if origin == Origin.Twitch then
      requireRevision(sourceGeneration)
      requireRevision(twitchEpisode)
    else require(sourceGeneration == 0 && twitchEpisode == 0, "generic events must not carry Twitch fences")
    def seq: Long = Integer.toUnsignedLong(buffer(base.toArray).getInt())

  def fromStats(base: RelayMessage.Stats, identity: Identity, generation: Long, revision: Long, availability: Availability): Stats =
    Stats(Tsb3Encoder.toDevice(base).drop(Tsb3.HeaderSize).toVector, identity, generation, revision, availability)

  /** Publication supplies original absolute deadline and typed provenance, never a title/kind heuristic. */
  def fromEvent(record: EventRecord, epoch: Long, generation: Long, episode: Long, deadline: Long, origin: Origin): Event =
    require(record.ttl.deciseconds >= 1 && record.ttl.deciseconds <= 300, "DESK hold must be explicit and at most 30 seconds")
    Event(
      Tsb3Encoder.toDevice(RelayMessage.Event(record), text = TextPolicy.AsciiFolded).drop(Tsb3.HeaderSize).toVector,
      epoch,
      generation,
      episode,
      deadline,
      origin
    )

  def stats(value: Stats): Array[Byte] = encode(0x22, 60, FrameFlags.Empty): bytes =>
    val _ = bytes.put(value.base.toArray).putLong(value.identity.relayEpoch).putLong(value.identity.connectionId)
    val _ = bytes.putInt(value.sourceGeneration.toInt).putInt(value.revision.toInt).put(value.availability.code.toByte)

  def event(value: Event, flags: FrameFlags = FrameFlags.Empty): Array[Byte] = encode(0x2b, 196, flags): bytes =>
    val _ = bytes.put(value.base.toArray).putLong(value.relayEpoch).putInt(value.sourceGeneration.toInt)
    val _ = bytes.putInt(value.twitchEpisode.toInt).putLong(value.deadlineRelayMs).put(value.origin.code.toByte)

  def decodeStats(frame: Frame): Either[String, Stats] = decode(frame, 0x22, 60): bytes =>
    val base = readBase(bytes, 32)
    val identity = Identity(bytes.getLong(), bytes.getLong())
    val generation = u32(bytes)
    val revision = u32(bytes)
    val code = u8(bytes)
    val availability = Availability.values.find(_.code == code).getOrElse(throw IllegalArgumentException("invalid availability"))
    requireZero(bytes, 3)
    Stats(base, identity, generation, revision, availability)

  def decodeEvent(frame: Frame): Either[String, Event] = decode(frame, 0x2b, 196): bytes =>
    val base = readBase(bytes, 168)
    val epoch = bytes.getLong()
    val generation = u32(bytes)
    val episode = u32(bytes)
    val deadline = bytes.getLong()
    val code = u8(bytes)
    val origin = Origin.values.find(_.code == code).getOrElse(throw IllegalArgumentException("invalid origin"))
    requireZero(bytes, 3)
    Event(base, epoch, generation, episode, deadline, origin)

  private def buffer(bytes: Array[Byte]): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
  private def readBase(bytes: ByteBuffer, length: Int): Vector[Byte] =
    val result = new Array[Byte](length)
    val _ = bytes.get(result)
    result.toVector
  private def u32(bytes: ByteBuffer): Long = Integer.toUnsignedLong(bytes.getInt())
  private def u8(bytes: ByteBuffer): Int = bytes.get() & 0xff
  private def requireRevision(value: Long): Unit = require(value > 0 && value <= 0xffffffffL, "revision must be nonzero u32")
  private def requireZero(bytes: ByteBuffer, count: Int): Unit =
    (0 until count).foreach(_ => require(bytes.get() == 0, "nonzero DESK reserved field"))

  private def decode[A](frame: Frame, code: Int, length: Int)(read: ByteBuffer => A): Either[String, A] =
    if !frame.header.version.isCurrent || frame.header.typeCode.value != code then Left("wrong DESK message record")
    else if frame.header.length != frame.payload.length || frame.payload.length < length || frame.payload.length > Tsb3.MaxPayload then
      Left("invalid DESK message record length")
    else
      try Right(read(buffer(frame.payload)))
      catch case error: IllegalArgumentException => Left(error.getMessage)

  private def encode(code: Int, length: Int, flags: FrameFlags)(write: ByteBuffer => Unit): Array[Byte] =
    val result = new Array[Byte](Tsb3.HeaderSize + length)
    result(0) = Tsb3.Magic0
    result(1) = Tsb3.Magic1
    result(2) = Tsb3.Version.toByte
    result(3) = code.toByte
    result(4) = length.toByte
    result(5) = (length >>> 8).toByte
    result(6) = flags.toByte
    result(7) = Tsb3.headerCheck(result)
    val bytes = buffer(result)
    val _ = bytes.position(Tsb3.HeaderSize)
    write(bytes)
    result
