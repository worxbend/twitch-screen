package twitchscreen.relay.observability

import java.time.Instant
import sttp.tapir.{Codec, CodecFormat, Schema}

/** Severity, as both Logback and the `GET /api/v1/logs` filter understand it. */
enum LogLevel:
  case Trace, Debug, Info, Warn, Error

object LogLevel:
  def fromLogback(name: String): LogLevel =
    values.find(_.toString.equalsIgnoreCase(name)).getOrElse(Info)

  extension (level: LogLevel) def atLeast(minimum: LogLevel): Boolean = level.ordinal >= minimum.ordinal

  given Schema[LogLevel] = Schema.derivedEnumeration[LogLevel].defaultStringBased
  given Codec[String, LogLevel, CodecFormat.TextPlain] = Codec.derivedEnumeration[String, LogLevel].defaultStringBased

/** One captured log line. */
final case class LogRecord(at: Instant, level: LogLevel, logger: String, thread: String, message: String) derives Schema
