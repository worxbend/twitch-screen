package twitchscreen.relay.protocol

import java.io.ByteArrayInputStream
import java.time.Instant
import scala.concurrent.duration.DurationInt

class ProtocolBoundarySuite extends munit.FunSuite:
  test("sequence allocation reaches u32 maximum and then refuses instead of wrapping"):
    assertEquals(SeqNo.fromWire(0xfffffffeL).next, Some(SeqNo.Max))
    assertEquals(SeqNo.Max.next, None)
    assert(SeqNo(0x100000000L).isLeft)

  private def decoded(message: RelayMessage): RelayMessage =
    val bytes = Tsb3Encoder.toDevice(message, text = TextPolicy.AsciiFolded)
    val frame = FrameReader(ByteArrayInputStream(bytes)).read().toOption.get
    Tsb3Decoder.fromRelay(frame).toOption.get

  test("chat rates saturate at the wire width instead of wrapping"):
    for value <- List(65535, 65536, 70000, Int.MaxValue) do
      val stats = StreamStats.Unknown.copy(chatRate = MessagesPerMinute.clamp(value))
      decoded(RelayMessage.Stats(stats, None)) match
        case RelayMessage.Stats(result, _) => assertEquals(result.chatRate.value, 65535)
        case other                         => fail(s"expected STATS, got $other")

  test("ASCII fallback applies only to audience display names"):
    for kind <- NotificationKind.values do
      val record = EventRequest(kind, "🎉", "", 6.seconds).record(SeqNo.fromWire(1), Instant.EPOCH)
      val result = decoded(RelayMessage.Event(record)) match
        case RelayMessage.Event(result) => result
        case other                      => fail(s"expected EVENT, got $other")
      val isAudience = Set(
        NotificationKind.Follow,
        NotificationKind.Sub,
        NotificationKind.Gift,
        NotificationKind.Raid,
        NotificationKind.Chat,
        NotificationKind.Bits
      ).contains(kind)
      assertEquals(result.actor, if isAudience then "viewer" else "", kind.toString)

  test("device identity keeps spaces distinct and enforces the wire byte contract"):
    assertEquals(DeviceId(" device ").map(_.value), Right(" device "))
    assert(DeviceId(" " * 31).isLeft)
    assert(DeviceId("x" * 31).isRight)
    assert(DeviceId("x" * 32).isLeft)
    assert(DeviceId("dev\n01").isLeft)
    assert(DeviceId("dév01").isLeft)

  private inline def caseCount[T](using m: scala.deriving.Mirror.SumOf[T]): Int =
    scala.compiletime.constValue[Tuple.Size[m.MirroredElemTypes]]

  test("§6.7: no ProtocolError variant advises BYE 4 (INVALID_SEQUENCE) or 6 (FRAME_TOO_LARGE)"):
    val samples: List[ProtocolError] = List(
      ProtocolError.BadMagic(0, 0),
      ProtocolError.HeaderCheckFailed(0, 1),
      ProtocolError.IllegalTypeCode,
      ProtocolError.LengthOutOfRange(300, 248),
      ProtocolError.FramingViolation(4096, 0),
      ProtocolError.EndOfStream,
      ProtocolError.TruncatedFrame(8, 3),
      ProtocolError.FrameTimeout(1.second),
      ProtocolError.UnknownType(0x0f),
      ProtocolError.WrongDirection(0x21),
      ProtocolError.ShortPayload("PING", 4, 3),
      ProtocolError.InvalidField("rx_max", 60),
      ProtocolError.UnsupportedVersion(2, 3),
      ProtocolError.InvalidDeviceId("x")
    )
    // Every variant is enumerated: a case added later changes the count and fails here until it gets a sample.
    assertEquals(samples.map(_.ordinal).toSet, (0 until caseCount[ProtocolError]).toSet)
    for error <- samples do
      val code = error.byeAdvice.map(_._1.value)
      assert(!code.contains(4) && !code.contains(6), s"${error.describe} advises reserved BYE $code")
    // The reserved codes are still decoded, so a peer that sends one is logged by name.
    assertEquals(ByeCode.fromWire(4), ByeCode.InvalidSequence)
    assertEquals(ByeCode.fromWire(6), ByeCode.FrameTooLarge)
    assertEquals(ByeCode.InvalidSequence.value, 4)
    assertEquals(ByeCode.FrameTooLarge.value, 6)
