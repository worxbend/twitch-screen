package twitchscreen.relay.device

import scala.collection.mutable
import twitchscreen.relay.protocol.*

/** Bounded, nonblocking DESK delivery owner. The hub supplies one coherent greeting and ordered publication references; a sole writer
  * reserves/encodes one frame immediately before I/O, then confirms complete flush or closes. Socket work never runs under this monitor.
  * DeviceSession installs this owner behind the explicit application-pairing gate; production advertisement remains disabled.
  */
private[device] final class DeskDelivery(
    greeting: DeskDelivery.Greeting,
    bootId: Long,
    baselineProcessed: Long,
    baselineEnqueued: Long,
    eventCapacity: Int,
    bootstrapEnqueued: Boolean = false
):
  import DeskDelivery.*
  require(eventCapacity > 0)
  private val progress = DeskSessionProgress(greeting.sync, bootId, baselineProcessed, baselineEnqueued, bootstrapEnqueued)
  private val events = mutable.Queue.empty[Ordered]
  private val replies = mutable.Queue.empty[DeskTimeCodec.Response]
  private var display = greeting.display
  private var weather = greeting.weather
  private var cpu = greeting.cpu
  private var stats = greeting.stats
  private val dirty = Array.fill(4)(false)
  private var stateIndex = 0
  private var workIndex = 0
  private var greetingIndex = 0
  private var queuedThrough = baselineProcessed
  private var inFlight: Option[Reservation] = None
  private var deferredReport: Option[(DeskReportCodec.Report, Long)] = None
  private var acceptedReportAt: Option[Long] = None
  def reportAcceptedAt: Option[Long] = synchronized(acceptedReportAt)
  private var closed = false

  def report: Option[DeskReportCodec.Report] = synchronized(progress.report)
  def pendingCount: Int = synchronized(events.size)
  def transport: TransportObservation = synchronized:
    if closed then TransportObservation(0, 0, 0, 0, 0, 0, inFlight.size, progress.reservedEvents)
    else
      TransportObservation(
        events.size,
        6 - greetingIndex,
        dirty.count(identity),
        replies.size,
        0,
        0,
        inFlight.size,
        progress.reservedEvents
      )
  def isClosed: Boolean = synchronized(closed)
  def observation(nowMs: Long): String = synchronized(progress.observation(nowMs, !closed))
  def nextDeadline(nowMs: Long): Option[Long] = synchronized:
    val expiry = events.headOption.collect { case value: Card if value.deadlineRelayMs > nowMs => value.deadlineRelayMs }
    (progress.nextDeadline.toList ++ expiry.toList).minOption

  /** Coalesced control retains cumulative episode/generation fences and the oldest-unapplied deadline. */
  def update(value: DeskControlCodec.Display, nowMs: Long): Unit = synchronized:
    require(value.identity == greeting.sync.identity && value.revision >= display.revision)
    if progress.overdue(nowMs).isDefined then closed = true
    if closed then ()
    else if value.revision == display.revision then require(value == display, "equal control revisions must be identical")
    else
      progress.controlAccepted(value, nowMs)
      display = value
      dirty(0) = true

  def update(value: DeskSnapshotCodec.Weather): Unit = synchronized:
    require(value.identity == greeting.sync.identity && value.revision >= weather.revision)
    if value.revision == weather.revision then require(value == weather, "equal weather revisions must be identical")
    else
      progress.sourceAccepted(value.revision, cpu.revision)
      weather = value
      dirty(1) = true

  def update(value: DeskSnapshotCodec.Cpu): Unit = synchronized:
    require(value.identity == greeting.sync.identity && value.revision >= cpu.revision)
    if value.revision == cpu.revision then require(value == cpu, "equal CPU revisions must be identical")
    else
      progress.sourceAccepted(weather.revision, value.revision)
      cpu = value
      dirty(2) = true

  def update(value: DeskMessageCodec.Stats): Unit = synchronized:
    require(value.identity == greeting.sync.identity && value.sourceGeneration == display.sourceGeneration)
    require(value.sourceGeneration >= stats.sourceGeneration)
    require(value.revision >= stats.revision)
    if value.revision == stats.revision then require(value == stats, "equal STATS revisions must be identical")
    if value != stats then
      stats = value
      dirty(3) = true

  /** Retention overflow closes before any successor can cross the missing record. Ordinary zero event credit is not overflow. */
  def offer(value: Ordered): Boolean = synchronized:
    require(
      (value.throughSeq > queuedThrough || (value.isInstanceOf[Barrier] && value.throughSeq == queuedThrough)) &&
        value.throughSeq <= 0xffffffffL,
      "ordered stream must increase except a replay completion barrier"
    )
    value match
      case card: Card => require(card.relayEpoch == greeting.sync.identity.relayEpoch)
      case _: Barrier => ()
    if closed then false
    else if events.size >= eventCapacity then
      closed = true
      false
    else
      events.enqueue(value)
      queuedThrough = value.throughSeq
      true

  def timeResponse(value: DeskTimeCodec.Response): Boolean = synchronized:
    require(value.relayEpoch == greeting.sync.identity.relayEpoch && value.connectionId == greeting.sync.identity.connectionId)
    if closed then false
    else if replies.size >= 4 then
      closed = true
      false
    else
      replies.enqueue(value)
      true

  /** Right(false) can mean a bounded deferred observation: it is not authority until the in-flight flush is confirmed. */
  def accept(value: DeskReportCodec.Report, nowMs: Long): Either[String, Boolean] = synchronized:
    progress.overdue(nowMs) match
      case Some(reason) =>
        closed = true
        Left(reason)
      case None if closed => Left("session_closed")
      case None =>
        progress.accept(value, nowMs) match
          case Left(reason)
              if inFlight.isDefined && Set(
                "report_delivery_pending",
                "report_enqueue_not_delivered",
                "report_control_not_delivered",
                "report_source_not_delivered",
                "report_credit_out_of_range"
              ).contains(reason) ||
                (reason == "report_before_sync" && inFlight.exists(_.completesSync)) =>
            deferredReport match
              case Some((previous, _)) if value.revision == previous.revision && value != previous => Left("report_revision_conflict")
              case Some((previous, _)) if value.revision <= previous.revision                      => Right(false)
              case _ =>
                deferredReport = Some((value, nowMs))
                Right(false)
          case Right(true) =>
            acceptedReportAt = Some(nowMs)
            Right(true)
          case result => result

  /** The caller waits on publication/report/deadline events when None is returned, not a timer-polling loop. */
  def reserve(nowMs: Long, auxiliary: Option[Vector[Byte]] = None): Option[Reservation] = synchronized:
    require(nowMs >= 0)
    require(inFlight.isEmpty, "only one writer reservation may be outstanding")
    if progress.overdue(nowMs).isDefined then closed = true
    if closed then None
    else
      val next =
        if greetingIndex < 6 then Some(initial(nowMs))
        else if dirty(0) then reserveState()
        else reserveWork(nowMs, auxiliary)
      inFlight = next
      next

  /** Called after whole-frame flush. Failed/ambiguous writes close without credit refunds or barrier progress repair. */
  def written(value: Reservation, nowMs: Long, success: Boolean): Unit = synchronized:
    require(inFlight.exists(_ eq value), "write completion must match the reserved frame")
    inFlight = None
    if !success || progress.overdue(nowMs).isDefined then closed = true
    else if !closed then
      if !progress.delivered(value.event, value.barrier, value.control, value.weather, value.cpu) then closed = true
      if !closed then
        if value.completesSync then progress.synchronizedAt(nowMs)
        deferredReport.foreach: (report, arrivedAt) =>
          progress.accept(report, nowMs) match
            case Left(_)      => closed = true
            case Right(true)  => acceptedReportAt = Some(arrivedAt)
            case Right(false) => ()
    deferredReport = None

  def close(): Unit = synchronized:
    closed = true
    events.clear()
    replies.clear()

  private def initial(nowMs: Long): Reservation =
    val bytes = greetingIndex match
      case 0 => DeskGreetingCodec.welcome(greeting.welcome, greeting.tail.copy(sampledRelayMs = nowMs))
      case 1 => DeskControlCodec.display(greeting.display)
      case 2 => DeskSnapshotCodec.weather(greeting.weather)
      case 3 => DeskSnapshotCodec.cpu(greeting.cpu)
      case 4 => DeskMessageCodec.stats(greeting.stats)
      case 5 => DeskControlCodec.sync(greeting.sync)
      case _ => throw IllegalStateException("initial batch exhausted")
    greetingIndex += 1
    Reservation(bytes.toVector, greetingIndex == 6)

  /** Control bypasses this rotation; other busy lanes cannot indefinitely starve each other. */
  private def reserveWork(nowMs: Long, auxiliary: Option[Vector[Byte]]): Option[Reservation] =
    (0 until 4).iterator
      .map(step => (workIndex + step) % 4)
      .flatMap: lane =>
        val next = lane match
          case 0 if replies.nonEmpty =>
            Some(Reservation(DeskTimeCodec.response(replies.dequeue().copy(sampledRelayMs = nowMs)).toVector, false))
          case 1 => reserveState()
          case 2 => reserveOrdered(nowMs)
          case 3 => auxiliary.map(bytes => Reservation(bytes, false, auxiliary = true))
          case _ => None
        next.map: value =>
          workIndex = (lane + 1) % 4
          value
      .nextOption()

  private def reserveState(): Option[Reservation] =
    val selected = if dirty(0) then Some(0) else (0 until 3).map(step => 1 + (stateIndex + step) % 3).find(dirty(_))
    selected.map: index =>
      dirty(index) = false
      if index != 0 then stateIndex = index % 3
      val bytes = index match
        case 0 => DeskControlCodec.display(display)
        case 1 => DeskSnapshotCodec.weather(weather)
        case 2 => DeskSnapshotCodec.cpu(cpu)
        case 3 => DeskMessageCodec.stats(stats)
        case _ => throw IllegalStateException("invalid state slot")
      Reservation(
        bytes.toVector,
        false,
        control = Option.when(index == 0)(display),
        weather = Option.when(index == 1)(weather.revision),
        cpu = Option.when(index == 2)(cpu.revision)
      )

  private def reserveOrdered(nowMs: Long): Option[Reservation] =
    if !progress.ready then None
    else
      events.headOption.flatMap:
        case card: Card if ineligible(card, nowMs) =>
          val _ = events.dequeue()
          progress.barrierReserved(card.throughSeq)
          Some(
            Reservation(
              DeskControlCodec.barrier(DeskControlCodec.Barrier(greeting.sync.identity, card.throughSeq)).toVector,
              false,
              barrier = Some(card.throughSeq)
            )
          )
        case card: Card if progress.reserve(card.throughSeq) =>
          val _ = events.dequeue()
          val value = DeskMessageCodec.fromEvent(
            card.record,
            card.relayEpoch,
            card.sourceGeneration,
            card.twitchEpisode,
            card.deadlineRelayMs,
            card.origin
          )
          Some(
            Reservation(
              DeskMessageCodec.event(value, if card.replay then FrameFlags.Replay else FrameFlags.Empty).toVector,
              false,
              event = Some(card.throughSeq)
            )
          )
        case _: Card => None
        case barrier: Barrier =>
          val _ = events.dequeue()
          progress.barrierReserved(barrier.throughSeq)
          Some(
            Reservation(
              DeskControlCodec.barrier(DeskControlCodec.Barrier(greeting.sync.identity, barrier.throughSeq)).toVector,
              false,
              barrier = Some(barrier.throughSeq)
            )
          )

  private def ineligible(card: Card, nowMs: Long): Boolean =
    java.lang.Long.compareUnsigned(nowMs, card.deadlineRelayMs) >= 0 ||
      (card.origin == DeskMessageCodec.Origin.Twitch && (card.replay || !display.enabled ||
        card.sourceGeneration != display.sourceGeneration || card.twitchEpisode != display.twitchEpisode))

