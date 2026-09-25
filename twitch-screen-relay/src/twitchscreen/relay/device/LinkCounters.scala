package twitchscreen.relay.device

import java.time.{Clock, Instant}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import ox.discard
import sttp.tapir.Schema

/** Throughput of one device link, reported by `GET /api/v1/devices`. */
final case class LinkTraffic(
    framesSent: Long,
    framesDropped: Long,
    framesReceived: Long,
    bytesSent: Long,
    bytesReceived: Long,
    lastSeenAt: Instant
) derives Schema

/** Per-connection counters, updated by whichever thread does the work: the writer fork counts what it put on the wire, the reader fork what
  * it took off, and [[DeviceHub]] what it had to drop because this device was too slow. They are atomics rather than hub state precisely so
  * that per-frame bookkeeping never reaches the hub's single thread.
  */
private[device] final class LinkCounters(clock: Clock):
  private val sentFrames = AtomicLong(0)
  private val droppedFrames = AtomicLong(0)
  private val receivedFrames = AtomicLong(0)
  private val sentBytes = AtomicLong(0)
  private val receivedBytes = AtomicLong(0)
  private val lastSeen = AtomicReference(clock.instant())

  def recordSent(bytes: Int): Unit =
    sentFrames.incrementAndGet().discard
    sentBytes.addAndGet(bytes.toLong).discard

  def recordReceived(bytes: Int): Unit =
    receivedFrames.incrementAndGet().discard
    receivedBytes.addAndGet(bytes.toLong).discard
    lastSeen.set(clock.instant())

  def recordDropped(): Unit = droppedFrames.incrementAndGet().discard

  def traffic: LinkTraffic =
    LinkTraffic(sentFrames.get(), droppedFrames.get(), receivedFrames.get(), sentBytes.get(), receivedBytes.get(), lastSeen.get())
