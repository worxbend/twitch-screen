package twitchscreen.relay.device

import java.io.{IOException, OutputStream}
import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import ox.*
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.ChatNotifications
import twitchscreen.relay.protocol.*

class HubResilienceSuite extends munit.FunSuite:
  test("a failed stats operation does not kill the actor or supervised application"):
    supervised:
      val failed = AtomicBoolean(false)
      val clock = new Clock:
        override def getZone: ZoneId = ZoneOffset.UTC
        override def withZone(zone: ZoneId): Clock = this
        override def instant(): Instant =
          if failed.get() then throw IllegalStateException("clock unavailable")
          Instant.EPOCH
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 32))
      failed.set(true)
      intercept[IllegalStateException](hub.broadcastStats(StreamStats.Unknown)).discard
      failed.set(false)
      val accepted = hub.publish(EventRequest.of(NotificationKind.Info, "still running", ""))
      assert(accepted.isRight)
      assertEquals(hub.snapshot.notificationsPublished, 1L)
      assertEquals(hub.latestStats, StreamStats.Unknown)

  test("cached telemetry remains readable while an actor operation is waiting"):
    supervised:
      val block = AtomicBoolean(false)
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val clock = new Clock:
        override def getZone: ZoneId = ZoneOffset.UTC
        override def withZone(zone: ZoneId): Clock = this
        override def instant(): Instant =
          if block.get() then
            entered.countDown()
            release.await()
          Instant.EPOCH
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 32))
      block.set(true)
      val pending = fork(hub.broadcastStats(StreamStats.Unknown))
      try
        assert(entered.await(2, TimeUnit.SECONDS))
        assertEquals(timeoutOption(1.second)(hub.snapshot.connectedDevices), Some(0))
      finally release.countDown()
      pending.join()

  test("reclaim rate budgets are independent and expire at the rolling-window boundary"):
    val policy = ReclaimPolicy()
    val first = DeviceId("first").toOption.get
    val other = DeviceId("other").toOption.get
    (1 to 16).foreach(_ => assert(policy.allow(first, Instant.EPOCH)))
    assert(!policy.allow(first, Instant.EPOCH.plusSeconds(59)))
    (1 to 16).foreach(_ => assert(policy.allow(other, Instant.EPOCH.plusSeconds(59))))
    assert(policy.allow(first, Instant.EPOCH.plusSeconds(60)))
    assert(!policy.allow(other, Instant.EPOCH.plusSeconds(60)))

  test("a failed PONG write makes the sink terminal without counting sent bytes"):
    var attempts = 0
    val target = new OutputStream:
      override def write(value: Int): Unit =
        attempts += 1
        throw IOException("peer gone")
    val counters = LinkCounters(Clock.systemUTC())
    val sink = FrameSink(target, counters)
    val pong = EncodedFrame(RelayMessage.Pong(Token.fromWire(1)))
    assertEquals(sink.write(pong), WriteResult.Failed)
    assertEquals(sink.write(pong), WriteResult.Failed)
    assertEquals(attempts, 1)
    assertEquals(counters.traffic.framesSent, 0L)

  test("a blocked handshake refusal uses the handshake write budget"):
    supervised:
      val blocked = CountDownLatch(1)
      val release = CountDownLatch(1)
      class RefusalSocket extends java.net.Socket:
        override def getOutputStream: OutputStream = new OutputStream:
          override def write(value: Int): Unit =
            blocked.countDown()
            release.await()
            throw IOException("socket closed")
        override def close(): Unit =
          release.countDown()
          super.close()
      class Listener extends java.net.ServerSocket(0):
        def accepted(): RefusalSocket =
          val socket = RefusalSocket()
          implAccept(socket)
          socket
      val listener = useCloseableInScope(Listener())
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 32))
      val config = TestRelay.config.copy(handshakeTimeout = 100.millis, idleTimeout = 60.seconds)
      val server = fork(DeviceSession.run(listener.accepted(), config, hub, clock))
      val device = useCloseableInScope(TestDevice(listener.getLocalPort))
      device.send(DeviceMessage.Ping(Token.fromWire(9)))
      assert(blocked.await(2, TimeUnit.SECONDS))
      assert(timeoutOption(2.seconds)(server.join()).isDefined)

  test("K-057: a failed PONG write ends the session with WriteFailed promptly, not at the idle timeout"):
    supervised:
      val failing = AtomicBoolean(false)
      class PongFailingSocket extends java.net.Socket:
        override def getOutputStream: OutputStream =
          val underlying = super.getOutputStream
          new OutputStream:
            private def guard(): Unit = if failing.get() then throw IOException("peer gone")
            override def write(value: Int): Unit =
              guard()
              underlying.write(value)
            override def write(bytes: Array[Byte], offset: Int, length: Int): Unit =
              guard()
              underlying.write(bytes, offset, length)
            override def flush(): Unit =
              guard()
              underlying.flush()
      class Listener extends java.net.ServerSocket(0):
        def accepted(): PongFailingSocket =
          val socket = PongFailingSocket()
          implAccept(socket)
          socket
      val listener = useCloseableInScope(Listener())
      val clock = Clock.systemUTC()
      val bus = EventBus(clock, 64)
      val events = bus.subscribe("detach")
      // Neither the heartbeat nor the idle timer can end the session inside the budget below: only the PONG path can.
      val config = TestRelay.config.copy(idleTimeout = 60.seconds, pingInterval = 30.seconds)
      val hub = DeviceHub.start(config, ChatNotifications.Show, clock, bus)
      val server = fork(DeviceSession.run(listener.accepted(), config, hub, clock))
      val device = useCloseableInScope(TestDevice(listener.getLocalPort))
      device.hello("roundlcd-01", lastSeq = 0)
      assertEquals(device.receiveMany(2).size, 2, "WELCOME and STATS reach the device before the stream fails")
      failing.set(true)
      device.send(DeviceMessage.Ping(Token.fromWire(7)))
      assertEquals(TestRelay.detachReason(events, 2.seconds), Some(DisconnectReason.WriteFailed.describe))
      assert(timeoutOption(2.seconds)(server.join()).isDefined, "the session ends without waiting for the 60 s idle timeout")
      assertEquals(device.drain(), Nil, "no PONG reaches the device, and the connection ends")
      assertEquals(hub.links, Nil)
