package twitchscreen.relay.protocol

import java.io.{ByteArrayInputStream, InputStream}
import java.net.SocketTimeoutException
import scala.concurrent.duration.DurationInt

class KimiProtocolRegressionSuite extends munit.FunSuite:
  test("socket read timeouts are protocol values with and without an explicit frame budget"):
    val source = new InputStream:
      override def read(): Int = throw SocketTimeoutException("timed out")
    assertEquals(FrameReader(source).read(Some(FrameBudget(2.seconds, _ => ()))), Left(ProtocolError.FrameTimeout(2.seconds)))
    assertEquals(FrameReader(source).read(), Left(ProtocolError.FrameTimeout(0.seconds)))

  test("deadline arithmetic remains valid near nanoTime wraparound"):
    var now = Long.MaxValue - 10.millis.toNanos
    val bytes = Tsb3Encoder.toRelay(DeviceMessage.Ping(Token.fromWire(7)))
    val source = new ByteArrayInputStream(bytes):
      override def read(target: Array[Byte], offset: Int, length: Int): Int =
        now += 5.millis.toNanos
        super.read(target, offset, 1)
    assertEquals(FrameReader(source).read(Some(FrameBudget(30.millis, _ => (), () => now))), Left(ProtocolError.FrameTimeout(30.millis)))

  test("narrow string fields never exceed their content capacity"):
    for width <- -1 to 4 do
      val field = WireStrings.truncate("abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8), width)
      assert(field.truncated)
      assertEquals(field.bytes.length, math.max(0, width - 1))

  test("folding preserves ASCII and handles capital sharp S and presentation ligatures"):
    assertEquals(WireStrings.fold("  plain  ASCII  "), "plain ASCII")
    assertEquals(WireStrings.fold("ẞ ﬀ ﬁ ﬂ ﬃ ﬄ ﬅ ﬆ"), "SS ff fi fl ffi ffl st st")

  test("TTL receiver clamp preserves 6000 and clamps 6001"):
    assertEquals(DisplayTtl.fromWire(6000).deciseconds, 6000)
    assertEquals(DisplayTtl.fromWire(6001).deciseconds, 6000)

  test("an unknown wire timestamp remains absent in the HTTP projection"):
    val record = EventRequest.of(NotificationKind.Info, "title", "body").record(SeqNo.fromWire(1), java.time.Instant.EPOCH).copy(at = None)
    assertEquals(Notification.from(record).at, None)
