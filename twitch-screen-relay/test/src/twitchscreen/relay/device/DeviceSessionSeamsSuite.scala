package twitchscreen.relay.device

import ch.qos.logback.classic.{Level, Logger as LogbackLogger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.time.{Clock, ZoneOffset}
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.Channel
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.ChatNotifications
import twitchscreen.relay.protocol.*

/** Pins two session behaviours that no socket-level suite can observe directly: a heartbeat PING that finds the outbound queue full is
  * counted as a dropped frame (K-151), and only an admitted attach is announced in the log (K-148).
  */
class DeviceSessionSeamsSuite extends munit.FunSuite:
  private val clock = Clock.fixed(Clock.systemUTC().instant(), ZoneOffset.UTC)

  private def fullQueue(): Channel[Outbound] =
    val queue = Channel.buffered[Outbound](1)
    queue.send(Outbound(RelayMessage.Ping(Token.fromWire(0))))
    queue

  test("K-151: a heartbeat PING that finds the outbound queue full is counted as a dropped frame"):
    val counters = LinkCounters(clock)
    val queue = fullQueue()
    DeviceSession.offerPing(queue, counters, clock)
    assertEquals(counters.traffic.framesDropped, 1L)
    assertEquals(queue.receive().message, RelayMessage.Ping(Token.fromWire(0)))

  test("K-151: the heartbeat loop counts every tick it cannot queue"):
    val counters = LinkCounters(clock)
    val queue = fullQueue()
    // 1 s is the smallest ping interval DeviceLinkConfig accepts (WELCOME carries whole seconds).
    val config = TestRelay.config.copy(pingInterval = 1.second)
    supervised:
      forkDiscard(DeviceSession.heartbeat(queue, config, counters, clock))
      val dropped = timeout(5.seconds):
        Iterator.continually { sleep(10.millis); counters.traffic.framesDropped }.find(_ >= 1L).getOrElse(0L)
      assert(dropped >= 1L, dropped)

  test("K-151: a PING that fits is queued, and a closed queue is teardown rather than a drop"):
    val counters = LinkCounters(clock)
    val open = Channel.buffered[Outbound](1)
    DeviceSession.offerPing(open, counters, clock)
    assertEquals(open.receive().message, RelayMessage.Ping(Token.fromWire(clock.instant().getEpochSecond)))
    val closed = Channel.buffered[Outbound](1)
    closed.done()
    DeviceSession.offerPing(closed, counters, clock)
    assertEquals(counters.traffic.framesDropped, 0L)

  private def withSessionLog[A](body: ListAppender[ILoggingEvent] => A): A =
    val logger = LoggerFactory.getLogger(DeviceSession.getClass).asInstanceOf[LogbackLogger]
    val appender = ListAppender[ILoggingEvent]()
    appender.start()
    logger.addAppender(appender)
    try body(appender)
    finally
      logger.detachAppender(appender).discard
      appender.stop()

  private def attachLines(appender: ListAppender[ILoggingEvent]): List[String] =
    appender.list.asScala.filter(_.getLevel == Level.INFO).map(_.getFormattedMessage).filter(_.contains("attached as")).toList

  private def request(queue: Channel[Outbound]): AttachRequest =
    AttachRequest(
      DeviceId("test").toOption.get,
      "test",
      Tsb3.Version,
      SeqNo.Zero,
      TestDevice.FullCaps,
      queue,
      LinkCounters(clock),
      () => (),
      Channel.buffered[Unit](1)
    )

  test("K-148: a reclaim-limited attach does not log 'attached as', an admitted one does"):
    withSessionLog: appender =>
      supervised:
        val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 32))
        // One initial attach plus the sixteen reclaims ReclaimPolicy allows in its window.
        val admitted = (1 to 17).map(_ => DeviceSession.attachAndAnnounce(hub, request(Channel.buffered[Outbound](32))))
        val queue = Channel.buffered[Outbound](32)
        val refused = DeviceSession.attachAndAnnounce(hub, request(queue))
        assertEquals(queue.receive().message, RelayMessage.Bye(ByeCode.RateLimit, ByeDetail.Zero, 60.seconds, "reclaim rate exceeded"))
        assertEquals(hub.link(refused), None)
        val lines = attachLines(appender)
        assert(!lines.exists(_.contains(s"attached as #${refused.value} ")), lines)
        assertEquals(lines.size, admitted.size, lines)
        admitted.foreach(id => assert(lines.exists(_.contains(s"attached as #${id.value} ")), lines))

  test("K-148: an attach refused during shutdown does not log 'attached as'"):
    withSessionLog: appender =>
      supervised:
        val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 32))
        assertEquals(hub.shutdown(), 0)
        val queue = Channel.buffered[Outbound](32)
        val refused = DeviceSession.attachAndAnnounce(hub, request(queue))
        queue.receive().message match
          case RelayMessage.Bye(code, _, _, _) => assertEquals(code, ByeCode.ServerShutdown)
          case other                           => fail(s"expected BYE, got $other")
        assertEquals(hub.link(refused), None)
        assertEquals(attachLines(appender), Nil)
