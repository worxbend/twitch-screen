package twitchscreen.relay.device

import java.io.{Closeable, IOException}
import java.time.{Clock, Instant}
import java.util.concurrent.atomic.AtomicBoolean
import ox.*
import ox.channels.{Channel, ChannelClosed}
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import twitchscreen.relay.protocol.*

/** K-081: an attached device owns its outbound queue, its counters and its transport. The hub asks it to take a frame and reads back an
  * [[Offer]]; the facade asks it to drain and close. These tests pin that behaviour directly, without an actor or a socket, so the socket
  * suites (DeviceBackpressureSuite, DeviceLinkSuite) remain the end-to-end proof.
  */
class AttachedDeviceSuite extends munit.FunSuite:
  private val clock = Clock.systemUTC()

  private final case class Fixture(
      device: AttachedDevice,
      queue: Channel[Outbound],
      counters: LinkCounters,
      closed: AtomicBoolean,
      drained: Channel[Unit]
  )

  private def newDevice(capacity: Int, transport: Option[Closeable] = None): Fixture =
    val queue = Channel.buffered[Outbound](capacity)
    val counters = LinkCounters(clock)
    val closed = AtomicBoolean(false)
    val drained = Channel.buffered[Unit](1)
    val connection: Closeable = transport.getOrElse(() => closed.set(true))
    val request = AttachRequest(
      DeviceId("t").toOption.get,
      "test",
      Tsb3.Version,
      SeqNo.Zero,
      TestDevice.FullCaps,
      queue,
      counters,
      connection,
      drained
    )
    Fixture(AttachedDevice(ConnectionId(1), request, Instant.now()), queue, counters, closed, drained)

  private def stats: Outbound = Outbound(RelayMessage.Stats(StreamStats.Unknown, Some(Instant.now())))

  private def event: Outbound =
    Outbound(RelayMessage.Event(EventRequest.of(NotificationKind.Info, "title", "body").record(SeqNo.fromWire(1), Instant.now())))

  private def elapsed(block: => Unit): FiniteDuration =
    val start = System.nanoTime()
    block
    (System.nanoTime() - start).nanos

  test("K-081: offer queues a frame and reports Accepted"):
    val f = newDevice(1)
    val frame = stats
    assertEquals(f.device.offer(frame), Offer.Accepted)
    assert(f.queue.receive() eq frame)
    assertEquals(f.counters.traffic.framesDropped, 0L)
    assert(!f.closed.get())

  test("K-081: a replaceable frame on a full queue is DroppedReplaceable, counted, link kept"):
    val f = newDevice(1)
    assertEquals(f.device.offer(stats), Offer.Accepted)
    assertEquals(f.device.offer(stats), Offer.DroppedReplaceable)
    assertEquals(f.counters.traffic.framesDropped, 1L)
    assert(!f.closed.get())
    f.queue.receive().discard
    assertEquals(f.device.offer(stats), Offer.Accepted)

  test("K-081: an EVENT on a full queue is Overflowed: counted, queue done, transport closed"):
    val f = newDevice(1)
    val queued = stats
    assertEquals(f.device.offer(queued), Offer.Accepted)
    assertEquals(f.device.offer(event), Offer.Overflowed)
    assertEquals(f.counters.traffic.framesDropped, 1L)
    assert(f.closed.get())
    assert(f.queue.receive() eq queued)
    assert(f.queue.receiveOrClosed().isInstanceOf[ChannelClosed])

  test("K-081: offer on a closed queue is Closed and records nothing"):
    val f = newDevice(1)
    f.queue.doneOrClosed().discard
    assertEquals(f.device.offer(event), Offer.Closed)
    assertEquals(f.counters.traffic.framesDropped, 0L)
    assert(!f.closed.get())

  test("K-081: drainThenClose closes the transport once the session signals drained"):
    val f = newDevice(1)
    f.drained.send(())
    val took = elapsed(f.device.drainThenClose(2.seconds))
    assert(took < 1.second, s"drainThenClose waited $took despite the drain signal")
    assert(f.closed.get())

  test("K-081: drainThenClose still closes the transport when the drain never arrives"):
    val f = newDevice(1)
    val took = elapsed(f.device.drainThenClose(50.millis))
    assert(took < 1.second, s"drainThenClose took $took")
    assert(f.closed.get())

  test("K-081: a failing transport close is swallowed"):
    val f = newDevice(1, Some(() => throw IOException("already reset")))
    f.device.drainThenClose(10.millis)

  test("K-081: shutdown-style drainThenClose shares one deadline across devices"):
    val all = List.fill(3)(newDevice(1))
    val took = elapsed(AttachedDevice.drainThenClose(all.map(_.device), 200.millis))
    assert(took < 500.millis, s"three undrained devices took $took; the deadline is per device")
    assert(all.forall(_.closed.get()))
