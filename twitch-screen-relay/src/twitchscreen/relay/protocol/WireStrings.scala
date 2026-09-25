package twitchscreen.relay.protocol

import java.nio.charset.StandardCharsets
import java.text.Normalizer

/** How the relay prepares an outbound string for the wire (§9.3).
  *
  * The device's fonts cover `0x20`–`0x7e` plus a degree sign and a bullet; anything else renders as a missing glyph. A device that
  * advertises `CAP_UTF8_TEXT` has a font beyond US-ASCII and is sent the string verbatim, and one that does not is sent a transliteration.
  * The wire is UTF-8 in both cases, so regenerating a font later is a firmware change alone with no protocol bump.
  */
private[relay] enum TextPolicy:
  case Verbatim, AsciiFolded

private[relay] object TextPolicy:
  /** Derived from the effective capability intersection of the connection, never from what the device asked for on its own. */
  def forCapabilities(caps: Capabilities): TextPolicy =
    if caps.contains(Capabilities.Utf8Text) then Verbatim else AsciiFolded

/** The bytes of one fixed-width string field, and whether the sender had to shorten the value to make them fit.
  *
  * The flag travels with the bytes because §6.4's `eflags` must agree with what was actually written: a record whose text was cut but whose
  * `TEXT_TRUNCATED` bit is clear tells the device it is looking at a complete message when it is not.
  */
private[relay] final case class WireField(bytes: Array[Byte], truncated: Boolean)

/** §9. Strings on this wire are fixed-width, NUL-padded UTF-8 byte arrays with no length prefix, so no length field can ever point past a
  * buffer. A `char[N]` carries at most `N − 1` content bytes and its final byte is always `0x00`.
  */
