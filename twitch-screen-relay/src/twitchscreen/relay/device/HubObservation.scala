package twitchscreen.relay.device

import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

/** Internal authoritative input for the admin projection, not the public AdminSnapshot DTO. Captured publication state shares one hub
  * transaction. Device observations remain independently sampled. No observer callback or browser I/O runs in the actor.
  */
/** Resident writer lanes, not a device FIFO. The in-flight frame is separate from queued frames; reserved admissions are cumulative. */
private[device] final case class TransportObservation(
    ordered: Int,
    greeting: Int,
    state: Int,
    timeReplies: Int,
    auxiliary: Int,
    finalFrames: Int,
    inFlight: Int,
    reservedEvents: Long
):
  def queuedFrames: Int = ordered + greeting + state + timeReplies + auxiliary + finalFrames

private[device] final case class DeskObservation(
    report: Option[twitchscreen.relay.protocol.DeskReportCodec.Report],
    receivedRelayMs: Option[Long],
    status: String,
    transportQueuedOrderedFrames: Int,
    transport: Option[TransportObservation] = None
)
private[device] final case class HubDeviceObservation(
    link: DeviceLink,
    caps: Long,
    firmwareVersion: String,
    desk: Option[DeskObservation],
    connected: Boolean = true,
    lastSeenRelayMs: Long = 0L,
    disconnectedRelayMs: Option[Long] = None,
    classicQueuedFrames: Int = 0
)

private[relay] final case class HubObservation(
    revision: Long,
    capturedAt: Instant,
    publication: PublicationSnapshot,
    links: List[DeviceLink],
    devices: Vector[HubDeviceObservation] = Vector.empty,
    charts: Option[Charts_OUT] = None
)

/** One latest replacement and one wake permit per observer. An unread browser never accumulates a history or blocks a producer. */
private[relay] final class HubSubscription private[device] (release: () => Unit) extends AutoCloseable:
  private val pending = AtomicReference[Option[HubObservation]](None)
  private val closed = AtomicBoolean(false)
  private val wake = Semaphore(0)

  private[device] def offer(value: HubObservation): Unit =
    if !closed.get() && pending.getAndSet(Some(value)).isEmpty then wake.release()
    if closed.get() then pending.set(None)

  /** One consumer per subscription. Interruption cancels the wait; the feed owner must close in finally. */
  def take(): Option[HubObservation] =
    wake.acquire()
    if closed.get() then None else pending.getAndSet(None)

  def takeWithin(waitMs: Long): Option[HubObservation] =
    if wake.tryAcquire(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS) then if closed.get() then None else pending.getAndSet(None)
    else None
  def isClosed: Boolean = closed.get()

  private[device] def poll(): Option[HubObservation] =
    if wake.tryAcquire() then if closed.get() then None else pending.getAndSet(None)
    else None

  override def close(): Unit =
    if closed.compareAndSet(false, true) then
      pending.set(None)
      release()
      wake.release()

/** Register and capture under the same lock as refresh. Limits are internal admission limits, not yet proof of HTTP SSE enforcement. */
private[device] final class HubObservers:
  private var current: Option[HubObservation] = None
  private var subscribers = Map.empty[HubSubscription, InetAddress]
  private var stopped = false
  private var capturedNanos = System.nanoTime()
  private val charts = new AdminChartHistory

  /** Absolute observation transitions only: source/card expiry, report staleness and historical eviction. No API polling. */
  private[device] def nextDeadline: Option[Long] = synchronized:
    current.flatMap: value =>
      val now = value.publication.sampledRelayMs
      val sources = value.publication.cpuDeadlineRelayMs.toList ++ value.publication.weatherDeadlineRelayMs.toList
      val cards = value.publication.retained.map(_.deadlineRelayMs)
      val devices = value.devices.flatMap: device =>
        device.disconnectedRelayMs.map(_ + 900000L).toList ++
          Option
            .when(device.connected)(device.desk.flatMap(_.report).filter(_.observedRelayLowerMs > 0).map(_.observedRelayLowerMs + 15001L))
            .flatten
            .toList
      val nextBucket = Option.when(value.publication.settings.isDefined)((now / 5000L + 1L) * 5000L)
      (sources ++ cards ++ devices ++ nextBucket).filter(_ > now).minOption

  /** Refresh wakes and recalculates this wait; only a reached deadline asks the actor to recapture. Scope interruption cancels it. */
  private[device] def awaitDeadline(): Boolean = synchronized:
    var due = false
    while !stopped && !due do
      nextDeadline match
        case None => wait()
        case Some(deadline) =>
          val elapsed = math.max(0L, (System.nanoTime() - capturedNanos) / 1000000L)
          val remaining = deadline - current.get.publication.sampledRelayMs - elapsed
          if remaining <= 0 then due = true else wait(remaining)
    !stopped

  private def retainDevices(publication: PublicationSnapshot, live: Vector[HubDeviceObservation]): Vector[HubDeviceObservation] =
    val now = publication.sampledRelayMs
    val previous = current.filter(_.publication.relayEpoch == publication.relayEpoch).toVector.flatMap(_.devices)
    val ids = live.map(_.link.device.value).toSet
    val historical = previous
      .filterNot(device => ids.contains(device.link.device.value))
      .map: device =>
        device.copy(
          connected = false,
          disconnectedRelayMs = device.disconnectedRelayMs.orElse(Some(now)),
          desk = device.desk.map(_.copy(status = "historical"))
        )
    val boundedLive = live.take(64).map(_.copy(connected = true, lastSeenRelayMs = now, disconnectedRelayMs = None))
    val retained = historical
      .filter(_.disconnectedRelayMs.exists(at => now >= at && now - at < 900000L))
      .sortBy(device => (-device.lastSeenRelayMs, device.link.device.value))
    boundedLive ++ retained.take(64 - boundedLive.size)

  def refresh(
      capturedAt: Instant,
      publication: PublicationSnapshot,
      links: List[DeviceLink],
      devices: Vector[HubDeviceObservation] = Vector.empty
  ): Unit = synchronized:
    if !stopped then
      val sameEpoch = current.exists(_.publication.relayEpoch == publication.relayEpoch)
      if sameEpoch && current.exists(_.revision == 0xffffffffL) then stop()
      else
        val revision = if sameEpoch then current.get.revision + 1L else 1L
        val bounded = publication.copy(retained = publication.retained.takeRight(80))
        val captured = HubObservation(revision, capturedAt, bounded, links.take(64), retainDevices(bounded, devices))
        val value = captured.copy(charts = Some(charts.capture(captured)))
        current = Some(value)
        capturedNanos = System.nanoTime()
        notifyAll()
        subscribers.keys.foreach(_.offer(value))

  def subscribe(remote: InetAddress): Either[String, (HubObservation, HubSubscription)] = synchronized:
    if stopped then Left("observer_stopped")
    else if subscribers.size >= 8 || subscribers.values.count(_ == remote) >= 2 then Left("observer_limit")
    else
      current
        .toRight("observer_unavailable")
        .map: captured =>
          lazy val subscription: HubSubscription = new HubSubscription(() => remove(subscription))
          subscribers = subscribers.updated(subscription, remote)
          (captured, subscription)

  private def remove(value: HubSubscription): Unit = synchronized:
    subscribers = subscribers.removed(value)

  def snapshot: Option[HubObservation] = synchronized(current)
  private[device] def size: Int = synchronized(subscribers.size)

  def stop(): Unit =
    val closing = synchronized:
      stopped = true
      notifyAll()
      val result = subscribers.keys.toList
      subscribers = Map.empty
      result
    closing.foreach(_.close())
