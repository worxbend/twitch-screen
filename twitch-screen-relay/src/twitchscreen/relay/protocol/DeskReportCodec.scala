package twitchscreen.relay.protocol

import java.nio.{ByteBuffer, ByteOrder}

/** Packed DEVICE_REPORT. Structural decoding is separate from session, age, revision, cursor and cumulative-credit admission. */
private[relay] object DeskReportCodec:
  import DeskControlCodec.Identity
  import DeskMessageCodec.Origin

  enum Base(val code: Int):
    case ConnectedOrbit extends Base(0)
    case DisconnectedOrbit extends Base(1)
    case Twitch extends Base(2)
    case Weather extends Base(3)
    case Cpu extends Base(4)

  enum Reason(val code: Int):
    case Selected extends Reason(0)
    case RelayDown extends Reason(1)
    case Synchronizing extends Reason(2)
    case GenericSelected extends Reason(3)
    case DataMissing extends Reason(4)
    case DataStale extends Reason(5)
    case TwitchOverride extends Reason(6)
    case Legacy extends Reason(7)
    case ClockUnavailable extends Reason(8)

  enum Phase(val code: Int):
    case None extends Phase(0)
    case Entrance extends Phase(1)
    case Hold extends Phase(2)
    case Exit extends Phase(3)

  final case class Entry(originalRelayEpoch: Long, seq: Long, kind: Int, origin: Origin):
    require(seq > 0 && seq <= 0xffffffffL && kind >= 0 && kind <= 255, "invalid report entry")
    require(if origin == Origin.Unknown then originalRelayEpoch == 0 else originalRelayEpoch != 0, "invalid retained card identity")

  final case class Report(
      identity: Identity,
      deviceBootId: Long,
      revision: Long,
      observedDeviceMs: Long,
      observedRelayLowerMs: Long,
      grantTotal: Long,
      processedSeq: Long,
      enqueuedSeq: Long,
      displayRevision: Long,
      sourceGeneration: Long,
      twitchEpisode: Long,
      weatherRevision: Long,
      cpuRevision: Long,
      base: Base,
      reason: Reason,
      phase: Phase,
      activeRemainingMs: Long,
      expiredTotal: Long,
      suppressedTotal: Long,
      refusedTotal: Long,
      active: Option[Entry],
      pending: Vector[Entry]
  ):
    require(deviceBootId != 0, "device boot identity must be nonzero")
    List(revision, displayRevision, sourceGeneration, twitchEpisode, weatherRevision, cpuRevision).foreach(requireRevision)
    List(processedSeq, enqueuedSeq, activeRemainingMs, expiredTotal, suppressedTotal, refusedTotal).foreach(requireU32)
    require(enqueuedSeq <= processedSeq && pending.size <= 8, "invalid report cursors or pending capacity")
    require(if phase == Phase.None then active.isEmpty && activeRemainingMs == 0 else active.isDefined, "invalid active lifecycle")
    // Original retained identities deliberately do not compare with current epoch/cursors or source fences.

  def encode(value: Report): Array[Byte] =
    val result = new Array[Byte](252)
    result(0) = Tsb3.Magic0
    result(1) = Tsb3.Magic1
    result(2) = Tsb3.Version.toByte
    result(3) = 0x06.toByte
    result(4) = 244.toByte
    result(7) = Tsb3.headerCheck(result)
    val bytes = buffer(result)
    val _ = bytes.position(Tsb3.HeaderSize)
    val _ = bytes.putLong(value.identity.relayEpoch).putLong(value.identity.connectionId).putLong(value.deviceBootId)
    val _ = bytes.putInt(value.revision.toInt).putLong(value.observedDeviceMs).putLong(value.observedRelayLowerMs).putLong(value.grantTotal)
    List(
      value.processedSeq,
      value.enqueuedSeq,
      value.displayRevision,
      value.sourceGeneration,
      value.twitchEpisode,
      value.weatherRevision,
      value.cpuRevision
    ).foreach: number =>
      val _ = bytes.putInt(number.toInt)
    val _ = bytes.put(value.base.code.toByte).put(value.reason.code.toByte).put(value.phase.code.toByte).put(value.pending.size.toByte)
    List(value.activeRemainingMs, value.expiredTotal, value.suppressedTotal, value.refusedTotal).foreach: number =>
      val _ = bytes.putInt(number.toInt)
    val entries = Vector(value.active) ++ value.pending.map(Some(_)) ++ Vector.fill(8 - value.pending.size)(None)
    entries.foreach: entry =>
      entry match
        case Some(card) =>
          val _ = bytes.putLong(card.originalRelayEpoch).putInt(card.seq.toInt).put(card.kind.toByte).put(card.origin.code.toByte)
          val _ = bytes.putShort(0.toShort)
        case None =>
          val _ = bytes.position(bytes.position() + 16)
    result

  def decode(frame: Frame): Either[String, Report] =
    if !frame.header.version.isCurrent || frame.header.typeCode.value != 0x06 then Left("wrong DEVICE_REPORT record")
    else if frame.header.length != frame.payload.length || frame.payload.length < 244 || frame.payload.length > Tsb3.MaxPayload then
      Left("invalid DEVICE_REPORT length")
    else
      try Right(read(buffer(frame.payload)))
      catch case error: IllegalArgumentException => Left(error.getMessage)

  private def read(bytes: ByteBuffer): Report =
    val identity = Identity(bytes.getLong(), bytes.getLong())
    val boot = bytes.getLong()
    val revision = u32(bytes)
    val deviceTime = bytes.getLong()
    val relayLower = bytes.getLong()
    val grant = bytes.getLong()
    val processed = u32(bytes)
    val enqueued = u32(bytes)
    val display = u32(bytes)
    val generation = u32(bytes)
    val episode = u32(bytes)
    val weather = u32(bytes)
    val cpu = u32(bytes)
    val baseCode = u8(bytes)
    val reasonCode = u8(bytes)
    val phaseCode = u8(bytes)
    val base = Base.values.find(_.code == baseCode).getOrElse(throw IllegalArgumentException("invalid base screen"))
    val reason = Reason.values.find(_.code == reasonCode).getOrElse(throw IllegalArgumentException("invalid base reason"))
    val phase = Phase.values.find(_.code == phaseCode).getOrElse(throw IllegalArgumentException("invalid active phase"))
    val count = u8(bytes)
    require(count <= 8, "invalid pending count")
    val remaining = u32(bytes)
    val expired = u32(bytes)
    val suppressed = u32(bytes)
    val refused = u32(bytes)
    val entries = Vector.tabulate(9)(_ => readEntry(bytes))
    require(entries.drop(count + 1).forall(_.isEmpty), "unused entries must be zero-filled")
    require(entries.slice(1, count + 1).forall(_.isDefined), "pending entries must be occupied")
    Report(
      identity,
      boot,
      revision,
      deviceTime,
      relayLower,
      grant,
      processed,
      enqueued,
      display,
      generation,
      episode,
      weather,
      cpu,
      base,
      reason,
      phase,
      remaining,
      expired,
      suppressed,
      refused,
      entries.head,
      entries.slice(1, count + 1).flatten
    )

  private def readEntry(bytes: ByteBuffer): Option[Entry] =
    val epoch = bytes.getLong()
    val seq = u32(bytes)
    val kind = u8(bytes)
    val code = u8(bytes)
    require(bytes.getShort() == 0, "nonzero report entry reserved field")
    if epoch == 0 && seq == 0 && kind == 0 && code == 0 then None
    else
      val origin = Origin.values.find(_.code == code).getOrElse(throw IllegalArgumentException("invalid report origin"))
      Some(Entry(epoch, seq, kind, origin))

  private def buffer(bytes: Array[Byte]): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
  private def u32(bytes: ByteBuffer): Long = Integer.toUnsignedLong(bytes.getInt())
  private def u8(bytes: ByteBuffer): Int = bytes.get() & 0xff
  private def requireU32(value: Long): Unit = require(value >= 0 && value <= 0xffffffffL, "report field must fit u32")
  private def requireRevision(value: Long): Unit =
    requireU32(value)
    require(value != 0, "report revision must be nonzero")
