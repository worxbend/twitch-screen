package twitchscreen.relay.observability

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.UnsynchronizedAppenderBase
import java.time.Instant
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import ox.discard

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

  def record(entry: LogRecord): Unit = records.updateAndGet(current => (current :+ entry).takeRight(capacity.get())).discard

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
        message = event.getFormattedMessage
      )
    )
