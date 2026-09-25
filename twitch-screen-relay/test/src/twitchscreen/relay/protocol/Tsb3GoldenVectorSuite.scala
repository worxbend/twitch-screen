package twitchscreen.relay.protocol

import java.io.ByteArrayInputStream
import java.time.Instant
import scala.concurrent.duration.DurationInt

/** Asserts every one of the twenty golden vectors of `twitch-screen-firmware/docs/PROTOCOL.md` §18, in both directions: the encoder
  * produces exactly those bytes, and the decoder reads exactly those bytes back into the documented field values.
  *
  * §17 makes this mandatory and §18 explains why: the relay and the firmware are written independently from one document, so the vectors
  * are the only thing that can catch a disagreement about an offset, a width or a byte order before it reaches a screen. A failure here is
  * a bug in this codec, never in the vector.
  *
  * Comparisons are made as lowercase hex strings so that a failure names the byte that moved instead of printing two arrays.
  */
class Tsb3GoldenVectorSuite extends munit.FunSuite:
  import Tsb3Vectors.*

  private val serverTime: Instant = Instant.ofEpochSecond(1790309000L)
  private val streamStart: Instant = Instant.ofEpochSecond(1790305340L)

  // §18: the device advertised CAP_UTF8_TEXT (V2), so every string in the scenario travels verbatim.
  private val verbatim: TextPolicy = TextPolicy.Verbatim

  test("all twenty vectors of §18 are present and internally consistent"):
    assertEquals(All.size, 20)
    All.foreach: (label, vector) =>
      val frame = bytesOf(vector)
      assertEquals(frame(0) & 0xff, 0xa7, s"$label magic0")
      assertEquals(frame(1) & 0xff, 0x53, s"$label magic1")
      assertEquals(frame(2) & 0xff, 0x03, s"$label version")
      assertEquals(frame(7), Tsb3.headerCheck(frame), s"$label header check (§3.1)")
      assertEquals((frame(4) & 0xff) | ((frame(5) & 0xff) << 8), frame.length - 8, s"$label length")

  // ── The handshake, device → relay ────────────────────────────────────────────────────────────────────────────────

  test("V1 hello_fresh_boot"):
    val hello: DeviceMessage.Hello = DeviceMessage.Hello(
      deviceId = deviceId("roundlcd-01"),
      lastSeq = SeqNo.Zero,
      caps = Capabilities.Ack | Capabilities.Chat | Capabilities.Generic,
      rxMax = FrameSize.fromWire(256),
      fwVersion = FirmwareVersion.fromWire("1.0.0")
    )
    assertBytes("V1", V1, Tsb3Encoder.toRelay(hello))
    assertEquals(decodeDevice(V1), hello)

  test("V2 hello_resume_utf8"):
    val hello: DeviceMessage.Hello = DeviceMessage.Hello(
      deviceId = deviceId("roundlcd-01"),
      lastSeq = SeqNo.fromWire(117),
      caps = Capabilities.RelaySupported,
      rxMax = FrameSize.fromWire(256),
      fwVersion = FirmwareVersion.fromWire("1.0.0")
    )
    assertBytes("V2", V2, Tsb3Encoder.toRelay(hello))
    assertEquals(decodeDevice(V2), hello)
    assertEquals(hello.caps.value, 0x0f)

  // ── The handshake and telemetry, relay → device ──────────────────────────────────────────────────────────────────

  test("V3 welcome"):
    val welcome = RelayMessage.Welcome(
      latestSeq = SeqNo.fromWire(127),
      serverTime = Some(serverTime),
      sessionId = SessionId.fromWire(0x9c4e17a0L),
      maxFrame = FrameSize.fromWire(256),
      pingInterval = 20.seconds,
      idleTimeout = 90.seconds,
      replayWindow = ReplayWindow.Durable,
      caps = Capabilities.RelaySupported
    )
    assertBytes("V3", V3, Tsb3Encoder.toDevice(welcome))
    assertEquals(decodeRelay(V3), welcome)

  test("V4 stats_live"):
    val stats = RelayMessage.Stats(
      StreamStats(
        state = StreamState.Live,
        viewers = Count.clamp(842),
        followers = Count.clamp(12400),
        subscribers = Count.clamp(318),
        uptime = 3660.seconds,
        chatRate = MessagesPerMinute.clamp(35),
        messagesTotal = Count.clamp(2135),
        streamStartedAt = Some(streamStart)
      ),
      serverTime = Some(serverTime)
    )
    assertBytes("V4", V4, Tsb3Encoder.toDevice(stats))
    assertEquals(decodeRelay(V4), stats)

  test("V5 stats_offline — msg_total is retained until the next stream starts"):
    val stats = RelayMessage.Stats(
      StreamStats(
        state = StreamState.Offline,
        viewers = Count.Zero,
        followers = Count.clamp(12400),
        subscribers = Count.clamp(318),
        uptime = 0.seconds,
        chatRate = MessagesPerMinute.Zero,
        messagesTotal = Count.clamp(2135),
        streamStartedAt = None
      ),
      serverTime = Some(Instant.ofEpochSecond(1790309600L))
    )
    assertBytes("V5", V5, Tsb3Encoder.toDevice(stats))
    assertEquals(decodeRelay(V5), stats)

  // ── The ten event kinds a v3 relay can emit ──────────────────────────────────────────────────────────────────────

  test("V6 event_stream_start"):
    val record = EventRecord.streamStart(
      seq = SeqNo.fromWire(118),
      channel = "w0rxbend",
      title = "Round LCD build night",
      startedAt = streamStart,
      at = streamStart,
      ttl = 10.seconds
    )
    assertBytes("V6", V6, Tsb3Encoder.toDevice(RelayMessage.Event(record), text = verbatim))
    assertEquals(eventOf(V6), record)
    assertEquals(record.kind.code, 0x10)
    assertEquals(record.value.value, 1790305340L)

  test("V7 event_follow — value is fixed at 0, because a follower total of 0 would mean 'not reported'"):
    val record = EventRecord.follow(SeqNo.fromWire(119), "newfriend", at = Instant.ofEpochSecond(1790308990L), ttl = 6.seconds)
    assertBytes("V7", V7, Tsb3Encoder.toDevice(RelayMessage.Event(record), text = verbatim))
    assertEquals(eventOf(V7), record)
    assertEquals(record.value.value, 0L)
    assertEquals(record.text, "")

  test("V8 event_sub — months and tier reach the wire as numbers, not as English"):
    val record = EventRecord.sub(
      seq = SeqNo.fromWire(120),
      subscriber = "loyalviewer",
      tier = SubTier.Tier2,
      months = SubMonths.clamp(14),
      message = "fourteen months!",
      at = Instant.ofEpochSecond(1790308995L),
      ttl = 8.seconds
    )
    assertBytes("V8", V8, Tsb3Encoder.toDevice(RelayMessage.Event(record), text = verbatim))
    assertEquals(eventOf(V8), record)
    assertEquals(record.tier.code, 3)
    assertEquals(record.months.value, 14)

  test("V9 event_gift"):
    val record = EventRecord.gift(
      seq = SeqNo.fromWire(121),
      gifter = "generouspal",
      tier = SubTier.Tier1,
      count = 5,
      anonymous = false,
      at = Instant.ofEpochSecond(1790308998L),
      ttl = 8.seconds
    )
    assertBytes("V9", V9, Tsb3Encoder.toDevice(RelayMessage.Event(record), text = verbatim))
    assertEquals(eventOf(V9), record)
    assertEquals(record.value.value, 5L)

  test("V10 event_raid_replayed — REPLAY lives in the header, and changes exactly bytes 6 and 7"):
    val record = EventRecord.raid(SeqNo.fromWire(122), "streamfriend", viewers = 128, at = serverTime, ttl = 10.seconds)
    val replayed = Tsb3Encoder.toDevice(RelayMessage.Event(record), flags = FrameFlags.Replay, text = verbatim)
    assertBytes("V10", V10, replayed)
    assertEquals(eventOf(V10), record)
    assertEquals(record.value.value, 128L)

    val live = Tsb3Encoder.toDevice(RelayMessage.Event(record), text = verbatim)
    assertEquals(hex(Tsb3Encoder.withFlags(live, FrameFlags.Replay)), hex(replayed), "§3.2 flag rewrite")
    assertEquals(live.zip(replayed).zipWithIndex.collect { case ((a, b), i) if a != b => i }.toList, List(6, 7))
    assert(frameOf(V10).header.flags.isReplay)
    assert(!frameOf(V11).header.flags.isReplay)

  test("V11 event_bits — a plain count of bits; bits are not money and carry no currency (§8)"):
    val record = EventRecord.bits(
      seq = SeqNo.fromWire(123),
      sender = "bitsfan",
      amount = 1500,
      message = "take my bits",
      at = Instant.ofEpochSecond(1790309030L),
      ttl = 8.seconds
    )
    assertBytes("V11", V11, Tsb3Encoder.toDevice(RelayMessage.Event(record), text = verbatim))
    assertEquals(eventOf(V11), record)
    assertEquals(record.value.value, 1500L)

  test("V12 event_chat_utf8 — field widths are byte counts, never character counts"):
    val record = EventRecord.chat(
      seq = SeqNo.fromWire(124),
      chatter = "Paweł",
      message = "świetny stream! 🎉",
      colour = ChatColour.fromHex("#ff7f50"),
      at = Instant.ofEpochSecond(1790309061L),
      ttl = 6.seconds
    )
    assertBytes("V12", V12, Tsb3Encoder.toDevice(RelayMessage.Event(record), text = verbatim))
    assertEquals(eventOf(V12), record)
    assert(record.flags.contains(EventFlags.ChatColourPresent))
    assertEquals(record.value.value, 0x00ff7f50L)
    assertEquals(record.actor.getBytes("UTF-8").length, 6)
    assertEquals(record.text.getBytes("UTF-8").length, 21)

  test("V13 event_chat_truncated — §9.2 cuts at a code-point boundary and sets both eflags bits"):
    // The sources are reconstructed from the vector: §9.2's walk-back is what turns 68 and 99 bytes into 46 and 94.
    val longActor = "Absolutnie Niezwyciezona Czarodziejka Znad Świetlistej Doliny Mglej"
    val longText = "to jest strasznie długa informacja na czacie która nie zmieści się w polu tekstowym urządzenia"
    assertEquals(longActor.getBytes("UTF-8").length, 68)
    assertEquals(longText.getBytes("UTF-8").length, 99)

    val record = EventRecord.chat(
      seq = SeqNo.fromWire(125),
      chatter = longActor,
      message = longText,
      colour = None,
      at = Instant.ofEpochSecond(1790309062L),
      ttl = 6.seconds
    )
    assertBytes("V13", V13, Tsb3Encoder.toDevice(RelayMessage.Event(record), text = verbatim))

    val decoded = eventOf(V13)
    assertEquals(decoded.actor, "Absolutnie Niezwyciezona Czarodziejka Znad ...")
    assertEquals(decoded.text, "to jest strasznie długa informacja na czacie która nie zmieści się w polu tekstowym urz...")
    assertEquals(decoded.actor.getBytes("UTF-8").length, 46)
    assertEquals(decoded.text.getBytes("UTF-8").length, 94)
    assertEquals(decoded.flags.value, 0x03)
    assertEquals(decoded.copy(actor = longActor, text = longText, flags = EventFlags.Empty), record)

  test("V14 event_info_generic — a posted card keeps its own title and body, numeric fields at 0 (§6.4.3)"):
    val record = EventRecord.card(
      seq = SeqNo.fromWire(126),
      kind = NotificationKind.Info,
      title = "Relay restarted",
      body = "manual card posted to /api/v1/notifications",
      at = Instant.ofEpochSecond(1790309070L),
      ttl = 8.seconds
    )
    assertBytes("V14", V14, Tsb3Encoder.toDevice(RelayMessage.Event(record), text = verbatim))
    assertEquals(eventOf(V14), record)
    assertEquals(record.kind.code, 0x00)
    assertEquals(record.value.value, 0L)
    assertEquals(record.months.value, 0)
    assertEquals(record.tier.code, 0)

  test("V15 event_stream_end"):
    val record = EventRecord.streamEnd(
      seq = SeqNo.fromWire(127),
      channel = "w0rxbend",
      duration = 3760.seconds,
      at = Instant.ofEpochSecond(1790309100L),
      ttl = 10.seconds
    )
    assertBytes("V15", V15, Tsb3Encoder.toDevice(RelayMessage.Event(record), text = verbatim))
    assertEquals(eventOf(V15), record)
    assertEquals(record.kind.code, 0x11)
    assertEquals(record.value.value, 3760L)

  // ── Heartbeat, acknowledgement and refusal ───────────────────────────────────────────────────────────────────────

  test("V16 ping_device"):
    val ping = DeviceMessage.Ping(Token.fromWire(4210))
    assertBytes("V16", V16, Tsb3Encoder.toRelay(ping))
    assertEquals(decodeDevice(V16), ping)

  test("V17 pong_relay echoes the token of V16 byte for byte"):
    val pong = RelayMessage.Pong(Token.fromWire(4210))
    assertBytes("V17", V17, Tsb3Encoder.toDevice(pong))
    assertEquals(decodeRelay(V17), pong)
    assertEquals(hex(bytesOf(V17)).drop(16), hex(bytesOf(V16)).drop(16), "the payload is the PING's, unchanged")

  test("V18 ping_relay"):
    val ping = RelayMessage.Ping(Token.fromWire(1790309000L))
    assertBytes("V18", V18, Tsb3Encoder.toDevice(ping))
    assertEquals(decodeRelay(V18), ping)

  test("V19 ack_device"):
    val ack = DeviceMessage.Ack(SeqNo.fromWire(127))
    assertBytes("V19", V19, Tsb3Encoder.toRelay(ack))
    assertEquals(decodeDevice(V19), ack)

  test("V20 bye_version_mismatch — the frozen payload that lets a peer read a refusal it cannot otherwise parse"):
    val bye = RelayMessage.Bye(
      code = ByeCode.UnsupportedVersion,
      detail = ByeDetail.of(3),
      retryAfter = 30.seconds,
      reason = "relay speaks v3 only"
    )
    assertBytes("V20", V20, Tsb3Encoder.toDevice(bye))
    assertEquals(decodeRelay(V20), bye)
    assertEquals(ByeCode.UnsupportedVersion.value, 1)

  test("§6.7: a BYE is encoded with the version byte of the frame that provoked it"):
    val bye = RelayMessage.Bye(ByeCode.UnsupportedVersion, ByeDetail.of(3), 30.seconds, "relay speaks v3 only")
    val encoded = Tsb3Encoder.toDevice(bye, version = ProtocolVersion.fromWire(2.toByte))
    assertEquals(encoded(2), 2.toByte)
    assertEquals(hex(encoded).drop(16), hex(bytesOf(V20)).drop(16), "the frozen payload is unchanged")
    assertEquals(encoded(7), Tsb3.headerCheck(encoded))

  test("§2.1: every multi-byte integer is little-endian — viewers = 0x12345678 encodes as 78 56 34 12"):
    val stats = RelayMessage.Stats(StreamStats.Unknown.copy(viewers = Count.clamp(0x12345678)), serverTime = None)
    assertEquals(hex(Tsb3Encoder.toDevice(stats)).slice(16, 24), "78563412")

  // ── Helpers ──────────────────────────────────────────────────────────────────────────────────────────────────────

  private def assertBytes(label: String, vector: String, encoded: Array[Byte]): Unit =
    assertEquals(hex(encoded), hex(bytesOf(vector)), s"$label bytes")

  private def hex(bytes: Array[Byte]): String = bytes.map(byte => f"${byte & 0xff}%02x").mkString

  private def bytesOf(vector: String): Array[Byte] =
    vector.filterNot(_.isWhitespace).grouped(2).map(pair => Integer.parseInt(pair, 16).toByte).toArray

  private def frameOf(vector: String): Frame =
    FrameReader(ByteArrayInputStream(bytesOf(vector))).read().fold(error => fail(error.describe), identity)

  private def decodeDevice(vector: String): DeviceMessage =
    Tsb3Decoder.fromDevice(frameOf(vector)).fold(error => fail(error.describe), identity)

  private def decodeRelay(vector: String): RelayMessage =
    Tsb3Decoder.fromRelay(frameOf(vector)).fold(error => fail(error.describe), identity)

  private def eventOf(vector: String): EventRecord = decodeRelay(vector) match
    case RelayMessage.Event(record) => record
    case other                      => fail(s"expected an EVENT, got $other")

  private def deviceId(raw: String): DeviceId = DeviceId(raw).fold(detail => fail(detail), identity)
