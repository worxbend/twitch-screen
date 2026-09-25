package twitchscreen.relay.activity

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import org.slf4j.LoggerFactory
import ox.{Ox, discard}
import twitchscreen.relay.bus.{BusEvent, EventBus, EventCategory, RelayEvent}
import twitchscreen.relay.config.ActivityConfig

/** A bounded in-memory history of everything that reached the bus, so an operator can answer "what just happened?" without a log
  * aggregator. Deliberately not durable: it is a diagnostic aid on a Raspberry Pi, not an audit trail.
  */
private[relay] final class ActivityLog(capacity: Int):
  private val lastId = AtomicLong(0)
  private val entries = AtomicReference(Vector.empty[ActivityEntry])

  def record(message: BusEvent): Unit =
    val entry = ActivityEntry(lastId.incrementAndGet(), message.at, message.event.category, message.event.summary)
    entries.updateAndGet(current => (current :+ entry).takeRight(capacity)).discard

  /** Most recent first. */
  def recent(limit: Int, category: Option[EventCategory]): List[ActivityEntry] =
    entries
      .get()
      .reverseIterator
      .filter(entry => category.forall(_ == entry.category))
      .take(limit)
      .toList

  def size: Int = entries.get().size

private[relay] object ActivityLog:
  private val logger = LoggerFactory.getLogger(getClass)

  /** Starts recording. The single consuming fork is what makes ids and positions agree: `record` is only ever called from one thread, while
    * readers see a consistent snapshot through the atomic reference.
    */
  def start(config: ActivityConfig, bus: EventBus)(using Ox): ActivityLog =
    val log = ActivityLog(config.bufferSize)
    logger.info(s"Recording the last ${config.bufferSize} relay events")
    bus.consume("activity")(log.record)
    log
