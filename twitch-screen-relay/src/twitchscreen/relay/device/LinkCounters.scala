package twitchscreen.relay.device

import java.time.{Clock, Instant}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import ox.discard
import sttp.tapir.Schema
import twitchscreen.relay.protocol.{FrameCounters, ProtocolError}

/** Throughput of one device link, reported by `GET /api/v1/devices`.
  *
  * §14: `bytesSent` and `bytesReceived` are true byte counts — `8 + length` per frame. v2 counted UTF-16 characters plus one, which was
  * wrong on two axes at once. The frame counters that follow are §4.3's: a well-framed frame this relay skipped rather than understood is
  * how a version skew or an encoder bug is diagnosed in the field, and they cost nothing.
  */
final case class LinkTraffic(
    framesSent: Long,
    framesDropped: Long,
    framesReceived: Long,
    /** Total of the §4.3 skips; the five counters below break it down per condition. */
    framesSkipped: Long,
    framesUnknownType: Long,
    framesWrongDirection: Long,
    framesShortPayload: Long,
    framesInvalidField: Long,
    framesOversizeSkipped: Long,
    /** §4.4/§14: candidate header windows the reader shifted past while looking for a frame boundary. */
    resyncEvents: Long,
    bytesSent: Long,
    bytesReceived: Long,
    /** Highest seq the device reported having enqueued for display (§6.6). Informational: delivery is never conditional on it, and the
      * relay never blocks on it or withholds events because of it. 0 until the device sends its first `ACK`.
      */
    ackedSeq: Long,
    lastSeenAt: Instant
) derives Schema

/** Per-connection counters, updated by whichever thread does the work: the writer fork counts what it put on the wire, the reader fork what
  * it took off, and [[DeviceHub]] what it had to drop because this device was too slow. They are atomics rather than hub state precisely so
  * that per-frame bookkeeping never reaches the hub's single thread.
  */
private[device] final class LinkCounters(clock: Clock) extends FrameCounters:
  private val sentFrames = AtomicLong(0)
  private val droppedFrames = AtomicLong(0)
  private val receivedFrames = AtomicLong(0)
  private val skippedFrames = AtomicLong(0)
  private val unknownType = AtomicLong(0)
  private val wrongDirection = AtomicLong(0)
  private val shortPayload = AtomicLong(0)
  private val invalidField = AtomicLong(0)
  private val oversizeSkipped = AtomicLong(0)
  private val resyncs = AtomicLong(0)
  private val sentBytes = AtomicLong(0)
  private val receivedBytes = AtomicLong(0)
  private val acknowledged = AtomicLong(0)
  private val lastSeen = AtomicReference(clock.instant())

  def recordSent(bytes: Int): Unit =
    sentFrames.incrementAndGet().discard
    sentBytes.addAndGet(bytes.toLong).discard

  def recordReceived(bytes: Int): Unit =
    receivedFrames.incrementAndGet().discard
    receivedBytes.addAndGet(bytes.toLong).discard
    lastSeen.set(clock.instant())

  def recordDropped(): Unit = droppedFrames.incrementAndGet().discard

  /** §4.3: well-framed, correctly counted as inbound traffic, but not usable — an unknown type, a misrouted frame, a short payload or a
    * field out of range. The link survives every one of them, which is why this is a counter and not a teardown.
    */
  def recordSkipped(error: ProtocolError): Unit =
    skippedFrames.incrementAndGet().discard
    val matching = error match
      case _: ProtocolError.UnknownType    => Some(unknownType)
      case _: ProtocolError.WrongDirection => Some(wrongDirection)
      case _: ProtocolError.ShortPayload   => Some(shortPayload)
      case _: ProtocolError.InvalidField   => Some(invalidField)
      case _                               => None
    matching.foreach(_.incrementAndGet().discard)

  /** §4.3: counted by the reader, which performs the skip itself; the frame never reaches the decoder. */
  def recordOversizeSkipped(): Unit =
    skippedFrames.incrementAndGet().discard
    oversizeSkipped.incrementAndGet().discard

  /** §4.4/§14: reported by the reader for every candidate window it shifts past. */
  def recordResync(): Unit = resyncs.incrementAndGet().discard

  /** §6.6: coalesced to the highest seq, so a late `ACK` reordered behind an earlier one must not walk the figure backwards. */
  def recordAck(seq: Long): Unit = acknowledged.updateAndGet(current => math.max(current, seq)).discard

  def traffic: LinkTraffic =
    LinkTraffic(
      framesSent = sentFrames.get(),
      framesDropped = droppedFrames.get(),
      framesReceived = receivedFrames.get(),
      framesSkipped = skippedFrames.get(),
      framesUnknownType = unknownType.get(),
      framesWrongDirection = wrongDirection.get(),
      framesShortPayload = shortPayload.get(),
      framesInvalidField = invalidField.get(),
      framesOversizeSkipped = oversizeSkipped.get(),
      resyncEvents = resyncs.get(),
      bytesSent = sentBytes.get(),
      bytesReceived = receivedBytes.get(),
      ackedSeq = acknowledged.get(),
      lastSeenAt = lastSeen.get()
    )
