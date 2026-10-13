package twitchscreen.relay.device

import java.io.ByteArrayInputStream
import java.net.{InetAddress, ServerSocket, Socket}
import java.nio.file.{Files, Path}
import java.util.HexFormat
import java.time.Clock
import ox.supervised
import ox.channels.Channel
import scala.jdk.CollectionConverters.*
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.ChatNotifications
import twitchscreen.relay.protocol.*
import twitchscreen.relay.settings.*

class DeskDeliverySuite extends munit.FunSuite:
  private def vector(name: String): Frame =
    val path = Iterator
      .iterate(Path.of("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("protocol/desk-v1-vectors.tsv"))
      .find(Files.isRegularFile(_))
      .get
    val bytes = HexFormat.of().parseHex(Files.readAllLines(path).asScala.find(_.startsWith(name + "\t")).get.split("\t")(3))
    FrameReader(ByteArrayInputStream(bytes)).read().toOption.get
  private val sync = DeskControlCodec.decodeSync(vector("sync_end")).toOption.get
  private val display = DeskControlCodec.decodeDisplay(vector("display_cpu")).toOption.get
  private val full = DeskReportCodec.decode(vector("report_full_zero_credit")).toOption.get
  private val greeting = DeskDelivery.Greeting(
    Tsb3Decoder.fromRelay(vector("welcome")).toOption.get.asInstanceOf[RelayMessage.Welcome],
    DeskGreetingCodec.decodeWelcome(vector("welcome"), 1000).toOption.get.get,
    display,
    DeskSnapshotCodec.decodeWeather(vector("weather_negative")).toOption.get,
    DeskSnapshotCodec.decodeCpu(vector("cpu_memory_zero")).toOption.get,
    DeskMessageCodec.decodeStats(vector("stats_observed")).toOption.get,
    sync
  )
  private def owner(capacity: Int = 16): DeskDelivery = DeskDelivery(greeting, full.deviceBootId, 120, 117, capacity)
  private def parsed(value: DeskDelivery.Reservation): Frame = FrameReader(ByteArrayInputStream(value.bytes.toArray)).read().toOption.get
  private def drainGreeting(value: DeskDelivery): Unit =
    (0 until 6).foreach: _ =>
      val reservation = value.reserve(5000).get
      value.written(reservation, 5000, true)
  private def card(name: String, replay: Boolean = false): DeskDelivery.Card =
    val value = DeskMessageCodec.decodeEvent(vector(name)).toOption.get
    DeskDelivery.Card(
      Tsb3Decoder
        .fromRelay(vector(name).copy(header = vector(name).header.copy(typeCode = TypeCode.fromWire(0x21.toByte))))
        .toOption
        .get
        .asInstanceOf[RelayMessage.Event]
        .record,
      value.relayEpoch,
      value.sourceGeneration,
      value.twitchEpisode,
      value.deadlineRelayMs,
      value.origin,
      replay
    )

  test("DESK card permits zero only for Twitch chat and keeps explicit hold bounds for every kind and origin"):
    val template = card("external_twitch_kind")
    for
      kind <- NotificationKind.values
      origin <- DeskMessageCodec.Origin.values
      hold <- List(0, 1, 300, 301)
    do
      val record = template.record.copy(kind = kind, ttl = DisplayTtl.fromWire(hold))
      def candidate: DeskDelivery.Card = template.copy(
        record = record,
        origin = origin,
        sourceGeneration = if origin == DeskMessageCodec.Origin.Twitch then 3L else 0L,
        twitchEpisode = if origin == DeskMessageCodec.Origin.Twitch then 5L else 0L
      )
      val allowed = origin != DeskMessageCodec.Origin.Unknown &&
        (hold == 1 || hold == 300 || (hold == 0 && kind == NotificationKind.Chat && origin == DeskMessageCodec.Origin.Twitch))
      val clue = s"kind=$kind origin=$origin hold=$hold"
      if allowed then assertEquals(candidate.record.ttl.deciseconds, hold, clue)
      else
        val error = intercept[IllegalArgumentException](candidate)
        assert(error.getMessage.startsWith("requirement failed"), clue)

  test("actual DeviceHub native chat publishes and reserves receiver-default DESK hold with current Twitch provenance"):
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 64))
      hub.installSettings(
        SettingsSnapshot(RuntimeSettings.firstRun("disabled", "synthetic").copy(display = DisplaySettings(true, "generic")), 1L, "startup")
      )
      val caps = TestDevice.FullCaps | Capabilities.fromWire(DeskGreetingCodec.CapabilityBit)
      assert((caps.unsigned & Capabilities.Chat.unsigned) != 0)
      val connection = hub.attach(
        AttachRequest(
          DeviceId("desk-native-chat").toOption.get,
          "127.0.0.1",
          ProtocolVersion.Three,
          SeqNo.Zero,
          caps,
          Channel.buffered[Outbound](128),
          LinkCounters(clock),
          () => (),
          Channel.buffered[Unit](1),
          deskHello = Some(DeskGreetingCodec.HelloTail(0, 0, 55, 1000))
        )
      )
      val desk = hub.desk(connection).get
      try
        (0 until 6).foreach: _ =>
          desk.written(desk.next().get, true)
        val captured = desk.greeting
        assert(captured.display.enabled)
        assert(captured.display.sourceGeneration > 0 && captured.display.twitchEpisode > 0)
        val ready = full.copy(
          identity = captured.identity,
          deviceBootId = 55,
          observedDeviceMs = 1001,
          observedRelayLowerMs = captured.tail.sampledRelayMs,
          grantTotal = 8,
          processedSeq = 0,
          enqueuedSeq = 0,
          displayRevision = captured.display.revision,
          sourceGeneration = captured.display.sourceGeneration,
          twitchEpisode = captured.display.twitchEpisode,
          weatherRevision = captured.weather.revision,
          cpuRevision = captured.cpu.revision,
          pending = Vector.empty
        )
        assertEquals(desk.report(ready), Right(true))
        val receipt = hub.publish(EventRequest.of(NotificationKind.Chat, "native chatter", "paced chat")).toOption.get
        val publication = hub.retainedPublications.last
        assert(publication.capableEligible)
        assertEquals(publication.record.ttl, DisplayTtl.Default)
        val reservation = desk.delivery.reserve(hub.relayClock.monotonicMs).get
        assertEquals(reservation.event, Some(receipt.seq.value))
        val frame = parsed(reservation)
        assertEquals(frame.header.typeCode.value, 0x2b)
        assertEquals(frame.payload.slice(18, 20).toVector, Vector[Byte](0, 0))
        val envelope = DeskMessageCodec.decodeEvent(frame).toOption.get
        assertEquals(envelope.origin, DeskMessageCodec.Origin.Twitch)
        assertEquals(envelope.relayEpoch, publication.relayEpoch)
        assertEquals(envelope.sourceGeneration, captured.display.sourceGeneration)
        assertEquals(envelope.twitchEpisode, captured.display.twitchEpisode)
        assertEquals(envelope.deadlineRelayMs, publication.deadlineRelayMs)
        val classic = frame.copy(header = frame.header.copy(typeCode = TypeCode.fromWire(0x21.toByte)))
        val record = Tsb3Decoder.fromRelay(classic).toOption.get.asInstanceOf[RelayMessage.Event].record
        assertEquals(record.seq, receipt.seq)
        assertEquals(record.kind, NotificationKind.Chat)
        assertEquals(record.ttl, DisplayTtl.Default)
        assertEquals((record.actor, record.text), ("native chatter", "paced chat"))
        desk.delivery.written(reservation, hub.relayClock.monotonicMs, true)
        assert(!desk.delivery.isClosed)
        assertEquals(desk.delivery.pendingCount, 0)
      finally
        desk.close()
        hub.detach(connection, DisconnectReason.PeerClosed)

  test("transport observation counts every writer lane and keeps in-flight and credits separate"):
    val delivery = owner()
    delivery.update(display.copy(revision = 10), 5000)
    assert(delivery.offer(card("external_twitch_kind")))
    assert(delivery.timeResponse(DeskTimeCodec.Response(sync.identity.relayEpoch, sync.identity.connectionId, 1000, 5000)))
    val initial = delivery.transport
    assertEquals(initial.ordered, 1)
    assertEquals(initial.greeting, 6)
    assertEquals(initial.state, 1)
    assertEquals(initial.timeReplies, 1)
    assertEquals(initial.queuedFrames, 9)
    assertEquals(initial.inFlight, 0)
    assertEquals(initial.reservedEvents, 0L)
    val first = delivery.reserve(5000).get
    assertEquals(delivery.transport.queuedFrames, 8)
    assertEquals(delivery.transport.inFlight, 1)
    delivery.written(first, 5000, true)
    assertEquals(delivery.transport.inFlight, 0)
    delivery.close()

  test("connection observation includes auxiliary and final frames outside the ordered stream"):
    val connection = DeskConnection(greeting, full.deviceBootId, 120, 117, 16, () => 5000L, () => ())
    assert(connection.heartbeat(RelayMessage.Stats(StreamStats.Unknown, None)))
    assertEquals(connection.observation(5000).transport.get.auxiliary, 1)
    assertEquals(connection.observation(5000).transport.get.queuedFrames, 7)
    connection.finish(RelayMessage.Bye(ByeCode.ServerShutdown, ByeDetail.Zero, scala.concurrent.duration.Duration.Zero, "synthetic"))
    assertEquals(connection.observation(5000).transport.get.finalFrames, 1)
    connection.close()

  test("session observation refresh follows deferred flush authority and preserves original report age"):
    var now = 5000L
    val wakes = new java.util.concurrent.atomic.AtomicInteger(0)
    val connection = DeskConnection(
      greeting,
      full.deviceBootId,
      120,
      117,
      16,
      () => now,
      () => (),
      observationChanged = () => { val _ = wakes.incrementAndGet() }
    )
    (0 until 5).foreach: _ =>
      val write = connection.next().get
      connection.written(write, true)
    val finalSync = connection.next().get
    now = 5100L
    assertEquals(connection.report(full), Right(false))
    assertEquals(connection.observation(now).report, None)
    val before = wakes.get()
    now = 5200L
    connection.written(finalSync, true)
    assert(wakes.get() > before)
    val accepted = connection.observation(now)
    assertEquals(accepted.report, Some(full))
    assertEquals(accepted.receivedRelayMs, Some(5100L))
    now = 5300L
    assertEquals(connection.report(full), Right(false))
    assertEquals(connection.observation(now).receivedRelayMs, Some(5100L))
    assertEquals(connection.observation(20101L).status, "stale")
    connection.close()
    assertEquals(connection.observation(now).status, "historical")

  test("failed final sync flush cannot expose a deferred report as accepted observation"):
    val connection = DeskConnection(greeting, full.deviceBootId, 120, 117, 16, () => 5000L, () => ())
    (0 until 5).foreach: _ =>
      connection.written(connection.next().get, true)
    val finalSync = connection.next().get
    assertEquals(connection.report(full), Right(false))
    connection.written(finalSync, false)
    assertEquals(connection.observation(5000L).report, None)
    connection.close()

  test("real loopback socket exercises atomic six-frame greeting and retained zero-credit report"):
    val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress)
    val client = Socket(InetAddress.getLoopbackAddress, server.getLocalPort)
    val peer = server.accept()
    try
      peer.setSoTimeout(2000)
      val delivery = owner()
      delivery.update(display.copy(revision = 10), 5000)
      assert(delivery.offer(card("external_twitch_kind")))
      val reader = FrameReader(peer.getInputStream)
      List(0x20, 0x27, 0x28, 0x29, 0x22, 0x2a).foreach: code =>
        val value = delivery.reserve(5000).get
        client.getOutputStream.write(value.bytes.toArray)
        client.getOutputStream.flush()
        delivery.written(value, 5000, true)
        assertEquals(reader.read().toOption.get.header.typeCode.value, code)
      assertEquals(delivery.accept(full, 5100), Right(true))
      val control = delivery.reserve(5200).get
      assertEquals(parsed(control).header.typeCode.value, 0x27)
      delivery.written(control, 5200, true)
      assertEquals(delivery.reserve(5200), None)
      assertEquals(delivery.pendingCount, 1)
      assertEquals(delivery.accept(full, 5200), Right(false))
      assertEquals(delivery.observation(20101), "stale")
      delivery.close()
      assertEquals(delivery.observation(5200), "historical")
    finally
      peer.close()
      client.close()
      server.close()

  test("cumulative reports open exactly one slot; barriers skip suppressed Twitch without credit"):
    val delivery = owner()
    drainGreeting(delivery)
    assertEquals(delivery.accept(full, 5100), Right(true))
    assert(delivery.offer(card("external_twitch_kind")))
    assert(delivery.offer(card("twitch_event")))
    val released = DeskReportCodec.decode(vector("report_retained_active")).toOption.get
    assertEquals(delivery.accept(released, 5200), Right(true))
    assertEquals(delivery.accept(released, 5200), Right(false))
    val external = delivery.reserve(5300).get
    assertEquals(DeskMessageCodec.decodeEvent(parsed(external)).toOption.get.origin, DeskMessageCodec.Origin.External)
    delivery.written(external, 5300, true)
    val barrier = delivery.reserve(5300).get
    assertEquals(DeskControlCodec.decodeBarrier(parsed(barrier)).toOption.get.throughSeq, 122L)
    delivery.written(barrier, 5300, true)
    assertEquals(delivery.pendingCount, 0)
    assertEquals(delivery.reserve(5300), None)
    assertEquals(delivery.accept(released.copy(revision = 3, grantTotal = 10), 5300), Left("report_credit_out_of_range"))
    assertEquals(delivery.accept(released.copy(revision = 3, processedSeq = 123), 5300), Left("report_cursor_out_of_range"))

  test("report conflicts wrong identities regressions and future revisions cannot alter authority"):
    val progress = DeskSessionProgress(sync, full.deviceBootId, 120, 117)
    assertEquals(progress.accept(full, 5100), Left("report_before_sync"))
    progress.synchronizedAt(5000)
    assertEquals(progress.accept(full, 5100), Right(true))
    List(
      full.copy(deviceBootId = 2),
      full.copy(identity = DeskControlCodec.Identity(3, 4)),
      full.copy(observedRelayLowerMs = 6000),
      full.copy(displayRevision = 10),
      full.copy(sourceGeneration = 2),
      full.copy(revision = 1, observedDeviceMs = 1300)
    ).foreach: value =>
      assert(progress.accept(value, 5200).isLeft)
      assertEquals(progress.report, Some(full))
    val next = full.copy(revision = 2, observedDeviceMs = 1300)
    assertEquals(progress.accept(next, 5200), Right(true))
    assertEquals(progress.accept(full, 5200), Right(false))
    assert(progress.accept(next.copy(revision = 3, observedDeviceMs = 1299), 5200).isLeft)

  test("control floods preserve oldest deadline and zero-credit source progress is independent"):
    val delivery = owner()
    drainGreeting(delivery)
    assertEquals(delivery.accept(full, 5100), Right(true))
    delivery.update(display.copy(revision = 10, twitchEpisode = 6), 6000)
    delivery.update(display.copy(revision = 11, twitchEpisode = 7), 10000)
    delivery.update(greeting.cpu.copy(revision = 7))
    val control = delivery.reserve(10000).get
    assertEquals(DeskControlCodec.decodeDisplay(parsed(control)).toOption.get.twitchEpisode, 7L)
    delivery.written(control, 10000, true)
    val cpu = delivery.reserve(10000).get
    assertEquals(parsed(cpu).header.typeCode.value, 0x29)
    delivery.written(cpu, 10000, true)
    assertEquals(delivery.reserve(11000), None)
    assert(delivery.isClosed)

  test("initial report deadline starts after flush; time response samples reservation; overload and write failure close"):
    val delivery = owner()
    assert(delivery.timeResponse(DeskTimeCodec.Response(sync.identity.relayEpoch, sync.identity.connectionId, 9, 1)))
    drainGreeting(delivery)
    val response = delivery.reserve(9000).get
    assertEquals(DeskTimeCodec.decodeResponse(parsed(response)).toOption.get.sampledRelayMs, 9000L)
    delivery.written(response, 9000, true)
    assertEquals(delivery.reserve(10000), None)
    assert(delivery.isClosed)
    val overflow = owner(1)
    assert(overflow.offer(card("external_twitch_kind")))
    assert(!overflow.offer(card("twitch_event")))
    assert(overflow.isClosed)
    val failed = owner()
    val reservation = failed.reserve(1).get
    failed.written(reservation, 1, false)
    assert(failed.isClosed)

  test("expired external cards advance without credit and STATS revisions cannot reset within a connection"):
    val delivery = owner()
    drainGreeting(delivery)
    assertEquals(delivery.accept(full, 5100), Right(true))
    assert(delivery.offer(card("external_twitch_kind")))
    val expired = delivery.reserve(65000).get
    assertEquals(DeskControlCodec.decodeBarrier(parsed(expired)).toOption.get.throughSeq, 121L)
    delivery.written(expired, 65000, true)
    assertEquals(delivery.pendingCount, 0)
    delivery.update(display.copy(revision = 10, sourceGeneration = 4), 65000)
    val _ = intercept[IllegalArgumentException](delivery.update(greeting.stats.copy(sourceGeneration = 4, revision = 1)))
    delivery.update(greeting.stats.copy(sourceGeneration = 4, revision = 8))
    val control = delivery.reserve(65000).get
    delivery.written(control, 65000, true)
    val stats = delivery.reserve(65000).get
    val decoded = DeskMessageCodec.decodeStats(parsed(stats)).toOption.get
    assertEquals(decoded.sourceGeneration, 4L)
    assertEquals(decoded.revision, 8L)
    delivery.written(stats, 65000, true)
    val _ = intercept[IllegalArgumentException](delivery.update(decoded.copy(availability = DeskMessageCodec.Availability.Unknown)))

  test("display fences precede generation STATS at every non-control rotation and during in-flight updates"):
    (0 until 4).foreach: prior =>
      val delivery = owner()
      drainGreeting(delivery)
      assertEquals(delivery.accept(full, 5100), Right(true))
      if prior == 1 then delivery.update(greeting.weather.copy(revision = 5))
      if prior == 2 then delivery.update(greeting.cpu.copy(revision = 7))
      if prior == 3 then delivery.update(greeting.stats.copy(revision = 8))
      if prior != 0 then
        val previous = delivery.reserve(5200).get
        delivery.update(display.copy(revision = 10, sourceGeneration = 4, twitchEpisode = 6), 5200)
        delivery.update(greeting.stats.copy(sourceGeneration = 4, revision = 9, availability = DeskMessageCodec.Availability.Unavailable))
        delivery.written(previous, 5200, true)
      else
        delivery.update(display.copy(revision = 10, sourceGeneration = 4, twitchEpisode = 6), 5200)
        delivery.update(greeting.stats.copy(sourceGeneration = 4, revision = 9, availability = DeskMessageCodec.Availability.Unknown))
      assert(delivery.timeResponse(DeskTimeCodec.Response(sync.identity.relayEpoch, sync.identity.connectionId, 9, 1)))
      val fence = delivery.reserve(5300).get
      assertEquals(parsed(fence).header.typeCode.value, 0x27)
      delivery.written(fence, 5300, true)
      val time = delivery.reserve(5300).get
      delivery.written(time, 5300, true)
      val reset = delivery.reserve(5300).get
      assertEquals(DeskMessageCodec.decodeStats(parsed(reset)).toOption.get.sourceGeneration, 4L)
      delivery.written(reset, 5300, true)
      assertEquals(delivery.reserve(5300), None)

  test("unsent and partially written events never establish report authority; completion reconciles fast-peer reports"):
    List(true, false).foreach: success =>
      val delivery = owner()
      drainGreeting(delivery)
      val released = DeskReportCodec.decode(vector("report_retained_active")).toOption.get
      assertEquals(delivery.accept(full, 5100), Right(true))
      assertEquals(delivery.accept(released, 5200), Right(true))
      assert(delivery.offer(card("external_twitch_kind")))
      val event = delivery.reserve(5300).get
      val observed = released.copy(revision = 3, processedSeq = 121, enqueuedSeq = 121, grantTotal = 2)
      assertEquals(delivery.accept(observed, 5300), Right(false))
      assertEquals(delivery.report, Some(released))
      val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress)
      val writer = Socket(InetAddress.getLoopbackAddress, server.getLocalPort)
      val reader = server.accept()
      try
        writer.getOutputStream.write(event.bytes.take(20).toArray)
        writer.getOutputStream.flush()
        assertEquals(reader.getInputStream.readNBytes(20).toVector, event.bytes.take(20))
        assertEquals(delivery.accept(observed, 5300), Right(false))
        assertEquals(delivery.report, Some(released))
        if success then
          writer.getOutputStream.write(event.bytes.drop(20).toArray)
          writer.getOutputStream.flush()
          assertEquals(reader.getInputStream.readNBytes(event.bytes.size - 20).toVector, event.bytes.drop(20))
        delivery.written(event, 5400, success)
        assertEquals(delivery.report, Some(if success then observed else released))
        assertEquals(delivery.isClosed, !success)
      finally
        reader.close()
        writer.close()
        server.close()

  test("barriers establish terminal progress only, never enqueue or replacement credits"):
    val delivery = owner()
    drainGreeting(delivery)
    assertEquals(delivery.accept(full, 5100), Right(true))
    assert(delivery.offer(DeskDelivery.Barrier(121)))
    val barrier = delivery.reserve(5200).get
    delivery.written(barrier, 5200, true)
    assertEquals(
      delivery.accept(full.copy(revision = 2, processedSeq = 121, enqueuedSeq = 121), 5300),
      Left("report_enqueue_not_delivered")
    )
    assertEquals(delivery.accept(full.copy(revision = 2, processedSeq = 121, grantTotal = 1), 5300), Left("report_credit_out_of_range"))
    assertEquals(delivery.accept(full.copy(revision = 2, processedSeq = 121), 5300), Right(true))

  test("coalesced nonexistent control tuples are rejected while actual delayed and in-flight controls reconcile"):
    val delivery = owner()
    drainGreeting(delivery)
    assertEquals(delivery.accept(full, 5100), Right(true))
    delivery.update(display.copy(revision = 10, twitchEpisode = 6), 5200)
    delivery.update(display.copy(revision = 11, sourceGeneration = 4, twitchEpisode = 7), 5200)
    assertEquals(
      delivery.accept(full.copy(revision = 2, displayRevision = 10, twitchEpisode = 6), 5300),
      Left("report_control_not_delivered")
    )
    val fence = delivery.reserve(5300).get
    val applied = full.copy(revision = 2, displayRevision = 11, sourceGeneration = 4, twitchEpisode = 7)
    assertEquals(delivery.accept(applied, 5300), Right(false))
    assertEquals(delivery.report, Some(full))
    delivery.written(fence, 5400, true)
    assertEquals(delivery.report, Some(applied))
    assertEquals(delivery.accept(applied.copy(revision = 3, sourceGeneration = 3), 5500), Left("report_control_not_delivered"))

  test("exact initial and oldest-control deadlines cannot be erased by report-before-writer callbacks"):
    List(9999L, 10000L, 10001L).foreach: now =>
      val delivery = owner()
      drainGreeting(delivery)
      val result = delivery.accept(full, now)
      assertEquals(result, if now < 10000 then Right(true) else Left("initial_report_timeout"))
      assertEquals(delivery.isClosed, now >= 10000)
    List(10999L, 11000L, 11001L).foreach: now =>
      val delivery = owner()
      drainGreeting(delivery)
      assertEquals(delivery.accept(full, 5100), Right(true))
      delivery.update(display.copy(revision = 10), 6000)
      val fence = delivery.reserve(6000).get
      delivery.written(fence, 6000, true)
      val result = delivery.accept(full.copy(revision = 2, displayRevision = 10), now)
      assertEquals(result, if now < 11000 then Right(true) else Left("control_application_timeout"))
      assertEquals(delivery.isClosed, now >= 11000)

  test("bounded delivered history closes on exhaustion rather than accepting fabricated intermediate revisions"):
    val delivery = owner()
    drainGreeting(delivery)
    assertEquals(delivery.accept(full, 5100), Right(true))
    (10L to 137L).foreach: revision =>
      delivery.update(display.copy(revision = revision), 5200)
      val fence = delivery.reserve(5200).get
      delivery.written(fence, 5200, true)
    assert(delivery.isClosed)
    assertEquals(delivery.accept(full, 5200), Left("session_closed"))

  test("history acknowledgment prunes bounded evidence and fairness survives continuously replenished lanes"):
    val delivery = owner()
    drainGreeting(delivery)
    assertEquals(delivery.accept(full, 5100), Right(true))
    (10L to 150L).foreach: revision =>
      delivery.update(display.copy(revision = revision), 5200)
      val fence = delivery.reserve(5200).get
      delivery.written(fence, 5200, true)
      assertEquals(delivery.accept(full.copy(revision = revision, displayRevision = revision), 5200), Right(true))
    assert(!delivery.isClosed)
    val seen = scala.collection.mutable.ArrayBuffer.empty[Int]
    (0 until 12).foreach: step =>
      if step % 3 == 0 then
        assert(delivery.timeResponse(DeskTimeCodec.Response(sync.identity.relayEpoch, sync.identity.connectionId, step, 1)))
        delivery.update(greeting.cpu.copy(revision = 7L + step))
        assert(delivery.offer(DeskDelivery.Barrier(121L + step)))
      val frame = delivery.reserve(5300).get
      val _ = seen.addOne(parsed(frame).header.typeCode.value)
      delivery.written(frame, 5300, true)
    assertEquals(seen.toList, List.fill(4)(List(0x26, 0x29, 0x2c)).flatten)

  test("mixed delivered events and skipped ranges preserve enqueue membership; delayed completion cannot erase deadlines"):
    val delivery = owner()
    drainGreeting(delivery)
    val released = DeskReportCodec.decode(vector("report_retained_active")).toOption.get
    assertEquals(delivery.accept(full, 5100), Right(true))
    assertEquals(delivery.accept(released, 5200), Right(true))
    assert(delivery.offer(card("external_twitch_kind")))
    assert(delivery.offer(card("twitch_event")))
    (0 until 2).foreach: _ =>
      val frame = delivery.reserve(5300).get
      delivery.written(frame, 5300, true)
    assertEquals(
      delivery.accept(released.copy(revision = 3, processedSeq = 122, enqueuedSeq = 122), 5400),
      Left("report_enqueue_not_delivered")
    )
    assertEquals(delivery.accept(released.copy(revision = 3, processedSeq = 122, enqueuedSeq = 121, grantTotal = 2), 5400), Right(true))
    delivery.update(display.copy(revision = 10), 6000)
    val fence = delivery.reserve(6000).get
    val before = delivery.report
    assertEquals(delivery.accept(before.get.copy(revision = 4, displayRevision = 10), 10999), Right(false))
    delivery.written(fence, 11000, true)
    assert(delivery.isClosed)
    assertEquals(delivery.report, before)
