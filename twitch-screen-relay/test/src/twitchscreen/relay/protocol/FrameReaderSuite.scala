package twitchscreen.relay.protocol

import java.io.{ByteArrayInputStream, InputStream}
import java.time.Instant
import scala.concurrent.duration.DurationInt

/** §4: framing, malformed input and resynchronisation.
  *
  * The two-tier rule of §4.6 is what these tests exist to pin. A malformed *frame* — well-framed but not understood — must not kill the
  * link, because a deterministic encoder bug on either side would otherwise produce an unbreakable reconnect loop with the screen stuck on
  * "connecting". A malformed *stream* must kill it, because a binary wire has no newline to resynchronise on and the budget of §4.5 is what
  * bounds how long the receiver tries before giving up.
  */
class FrameReaderSuite extends munit.FunSuite:
  import WireBytes.*

  private val welcome: Array[Byte] = parse(Tsb3Vectors.V3)
  private val ping: Array[Byte] = parse(Tsb3Vectors.V18)
  private val event: Array[Byte] = parse(Tsb3Vectors.V6)

  // ── Accumulation across short reads (§2) ─────────────────────────────────────────────────────────────────────────

  test("a frame split across several short reads is assembled, not truncated"):
    val stream = ChunkedInputStream(welcome, Seq(3, 5, 10, 14))
    val frame = readOne(FrameReader(stream))
    assertEquals(hex(rebuild(frame)), hex(welcome))
    assert(stream.reads.get() >= 3, s"expected the reader to short-count, it made ${stream.reads.get()} reads")

  test("a frame delivered one byte at a time is assembled"):
    val stream = ChunkedInputStream(event, Seq.fill(event.length)(1))
    val frame = readOne(FrameReader(stream))
    assertEquals(frame.header.length, 168)
    assertEquals(hex(rebuild(frame)), hex(event))

  test("two frames arriving in one read are both returned, and neither consumes the other's bytes"):
    val reader = FrameReader(ByteArrayInputStream(welcome ++ ping))
    assertEquals(hex(rebuild(readOne(reader))), hex(welcome))
    assertEquals(hex(rebuild(readOne(reader))), hex(ping))
    assertEquals(reader.read(), Left(ProtocolError.EndOfStream))

  test("five frames back to back are returned in order"):
    val stream = welcome ++ event ++ ping ++ event ++ welcome
    val reader = FrameReader(ByteArrayInputStream(stream))
    val types = List.fill(5)(readOne(reader).header.typeCode.value)
    assertEquals(types, List(0x20, 0x21, 0x23, 0x21, 0x20))

  // ── Resynchronisation by one-byte window shift (§4.4) ────────────────────────────────────────────────────────────

  test("§4.4: a single injected byte costs a few bytes of garbage, not the connection"):
    val reader = FrameReader(ByteArrayInputStream(Array(0x5a.toByte) ++ welcome))
    assertEquals(hex(rebuild(readOne(reader))), hex(welcome))

  test("§4.4: an injected 0xa7 does not cause a false lock — the window shifts by one, not by eight"):
    val reader = FrameReader(ByteArrayInputStream(Array(Tsb3.Magic0) ++ welcome ++ ping))
    assertEquals(hex(rebuild(readOne(reader))), hex(welcome))
    assertEquals(hex(rebuild(readOne(reader))), hex(ping))

  test("§4.4: a payload carrying 0xa7 is never mistaken for a header, because length is honoured"):
    val record = EventRecord.card(
      seq = SeqNo.fromWire(1),
      kind = NotificationKind.Info,
      title = "§ sign",
      body = "a section sign is c2 a7 in UTF-8 §§§",
      at = Instant.ofEpochSecond(1790309000L),
      ttl = 8.seconds
    )
    val frame = Tsb3Encoder.toDevice(RelayMessage.Event(record))
    assert(frame.drop(Tsb3.HeaderSize).contains(Tsb3.Magic0), "the payload must contain a stray magic byte for this test to mean anything")

    val reader = FrameReader(ByteArrayInputStream(frame ++ ping))
    assertEquals(hex(rebuild(readOne(reader))), hex(frame))
    assertEquals(hex(rebuild(readOne(reader))), hex(ping))

  test("§4.4: a header whose length is impossible is resynchronised past, not trusted"):
    val impossible = header(typeCode = MessageType.Stats.code, length = 249)
    assertEquals(FrameHeader.decode(impossible), Left(ProtocolError.LengthOutOfRange(249, Tsb3.MaxPayload)))

    val reader = FrameReader(ByteArrayInputStream(impossible ++ welcome))
    assertEquals(hex(rebuild(readOne(reader))), hex(welcome))

  // ── The resync budget (§4.5) ─────────────────────────────────────────────────────────────────────────────────────

  test("§4.5: 4096 discarded bytes declare the stream untrustworthy, exactly once"):
    val garbage = Array.fill(4096)(0x42.toByte)
    val reader = FrameReader(ByteArrayInputStream(garbage ++ welcome))
    assertEquals(reader.read(), Left(ProtocolError.FramingViolation(Tsb3.MaxDiscardedBytes, 0)))
    assertEquals(ProtocolError.FramingViolation(4096, 0).disposition, ErrorDisposition.CloseLink)
    assertEquals(ProtocolError.FramingViolation(4096, 0).byeAdvice.map(_._1), Some(ByeCode.FramingViolation))

  test("§4.5: 16 rejected candidate headers declare the stream untrustworthy"):
    val magicRun = Array.fill(64)(Tsb3.Magic0)
    val reader = FrameReader(ByteArrayInputStream(magicRun ++ welcome))
    assertEquals(reader.read(), Left(ProtocolError.FramingViolation(16, Tsb3.MaxRejectedCandidates)))

  test("§4.5: a window that does not start with the magic byte is not a rejected candidate"):
    // 4095 bytes of garbage is one short of the budget, so the frame behind it is still delivered.
    val garbage = Array.fill(Tsb3.MaxDiscardedBytes - 1)(0x42.toByte)
    val reader = FrameReader(ByteArrayInputStream(garbage ++ welcome))
    assertEquals(hex(rebuild(readOne(reader))), hex(welcome))

  test("§4.5: the budget resets on every frame, so scattered corruption never accumulates into a teardown"):
    val noise = Array.fill(3000)(0x42.toByte)
    val stream = noise ++ welcome ++ noise ++ ping ++ noise ++ welcome
    val reader = FrameReader(ByteArrayInputStream(stream))
    assertEquals(List.fill(3)(readOne(reader).header.typeCode.value), List(0x20, 0x23, 0x20))

  test("§14: every window shifted past garbage is reported as a resync, and clean frames report none"):
    var resyncs = 0
    val counting = new FrameCounters:
      def recordOversizeSkipped(): Unit = ()
      def recordResync(): Unit = resyncs += 1
    val noise = Array.fill(30)(0x42.toByte)
    val reader = FrameReader(ByteArrayInputStream(welcome ++ noise ++ ping ++ welcome), counters = counting)
    (1 to 3).foreach(_ => readOne(reader))
    assertEquals(resyncs, 30)

  // ── §4.1's SKIP state ───────────────────────────────────────────────────────────────────────────────

  test("§4.3: a well-framed frame past this receiver's buffer is skipped by `length`, and the link survives it"):
    // §4.3 files this under "payload-level problems — skip the frame, keep the link … It MUST NOT close the
    // connection". Unreachable between two v3 peers, because §4.2 caps `length` at 248 and §5 makes 256 the
    // smallest frame any peer may accept — so the ceiling is forced down here to reach the branch at all.
    var skipped = 0
    val counting = new FrameCounters:
      def recordOversizeSkipped(): Unit = skipped += 1
      def recordResync(): Unit = ()
    val oversize = header(typeCode = MessageType.Event.code, length = 168) ++ Array.fill(168)(0x5a.toByte)
    val reader = FrameReader(ByteArrayInputStream(oversize ++ welcome), maxPayload = 64, counters = counting)

    assertEquals(hex(rebuild(readOne(reader))), hex(welcome))
    assertEquals(skipped, 1)

  test("§4.5: a frame skipped for being oversized resets the budget, because it was correctly framed"):
    // 4095 bytes of garbage, then an oversized frame, then 4095 more: past the budget in total, but the skip in
    // the middle resets it, so the WELCOME behind all of it still arrives.
    val garbage = Array.fill(Tsb3.MaxDiscardedBytes - 1)(0x42.toByte)
    val oversize = header(typeCode = MessageType.Event.code, length = 168) ++ Array.fill(168)(0x5a.toByte)
    val reader = FrameReader(ByteArrayInputStream(garbage ++ oversize ++ garbage ++ welcome), maxPayload = 64)
    assertEquals(hex(rebuild(readOne(reader))), hex(welcome))

  test("§4.2: an impossible header length resynchronizes without trusting its payload length"):
    assertEquals(ProtocolError.LengthOutOfRange(249, 248).disposition, ErrorDisposition.Resynchronize)
    assertEquals(ProtocolError.LengthOutOfRange(249, 248).byeAdvice, None)

  // ── §12's frame budget ────────────────────────────────────────────────────────────────────────

  test("§12: the idle timeout is a budget for one WHOLE frame, so a peer that never completes one is dropped"):
    // The failure this pins: `SO_TIMEOUT` bounds a single read, so a peer emitting one byte per timeout window
    // restarts it forever while completing nothing — it holds a session, a row in the device table and a
    // 128-frame outbound queue open indefinitely, and §4.5's 4096-byte budget would take days to fire at that rate.
    var lastTimeout = 0
    val reader = FrameReader(DribblingInputStream(welcome, perByte = 25.millis))
    val outcome = reader.read(Some(FrameBudget(120.millis, lastTimeout = _)))
    assertEquals(outcome, Left(ProtocolError.FrameTimeout(120.millis)))
    // The socket's own timeout is narrowed to what is left of the budget before each blocking read, so the wait
    // is bounded by the budget itself rather than by one read's worth of it.
    assert(lastTimeout <= 120 && lastTimeout > 0, s"expected a narrowed read timeout, got $lastTimeout")

  test("§12: a frame that arrives inside the budget is returned, budget or no budget"):
    val reader = FrameReader(ByteArrayInputStream(welcome ++ ping))
    val budget = Some(FrameBudget(10.seconds, _ => ()))
    assertEquals(hex(rebuild(reader.read(budget).toOption.get)), hex(welcome))
    assertEquals(hex(rebuild(reader.read(budget).toOption.get)), hex(ping))

  // ── End of stream ────────────────────────────────────────────────────────────────────────────────────────────────

  test("a clean close between frames is end of stream, not a framing violation"):
    val reader = FrameReader(ByteArrayInputStream(ping))
    assertEquals(readOne(reader).header.typeCode.value, 0x23)
    assertEquals(reader.read(), Left(ProtocolError.EndOfStream))

  test("a close in the middle of a payload is reported with what was expected and what arrived"):
    val reader = FrameReader(ByteArrayInputStream(welcome.take(20)))
    assertEquals(reader.read(), Left(ProtocolError.TruncatedFrame(24, 12)))

  test("a close in the middle of a header is reported, not silently retried"):
    assertEquals(FrameReader(ByteArrayInputStream(welcome.take(5))).read(), Left(ProtocolError.TruncatedFrame(8, 5)))

  test("a reader over an empty stream reports end of stream without blocking"):
    assertEquals(FrameReader(InputStream.nullInputStream()).read(), Left(ProtocolError.EndOfStream))

  // ── Header validation (§3.1, §4.2) ───────────────────────────────────────────────────────────────────────────────

  test("§3.1: the header check weights are positional, so a byte swap that a plain sum would miss is caught"):
    val swapped = welcome.clone()
    swapped(3) = welcome(4)
    swapped(4) = welcome(3)
    assertNotEquals(Tsb3.headerCheck(swapped), swapped(7))
    assertEquals(welcome(3).toInt + welcome(4).toInt, swapped(3).toInt + swapped(4).toInt, "a plain additive sum is blind to this")

  test("§4.2: version is deliberately not part of header validation, so a BYE can cross a version boundary"):
    val fromVersionTwo = withHeader(welcome, version = 2)
    val frame = readOne(FrameReader(ByteArrayInputStream(fromVersionTwo)))
    assertEquals(frame.header.version.value, 2)
    assert(!frame.header.version.isCurrent)

  test("§3.2: an unrecognised flags bit is carried, never a reason to reject the frame"):
    val futureFlag = withHeader(welcome, flags = 0x80)
    val frame = readOne(FrameReader(ByteArrayInputStream(futureFlag)))
    assertEquals(frame.header.flags.value, 0x80)
    assert(!frame.header.flags.isReplay)
    assert(Tsb3Decoder.fromRelay(frame).isRight)

  test("§4.2: type 0x00 is illegal and is resynchronised past"):
    assertEquals(FrameHeader.decode(header(typeCode = 0x00, length = 4)), Left(ProtocolError.IllegalTypeCode))

  test("§4.2: a frame that is not TSB/3 fails on the first byte rather than by timeout"):
    val ndjson = """{"op":"hello","device":"roundlcd-01"}""".getBytes("UTF-8")
    assertEquals(ndjson(0), 0x7b.toByte)
    assert(FrameHeader.decode(ndjson).left.exists {
      case _: ProtocolError.BadMagic => true
      case _                         => false
    })

  private def readOne(reader: FrameReader): Frame =
    reader.read().fold(error => fail(error.describe), identity)

  /** Reassembles the bytes a frame came from, so a test can compare against the vector it started with. */
  private def rebuild(frame: Frame): Array[Byte] =
    val bytes = new Array[Byte](Tsb3.HeaderSize + frame.payload.length)
    System.arraycopy(frame.payload, 0, bytes, Tsb3.HeaderSize, frame.payload.length)
    FrameHeader.encodeInto(bytes, frame.header.version, frame.header.typeCode.known.get, frame.header.flags, frame.payload.length)
    bytes
