package twitchscreen.relay.device

import twitchscreen.relay.collection.appendBounded
import twitchscreen.relay.protocol.*

/** Provenance is retained with the record, never reconstructed from its kind or text. */
private[device] final case class RetainedEvent(
    record: EventRecord,
    origin: DeskMessageCodec.Origin,
    relayEpoch: Long = 0L,
    receivedRelayMs: Long = 0L,
    deadlineRelayMs: Long = 0L,
    sourceGeneration: Long = 0L,
    twitchEpisode: Long = 0L,
    expiresInMs: Long = 60000L,
    capableEligible: Boolean = true
)

/** Actor-confined replay storage; chat cannot evict durable notifications. */
private[device] final class ReplayBuffers(durableCapacity: Int, chatCapacity: Int):
  private val durable = ReplayRing(durableCapacity)
  private val chat = ReplayRing(chatCapacity)

  def remember(record: EventRecord, origin: DeskMessageCodec.Origin = DeskMessageCodec.Origin.Unknown): Unit =
    remember(RetainedEvent(record, origin))

  def remember(publication: RetainedEvent): Unit =
    val ring = if publication.record.kind.isDurable then durable else chat
    ring.append(publication)

  def size: Int = durable.records.size + chat.records.size
  def publications: Vector[RetainedEvent] = (durable.records ++ chat.records).sortBy(_.record.seq.value)
  def ordered: Vector[EventRecord] = publications.map(_.record)
  def after(sequence: SeqNo): Vector[EventRecord] = ordered.filter(_.seq.isAfter(sequence))

private final class ReplayRing(capacity: Int):
  private var retained = Vector.empty[RetainedEvent]
  def records: Vector[RetainedEvent] = retained
  def append(record: RetainedEvent): Unit = retained = retained.appendBounded(record, capacity)
