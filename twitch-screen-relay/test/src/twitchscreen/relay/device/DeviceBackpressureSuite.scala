package twitchscreen.relay.device

import ch.qos.logback.classic.{Level, Logger as LogbackLogger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.io.{Closeable, IOException, OutputStream}
import java.net.{ServerSocket, Socket}
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.Channel
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.ChatNotifications
import twitchscreen.relay.protocol.*

class DeviceBackpressureSuite extends munit.FunSuite:
  private val clock = Clock.systemUTC()
  private val Loopback = "127.0.0.1"

  test("reclaim storms are refused without replacing the current connection"):
    supervised:
      val fixed = Clock.fixed(clock.instant(), java.time.ZoneOffset.UTC)
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, fixed, EventBus(fixed, 32))
      def attach(): Channel[Outbound] =
        val queue = Channel.buffered[Outbound](32)
        hub
          .attach(
            AttachRequest(
              DeviceId("test").toOption.get,
              "test",
              Tsb3.Version,
              SeqNo.Zero,
              TestDevice.FullCaps,
              queue,
              LinkCounters(fixed),
              () => (),
              Channel.buffered[Unit](1)
            )
          )
          .discard
        queue
      (1 to 17).foreach(_ => attach().discard)
      val current = hub.links.head.connection
      val refused = attach().receive().message
      assertEquals(refused, RelayMessage.Bye(ByeCode.RateLimit, ByeDetail.Zero, 60.seconds, "reclaim rate exceeded"))
      assertEquals(hub.links.map(_.connection), List(current))

  test("pending handshakes count toward the listener session limit"):
    supervised:
      val (_, port) = TestRelay.start(TestRelay.config.copy(handshakeTimeout = 30.seconds))
      val peers = (1 to DeviceLinkServer.MaxConnections).map(_ => useCloseableInScope(Socket(Loopback, port)))
      assertEquals(peers.size, DeviceLinkServer.MaxConnections)
      val refused = useCloseableInScope(Socket(Loopback, port))
      refused.setSoTimeout(5000)
      assertEquals(refused.getInputStream.read(), -1)

  test("an over-limit refusal is counted in the snapshot and logged with the running total"):
    val logger = LoggerFactory.getLogger(DeviceLinkServer.getClass).asInstanceOf[LogbackLogger]
    val appender = ListAppender[ILoggingEvent]()
    appender.start()
    logger.addAppender(appender)
    try
      supervised:
        val (hub, port) = TestRelay.start(TestRelay.config.copy(handshakeTimeout = 30.seconds))
        assertEquals(hub.snapshot.connectionsRefused, 0L)
        (1 to DeviceLinkServer.MaxConnections).foreach(_ => useCloseableInScope(Socket(Loopback, port)).discard)
        val refused = useCloseableInScope(Socket(Loopback, port))
        refused.setSoTimeout(5000)
        assertEquals(refused.getInputStream.read(), -1)
        // The listener counts and logs before it closes the socket, so EOF above already orders both before this read.
        assertEquals(hub.snapshot.connectionsRefused, 1L)
        assertEquals(hub.connectionsRefused, 1L)
        val warnings = appender.list.asScala.filter(_.getLevel == Level.WARN).map(_.getFormattedMessage).toList
        assertEquals(warnings.count(_.contains("refused 1 connections")), 1, warnings)
    finally
      logger.detachAppender(appender).discard
      appender.stop()

  test("EVENT overflow closes and removes the connection before any later event can cross the gap"):
    supervised:
      val bus = EventBus(clock, 32)
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, bus)
      val queue = Channel.buffered[Outbound](2)
      val closed = AtomicBoolean(false)
      val transport: Closeable = () => closed.set(true)
      val counters = LinkCounters(clock)
      hub
        .attach(
          AttachRequest(
            DeviceId("test").toOption.get,
            "test",
            Tsb3.Version,
            SeqNo.Zero,
            TestDevice.FullCaps,
            queue,
            counters,
            transport,
            Channel.buffered[Unit](1)
          )
        )
        .discard
      // The undrained greeting fills both slots deterministically.
      hub.publish(EventRequest.of(NotificationKind.Follow, "first", "")).discard
      assert(closed.get())
      assertEquals(hub.links, Nil)
      assertEquals(hub.connectedCount, 0)
      hub.publish(EventRequest.of(NotificationKind.Follow, "later", "")).discard
      assertEquals(counters.traffic.framesDropped, 1L)
      assertEquals(queue.receive().message.messageType, MessageType.Welcome)
      assertEquals(queue.receive().message.messageType, MessageType.Stats)
      assert(queue.receiveOrClosed().isInstanceOf[ox.channels.ChannelClosed])

  test("STATS overflow is counted and dropped without closing the connection or skipping a seq"):
    supervised:
      val bus = EventBus(clock, 32)
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, bus)
      val queue = Channel.buffered[Outbound](2)
      val closed = AtomicBoolean(false)
      val transport: Closeable = () => closed.set(true)
      val counters = LinkCounters(clock)
      hub
        .attach(
          AttachRequest(
            DeviceId("test").toOption.get,
            "test",
            Tsb3.Version,
            SeqNo.Zero,
            TestDevice.FullCaps,
            queue,
            counters,
            transport,
            Channel.buffered[Unit](1)
          )
        )
        .discard
      // The undrained greeting fills both slots, so this snapshot cannot be enqueued.
      hub.broadcastStats(StreamStats.Unknown)
      // `links` is an actor ask, so it is ordered after the `broadcastStats` tell.
      assertEquals(hub.links.size, 1)
      assert(!closed.get(), "a dropped STATS must not close the transport")
      assertEquals(hub.connectedCount, 1)
      assertEquals(counters.traffic.framesDropped, 1L)
      assertEquals(queue.receive().message.messageType, MessageType.Welcome)
      val next = hub
        .publish(EventRequest.of(NotificationKind.Follow, "after-stats", ""))
        .fold(refused => fail(s"unexpected refusal $refused"), identity)
      assertEquals(next.seq, SeqNo(1L).toOption.get)
      assertEquals(queue.receive().message.messageType, MessageType.Stats)
      queue.receive().message match
        case RelayMessage.Event(record) => assertEquals(record.seq, next.seq)
        case other                      => fail(s"expected the next EVENT, got $other")
      assert(!closed.get())
      assertEquals(hub.links.size, 1)
      assertEquals(counters.traffic.framesDropped, 1L)

  test("operator disconnect retains its reason on the event bus"):
    supervised:
      val bus = EventBus(clock, 32)
      val messages = bus.subscribe("test")
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, bus)
      val id = hub.attach(
        AttachRequest(
          DeviceId("test").toOption.get,
          "test",
          Tsb3.Version,
          SeqNo.Zero,
          TestDevice.FullCaps,
          Channel.buffered[Outbound](32),
          LinkCounters(clock),
          () => (),
          Channel.buffered[Unit](1)
        )
      )
      messages.receive().discard
      hub.disconnect(id).discard
      assertEquals(
        messages.receive().event,
        RelayEvent.DeviceDisconnected(DeviceId("test").toOption.get, id, DisconnectReason.RequestedByOperator.describe)
      )

  test("a stalled socket write cannot prevent the independent deadline closing the session"):
    supervised:
      val entered = CountDownLatch(1)
      val released = CountDownLatch(1)
      class StalledSocket extends Socket:
        override def getOutputStream: OutputStream = new OutputStream:
          override def write(value: Int): Unit =
            entered.countDown()
            released.await()
            throw IOException("socket closed")
        override def close(): Unit =
          released.countDown()
          super.close()
      class Listener extends ServerSocket(0):
        def stalled(): StalledSocket =
          val socket = StalledSocket()
          implAccept(socket)
          socket
      val listener = useCloseableInScope(Listener())
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 32))
      val server = fork:
        val socket = listener.stalled()
        DeviceSession.run(socket, TestRelay.config.copy(idleTimeout = 2.seconds, pingInterval = 1.second), hub, clock)
      val device = useCloseableInScope(TestDevice(listener.getLocalPort))
      device.hello("stalled", 0L)
      assert(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
      device.send(DeviceMessage.Ping(Token.fromWire(9)))
      assert(timeoutOption(5.seconds)(server.join()).isDefined)
      // A snapshot ask is ordered after the session's detach tell.
      assertEquals(hub.snapshot.connectedDevices, 0)

  test("K-140: an idle established session with no writes is not closed by the write deadline"):
    supervised:
      // A write budget far shorter than the idle wait below: a deadline left armed with no write in flight would close the socket.
      val config = TestRelay.config.copy(handshakeTimeout = 100.millis, idleTimeout = 5.seconds, pingInterval = 4.seconds)
      val (hub, port) = TestRelay.start(config)
      val device = useCloseableInScope(TestDevice(port))
      device.hello("idle", 0L)
      assertEquals(device.receiveMessage().map(_.messageType), Some(MessageType.Welcome))
      assertEquals(device.receiveMessage().map(_.messageType), Some(MessageType.Stats))
      Thread.sleep(500)
      device.send(DeviceMessage.Ping(Token.fromWire(11)))
      assertEquals(device.receiveMessage(), Some(RelayMessage.Pong(Token.fromWire(11))))
      assertEquals(hub.snapshot.connectedDevices, 1)
