package twitchscreen.relay.protocol

import java.nio.{ByteBuffer, ByteOrder}

/** Typed DESK snapshot wire records. These codecs do not establish freshness, calibration, current-session identity or negotiation. Keep
  * full known wire bytes for equal-revision validation before any text projection. Capability advertisement remains disabled.
  */
private[relay] object DeskSnapshotCodec:
  import DeskControlCodec.Identity

  enum Status(val code: Int):
    case Missing extends Status(0)
    case Fresh extends Status(1)
    case Stale extends Status(2)

  /** Immutable fixed-width bytes, including padding. Receiver text normalization must not erase differences between equal revisions. */
  final case class Label(bytes: Vector[Byte]):
    require(bytes.size == 32, "snapshot label must contain 32 wire bytes")
    def text: String = WireStrings.read(bytes.toArray, 0, 32)

  object Label:
    val Empty: Label = Label(Vector.fill(32)(0.toByte))
    def fromText(value: String): Label =
      val bytes = new Array[Byte](32)
      WireStrings.write(bytes, 0, 32, WireStrings.field(value, 32, TextPolicy.AsciiFolded))
      require(bytes(0) != 0, "snapshot label must remain nonempty after folding")
      Label(bytes.toVector)

  final case class Weather(
      identity: Identity,
      revision: Long,
      deadlineRelayMs: Long,
      status: Status,
      unit: Int = 0,
      condition: Int = 0,
      temperatureCenti: Int = 0,
      humidityPercent: Option[Int] = None,
      location: Label = Label.Empty
  ):
    requireRevision(revision)
    if status == Status.Fresh then
      require(deadlineRelayMs != 0 && location.bytes.head != 0, "fresh weather needs a deadline and label")
      require(unit >= 0 && unit <= 1 && condition >= 0 && condition <= 8, "invalid weather enums")
      require(
        temperatureCenti >= (if unit == 0 then -10000 else -14800) &&
          temperatureCenti <= (if unit == 0 then 10000 else 21200),
        "invalid weather temperature"
      )
      humidityPercent.foreach(value => require(value >= 0 && value <= 100, "invalid humidity"))
    else
      require(
        deadlineRelayMs == 0 && unit == 0 && condition == 0 && temperatureCenti == 0 &&
          humidityPercent.isEmpty && location == Label.Empty,
        "unavailable weather must be zero-filled"
      )

  final case class Cpu(
      identity: Identity,
      revision: Long,
      deadlineRelayMs: Long,
      status: Status,
      cpuBasisPoints: Int = 0,
      memoryBasisPoints: Option[Int] = None,
      temperatureCenti: Option[Int] = None,
      host: Label = Label.Empty
  ):
    requireRevision(revision)
    if status == Status.Fresh then
      require(deadlineRelayMs != 0 && host.bytes.head != 0, "fresh CPU needs a deadline and label")
      require(cpuBasisPoints >= 0 && cpuBasisPoints <= 10000, "invalid CPU percentage")
      memoryBasisPoints.foreach(value => require(value >= 0 && value <= 10000, "invalid memory percentage"))
      temperatureCenti.foreach(value => require(value >= -2000 && value <= 15000, "invalid CPU temperature"))
    else
      require(
        deadlineRelayMs == 0 && cpuBasisPoints == 0 && memoryBasisPoints.isEmpty &&
          temperatureCenti.isEmpty && host == Label.Empty,
        "unavailable CPU must be zero-filled"
      )

  def weather(value: Weather): Array[Byte] =
    requireOutboundLabel(value.location)
    encode(0x28, value.identity, value.revision, value.deadlineRelayMs, value.status): bytes =>
      val _ = bytes.put(value.unit.toByte).put(value.condition.toByte).put(if value.humidityPercent.isDefined then 1.toByte else 0.toByte)
      val _ = bytes.putShort(value.temperatureCenti.toShort).put(value.humidityPercent.getOrElse(0).toByte).put(0.toByte)
      val _ = bytes.put(value.location.bytes.toArray)

  def cpu(value: Cpu): Array[Byte] =
    requireOutboundLabel(value.host)
    encode(0x29, value.identity, value.revision, value.deadlineRelayMs, value.status): bytes =>
      val presence = (if value.memoryBasisPoints.isDefined then 1 else 0) | (if value.temperatureCenti.isDefined then 2 else 0)
      val _ = bytes.put(presence.toByte).putShort(value.cpuBasisPoints.toShort).putShort(value.memoryBasisPoints.getOrElse(0).toShort)
      val _ = bytes.putShort(value.temperatureCenti.getOrElse(0).toShort).put(value.host.bytes.toArray)

  def decodeWeather(frame: Frame): Either[String, Weather] = decode(frame, 0x28): bytes =>
    val identity = Identity(bytes.getLong(), bytes.getLong())
    val revision = Integer.toUnsignedLong(bytes.getInt())
    val deadline = bytes.getLong()
    val status = readStatus(bytes)
    val unit = u8(bytes)
    val condition = u8(bytes)
    val presence = u8(bytes)
    val temperature = bytes.getShort().toInt
    val humidity = u8(bytes)
    require(u8(bytes) == 0 && presence <= 1, "invalid weather reserved or presence field")
    require(presence != 0 || humidity == 0, "absent humidity must be zero-filled")
    Weather(identity, revision, deadline, status, unit, condition, temperature, Option.when(presence == 1)(humidity), readLabel(bytes))

  def decodeCpu(frame: Frame): Either[String, Cpu] = decode(frame, 0x29): bytes =>
    val identity = Identity(bytes.getLong(), bytes.getLong())
    val revision = Integer.toUnsignedLong(bytes.getInt())
    val deadline = bytes.getLong()
    val status = readStatus(bytes)
    val presence = u8(bytes)
    val usage = u16(bytes)
    val memory = u16(bytes)
    val temperature = bytes.getShort().toInt
    require(presence <= 3, "invalid CPU presence field")
    require((presence & 1) != 0 || memory == 0, "absent memory must be zero-filled")
    require((presence & 2) != 0 || temperature == 0, "absent temperature must be zero-filled")
    Cpu(
      identity,
      revision,
      deadline,
      status,
      usage,
      Option.when((presence & 1) != 0)(memory),
      Option.when((presence & 2) != 0)(temperature),
      readLabel(bytes)
    )

  private def requireOutboundLabel(label: Label): Unit =
    val content = label.bytes.takeWhile(_ != 0)
    require(
      label.bytes.last == 0 && content.forall(byte => byte >= 0x20 && byte <= 0x7e) &&
        label.bytes.drop(content.size).forall(_ == 0),
      "outbound snapshot label must be printable ASCII and NUL-padded"
    )

  private def requireRevision(value: Long): Unit = require(value > 0 && value <= 0xffffffffL, "snapshot revision must be nonzero u32")
  private def u8(bytes: ByteBuffer): Int = bytes.get() & 0xff
  private def u16(bytes: ByteBuffer): Int = bytes.getShort() & 0xffff
  private def readStatus(bytes: ByteBuffer): Status =
    val code = u8(bytes)
    Status.values.find(_.code == code).getOrElse(throw IllegalArgumentException("invalid snapshot status"))
  private def readLabel(bytes: ByteBuffer): Label =
    val value = new Array[Byte](32)
    val _ = bytes.get(value)
    Label(value.toVector)

  private def decode[A](frame: Frame, code: Int)(read: ByteBuffer => A): Either[String, A] =
    if !frame.header.version.isCurrent || frame.header.typeCode.value != code then Left("wrong DESK snapshot record")
    else if frame.header.length != frame.payload.length || frame.payload.length < 68 || frame.payload.length > Tsb3.MaxPayload then
      Left("invalid DESK snapshot record length")
    else
      try Right(read(ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)))
      catch case error: IllegalArgumentException => Left(error.getMessage)

  private def encode(code: Int, identity: Identity, revision: Long, deadline: Long, status: Status)(
      write: ByteBuffer => Unit
  ): Array[Byte] =
    val result = new Array[Byte](Tsb3.HeaderSize + 68)
    result(0) = Tsb3.Magic0
    result(1) = Tsb3.Magic1
    result(2) = Tsb3.Version.toByte
    result(3) = code.toByte
    result(4) = 68.toByte
    result(7) = Tsb3.headerCheck(result)
    val bytes = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
    val _ = bytes.position(Tsb3.HeaderSize)
    val _ =
      bytes.putLong(identity.relayEpoch).putLong(identity.connectionId).putInt(revision.toInt).putLong(deadline).put(status.code.toByte)
    write(bytes)
    result
