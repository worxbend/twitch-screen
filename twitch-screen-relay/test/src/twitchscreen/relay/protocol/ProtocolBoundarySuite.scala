package twitchscreen.relay.protocol

import java.io.ByteArrayInputStream
import java.time.Instant
import scala.concurrent.duration.DurationInt

class ProtocolBoundarySuite extends munit.FunSuite:
  private def decoded(message: RelayMessage): RelayMessage =
    val bytes = Tsb3Encoder.toDevice(message, text = TextPolicy.AsciiFolded)
    val frame = FrameReader(ByteArrayInputStream(bytes)).read().toOption.get
    Tsb3Decoder.fromRelay(frame).toOption.get

  test("chat rates saturate at the wire width instead of wrapping"):
    for value <- List(65535, 65536, 70000, Int.MaxValue) do
      val stats = StreamStats.Unknown.copy(chatRate = MessagesPerMinute.clamp(value))
      val result = decoded(RelayMessage.Stats(stats, None)).asInstanceOf[RelayMessage.Stats]
      assertEquals(result.stats.chatRate.value, 65535)

  test("ASCII fallback applies only to audience display names"):
    for kind <- NotificationKind.values do
      val record = EventRequest(kind, "🎉", "", 6.seconds).record(SeqNo.fromWire(1), Instant.EPOCH)
      val result = decoded(RelayMessage.Event(record)).asInstanceOf[RelayMessage.Event]
      val isAudience = Set(
        NotificationKind.Follow,
        NotificationKind.Sub,
        NotificationKind.Gift,
        NotificationKind.Raid,
        NotificationKind.Chat,
        NotificationKind.Bits
      ).contains(kind)
      assertEquals(result.record.actor, if isAudience then "viewer" else "", kind.toString)

  test("device identity keeps spaces distinct and enforces the wire byte contract"):
    assertEquals(DeviceId(" device ").map(_.value), Right(" device "))
    assert(DeviceId(" " * 31).isLeft)
    assert(DeviceId("x" * 31).isRight)
    assert(DeviceId("x" * 32).isLeft)
    assert(DeviceId("dev\n01").isLeft)
    assert(DeviceId("dév01").isLeft)
