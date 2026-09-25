package twitchscreen.relay.device

import ox.{discard, supervised}
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.protocol.{NotificationKind, NotificationRequest}

/** Exercises protocol v2 end to end over real sockets: handshake, push, replay, heartbeat and teardown. */
class DeviceLinkSuite extends munit.FunSuite:
  private val offlineStats = """{"op":"stats","live":false,"viewers":0,"followers":0,"subs":0,"uptime_s":0,"chat_rate":0}"""

  private def notification(title: String) = NotificationRequest(NotificationKind.Follow, title, "body", 30.seconds)

  private def seqOf(frame: String): Long = frame.split("\"seq\":")(1).takeWhile(_.isDigit).toLong

  private def withDevice(port: Int)(body: TestDevice => Unit): Unit =
    val device = TestDevice(port)
    try body(device)
    finally device.close()

  test("a freshly booted device is greeted with a baseline and the current stats, and gets no replay"):
    supervised:
      val (hub, port) = TestRelay.start()
      hub.publish(notification("published before the device connected")).discard
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        val frames = device.receiveMany(2)
        assert(frames.head.startsWith("""{"op":"welcome","proto":2,"latest_seq":1,"server_time":"""), frames.head)
        assertEquals(frames(1), offlineStats)

  test("a notification published while a device is attached reaches it"):
    supervised:
      val (hub, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        device.receiveMany(2).discard
        hub.publish(notification("new follower")).discard
        assert(
          device.receive().exists(_.contains(""""op":"notify","seq":1,"id":"ntf-0001","type":"follow","title":"new follower"""")),
          "expected a notify frame for the published notification"
        )

  test("a reconnecting device is replayed only what it missed"):
    supervised:
      val (hub, port) = TestRelay.start()
      withDevice(port)(_.hello("roundlcd-01", lastSeq = 0))
      List("one", "two", "three").foreach(title => hub.publish(notification(title)).discard)
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 1)
        val replayed = device.receiveMany(4).filter(_.contains("\"op\":\"notify\""))
        assertEquals(replayed.map(seqOf), List(2L, 3L))

  test("a ping is answered with a pong carrying the device's own timestamp"):
    supervised:
      val (_, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        device.receiveMany(2).discard
        device.send("""{"op":"ping","t":1790309000}""")
        assertEquals(device.receive(), Some("""{"op":"pong","t":1790309000}"""))

  test("a device speaking another protocol version is refused rather than served badly"):
    supervised:
      val (_, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0, proto = 1)
        assertEquals(device.receive(), None)

  test("a frame past the length limit tears the link down instead of being buffered"):
    supervised:
      val (_, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        device.receiveMany(2).discard
        device.send("x" * (TestRelay.config.maxFrameLength + 10))
        assertEquals(device.receive(), None)

  test("an unreadable frame is ignored and the link survives it"):
    supervised:
      val (_, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        device.receiveMany(2).discard
        device.send("this is not json")
        device.send("""{"op":"ping","t":7}""")
        assertEquals(device.receive(), Some("""{"op":"pong","t":7}"""))

  test("an attached device appears in the hub's link list"):
    supervised:
      val (hub, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        device.receiveMany(2).discard
        assertEquals(hub.links.map(_.device.value), List("roundlcd-01"))

  test("disconnecting through the hub closes the socket"):
    supervised:
      val (hub, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        device.receiveMany(2).discard
        val connection = hub.links.head.connection
        assertEquals(hub.disconnect(connection).map(_.device.value), Some("roundlcd-01"))
        assertEquals(device.receive(), None)