private[relay] object WireStrings:
  /** §9.2 step 3. Three ASCII full stops, never the single-character ellipsis, which is three bytes in UTF-8 and unrenderable folded. */
  private val Ellipsis: Array[Byte] = Array(0x2e.toByte, 0x2e.toByte, 0x2e.toByte)

  /** §9.3. The relay has no login and no user id to fall back on — it carries the display name and nothing else — so a name that folds away
    * to nothing becomes this fixed placeholder rather than an empty `actor`.
    */
  val FoldedPlaceholder: String = "viewer"

  /** Prepares one value for a `char[width]` field: sanitise, then fold if the device has no UTF-8 font, then truncate (§9.3: folding
    * happens before truncation, so the cap is a true byte budget in both modes).
    *
    * `placeholder` is supplied only for a display name, which §9.3 forbids leaving empty after a fold; `text` fields and an anonymous
    * gifter's `actor` are legitimately empty and pass `None`.
    */
  def field(value: String, width: Int, policy: TextPolicy, placeholder: Option[String] = None): WireField =
    val clean = sanitise(value)
    val prepared = policy match
      case TextPolicy.Verbatim => clean
      case TextPolicy.AsciiFolded =>
        val folded = fold(clean)
        if folded.isEmpty && !clean.isBlank then placeholder.getOrElse(folded) else folded
    truncate(prepared.getBytes(StandardCharsets.UTF_8), width)

  /** §9.2, exactly as the specification pins it: cut at `cap − 3`, walk back while the byte at the cut is a UTF-8 continuation byte, append
    * `"..."`. A multi-byte sequence is never split, so the result may be shorter than the cap when the walk-back dropped a whole character
    * — which is what vector V13 fixes at 46 and 94 bytes against caps of 47 and 95.
    */
  def truncate(bytes: Array[Byte], width: Int): WireField =
    val cap = width - 1
    if bytes.length <= cap then WireField(bytes, truncated = false)
    else
      var cut = cap - 3
      while cut > 0 && (bytes(cut) & 0xc0) == 0x80 do cut -= 1
      WireField(bytes.take(cut) ++ Ellipsis, truncated = true)

  /** §9: a sender MUST NOT emit a byte below `0x20` other than the padding NULs, and MUST NOT emit `0x7f`. Control characters become spaces
    * rather than vanishing, so a tab between two words does not join them. Nothing is collapsed or trimmed here: §9.3 sends a CAP_UTF8_TEXT
    * string verbatim, and only the folded path ([[fold]]) collapses runs of spaces.
    */
  def sanitise(value: String): String = value.map(ch => if isControl(ch) then ' ' else ch)

  /** §9.3. Transliterate where there is a mapping, decompose and strip the accent where there is not, drop what neither reaches, then
    * collapse the runs of spaces that dropping leaves behind.
    */
  def fold(value: String): String = collapse(value.flatMap(foldChar))

  /** Writes `field` into a `char[width]` slot and zeroes every remaining byte, so the field's last byte is always the NUL. */
  def write(target: Array[Byte], offset: Int, width: Int, field: WireField): Unit =
    val length = math.min(field.bytes.length, width - 1)
    System.arraycopy(field.bytes, 0, target, offset, length)
    java.util.Arrays.fill(target, offset + length, offset + width, 0.toByte)

  /** §9.1: a receiver never trusts the sender for termination. Reading stops at the first NUL or at `width − 1` bytes, whichever comes
    * first, which is that rule expressed as a bound rather than as a store. Invalid UTF-8 is replaced, never rejected: malformed text is a
    * cosmetic problem, never a link problem.
    */
  def read(source: Array[Byte], offset: Int, width: Int): String =
    val limit = offset + width - 1
    var end = offset
    while end < limit && source(end) != 0 do end += 1
    String(source, offset, end - offset, StandardCharsets.UTF_8)

  private def isControl(ch: Char): Boolean = ch < 0x20 || ch == 0x7f || (ch >= 0x80 && ch <= 0x9f)

  private def collapse(value: String): String = value.replaceAll(" +", " ").trim

  private def foldChar(ch: Char): String =
    if ch >= 0x20 && ch <= 0x7e then ch.toString
    else Transliterations.getOrElse(ch, decompose(ch))

  /** NFD splits a precomposed letter into its base and its combining marks, so dropping everything outside printable ASCII leaves the base:
    * `ä` → `a`, `ś` → `s`, `ę` → `e`. A character with no decomposition and no mapping — an emoji, a CJK ideograph, half a surrogate pair —
    * leaves nothing, which is the specified behaviour.
    */
  private def decompose(ch: Char): String =
    Normalizer.normalize(ch.toString, Normalizer.Form.NFD).filter(c => c >= 0x20 && c <= 0x7e)

  /** Latin letters NFD cannot reach, the punctuation a chat client substitutes, and the Cyrillic table §9.3 names with `Ж` → `Zh`. */
  private val Transliterations: Map[Char, String] =
    val latin = Map(
      'ł' -> "l",
      'đ' -> "d",
      'ø' -> "o",
      'ß' -> "ss",
      'æ' -> "ae",
      'œ' -> "oe",
      'þ' -> "th",
      'ð' -> "d",
      'ı' -> "i",
      'ħ' -> "h",
      'ŋ' -> "ng",
      'ŧ' -> "t"
    )
    // Written as code points: a literal no-break space or en dash in source is indistinguishable from the ASCII character it replaces.
    val punctuation = Map(
      0x00a0 -> " ", // no-break space
      0x00ab -> "\"", // left guillemet
      0x00b0 -> " deg", // degree sign
      0x00bb -> "\"", // right guillemet
      0x2013 -> "-", // en dash
      0x2014 -> "-", // em dash
      0x2018 -> "'", // left single quote
      0x2019 -> "'", // right single quote
      0x201a -> ",", // single low quote
      0x201c -> "\"", // left double quote
      0x201d -> "\"", // right double quote
      0x2022 -> "*", // bullet
      0x2026 -> "..." // horizontal ellipsis
    ).map((codePoint, replacement) => codePoint.toChar -> replacement)
    val cyrillic = Map(
      'а' -> "a",
      'б' -> "b",
      'в' -> "v",
      'г' -> "g",
      'ґ' -> "g",
      'д' -> "d",
      'е' -> "e",
      'ё' -> "e",
      'є' -> "ye",
      'ж' -> "zh",
      'з' -> "z",
      'и' -> "i",
      'і' -> "i",
      'ї' -> "yi",
      'й' -> "y",
      'к' -> "k",
      'л' -> "l",
      'м' -> "m",
      'н' -> "n",
      'о' -> "o",
      'п' -> "p",
      'р' -> "r",
      'с' -> "s",
      'т' -> "t",
      'у' -> "u",
      'ф' -> "f",
      'х' -> "kh",
      'ц' -> "ts",
      'ч' -> "ch",
      'ш' -> "sh",
      'щ' -> "shch",
      'ъ' -> "",
      'ы' -> "y",
      'ь' -> "",
      'э' -> "e",
      'ю' -> "yu",
      'я' -> "ya"
    )
    val lower = latin ++ punctuation ++ cyrillic
    lower ++ lower.collect {
      case (ch, replacement) if ch.toUpper != ch && replacement.nonEmpty =>
        ch.toUpper -> (replacement.head.toUpper.toString + replacement.tail)
    }
