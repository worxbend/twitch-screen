package twitchscreen.relay.protocol

import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.concurrent.duration.DurationInt

/** §9: strings on the wire — the fixed-width NUL-padded field, the truncation algorithm, and the ASCII fold.
  *
  * The truncation algorithm is pinned here rather than paraphrased because §9.2 states it as four numbered steps and because getting it
  * wrong is invisible until a Polish chat line arrives: a naive `take(cap - 3)` splits a multi-byte sequence and the device renders a
  * replacement glyph at the end of every long message.
  */
class WireStringsSuite extends munit.FunSuite:
  private def utf8(value: String): Array[Byte] = value.getBytes(StandardCharsets.UTF_8)
  private def truncate(value: String, width: Int): WireField = WireStrings.truncate(utf8(value), width)
  private def text(field: WireField): String = String(field.bytes, StandardCharsets.UTF_8)

  // ── §9.2 truncation ──────────────────────────────────────────────────────────────────────────────────────────────

  test("§9.2 step 1: a value that fits is emitted unchanged and is not marked truncated"):
    val fits = truncate("w0rxbend", Tsb3.ActorWidth)
    assertEquals(text(fits), "w0rxbend")
    assertEquals(fits.truncated, false)

  test("§9.2: a value of exactly cap bytes fits — the cap is N − 1, not N"):
    val exact = truncate("a" * 47, Tsb3.ActorWidth)
    assertEquals(exact.bytes.length, 47)
    assertEquals(exact.truncated, false)
    assertEquals(truncate("a" * 48, Tsb3.ActorWidth).truncated, true)

  test("§9.2 steps 2 and 3: an ASCII value is cut at cap − 3 and gains three full stops"):
    val cut = truncate("a" * 100, Tsb3.ActorWidth)
    assertEquals(text(cut), "a" * 44 + "...")
    assertEquals(cut.bytes.length, 47)
    assert(cut.truncated)

  test("§9.2 step 2: the walk-back never splits a multi-byte sequence, so the result may be shorter than the cap"):
    // 'ą' is two bytes. Placing one across the cut forces the walk-back, and the field comes out one byte short of the cap.
    val value = "a" * 44 + "ą" + "b" * 20
    val cut = truncate(value, Tsb3.ActorWidth)
    assertEquals(text(cut), "a" * 44 + "...")
    assertEquals(cut.bytes.length, 47)

    val earlier = "a" * 43 + "ą" + "b" * 20
    val cutEarlier = truncate(earlier, Tsb3.ActorWidth)
    assertEquals(cutEarlier.bytes.length, 46, "the walk-back dropped a two-byte character rather than half of one")
    assertEquals(text(cutEarlier), "a" * 43 + "...")

  test("§9.2: a four-byte sequence across the cut is dropped whole"):
    val value = "b" * 92 + "🎉" + "tail"
    val cut = truncate(value, Tsb3.TextWidth)
    assertEquals(cut.bytes.length, 95)
    assertEquals(text(cut), "b" * 92 + "...")

    val across = "b" * 91 + "🎉" + "tail"
    assertEquals(truncate(across, Tsb3.TextWidth).bytes.length, 94)
    assertEquals(text(truncate(across, Tsb3.TextWidth)), "b" * 91 + "...")

  test("§9.2: every truncated field decodes as valid UTF-8 — no half characters ever reach the wire"):
    val awkward = "źdźbło ".repeat(40)
    List(Tsb3.ActorWidth, Tsb3.TextWidth, Tsb3.ReasonWidth).foreach: width =>
      (0 until 200).foreach: length =>
        val field = truncate(awkward.take(length), width)
        assert(field.bytes.length <= width - 1, s"width $width, length $length")
        assertEquals(utf8(text(field)).toSeq, field.bytes.toSeq, s"width $width, length $length is not valid UTF-8")

  // ── §9: the field itself ─────────────────────────────────────────────────────────────────────────────────────────

  test("§9: the final byte of a string field is always the NUL, however long the value was"):
    val target = Array.fill(Tsb3.ActorWidth)(0x41.toByte)
    WireStrings.write(target, 0, Tsb3.ActorWidth, truncate("a" * 500, Tsb3.ActorWidth))
    assertEquals(target(Tsb3.ActorWidth - 1), 0.toByte)
    assertEquals(WireStrings.read(target, 0, Tsb3.ActorWidth).length, 47)

  test("§9: a short value zeroes every remaining byte of its field"):
    val target = Array.fill(Tsb3.ActorWidth)(0x41.toByte)
    WireStrings.write(target, 0, Tsb3.ActorWidth, truncate("hi", Tsb3.ActorWidth))
    assertEquals(target.drop(2).count(_ == 0.toByte), Tsb3.ActorWidth - 2)

  test("§9: a sender never emits a byte below 0x20 or the 0x7f delete"):
    val nasty = "line\u0001one\ttwo\u007fthree\nfour"
    val field = WireStrings.field(nasty, Tsb3.TextWidth, TextPolicy.Verbatim)
    assert(!field.bytes.exists(byte => (byte & 0xff) < 0x20 || (byte & 0xff) == 0x7f), text(field))
    assertEquals(text(field), "line one two three four")

  // ── §9.3 ASCII folding ───────────────────────────────────────────────────────────────────────────────────────────

  test("§9.3: without CAP_UTF8_TEXT the relay folds to printable US-ASCII — V12's strings as the spec spells them out"):
    assertEquals(folded("Paweł"), "Pawel")
    assertEquals(folded("świetny stream! 🎉"), "swietny stream!")

  test("§9.3: the mappings the specification names by hand"):
    assertEquals(folded("ł"), "l")
    assertEquals(folded("ä"), "a")
    assertEquals(folded("Ж"), "Zh")
    assertEquals(folded("Żółć"), "Zolc")

  test("§9.3: folding happens before truncation, so the cap is a true byte budget in both modes"):
    val name = "ąćęłńóśźż".repeat(10)
    val foldedField = WireStrings.field(name, Tsb3.ActorWidth, TextPolicy.AsciiFolded)
    assertEquals(text(foldedField), "acelnoszz".repeat(4) + "acelnosz" + "...")
    assertEquals(foldedField.bytes.length, 47)

  test("§9.3: a display name that folds away to nothing becomes the fixed placeholder, never an empty actor"):
    val placeholder = WireStrings.field("🎉🎉🎉", Tsb3.ActorWidth, TextPolicy.AsciiFolded, Some(WireStrings.FoldedPlaceholder))
    assertEquals(text(placeholder), "viewer")

  test("§9.3: a text field that folds away to nothing stays empty — only a display name gets the placeholder"):
    assertEquals(text(WireStrings.field("🎉🎉🎉", Tsb3.TextWidth, TextPolicy.AsciiFolded)), "")

  test("§9.3: an actor that was empty to begin with stays empty, because an anonymous gifter may have none"):
    val empty = WireStrings.field("", Tsb3.ActorWidth, TextPolicy.AsciiFolded, Some(WireStrings.FoldedPlaceholder))
    assertEquals(text(empty), "")

  test("§9.3: with CAP_UTF8_TEXT the string travels verbatim, byte for byte"):
    assertEquals(TextPolicy.forCapabilities(Capabilities.RelaySupported), TextPolicy.Verbatim)
    assertEquals(TextPolicy.forCapabilities(Capabilities.Ack | Capabilities.Chat), TextPolicy.AsciiFolded)
    val verbatim = WireStrings.field("świetny stream! 🎉", Tsb3.TextWidth, TextPolicy.Verbatim)
    assertEquals(text(verbatim), "świetny stream! 🎉")
    assertEquals(verbatim.bytes.length, 21)

  test("§9.3: verbatim means verbatim — runs of spaces and edge spaces survive; only the folded path collapses them"):
    assertEquals(text(WireStrings.field(" a  b ", Tsb3.TextWidth, TextPolicy.Verbatim)), " a  b ")
    assertEquals(text(WireStrings.field(" a  b ", Tsb3.TextWidth, TextPolicy.AsciiFolded)), "a b")

  test("§9.3: the encoder honours the policy, so a device without a UTF-8 font never sees UTF-8"):
    val record = EventRecord.chat(
      seq = SeqNo.fromWire(1),
      chatter = "Paweł",
      message = "świetny stream! 🎉",
      colour = None,
      at = Instant.EPOCH,
      ttl = 6.seconds
    )
    val encoded = Tsb3Encoder.toDevice(RelayMessage.Event(record), text = TextPolicy.AsciiFolded)
    assert(encoded.drop(Tsb3.HeaderSize).forall(byte => (byte & 0xff) <= 0x7e), "a folded frame carries no byte above 0x7e")
    Tsb3Decoder.fromRelay(WireBytes.frameOf(encoded).toOption.get) match
      case Right(RelayMessage.Event(decoded)) =>
        assertEquals(decoded.actor, "Pawel")
        assertEquals(decoded.text, "swietny stream!")
      case other => fail(s"expected an EVENT, got $other")

  private def folded(value: String): String = WireStrings.fold(WireStrings.sanitise(value))
