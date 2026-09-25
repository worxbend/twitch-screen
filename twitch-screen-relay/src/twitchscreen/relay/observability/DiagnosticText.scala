package twitchscreen.relay.observability

import java.nio.charset.StandardCharsets.UTF_8
import java.util.regex.Pattern
import twitchscreen.relay.protocol.WireStrings

/** Text leaving a diagnostic boundary must not inject controls or reveal credential headers. */
private[relay] object DiagnosticText:
  private val credentials = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[^\\s,;]+")

  def apply(value: String, limit: Int = 4096): String =
    val redacted = credentials.matcher(Option(value).getOrElse("")).replaceAll("$1 [redacted]")
    String(WireStrings.truncate(WireStrings.sanitise(redacted).getBytes(UTF_8), limit + 1).bytes, UTF_8)
