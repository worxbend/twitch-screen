package twitchscreen.relay.protocol

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Path}
import java.util.HexFormat
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*

class DeskMessageReportCodecSuite extends munit.FunSuite:
  import DeskMessageCodec.*
  import DeskReportCodec.*
  private val identity = DeskControlCodec.Identity(0x0123456789abcdefL, 0xfedcba9876543210L)
  private val hex = HexFormat.of()
  private def frame(bytes: Array[Byte]): Frame = FrameReader(ByteArrayInputStream(bytes)).read().toOption.get
  private def vector(name: String): Array[Byte] =
    val path = Iterator
      .iterate(Path.of("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("protocol/desk-v1-vectors.tsv"))
      .find(Files.isRegularFile(_))
      .get
    hex.parseHex(Files.readAllLines(path).asScala.find(_.startsWith(name + "\t")).get.split("\t")(3))
  private def changed(name: String, offset: Int, value: Long, width: Int = 1): Frame =
    val original = frame(vector(name))
    val payload = original.payload.clone()
    (0 until width).foreach(index => payload(offset + index) = (value >>> (8 * index)).toByte)
    original.copy(payload = payload)

  private val statsValue = fromStats(
    RelayMessage.Stats(
      StreamStats(
        StreamState.Live,
        Count.clamp(305419896),
        Count.clamp(12400),
        Count.clamp(318),
        3660.seconds,
        MessagesPerMinute.clamp(35),
        Count.clamp(2135)
      ),
      None
    ),
    identity,
    3,
    7,
    Availability.Observed
  )
  private def card(seq: Long, kind: NotificationKind, hold: Int, title: String, body: String): EventRecord =
    EventRecord(
      SeqNo.fromWire(seq),
      None,
      EventValue.Zero,
      SubMonths.fromWire(0),
      DisplayTtl.fromDuration(hold.millis),
      kind,
      SubTier.fromWire(0),
      EventFlags.Empty,
      title,
      body
    )
  private val eventCases = List(
    "external_twitch_kind" -> fromEvent(
      card(121, NotificationKind.Raid, 200, "Manual raid", ""),
      identity.relayEpoch,
      0,
      0,
      65000,
      Origin.External
    ),
    "twitch_event" -> fromEvent(card(122, NotificationKind.Follow, 6000, "viewer", ""), identity.relayEpoch, 3, 5, 65000, Origin.Twitch),
    "relay_replay" -> fromEvent(
      card(123, NotificationKind.Warning, 8000, "Relay warning", "Independent link health"),
      identity.relayEpoch,
      0,
      0,
      65000,
      Origin.Relay
    )
  )
  private val pending = (101L to 108L).map(seq => Entry(identity.relayEpoch, seq, 0, Origin.External)).toVector
  private val full = Report(
    identity,
    0x8877665544332211L,
    1,
    1200,
    5100,
    0,
    120,
    117,
    9,
    3,
    5,
    4,
    6,
    Base.Cpu,
    Reason.Selected,
    Phase.None,
    0,
    0,
    0,
    0,
    None,
    pending
  )
  private val reportCases = List(
    "report_full_zero_credit" -> full,
    "report_retained_active" -> full.copy(
      revision = 2,
      grantTotal = 1,
      phase = Phase.Entrance,
      activeRemainingMs = 30630,
      expiredTotal = 1,
      suppressedTotal = 2,
      active = Some(Entry(0x1122334455667788L, 999, 18, Origin.Twitch)),
      pending = pending.tail
    ),
    "report_legacy_active" -> full.copy(
      grantTotal = 8,
      phase = Phase.Hold,
      activeRemainingMs = 8000,
      active = Some(Entry(0, 0xffffffffL, 0, Origin.Unknown)),
      pending = Vector.empty
    ),
    "report_idle" -> full.copy(
      revision = 3,
      grantTotal = 16,
      processedSeq = 127,
      enqueuedSeq = 123,
      expiredTotal = 0xffffffffL,
      pending = Vector.empty
    )
  )

  test("event publication permits receiver-default hold only for native Twitch chat and retains all explicit hold bounds"):
    for
      kind <- NotificationKind.values
      origin <- Origin.values
      hold <- List(0, 100, 30000, 30100)
    do
      def candidate: DeskMessageCodec.Event = fromEvent(
        card(1, kind, hold, "synthetic", "hold boundary"),
        identity.relayEpoch,
        if origin == Origin.Twitch then 3L else 0L,
        if origin == Origin.Twitch then 5L else 0L,
        65000,
        origin
      )
      val allowed = origin != Origin.Unknown &&
        (hold == 100 || hold == 30000 || (hold == 0 && kind == NotificationKind.Chat && origin == Origin.Twitch))
      val clue = s"kind=$kind origin=$origin holdMs=$hold"
      if allowed then
        val envelope = candidate
        val encoded = frame(event(envelope))
        assertEquals(decodeEvent(encoded), Right(envelope), clue)
        assertEquals(encoded.payload(18) & 0xff | ((encoded.payload(19) & 0xff) << 8), hold / 100, clue)
        assertEquals(encoded.payload(20) & 0xff, kind.code, clue)
        assertEquals(envelope.origin, origin, clue)
      else
        val error = intercept[IllegalArgumentException](candidate)
        assert(error.getMessage.startsWith("requirement failed"), clue)

  test("all eight remaining canonical vectors match independent expected typed fields and exact bytes"):
    assertEquals(hex.formatHex(stats(statsValue)), hex.formatHex(vector("stats_observed")))
    assertEquals(decodeStats(frame(vector("stats_observed"))), Right(statsValue))
    eventCases.foreach: (name, value) =>
      val flags = if name == "relay_replay" then FrameFlags.Replay else FrameFlags.Empty
      assertEquals(hex.formatHex(event(value, flags)), hex.formatHex(vector(name)))
      assertEquals(decodeEvent(frame(vector(name))), Right(value))
    reportCases.foreach: (name, value) =>
      assertEquals(hex.formatHex(encode(value)), hex.formatHex(vector(name)))
      assertEquals(decode(frame(vector(name))), Right(value))
      assertEquals(encode(value).length, 252)

  test("every bounded unknown tail survives while all short lengths mismatch oversize version and direction fail"):
    val readers: List[(String, Int, Frame => Either[String, Any])] =
      List(("stats_observed", 60, decodeStats), ("external_twitch_kind", 196, decodeEvent), ("report_idle", 244, decode))
    readers.foreach: (name, known, read) =>
      val original = frame(vector(name))
      (known to 248).foreach: length =>
        val payload = java.util.Arrays.copyOf(original.payload, length)
        java.util.Arrays.fill(payload, known, length, 0xa5.toByte)
        assertEquals(read(original.copy(header = original.header.copy(length = length), payload = payload)), read(original))
      (0 until known).foreach: length =>
        assert(read(original.copy(header = original.header.copy(length = length), payload = original.payload.take(length))).isLeft)
      assert(read(original.copy(header = original.header.copy(length = known + 1))).isLeft)
      assert(read(original.copy(header = original.header.copy(length = 249), payload = new Array[Byte](249))).isLeft)
      assert(read(original.copy(header = original.header.copy(version = ProtocolVersion.fromWire(2)))).isLeft)
      assert(read(original.copy(header = original.header.copy(typeCode = TypeCode.fromWire(0x04.toByte)))).isLeft)
      assertEquals(read(original.copy(header = original.header.copy(flags = FrameFlags.fromWire(0x80.toByte)))), read(original))

  test("STATS and event envelopes reject invalid identity provenance fences and reserved fields"):
    List((32, 8), (40, 8), (48, 4), (52, 4)).foreach: (offset, width) =>
      assert(decodeStats(changed("stats_observed", offset, 0, width)).isLeft)
    assert(decodeStats(changed("stats_observed", 56, 3)).isLeft)
    (57 to 59).foreach(offset => assert(decodeStats(changed("stats_observed", offset, 1)).isLeft))
    List((0, 4), (168, 8), (184, 8)).foreach: (offset, width) =>
      assert(decodeEvent(changed("external_twitch_kind", offset, 0, width)).isLeft)
    List(3, 255).foreach(code => assert(decodeEvent(changed("external_twitch_kind", 192, code)).isLeft))
    List(176, 180).foreach: offset =>
      assert(decodeEvent(changed("external_twitch_kind", offset, 1, 4)).isLeft)
      assert(decodeEvent(changed("twitch_event", offset, 0, 4)).isLeft)
    (193 to 195).foreach(offset => assert(decodeEvent(changed("twitch_event", offset, 1)).isLeft))

  test("report rejects malformed lifecycle capacity enums reserved identities and unused entries"):
    List((0, 8), (8, 8), (16, 8), (24, 4), (60, 4), (64, 4), (68, 4), (72, 4), (76, 4)).foreach: (offset, width) =>
      assert(decode(changed("report_idle", offset, 0, width)).isLeft)
    List((80, 5), (81, 9), (82, 4), (83, 9), (84, 1), (100, 1)).foreach: (offset, value) =>
      assert(decode(changed("report_idle", offset, value)).isLeft)
    assert(decode(changed("report_idle", 56, 128, 4)).isLeft)
    assert(decode(changed("report_full_zero_credit", 124, 0, 4)).isLeft)
    assert(decode(changed("report_full_zero_credit", 129, 255)).isLeft)
    assert(decode(changed("report_legacy_active", 100, 1)).isLeft)
    assert(decode(changed("report_legacy_active", 113, 0)).isLeft)
    (0 until 9).foreach: index =>
      assert(decode(changed("report_full_zero_credit", 114 + index * 16, 1)).isLeft)
    assert(decode(changed("report_retained_active", 82, 0)).isLeft)
    assert(decode(changed("report_full_zero_credit", 82, 1)).isLeft)

  test("unsigned fields raw base bytes and old retained identities remain lossless without renewed age"):
    val report = full.copy(
      identity = DeskControlCodec.Identity(Long.MinValue, -1L),
      deviceBootId = -1L,
      observedDeviceMs = -1L,
      observedRelayLowerMs = Long.MinValue,
      grantTotal = -1L,
      revision = 0xffffffffL,
      pending = Vector(Entry(-1L, 0xffffffffL, 255, Origin.Relay))
    )
    assertEquals(decode(frame(encode(report))), Right(report))
    val original = frame(vector("external_twitch_kind"))
    val raw = original.payload.clone()
    raw(12) = 17.toByte // Classic reserved bytes remain ignored by classic receivers, but raw equality stays exact.
    raw(167) = 0x7f.toByte
    val decoded = decodeEvent(original.copy(payload = raw)).toOption.get
    assertEquals(decoded.base, raw.take(168).toVector)
    assertEquals(frame(event(decoded)).payload.toVector, raw.toVector)
    val _ = intercept[IllegalArgumentException](fromEvent(card(1, NotificationKind.Info, 0, "x", ""), 1, 0, 0, 10, Origin.External))
    val _ = intercept[IllegalArgumentException](fromEvent(card(1, NotificationKind.Info, 30100, "x", ""), 1, 0, 0, 10, Origin.External))