private[device] object DeskDelivery:
  final case class Greeting(
      welcome: RelayMessage.Welcome,
      tail: DeskGreetingCodec.WelcomeTail,
      display: DeskControlCodec.Display,
      weather: DeskSnapshotCodec.Weather,
      cpu: DeskSnapshotCodec.Cpu,
      stats: DeskMessageCodec.Stats,
      sync: DeskControlCodec.Sync
  ):
    val identity: DeskControlCodec.Identity = DeskControlCodec.Identity(tail.relayEpoch, tail.connectionId)
    require(
      display.identity == identity && weather.identity == identity && cpu.identity == identity && stats.identity == identity &&
        sync.identity == identity,
      "greeting identity mismatch"
    )
    require(
      sync.displayRevision == display.revision && sync.weatherRevision == weather.revision && sync.cpuRevision == cpu.revision &&
        sync.statsRevision == stats.revision && sync.sourceGeneration == display.sourceGeneration &&
        sync.sourceGeneration == stats.sourceGeneration && sync.twitchEpisode == display.twitchEpisode,
      "incoherent greeting"
    )
    require((welcome.caps.unsigned & DeskGreetingCodec.CapabilityBit) != 0, "greeting must select DESK")

  sealed trait Ordered:
    def throughSeq: Long
  final case class Card(
      record: EventRecord,
      relayEpoch: Long,
      sourceGeneration: Long,
      twitchEpisode: Long,
      deadlineRelayMs: Long,
      origin: DeskMessageCodec.Origin,
      replay: Boolean = false
  ) extends Ordered:
    require(relayEpoch != 0 && deadlineRelayMs != 0 && record.seq.value > 0)
    require(record.ttl.deciseconds >= 1 && record.ttl.deciseconds <= 300)
    require(origin != DeskMessageCodec.Origin.Unknown)
    if origin == DeskMessageCodec.Origin.Twitch then
      require(sourceGeneration > 0 && sourceGeneration <= 0xffffffffL && twitchEpisode > 0 && twitchEpisode <= 0xffffffffL)
    else require(sourceGeneration == 0 && twitchEpisode == 0)
    def throughSeq: Long = record.seq.value
  final case class Barrier(throughSeq: Long) extends Ordered
  final class Reservation private[device] (
      val bytes: Vector[Byte],
      val completesSync: Boolean,
      val event: Option[Long] = None,
      val barrier: Option[Long] = None,
      val control: Option[DeskControlCodec.Display] = None,
      val weather: Option[Long] = None,
      val cpu: Option[Long] = None,
      val auxiliary: Boolean = false
  )
