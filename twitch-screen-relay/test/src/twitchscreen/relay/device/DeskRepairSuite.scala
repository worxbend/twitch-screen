package twitchscreen.relay.device

import java.io.{ByteArrayInputStream, Closeable}
import java.nio.file.{Files, Path}
import java.util.HexFormat
import scala.jdk.CollectionConverters.*
import twitchscreen.relay.protocol.*

class DeskRepairSuite extends munit.FunSuite:
  private def vector(name: String): Frame =
    val path = Iterator
      .iterate(Path.of("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("protocol/desk-v1-vectors.tsv"))
      .find(Files.isRegularFile(_))
      .get
    val bytes = HexFormat.of().parseHex(Files.readAllLines(path).asScala.find(_.startsWith(name + "\t")).get.split("\t")(3))
    FrameReader(ByteArrayInputStream(bytes)).read().toOption.get
  private val full = DeskReportCodec.decode(vector("report_full_zero_credit")).toOption.get
  private val display = DeskControlCodec.decodeDisplay(vector("display_cpu")).toOption.get
  private val greeting = DeskDelivery.Greeting(
    Tsb3Decoder.fromRelay(vector("welcome")).toOption.get.asInstanceOf[RelayMessage.Welcome],
    DeskGreetingCodec.decodeWelcome(vector("welcome"), 1000).toOption.flatten.get,
    display,
    DeskSnapshotCodec.decodeWeather(vector("weather_negative")).toOption.get,
    DeskSnapshotCodec.decodeCpu(vector("cpu_memory_zero")).toOption.get,
    DeskMessageCodec.decodeStats(vector("stats_observed")).toOption.get,
    DeskControlCodec.decodeSync(vector("sync_end")).toOption.get
  )
  private def drain(owner: DeskConnection): Unit = (0 until 6).foreach: _ =>
    owner.written(owner.next().get, true)

  test("heartbeat participates in fair scheduling under continuously replenished barriers and preserves control priority"):
    var now = 5000L
    var closed = false
    val owner = DeskConnection(
      greeting,
      full.deviceBootId,
      120,
      117,
      32,
      () => now,
      new Closeable:
        def close(): Unit = closed = true
    )
    drain(owner)
    now = 5100
    assertEquals(owner.report(full), Right(true))
    assert(owner.heartbeat(RelayMessage.Ping(Token.fromWire(1))))
    val codes = (121L to 140L).map: seq =>
      assert(owner.offer(DeskDelivery.Barrier(seq)))
      val frame = owner.next().get
      owner.written(frame, true)
      frame.bytes(3) & 0xff
    assert(codes.contains(0x23), codes.toString)
    assert(!closed)
    owner.close()

  test("a deferred accepted report refreshes liveness from arrival, not completion; duplicates do not renew it"):
    var now = 5000L
    var closed = false
    val owner = DeskConnection(
      greeting,
      full.deviceBootId,
      120,
      117,
      32,
      () => now,
      new Closeable:
        def close(): Unit = closed = true
    )
    drain(owner)
    now = 5100
    assertEquals(owner.report(full), Right(true))
    val release = DeskReportCodec.decode(vector("report_retained_active")).toOption.get
    now = 5200
    assertEquals(owner.report(release), Right(true))
    assert(owner.offer(DeskDelivery.Barrier(121)))
    now = 19000
    val frame = owner.next().get
    val report = release.copy(revision = 3, processedSeq = 121)
    assertEquals(owner.report(report), Right(false))
    now = 19100
    owner.written(frame, true)
    assertEquals(owner.delivery.report, Some(report))
    now = 20200
    assert(owner.offer(DeskDelivery.Barrier(122)))
    val later = owner.next()
    assert(later.isDefined)
    assert(!closed)
    owner.written(later.get, true)
    assertEquals(owner.report(report), Right(false))
    now = 34000
    assertEquals(owner.next(), None)
    assert(closed)

  test("partial control acknowledgment retires only applied acceptance deadlines"):
    val delivery = DeskDelivery(greeting, full.deviceBootId, 120, 117, 16)
    (0 until 6).foreach: _ =>
      val frame = delivery.reserve(5000).get
      delivery.written(frame, 5000, true)
    assertEquals(delivery.accept(full, 5100), Right(true))
    delivery.update(display.copy(revision = 10), 6000)
    val first = delivery.reserve(6000).get
    delivery.written(first, 6000, true)
    delivery.update(display.copy(revision = 11), 10000)
    val second = delivery.reserve(10000).get
    delivery.written(second, 10000, true)
    assertEquals(delivery.accept(full.copy(revision = 2, displayRevision = 10), 10001), Right(true))
    assertEquals(delivery.nextDeadline(11000), Some(15000L))
    assertEquals(delivery.reserve(11000), None)
    assert(!delivery.isClosed)
    assertEquals(delivery.reserve(15000), None)
    assert(delivery.isClosed)

  test("resumed enqueue bootstrap is bounded by processed authority and closes after the first valid report"):
    List(0L, 117L, 120L).foreach: enqueued =>
      val delivery = DeskDelivery(greeting, full.deviceBootId, 120, 0, 16, bootstrapEnqueued = true)
      (0 until 6).foreach: _ =>
        val frame = delivery.reserve(5000).get
        delivery.written(frame, 5000, true)
      val initial = full.copy(enqueuedSeq = enqueued)
      assertEquals(delivery.accept(initial.copy(deviceBootId = 2), 5100), Left("report_identity_mismatch"))
      assertEquals(delivery.accept(initial, 5100), Right(true))
      assertEquals(delivery.accept(initial, 5200), Right(false))
      if enqueued < 120 then
        assertEquals(delivery.accept(initial.copy(revision = 2, enqueuedSeq = enqueued + 1), 5300), Left("report_enqueue_not_delivered"))
      assert(delivery.offer(DeskDelivery.Barrier(121)))
      val barrier = delivery.reserve(5400).get
      delivery.written(barrier, 5400, true)
      assertEquals(
        delivery.accept(initial.copy(revision = 2, processedSeq = 121, enqueuedSeq = 121), 5500),
        Left("report_enqueue_not_delivered")
      )
      assertEquals(delivery.accept(initial.copy(revision = 2, processedSeq = 121), 5500), Right(true))

  test("deferred control and source observations commit their original receipt once; failed writes do not renew liveness"):
    List("control", "cpu", "weather").foreach: lane =>
      List(true, false).foreach: success =>
        val delivery = DeskDelivery(greeting, full.deviceBootId, 120, 117, 16)
        (0 until 6).foreach: _ =>
          val frame = delivery.reserve(5000).get
          delivery.written(frame, 5000, true)
        assertEquals(delivery.accept(full, 5100), Right(true))
        val observed = lane match
          case "control" =>
            delivery.update(display.copy(revision = 10), 5200)
            full.copy(revision = 2, displayRevision = 10)
          case "cpu" =>
            delivery.update(greeting.cpu.copy(revision = 7))
            full.copy(revision = 2, cpuRevision = 7)
          case _ =>
            delivery.update(greeting.weather.copy(revision = 5))
            full.copy(revision = 2, weatherRevision = 5)
        val frame = delivery.reserve(5300).get
        assertEquals(delivery.accept(observed, 5400), Right(false))
        assertEquals(delivery.accept(observed, 5500), Right(false))
        delivery.written(frame, 5600, success)
        assertEquals(delivery.reportAcceptedAt, Some(if success then 5400L else 5100L))
        assertEquals(delivery.report, Some(if success then observed else full))
        assertEquals(delivery.isClosed, !success)
