package twitchscreen.relay.protocol

import java.io.ByteArrayInputStream
import java.time.Instant
import scala.concurrent.duration.{DurationInt, DurationLong}

/** §4.3 and §6: what the decoder does with a frame that is well-framed but not usable.
  *
  * Every case here is a value, never an exception. The distinction §4.3 draws — discard the payload, count it, keep reading — only works if
  * the caller is handed the problem, and a codec that threw would turn a device running unexpected firmware into a crashed session thread.
  */
class Tsb3DecoderSuite extends munit.FunSuite:
  import WireBytes.*

  private val hello: Array[Byte] = parse(Tsb3Vectors.V1)
  private val welcome: Array[Byte] = parse(Tsb3Vectors.V3)
  private val stats: Array[Byte] = parse(Tsb3Vectors.V4)
  private val event: Array[Byte] = parse(Tsb3Vectors.V6)
  private val ping: Array[Byte] = parse(Tsb3Vectors.V16)
  private val ack: Array[Byte] = parse(Tsb3Vectors.V19)

  // ── §4.3: skip the frame, keep the link ──────────────────────────────────────────────────────────────────────────

  test("§17.1: an unknown type is skipped and the next frame decodes correctly"):
    val fromTheFuture = withHeader(hello, typeCode = 0x0f)
    val reader = FrameReader(ByteArrayInputStream(fromTheFuture ++ ping))
    assertEquals(Tsb3Decoder.fromDevice(readOne(reader)), Left(ProtocolError.UnknownType(0x0f)))
    assertEquals(Tsb3Decoder.fromDevice(readOne(reader)), Right(DeviceMessage.Ping(Token.fromWire(4210))))
    assertEquals(ProtocolError.UnknownType(0x0f).disposition, ErrorDisposition.SkipFrame)

  test("§4.3: a type from the receiver's own outbound range is a confused peer, not a corrupt stream"):
    assertEquals(Tsb3Decoder.fromDevice(frame(welcome)), Left(ProtocolError.WrongDirection(0x20)))
    assertEquals(Tsb3Decoder.fromRelay(frame(hello)), Left(ProtocolError.WrongDirection(0x01)))
    assertEquals(ProtocolError.WrongDirection(0x20).disposition, ErrorDisposition.SkipFrame)

  test("§4.3: an unknown type from the receiver's own range is wrong-direction, not unknown"):
    assertEquals(Tsb3Decoder.fromDevice(frame(withHeader(welcome, typeCode = 0x3f))), Left(ProtocolError.WrongDirection(0x3f)))

  test("§4.3: a reserved type outside both ranges is unknown"):
    assertEquals(Tsb3Decoder.fromDevice(frame(withHeader(hello, typeCode = 0x80))), Left(ProtocolError.UnknownType(0x80)))

  test("§17.2: a length one byte short of a known base is skipped, counted, and the link survives"):
    val short = resized(stats, MessageType.Stats.baseLength - 1)
    val reader = FrameReader(ByteArrayInputStream(short ++ welcome))
    assertEquals(Tsb3Decoder.fromRelay(readOne(reader)), Left(ProtocolError.ShortPayload("Stats", 32, 31)))
    assert(Tsb3Decoder.fromRelay(readOne(reader)).isRight, "the frame after a skipped one still decodes")

  test("§4.3: length 0 for a type whose base length is not 0 is a short payload, not an empty message"):
    assertEquals(Tsb3Decoder.fromDevice(frame(resized(ping, 0))), Left(ProtocolError.ShortPayload("DevicePing", 4, 0)))

  test("§17.4: a STATS frame with four extra trailing bytes decodes correctly (§16 rule 2, tail extension)"):
    val extended = resized(stats, MessageType.Stats.baseLength + 4)
    assertEquals(Tsb3Decoder.fromRelay(frame(extended)), Tsb3Decoder.fromRelay(frame(stats)))
    assertEquals(frame(extended).payload.length, 36)

  // ── §6.4: EVENT fields ───────────────────────────────────────────────────────────────────────────────────────────

  test("§17.5: an EVENT with kind 0x7f renders as INFO from actor and text, and is never dropped"):
    val unknownKind = withPayloadByte(event, Tsb3.Event.Kind, 0x7f)
    Tsb3Decoder.fromRelay(frame(unknownKind)) match
      case Right(RelayMessage.Event(record)) =>
        assertEquals(record.kind, NotificationKind.Info)
        assertEquals(record.actor, "w0rxbend")
        assertEquals(record.text, "Round LCD build night")
      case other => fail(s"an unknown kind must decode, got $other")

  test("§17.6: an EVENT with seq = 0 is a field error and is skipped"):
    val zeroSeq = withPayloadByte(event, Tsb3.Event.Seq, 0)
    assertEquals(Tsb3Decoder.fromRelay(frame(zeroSeq)), Left(ProtocolError.InvalidField("seq", 0)))
    assertEquals(ProtocolError.InvalidField("seq", 0).disposition, ErrorDisposition.SkipFrame)

  test("§17.11: non-zero reserved bytes are decoded normally and ignored, never rejected"):
    val dirty = (Tsb3.Event.Reserved1 until Tsb3.Event.Reserved1 + 4)
      .foldLeft(withPayloadByte(event, Tsb3.Event.Reserved2, 0xff))((acc, offset) => withPayloadByte(acc, offset, 0xff))
    assertEquals(Tsb3Decoder.fromRelay(frame(dirty)), Tsb3Decoder.fromRelay(frame(event)))

  test("§8.1: the relay writes the five reserved EVENT bytes as zero and never as anything else"):
    val encoded = Tsb3Encoder.toDevice(
      RelayMessage.Event(EventRecords.raid(SeqNo.fromWire(9), "raider", viewers = 3, at = Instant.EPOCH, ttl = 8.seconds))
    )
    val payload = frame(encoded).payload
    assertEquals(hex(payload.slice(Tsb3.Event.Reserved1, Tsb3.Event.Reserved1 + 4)), "00000000")
    assertEquals(payload(Tsb3.Event.Reserved2), 0.toByte)

  test("§6.4: a tier above 4 is treated as 0 and renders no tier"):
    val strangeTier = withPayloadByte(event, Tsb3.Event.Tier, 9)
    assertEquals(eventOf(strangeTier).tier, SubTier.NotApplicable)

  test("§6.4: a ttl_ds above 6000 is clamped by the receiver, not carried through at face value"):
    // 0xffff deciseconds is 1 h 49 m. The firmware clamps it to ten minutes; a decoder that does not is not a
    // conformant receiver, and a round trip through it cannot catch a sender that emits one.
    val absurd = withPayloadByte(withPayloadByte(event, Tsb3.Event.TtlDs, 0xff), Tsb3.Event.TtlDs + 1, 0xff)
    assertEquals(eventOf(absurd).ttl.deciseconds, DisplayTtl.Max.deciseconds)
    assertEquals(DisplayTtl.Max.deciseconds, 6000)
    // Anything at or below the ceiling is untouched, including the 100 the stream vectors carry.
    assertEquals(eventOf(event).ttl.deciseconds, 100)

  test("§6.4.1: the per-kind display time is the one §18 pins, frame by frame"):
    assertEquals(DisplayTtl.fromDuration(DisplayTtl.defaultFor(NotificationKind.Follow)).deciseconds, 60)
    assertEquals(DisplayTtl.fromDuration(DisplayTtl.defaultFor(NotificationKind.Chat)).deciseconds, 60)
    assertEquals(DisplayTtl.fromDuration(DisplayTtl.defaultFor(NotificationKind.Sub)).deciseconds, 80)
    assertEquals(DisplayTtl.fromDuration(DisplayTtl.defaultFor(NotificationKind.Gift)).deciseconds, 80)
    assertEquals(DisplayTtl.fromDuration(DisplayTtl.defaultFor(NotificationKind.Bits)).deciseconds, 80)
    assertEquals(DisplayTtl.fromDuration(DisplayTtl.defaultFor(NotificationKind.Info)).deciseconds, 80)
    assertEquals(DisplayTtl.fromDuration(DisplayTtl.defaultFor(NotificationKind.Raid)).deciseconds, 100)
    assertEquals(DisplayTtl.fromDuration(DisplayTtl.defaultFor(NotificationKind.StreamStart)).deciseconds, 100)
    assertEquals(DisplayTtl.fromDuration(DisplayTtl.defaultFor(NotificationKind.StreamEnd)).deciseconds, 100)
    // No kind may fall through to a value the device would have to guess at (§6.4: 0 = receiver's default).
    assert(NotificationKind.values.forall(kind => DisplayTtl.fromDuration(DisplayTtl.defaultFor(kind)).deciseconds > 0))

  test("§6.4: unknown eflags bits are carried, never a reason to reject"):
    val futureFlags = withPayloadByte(event, Tsb3.Event.EFlags, 0xe4)
    assertEquals(eventOf(futureFlags).flags.value, 0xe4)

  test("§17.7: a string field with no NUL in its last byte is terminated by the receiver"):
    val unterminated = (Tsb3.Event.Actor until Tsb3.Event.Actor + Tsb3.ActorWidth)
      .foldLeft(event)((acc, offset) => withPayloadByte(acc, offset, 0x41))
    val record = eventOf(unterminated)
    assertEquals(record.actor, "A" * 47)
    assertEquals(record.actor.length, Tsb3.ActorWidth - 1)

  test("§9.1: invalid UTF-8 is replaced rather than rejected — malformed text is cosmetic, never a link problem"):
    val broken = withPayloadByte(withPayloadByte(event, Tsb3.Event.Actor, 0xc3), Tsb3.Event.Actor + 1, 0x28)
    assert(Tsb3Decoder.fromRelay(frame(broken)).isRight)

  // ── §6.1: HELLO validation ───────────────────────────────────────────────────────────────────────────────────────

  test("§6.1: rx_max below 256 is an invalid parameter, and the BYE detail is the field's payload offset"):
    val tiny = withPayloadByte(withPayloadByte(hello, Tsb3.Hello.RxMax, 0x80), Tsb3.Hello.RxMax + 1, 0x00)
    val error = ProtocolError.InvalidField("rx_max", Tsb3.Hello.RxMax)
    assertEquals(Tsb3Decoder.fromDevice(frame(tiny)), Left(error))
    assertEquals(error.byeAdvice.map((code, detail) => (code, detail.value)), Some((ByeCode.InvalidParameter, 8)))

  test("§6.1: a device id with no NUL anywhere in its field is refused"):
    val unterminated = (Tsb3.Hello.DeviceId until Tsb3.Hello.DeviceId + Tsb3.DeviceIdWidth)
      .foldLeft(hello)((acc, offset) => withPayloadByte(acc, offset, 0x41))
    assert(invalidDeviceId(unterminated))

  test("§6.1: a device id that is blank after trimming ASCII whitespace is refused"):
    val blank = (0 until 11).foldLeft(hello)((acc, index) => withPayloadByte(acc, Tsb3.Hello.DeviceId + index, 0x20))
    assert(invalidDeviceId(blank))

  test("§6.1: a byte outside 0x20…0x7e before the terminator is refused"):
    assert(invalidDeviceId(withPayloadByte(hello, Tsb3.Hello.DeviceId + 2, 0x7f)))
    assert(invalidDeviceId(withPayloadByte(hello, Tsb3.Hello.DeviceId + 2, 0x09)))
    assert(invalidDeviceId(withPayloadByte(hello, Tsb3.Hello.DeviceId + 2, 0xc5)))

  test("§6.1: an invalid device id closes the link with BYE(3), it is not skipped"):
    val error = ProtocolError.InvalidDeviceId("blank")
    assertEquals(error.disposition, ErrorDisposition.CloseLink)
    assertEquals(error.byeAdvice.map(_._1), Some(ByeCode.InvalidDeviceId))

  test("§6.1: an unterminated or non-ASCII fw_version is logged and otherwise ignored, never fatal"):
    val noisy = (Tsb3.Hello.FwVersion until Tsb3.Hello.FwVersion + Tsb3.FwVersionWidth)
      .foldLeft(hello)((acc, offset) => withPayloadByte(acc, offset, 0x41))
    assert(Tsb3Decoder.fromDevice(frame(noisy)).isRight)

  test("§6.1: unknown capability bits are ignored, never rejected"):
    val futureCaps = withPayloadByte(hello, Tsb3.Hello.Caps + 3, 0x80)
    Tsb3Decoder.fromDevice(frame(futureCaps)) match
      case Right(message: DeviceMessage.Hello) =>
        assert(message.caps.contains(Capabilities.Ack))
        assertEquals(message.caps.intersect(Capabilities.RelaySupported).value, 0x07)
      case other => fail(s"unknown capability bits must decode, got $other")

  // ── §7: version negotiation ──────────────────────────────────────────────────────────────────────────────────────

  test("§7: the version check on HELLO is exact equality, and the refusal carries the version this relay speaks"):
    val fromVersionTwo = withHeader(hello, version = 2)
    val error = ProtocolError.UnsupportedVersion(2, 3)
    assertEquals(Tsb3Decoder.fromDevice(frame(fromVersionTwo)), Left(error))
    assertEquals(error.disposition, ErrorDisposition.CloseLink)
    assertEquals(error.byeAdvice.map((code, detail) => (code, detail.value)), Some((ByeCode.UnsupportedVersion, 3)))

  test("§7: a version above 3 is refused just as a version below it is"):
    assertEquals(Tsb3Decoder.fromDevice(frame(withHeader(hello, version = 4))), Left(ProtocolError.UnsupportedVersion(4, 3)))

  // ── §6.6, §6.3: the small frames ─────────────────────────────────────────────────────────────────────────────────

  test("§6.6: ACK carries only a sequence number, and seq 0 on an ACK is legal"):
    assertEquals(Tsb3Decoder.fromDevice(frame(ack)), Right(DeviceMessage.Ack(SeqNo.fromWire(127))))
    val zero = (0 until 4).foldLeft(ack)((acc, offset) => withPayloadByte(acc, offset, 0))
    assertEquals(Tsb3Decoder.fromDevice(frame(zero)), Right(DeviceMessage.Ack(SeqNo.Zero)))

  test("§6.3: a token is opaque and survives the full u32 range"):
    val encoded = Tsb3Encoder.toRelay(DeviceMessage.Ping(Token.fromWire(0xffffffffL)))
    assertEquals(Tsb3Decoder.fromDevice(frame(encoded)), Right(DeviceMessage.Ping(Token.fromWire(0xffffffffL))))
    assertEquals(hex(encoded).drop(16), "ffffffff")

  // ── Round trips over the full field ranges ───────────────────────────────────────────────────────────────────────

  test("every relay message round-trips through its own bytes"):
    val messages = List(
      RelayMessage.Welcome(
        SeqNo.fromWire(0xffffffffL),
        Some(Instant.ofEpochSecond(0xffffffffL)),
        SessionId.fromWire(0xffffffffL),
        FrameSize.fromWire(0xffff),
        0.seconds,
        65535.seconds,
        ReplayWindow.fromWire(0xffff),
        Capabilities.fromWire(0xffffffffL)
      ),
      RelayMessage.Stats(
        StreamStats(
          StreamState.Live,
          Count.clamp(Int.MaxValue),
          Count.clamp(Int.MaxValue),
          Count.clamp(Int.MaxValue),
          4294967295L.seconds,
          MessagesPerMinute.clamp(0xffff),
          Count.clamp(Int.MaxValue),
          Some(Instant.ofEpochSecond(1L))
        ),
        Some(Instant.ofEpochSecond(1L))
      ),
      RelayMessage.Ping(Token.fromWire(0)),
      RelayMessage.Pong(Token.fromWire(1)),
      RelayMessage.Bye(ByeCode.Unknown(1042), ByeDetail.of(0xffff), 65535.seconds, "a" * 40)
    )
    messages.foreach: message =>
      val encoded = Tsb3Encoder.toDevice(message)
      assert(encoded.length <= Tsb3.MaxFrame, s"$message encoded to ${encoded.length} bytes")
      assertEquals(Tsb3Decoder.fromRelay(frame(encoded)).map(decoded => hex(Tsb3Encoder.toDevice(decoded))), Right(hex(encoded)))

  test("a BYE reason longer than its field is truncated rather than dropped, and the frame stays 40 bytes"):
    val bye = RelayMessage.Bye(ByeCode.ServerShutdown, ByeDetail.Zero, 5.seconds, "the relay is going away for maintenance")
    val encoded = Tsb3Encoder.toDevice(bye)
    assertEquals(encoded.length, 40)
    Tsb3Decoder.fromRelay(frame(encoded)) match
      case Right(decoded: RelayMessage.Bye) =>
        assertEquals(decoded.reason, "the relay is going a...")
        assertEquals(decoded.reason.length, 23)
      case other => fail(s"expected a BYE, got $other")

  // ── Helpers ──────────────────────────────────────────────────────────────────────────────────────────────────────

  private def readOne(reader: FrameReader): Frame = reader.read().fold(error => fail(error.describe), identity)

  private def frame(bytes: Array[Byte]): Frame = frameOf(bytes).fold(error => fail(error.describe), identity)

  private def eventOf(bytes: Array[Byte]): EventRecord = Tsb3Decoder.fromRelay(frame(bytes)) match
    case Right(RelayMessage.Event(record)) => record
    case other                             => fail(s"expected an EVENT, got $other")

  private def invalidDeviceId(bytes: Array[Byte]): Boolean = Tsb3Decoder.fromDevice(frame(bytes)).left.exists {
    case _: ProtocolError.InvalidDeviceId => true
    case _                                => false
  }
