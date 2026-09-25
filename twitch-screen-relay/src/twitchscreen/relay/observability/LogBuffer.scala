package twitchscreen.relay.observability

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.UnsynchronizedAppenderBase
import java.time.Instant
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import ox.discard
import twitchscreen.relay.collection.appendBounded

/** The last N log lines, kept in memory so `GET /api/v1/logs` can answer "what is it doing right now?" over SSH-less links — the relay's
  * usual home is a headless Raspberry Pi.
  *
  * This is the one piece of global state in the relay, and it is global because Logback constructs its appenders from `logback.xml` and
  * hands them no context. [[LogBuffer.resize]] is called once at startup to apply the configured capacity; until then the buffer holds a
  * conservative default so that startup logging is not lost.
  */
private[relay] object LogBuffer:
  private val DefaultCapacity = 200

  private val capacity = AtomicInteger(DefaultCapacity)
  private val records = AtomicReference(Vector.empty[LogRecord])

  def resize(newCapacity: Int): Unit =
    capacity.set(newCapacity)
    records.updateAndGet(_.takeRight(newCapacity)).discard

  def record(entry: LogRecord): Unit =
    val bounded = entry.copy(
      message = DiagnosticText(entry.message, 4096),
      logger = DiagnosticText(entry.logger, 256),
      thread = DiagnosticText(entry.thread, 128),
      cause = entry.cause.map(DiagnosticText(_, 1024))
    )
    records.updateAndGet(_.appendBounded(bounded, capacity.get())).discard

  /** Most recent first. */
  def recent(limit: Int, minimumLevel: LogLevel): List[LogRecord] =
    records.get().reverseIterator.filter(_.level.atLeast(minimumLevel)).take(limit).toList

  def size: Int = records.get().size

/** Wired up in `resources/logback.xml`; see [[LogBuffer]] for why it reaches a global. */
final class InMemoryLogAppender extends UnsynchronizedAppenderBase[ILoggingEvent]:
  override def append(event: ILoggingEvent): Unit =
    LogBuffer.record(
      LogRecord(
        at = Instant.ofEpochMilli(event.getTimeStamp),
        level = LogLevel.fromLogback(event.getLevel.toString),
        logger = event.getLoggerName,
        thread = event.getThreadName,
        message = event.getFormattedMessage,
        // Exception messages often contain remote URLs or credentials. Keep type and call site, never raw cause text.
        cause = Option(event.getThrowableProxy).map: cause =>
          val frames = Option(cause.getStackTraceElementProxyArray).toList.flatMap(_.take(4)).map(_.toString)
          (cause.getClassName :: frames).mkString(" | ")
      )
    )
