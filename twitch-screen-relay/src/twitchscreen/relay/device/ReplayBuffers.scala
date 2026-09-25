package twitchscreen.relay.device

import twitchscreen.relay.collection.appendBounded
import twitchscreen.relay.protocol.*

/** Actor-confined replay storage; chat cannot evict durable notifications. */
private[device] final class ReplayBuffers(durableCapacity: Int, chatCapacity: Int):
  private val durable = ReplayRing(durableCapacity)
  private val chat = ReplayRing(chatCapacity)

  def remember(record: EventRecord): Unit =
    val ring = if record.kind.isDurable then durable else chat
    ring.append(record)

  def size: Int = durable.records.size + chat.records.size
  def ordered: Vector[EventRecord] = (durable.records ++ chat.records).sortBy(_.seq.value)
  def after(sequence: SeqNo): Vector[EventRecord] = ordered.filter(_.seq.isAfter(sequence))

private final class ReplayRing(capacity: Int):
  private var retained = Vector.empty[EventRecord]
  def records: Vector[EventRecord] = retained
  def append(record: EventRecord): Unit = retained = retained.appendBounded(record, capacity)
