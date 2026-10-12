package twitchscreen.relay.http

import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

private[relay] object ApiTime:
  private val milliseconds = DateTimeFormatterBuilder().appendInstant(3).toFormatter
  def format(value: Instant): String = milliseconds.format(value)
  def valid(value: String): Boolean =
    value != null && value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z") &&
      scala.util.Try(Instant.parse(value)).isSuccess
