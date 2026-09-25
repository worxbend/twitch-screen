package twitchscreen.relay.device

import ox.{discard, forkDiscard, supervised}
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.config.ChatNotifications
import twitchscreen.relay.protocol.*

/** Exercises TSB/3 end to end over real sockets: handshake, capability negotiation, push, replay, heartbeat, refusal and teardown.
  *
  * §17 keeps every acceptance criterion the v2 suite had and adds the cases a binary wire makes possible. The ones that belong here rather
  * than in the codec suites are the ones that need a socket: that a `BYE` really reaches the device before the close, that a malformed
  * frame does not kill the link while a malformed stream does, and that a `PONG` is not lost behind a full outbound queue.
  */
class DeviceLinkSuite extends munit.FunSuite:
  private def follow(name: String) = EventRequest(NotificationKind.Follow, actor = name, text = "", ttl = 30.seconds)

  private def chat(name: String, text: String) = EventRequest(NotificationKind.Chat, actor = name, text = text, ttl = 6.seconds)

  private def withDevice[T](port: Int)(body: TestDevice => T): T =
    val device = TestDevice(port)
    try body(device)
    finally device.close()

  private def events(frames: List[Frame]): List[EventRecord] =
    frames.flatMap(frame => Tsb3Decoder.fromRelay(frame).toOption).collect { case RelayMessage.Event(record) => record }

  private def welcomeOf(frames: List[Frame]): Option[RelayMessage.Welcome] =
    frames.headOption.flatMap(Tsb3Decoder.fromRelay(_).toOption).collect { case welcome: RelayMessage.Welcome => welcome }

  private def byesOf(frames: List[Frame]): List[RelayMessage.Bye] =
    frames.flatMap(Tsb3Decoder.fromRelay(_).toOption).collect { case bye: RelayMessage.Bye => bye }

  test("a freshly booted device is greeted with exactly two frames: a WELCOME and a STATS"):
    supervised:
      val (hub, port) = TestRelay.start()
      hub.publish(follow("published before the device connected")).discard
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        val frames = device.receiveMany(2)
        assertEquals(frames.map(_.header.typeCode.known), List(Some(MessageType.Welcome), Some(MessageType.Stats)))
        assertEquals(welcomeOf(frames).map(_.latestSeq.value), Some(1L))
        assertEquals(welcomeOf(frames).map(_.maxFrame.value), Some(Tsb3.MaxFrame))
        // §6.2: `replay_window` is what this relay actually retains, which is `device-link.replay-buffer-size`
        // and not §5's default. A device told 64 and served 8 has no way to notice the six cards it never got.
        assertEquals(welcomeOf(frames).map(_.replayWindow.value), Some(TestRelay.config.replayBufferSize))

  test("§6.2: WELCOME advertises the durable ring this relay was configured with, not the specification's default"):
    supervised:
      val (_, port) = TestRelay.start(TestRelay.config.copy(replayBufferSize = 12))
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        val frames = device.receiveMany(2)
        assertEquals(welcomeOf(frames).map(_.replayWindow.value), Some(12))
        assertNotEquals(welcomeOf(frames).map(_.replayWindow.value), Some(ReplayWindow.Durable.value))

  test("WELCOME carries the capability intersection, not what the device asked for"):
    supervised:
      val (_, port) = TestRelay.start()
      withDevice(port): device =>
        // Bit 20 is reserved; §6.1 says unknown bits are ignored, never rejected, and never granted back.
        device.hello("roundlcd-01", lastSeq = 0, caps = TestDevice.FullCaps | Capabilities.fromWire(0x00100000L))
        assertEquals(welcomeOf(device.receiveMany(2)).map(_.caps.value), Some(Capabilities.RelaySupported.value))

  test("a device that asked for nothing is granted nothing, and is served anyway"):
    supervised:
      val (hub, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0, caps = Capabilities.Empty)
        assertEquals(welcomeOf(device.receiveMany(2)).map(_.caps.value), Some(Capabilities.Empty.value))
        // §6.1: a missing capability degrades, never refuses. The follow still arrives.
        hub.publish(follow("newfriend")).discard
        assertEquals(events(device.receiveMany(1)).map(_.actor), List("newfriend"))

  test("the session id is the same for every connection of one relay process"):
    supervised:
      val (_, port) = TestRelay.start()
      def sessionId(): Long =
        withDevice(port): device =>
          device.hello("roundlcd-01", lastSeq = 0)
          welcomeOf(device.receiveMany(2)).map(_.sessionId.value).getOrElse(-1L)
      // §10.2: it changes once per relay process start, which is what tells a device its sequence space was reset.
      assertEquals(sessionId(), sessionId())
      assertNotEquals(sessionId(), -1L)

  test("an event published while a device is attached reaches it with its structured fields intact"):
    supervised:
      val (hub, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        device.receiveMany(2).discard
        hub.publish(EventRequest(NotificationKind.Raid, "streamfriend", "", 10.seconds, value = EventValue.clamp(128L))).discard
        val frame = device.receiveMany(1).head
        assert(!frame.header.flags.isReplay, "a live event carries no REPLAY flag")
        val record = events(List(frame)).head
        // V10's shape: the raider in `actor` and the viewer count in `value`, not a headline and an English sentence.
        assertEquals((record.kind, record.actor, record.value.value, record.seq.value), (NotificationKind.Raid, "streamfriend", 128L, 1L))

  test("a reconnecting device is replayed only what it missed, with the REPLAY flag set"):
    supervised:
      val (hub, port) = TestRelay.start()
      withDevice(port)(_.hello("roundlcd-01", lastSeq = 0))
      List("one", "two", "three").foreach(name => hub.publish(follow(name)).discard)
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 1)
        val frames = device.receiveMany(4)
        val replayed = frames.filter(_.header.typeCode.known.contains(MessageType.Event))
        assertEquals(events(replayed).map(_.seq.value), List(2L, 3L))
        assert(replayed.forall(_.header.flags.isReplay), "every replayed EVENT must carry flags.REPLAY")
        assertEquals(frames.last.header.typeCode.known, Some(MessageType.Stats), "the burst ends with exactly one STATS")

  test("a device whose last_seq is ahead of the relay's gets no replay, because that sequence space no longer exists"):
    supervised:
      val (hub, port) = TestRelay.start()
      List("one", "two").foreach(name => hub.publish(follow(name)).discard)
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 9_000)
        assertEquals(device.receiveMany(2).map(_.header.typeCode.known), List(Some(MessageType.Welcome), Some(MessageType.Stats)))

  test("chat is replayed out of a ring of its own, so a busy chat cannot evict a follow"):
    supervised:
      // A durable ring of four against thirty chat lines: with one shared ring the follow would have been evicted
      // twenty-six times over. The outbound queue is widened because the greeting burst is eighteen frames and
      // §10.4's drop-on-full is a different property, pinned by its own test.
      val (hub, port) = TestRelay.start(TestRelay.config.copy(replayBufferSize = 4, outboundQueueCapacity = 64))
      hub.publish(follow("first")).discard // seq 1, the baseline the device reports
      hub.publish(follow("newfriend")).discard // seq 2, durable
      (1 to 30).foreach(index => hub.publish(chat(s"chatter$index", "hi")).discard) // seqs 3…32, chat ring only
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 1)
        val frames = device.receiveMany(2 + 1 + DeviceHubState.ChatReplaySize)
        val replayed = events(frames)
        assert(replayed.exists(_.actor == "newfriend"), "the follow survived thirty chat lines")
        assertEquals(replayed.count(_.kind == NotificationKind.Chat), DeviceHubState.ChatReplaySize)
        assertEquals(replayed.map(_.seq.value), replayed.map(_.seq.value).sorted, "both rings merged into one ascending order")
        assertEquals(frames.last.header.typeCode.known, Some(MessageType.Stats), "the burst still ends with exactly one STATS")

  test("chat reaches a device that wants it and is withheld from one that does not"):
    supervised:
      val (hub, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0, caps = TestDevice.AsciiOnlyCaps)
        device.receiveMany(2).discard
        hub.publish(chat("sparkplug", "o7")).discard
        hub.publish(follow("newfriend")).discard
        // The chat event was still sequenced — it holds seq 1 — but a device without CAP_CHAT is not sent it.
        val record = events(device.receiveMany(1)).head
        assertEquals((record.kind, record.seq.value), (NotificationKind.Follow, 2L))

  test("the relay's own chat policy narrows what a device asked for, and cannot widen it"):
    supervised:
      val (hub, port) = TestRelay.start(chat = ChatNotifications.Hide)
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0, caps = TestDevice.FullCaps)
        device.receiveMany(2).discard
        hub.publish(chat("sparkplug", "o7")).discard
        hub.publish(follow("newfriend")).discard
        assertEquals(events(device.receiveMany(1)).map(_.kind), List(NotificationKind.Follow))

  test("a device without CAP_UTF8_TEXT is sent a transliteration rather than bytes its font cannot draw"):
    supervised:
      val (hub, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0, caps = TestDevice.AsciiOnlyCaps)
        device.receiveMany(2).discard
        hub.publish(EventRequest(NotificationKind.Follow, "Paweł", "świetny stream!", 6.seconds)).discard
        val record = events(device.receiveMany(1)).head
        assertEquals((record.actor, record.text), ("Pawel", "swietny stream!"))

  test("a device with CAP_UTF8_TEXT is sent the string verbatim"):
    supervised:
      val (hub, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0, caps = TestDevice.FullCaps)
        device.receiveMany(2).discard
        hub.publish(EventRequest(NotificationKind.Follow, "Paweł", "świetny stream!", 6.seconds)).discard
        val record = events(device.receiveMany(1)).head
        assertEquals((record.actor, record.text), ("Paweł", "świetny stream!"))

  test("an over-long chat line is truncated by the sender instead of tearing the link down"):
    supervised:
      val (hub, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        device.receiveMany(2).discard
        hub.publish(chat("chatter", "x" * 400)).discard
        val frame = device.receiveMany(1).head
        assertEquals(frame.header.frameSize, 176, "every EVENT is 176 bytes whatever it carries")
        val record = events(List(frame)).head
        assertEquals(record.text.length, 95)
        assert(record.text.endsWith("..."), record.text)
        assert(record.flags.contains(EventFlags.TextTruncated), "the sender must say it shortened the text")
        // The link is still usable afterwards, which is the point of bounding the text at the sender.
        device.send(DeviceMessage.Ping(Token.fromWire(7L)))
        assertEquals(device.receiveMessage(), Some(RelayMessage.Pong(Token.fromWire(7L))))

  test("a device PING is answered with a PONG echoing its token"):
    supervised:
      val (_, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        device.receiveMany(2).discard
        device.send(DeviceMessage.Ping(Token.fromWire(4210L)))
        assertEquals(device.receiveMessage(), Some(RelayMessage.Pong(Token.fromWire(4210L))))

  test("a PONG still arrives when the outbound queue has been overrun"):
    supervised:
      val (hub, port) = TestRelay.start(TestRelay.config.copy(outboundQueueCapacity = 2))
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        device.receiveMany(2).discard
        // Far more than the queue holds, published while nothing is being read.
        (1 to 300).foreach(index => hub.publish(follow(s"friend$index")).discard)
        device.send(DeviceMessage.Ping(Token.fromWire(99L)))
        val pong = LazyList
          .continually(device.receiveMessage())
          .take(512)
          .takeWhile(_.isDefined)
          .flatten
          .collectFirst { case RelayMessage.Pong(token) => token.value }
        // §6.3 and §10.4: PING/PONG is unrecoverable, so a pong queued behind a backlog that is being dropped is a
        // pong that never comes — and a device that stops getting pongs reconnects and re-triggers the burst.
        assertEquals(pong, Some(99L))

  test("an ACK is recorded against the link and withholds nothing"):
    supervised:
      val (hub, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        device.receiveMany(2).discard
        hub.publish(follow("newfriend")).discard
        device.receiveMany(1).discard
        device.send(DeviceMessage.Ack(SeqNo.fromWire(1L)))
        device.send(DeviceMessage.Ping(Token.fromWire(1L)))
        assertEquals(device.receiveMessage(), Some(RelayMessage.Pong(Token.fromWire(1L))))
        assertEquals(hub.links.headOption.map(_.traffic.ackedSeq), Some(1L))

  test("a device speaking another protocol version is told so, with the 30 s floor a reflash needs"):
    supervised:
      val (_, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0, version = ProtocolVersion.fromWire(4.toByte))
        val frames = device.drain()
        assertEquals(frames.size, 1, "a BYE and nothing else")
        // §6.7: stamped with the version byte of the frame that provoked it, so a v4 device can read the refusal.
        assertEquals(frames.head.header.version.value, 4)
        assertEquals(
          byesOf(frames),
          List(RelayMessage.Bye(ByeCode.UnsupportedVersion, ByeDetail.of(3), 30.seconds, "relay speaks v3 only"))
        )

  test("§7: a wrong-version HELLO too short for a v3 payload still gets BYE(1) and the 30 s floor, not BAD_HANDSHAKE"):
    supervised:
      val (_, port) = TestRelay.start()
      withDevice(port): device =>
        device.sendBytes(WireBytes.header(typeCode = MessageType.Hello.code, length = 10, version = 2) ++ new Array[Byte](10))
        val frames = device.drain()
        assertEquals(frames.map(_.header.version.value), List(2))
        assertEquals(
          byesOf(frames).map(bye => (bye.code, bye.detail.value, bye.retryAfter)),
          List((ByeCode.UnsupportedVersion, 3, 30.seconds))
        )

  test("§11.1 rule 2: the handshake timeout is a deadline from accept — a HELLO trickled a byte at a time does not stretch it"):
    supervised:
      val (_, port) = TestRelay.start(TestRelay.config.copy(handshakeTimeout = 600.millis))
      withDevice(port): device =>
        val hello = Tsb3Encoder.toRelay(
          DeviceMessage.Hello(
            deviceId = DeviceId("roundlcd-01").toOption.get,
            lastSeq = SeqNo.Zero,
            caps = TestDevice.FullCaps,
            rxMax = FrameSize.fromWire(Tsb3.MinRxMax),
            fwVersion = FirmwareVersion.fromWire("1.0.0")
          )
        )
        forkDiscard:
          try
            hello.foreach { byte =>
              device.sendBytes(Array(byte)); Thread.sleep(200)
            }
          catch case _: java.io.IOException => () // the relay hung up on us, which is the point
        val started = System.nanoTime()
        assertEquals(byesOf(device.drain()).map(_.code), List(ByeCode.HandshakeTimeout))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assert(elapsedMs < 2000, s"handshake took $elapsedMs ms, the deadline is 600 ms")

  test("a first frame that is not a HELLO is refused with the type code it actually was"):
    supervised:
      val (_, port) = TestRelay.start()
      withDevice(port): device =>
        device.send(DeviceMessage.Ping(Token.fromWire(1L)))
        assertEquals(
          byesOf(device.drain()).map(bye => (bye.code, bye.detail.value)),
          List((ByeCode.BadHandshake, MessageType.DevicePing.code))
        )

  test("a device that says nothing is told the handshake timed out rather than left guessing"):
    supervised:
      val (_, port) = TestRelay.start(TestRelay.config.copy(handshakeTimeout = 300.millis))
      withDevice(port): device =>
        assertEquals(byesOf(device.drain()).map(_.code), List(ByeCode.HandshakeTimeout))

  test("a HELLO declaring an rx_max below the mandatory 256 names the offending field's offset"):
    supervised:
      val (_, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0, rxMax = 128)
        assertEquals(
          byesOf(device.drain()).map(bye => (bye.code, bye.detail.value)),
          List((ByeCode.InvalidParameter, Tsb3.Hello.RxMax))
        )

  test("a second HELLO on an established session ends it, and says which rule was broken"):
    supervised:
      val (_, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        device.receiveMany(2).discard
        device.hello("roundlcd-01", lastSeq = 0)
        assertEquals(byesOf(device.drain()).map(_.code), List(ByeCode.DuplicateHello))

  test("§11.1 rule 3: a second HELLO too short to decode is still a DUPLICATE_HELLO, not a §4.3 skip"):
    supervised:
      val (_, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        device.receiveMany(2).discard
        device.sendBytes(WireBytes.header(typeCode = MessageType.Hello.code, length = 4) ++ new Array[Byte](4))
        assertEquals(byesOf(device.drain()).map(_.code), List(ByeCode.DuplicateHello))

  test("§6.5: a STREAM_START is followed on the wire by a STATS, even when the post-transition STATS reached the hub first"):
    supervised:
      val (hub, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        device.receiveMany(2).discard
        hub.broadcastStats(StreamStats.Unknown.copy(state = StreamState.Live))
        device.receiveMany(1).discard
        hub.publish(EventRequest(NotificationKind.StreamStart, actor = "", text = "Round LCD build night", ttl = 30.seconds)).discard
        assertEquals(
          device.receiveMany(2).map(_.header.typeCode.known),
          List(Some(MessageType.Event), Some(MessageType.Stats))
        )

  test("a well-framed frame of an unknown type is skipped, counted, and the next frame decodes correctly"):
    supervised:
      val (hub, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        device.receiveMany(2).discard
        // §17 case 1: a type a newer device might send, inside the device→relay range so it is not a misroute.
        device.sendBytes(WireBytes.header(typeCode = 0x0f, length = 0))
        device.send(DeviceMessage.Ping(Token.fromWire(7L)))
        assertEquals(device.receiveMessage(), Some(RelayMessage.Pong(Token.fromWire(7L))))
        assertEquals(hub.links.headOption.map(_.traffic.framesSkipped), Some(1L))
        // §4.3: counted under its own condition, not only in the total.
        assertEquals(hub.links.headOption.map(_.traffic.framesUnknownType), Some(1L))
        assertEquals(hub.links.headOption.map(_.traffic.framesShortPayload), Some(0L))

  test("a payload one byte short of its base length is skipped and the link survives"):
    supervised:
      val (hub, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        device.receiveMany(2).discard
        // §17 case 2: a three-byte PING where the base length is four.
        device.sendBytes(WireBytes.resized(Tsb3Encoder.toRelay(DeviceMessage.Ping(Token.fromWire(7L))), payloadLength = 3))
        device.send(DeviceMessage.Ping(Token.fromWire(8L)))
        assertEquals(device.receiveMessage(), Some(RelayMessage.Pong(Token.fromWire(8L))))
        assertEquals(hub.links.headOption.map(_.traffic.framesSkipped), Some(1L))
        assertEquals(hub.links.headOption.map(_.traffic.framesShortPayload), Some(1L))
        assertEquals(hub.links.headOption.map(_.traffic.framesUnknownType), Some(0L))

  test("the magic bytes appearing inside a payload do not confuse the reader"):
    supervised:
      val (_, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        device.receiveMany(2).discard
        // §17 case 8: `a7 53` twice over, as a PING token, followed by a real frame the reader must still find.
        device.send(DeviceMessage.Ping(Token.fromWire(0x53a753a7L)))
        assertEquals(device.receiveMessage(), Some(RelayMessage.Pong(Token.fromWire(0x53a753a7L))))
        device.send(DeviceMessage.Ping(Token.fromWire(5L)))
        assertEquals(device.receiveMessage(), Some(RelayMessage.Pong(Token.fromWire(5L))))

  test("garbage past the resync budget tears the link down with a BYE, not a silent close"):
    supervised:
      val (_, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        device.receiveMany(2).discard
        // §17 case 9. §4.5's budget — 16 rejected candidates or 4096 discarded bytes — is what separates a
        // malformed stream from a malformed frame; without it this would be an unbreakable reconnect loop.
        device.sendBytes(Array.fill(Tsb3.MaxDiscardedBytes + 64)(Tsb3.Magic0))
        assertEquals(byesOf(device.drain()).map(_.code), List(ByeCode.FramingViolation))

  test("§6.7 code 9: a second connection claiming a device id reclaims it, and the stale one is told why"):
    supervised:
      val (hub, port) = TestRelay.start()
      withDevice(port): first =>
        first.hello("roundlcd-01", lastSeq = 0)
        assertEquals(first.receiveMany(2).size, 2)
        assertEquals(hub.links.map(_.device.value), List("roundlcd-01"))
        withDevice(port): second =>
          second.hello("roundlcd-01", lastSeq = 0)
          assertEquals(second.receiveMany(2).size, 2)
          // The half-open corpse is told, then closed: one physical screen is one row, not two, and the dead
          // connection's 128-frame outbound queue stops absorbing every broadcast.
          assertEquals(byesOf(first.drain()).map(_.code), List(ByeCode.Replaced))
          assertEquals(hub.links.map(_.connection.value), List(2L))

  test("§6.7 code 9: a different device id on the same relay is not reclaimed"):
    supervised:
      val (hub, port) = TestRelay.start()
      withDevice(port): first =>
        first.hello("roundlcd-01", lastSeq = 0)
        assertEquals(first.receiveMany(2).size, 2)
        withDevice(port): second =>
          second.hello("roundlcd-02", lastSeq = 0)
          assertEquals(second.receiveMany(2).size, 2)
          assertEquals(hub.links.map(_.device.value).sorted, List("roundlcd-01", "roundlcd-02"))

  test("§6.7 code 8: a relay shutting down says so instead of dropping the socket in silence"):
    supervised:
      val (hub, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        assertEquals(device.receiveMany(2).size, 2)
        assertEquals(hub.shutdown(), 1)
        assertEquals(byesOf(device.drain()).map(_.code), List(ByeCode.ServerShutdown))
        assertEquals(hub.links, Nil)

  test("an attached device appears in the hub's link list with its counters in bytes"):
    supervised:
      val (hub, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        device.receiveMany(2).discard
        val link = hub.links.headOption
        assertEquals(link.map(_.device.value), Some("roundlcd-01"))
        // §14: `8 + length` per frame. A HELLO is 68 bytes; a WELCOME is 32 and a STATS is 40.
        assertEquals(link.map(_.traffic.bytesReceived), Some(68L))
        assertEquals(link.map(_.traffic.bytesSent), Some(72L))

  test("disconnecting through the hub closes the socket"):
    supervised:
      val (hub, port) = TestRelay.start()
      withDevice(port): device =>
        device.hello("roundlcd-01", lastSeq = 0)
        device.receiveMany(2).discard
        val connection = hub.links.head.connection
        assertEquals(hub.disconnect(connection).map(_.device.value), Some("roundlcd-01"))
        assertEquals(device.receive(), None)
