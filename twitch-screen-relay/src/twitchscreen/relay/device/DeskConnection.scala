package twitchscreen.relay.device

import java.io.{Closeable, IOException}
import scala.collection.mutable
import twitchscreen.relay.protocol.*

/** Installed per-attachment scheduler. All hub admissions are bounded and nonblocking; only the session writer waits or performs I/O. */
private[device] final class DeskConnection(
    val greeting: DeskDelivery.Greeting,
    bootId: Long,
    baselineProcessed: Long,
    baselineEnqueued: Long,
    capacity: Int,
    now: () => Long,
    transport: Closeable,
    bootstrapEnqueued: Boolean = false,
    observationChanged: () => Unit = () => ()
):
  val delivery = DeskDelivery(greeting, bootId, baselineProcessed, baselineEnqueued, capacity, bootstrapEnqueued)
  private val auxiliary = mutable.Queue.empty[Vector[Byte]]
  private var finalFrame: Option[Vector[Byte]] = None
  private var stopping = false
  private var completedGreeting = 0
  private var lastReportAt: Option[Long] = None
  private var pendingWeather: Option[DeskSnapshotCodec.Weather] = None
  private var pendingCpu: Option[DeskSnapshotCodec.Cpu] = None
  private var weatherSentAt = Long.MinValue / 2
  private var cpuSentAt = Long.MinValue / 2
  private var weatherRevision = greeting.weather.revision
  private var cpuRevision = greeting.cpu.revision

  def update(snapshot: PublicationSnapshot): Unit = synchronized:
    if !stopping then
      val identity = greeting.identity
      delivery.update(DeskProjection.display(snapshot, identity), now())
      if snapshot.weather.get.revision > weatherRevision then
        pendingWeather = Some(DeskProjection.weather(snapshot, identity))
        weatherRevision = snapshot.weather.get.revision
      if snapshot.cpu.get.revision > cpuRevision then
        pendingCpu = Some(DeskProjection.cpu(snapshot, identity))
        cpuRevision = snapshot.cpu.get.revision
      delivery.update(DeskProjection.stats(snapshot, identity))
      changed()

  def offer(value: DeskDelivery.Ordered): Boolean = synchronized:
    val accepted = !stopping && delivery.offer(value)
    changed()
    accepted

  def report(value: DeskReportCodec.Report): Either[String, Boolean] = synchronized:
    val sampled = now()
    val result =
      if lastReportAt.exists(sampled >= _ + 15000) then
        delivery.close()
        Left("report_timeout")
      else delivery.accept(value, sampled)
    lastReportAt = delivery.reportAcceptedAt
    changed()
    observationChanged()
    result

  def time(probe: Long): Boolean = synchronized:
    val accepted =
      !stopping && delivery.timeResponse(DeskTimeCodec.Response(greeting.identity.relayEpoch, greeting.identity.connectionId, probe, now()))
    changed()
    accepted

  def heartbeat(message: RelayMessage): Boolean = synchronized:
    if stopping || auxiliary.size >= 4 then false
    else
      auxiliary.enqueue(Tsb3Encoder.toDevice(message).toVector)
      changed()
      true

  def finish(message: RelayMessage.Bye): Unit = synchronized:
    finalFrame = Some(Tsb3Encoder.toDevice(message).toVector)
    stopping = true
    delivery.close()
    notifyAll()

  def close(): Unit = synchronized:
    stopping = true
    delivery.close()
    notifyAll()
    closeTransport()

  /** No polling: publication/report/close wakes the monitor; finite waits target exact absolute application/expiry/coalescing deadlines. */
  def next(): Option[DeskConnection.Write] = synchronized:
    var result = Option.empty[DeskConnection.Write]
    while result.isEmpty && !stopping do
      val sampled = now()
      if lastReportAt.exists(sampled >= _ + 15000) then delivery.close()
      flushSources(sampled)
      result = delivery
        .reserve(sampled, auxiliary.headOption)
        .map: value =>
          if value.auxiliary then
            val _ = auxiliary.dequeue()
          DeskConnection.Write(value.bytes, Some(value))
      if delivery.isClosed then
        stopping = true
        closeTransport()
      else if result.isEmpty then
        val deadlines = delivery.nextDeadline(sampled).toList ++ lastReportAt.map(_ + 15000).toList ++
          pendingWeather.map(_ => weatherSentAt + 200).toList ++ pendingCpu.map(_ => cpuSentAt + 200).toList
        deadlines.minOption match
          case Some(deadline) => wait(math.max(1L, deadline - sampled))
          case None           => wait()
    if result.isDefined then
      observationChanged()
      result
    else
      finalFrame.map: bytes =>
        finalFrame = None
        DeskConnection.Write(bytes, None)

  def written(value: DeskConnection.Write, success: Boolean): Unit = synchronized:
    value.reservation.foreach: reservation =>
      delivery.written(reservation, now(), success)
      if completedGreeting < 6 then completedGreeting += 1
      val code = value.bytes(3) & 0xff
      if code == 0x28 then weatherSentAt = now()
      if code == 0x29 then cpuSentAt = now()
    // Complete flush may commit a newer deferred report; retain its original receipt time.
    lastReportAt = delivery.reportAcceptedAt
    if !success || delivery.isClosed then
      stopping = true
      closeTransport()
    notifyAll()
    observationChanged()

  /** One session-locked sample; no ACK-derived FIFO or receipt-based freshness substitution. */
  def observation(nowMs: Long): DeskObservation = synchronized:
    val resident = delivery.transport
    val pressure = resident.copy(
      state = resident.state + (if stopping then 0 else pendingWeather.size + pendingCpu.size),
      auxiliary = if stopping then 0 else auxiliary.size,
      finalFrames = finalFrame.size
    )
    DeskObservation(delivery.report, delivery.reportAcceptedAt, delivery.observation(nowMs), delivery.pendingCount, Some(pressure))

  private def flushSources(sampled: Long): Unit =
    if completedGreeting >= 6 then
      if pendingWeather.isDefined && sampled >= weatherSentAt + 200 then
        delivery.update(pendingWeather.get)
        pendingWeather = None
      if pendingCpu.isDefined && sampled >= cpuSentAt + 200 then
        delivery.update(pendingCpu.get)
        pendingCpu = None

  private def changed(): Unit =
    notifyAll()
    observationChanged()
    if delivery.isClosed then closeTransport()
  private def closeTransport(): Unit =
    try transport.close()
    catch case _: IOException => ()

private[device] object DeskConnection:
  final case class Write(bytes: Vector[Byte], reservation: Option[DeskDelivery.Reservation])
