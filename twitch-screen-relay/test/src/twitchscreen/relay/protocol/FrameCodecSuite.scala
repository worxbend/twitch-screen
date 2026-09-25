package twitchscreen.relay.protocol

import java.time.Instant
import scala.concurrent.duration.DurationInt

/** Pins the exact bytes of protocol v2 against `twitch-screen-firmware/docs/PROTOCOL.md`. */
class FrameCodecSuite extends munit.FunSuite:
  private val maxFrame = 512
  private val at = Instant.ofEpochSecond(1790309000L)

  test("welcome encodes with the documented field names"):
    val frame = ServerFrame.welcome(protocolVersion = 2, latestSeq = SeqNo(120).toOption.get, now = at)
    assertEquals(FrameCodec.encode(frame), """{"op":"welcome","proto":2,"latest_seq":120,"server_time":1790309000}""")

  test("notify encodes with the documented field names"):
    val notification = Notification(
      seq = SeqNo(121).toOption.get,
      id = NotificationId.forSeq(SeqNo(121).toOption.get),
      kind = NotificationKind.Warning,
      title = "CPU temperature",
      body = "server rack A reached 78C",
      at = at,
      ttl = 30.seconds
    )
    assertEquals(
      FrameCodec.encode(ServerFrame.notify(notification)),
      """{"op":"notify","seq":121,"id":"ntf-0121","type":"warning","title":"CPU temperature","body":"server rack A reached 78C","timestamp":1790309000,"ttl_ms":30000}"""
    )

  test("stats encodes with the documented field names"):
    val stats = StreamStats(
      state = StreamState.Live,
      viewers = Count.clamp(842),
      followers = Count.clamp(12400),
      subscribers = Count.clamp(318),
      uptime = 3660.seconds,
      chatRate = MessagesPerMinute.clamp(35)
    )
    assertEquals(
      FrameCodec.encode(ServerFrame.stats(stats)),
      """{"op":"stats","live":true,"viewers":842,"followers":12400,"subs":318,"uptime_s":3660,"chat_rate":35}"""
    )

  test("ping encodes with the documented field names"):
    assertEquals(FrameCodec.encode(ServerFrame.ping(at)), """{"op":"ping","t":1790309000}""")

  test("hello from the firmware decodes to a validated command"):
    val decoded = FrameCodec.decode("""{"op":"hello","device":"roundlcd-01","proto":2,"last_seq":105}""", maxFrame)
    assertEquals(decoded, Right(DeviceCommand.Hello(DeviceId("roundlcd-01").toOption.get, 2, SeqNo(105).toOption.get)))

  test("pong from the firmware decodes to a validated command"):
    assertEquals(FrameCodec.decode("""{"op":"pong","t":1790309000}""", maxFrame), Right(DeviceCommand.Pong(1790309000L)))

  test("an unknown op is reported rather than thrown"):
    assert(FrameCodec.decode("""{"op":"shutdown"}""", maxFrame).left.exists(_.isInstanceOf[ProtocolError.Malformed]))

  test("a line past the frame limit is rejected before parsing"):
    val oversized = s"""{"op":"hello","device":"${"d" * 600}","proto":2,"last_seq":0}"""
    assertEquals(FrameCodec.decode(oversized, maxFrame), Left(ProtocolError.FrameTooLong(maxFrame)))

  test("a blank device id is rejected"):
    assert(FrameCodec.decode("""{"op":"hello","device":"  ","proto":2,"last_seq":0}""", maxFrame).left.exists {
      case _: ProtocolError.InvalidDeviceId => true
      case _                                => false
    })

  test("a newline inside a notification body cannot break the framing"):
    val notification = Notification(
      seq = SeqNo(1).toOption.get,
      id = NotificationId.forSeq(SeqNo(1).toOption.get),
      kind = NotificationKind.Chat,
      title = "chat",
      body = "line one\nline two",
      at = at,
      ttl = 1.second
    )
    assert(!FrameCodec.encode(ServerFrame.notify(notification)).contains('\n'))

  test("an unknown notification type degrades to info, as the firmware does"):
    assertEquals(NotificationKind.fromWire("hypetrain"), NotificationKind.Info)
