package twitchscreen.relay.device

import scala.collection.mutable
import twitchscreen.relay.protocol.{DeskControlCodec, DeskReportCodec}

/** Per-connection report authority. All methods are serialized by the delivery owner, never a lossy bus. No ACK creates event credit. This
  * DeviceSession installs this authority only behind the explicit pairing gate; production capability advertisement remains disabled.
  */
private[device] final class DeskSessionProgress(
    val sync: DeskControlCodec.Sync,
    bootId: Long,
    baselineProcessed: Long,
    baselineEnqueued: Long,
    bootstrapEnqueued: Boolean = false
):
  require(bootId != 0 && baselineProcessed >= 0 && baselineProcessed <= 0xffffffffL)
  require(baselineEnqueued >= 0 && baselineEnqueued <= baselineProcessed)

  private var initialDeadline: Option[Long] = None
  private var last: Option[DeskReportCodec.Report] = None
  private var reserved = 0L
  private var handledThrough = baselineProcessed
  private var deliveredThrough = baselineProcessed
  private var deliveredEvents = 0L
  private val enqueueCandidates = mutable.Set(baselineEnqueued)
  private val controls = mutable.Map(sync.displayRevision -> (sync.sourceGeneration, sync.twitchEpisode))
  private val weatherRevisions = mutable.Set(sync.weatherRevision)
  private val cpuRevisions = mutable.Set(sync.cpuRevision)
  private val historyLimit = 128
  private var latestDisplay = sync.displayRevision
  private var latestGeneration = sync.sourceGeneration
  private var latestEpisode = sync.twitchEpisode
  private var latestWeather = sync.weatherRevision
  private var latestCpu = sync.cpuRevision
  private val pendingControls = mutable.Queue.empty[(Long, Long)]
  private var controlHistoryOverflow = false
  private def oldestControl: Option[Long] = pendingControls.headOption.map(_._2)

  def report: Option[DeskReportCodec.Report] = last
  def reservedEvents: Long = reserved
  def ready: Boolean = last.isDefined
  def creditAvailable: Boolean = last.exists(value => java.lang.Long.compareUnsigned(reserved, value.grantTotal) < 0)

  /** Called only after the complete initial SYNC_END is flushed, not at attachment. */
  def synchronizedAt(nowMs: Long): Unit =
    require(nowMs >= 0 && nowMs <= Long.MaxValue - 5000)
    require(initialDeadline.isEmpty, "initial synchronization must be completed once")
    initialDeadline = Some(nowMs + 5000)

  /** Called when a new authoritative control is accepted, even if the latest wire slot later coalesces it. */
  def controlAccepted(value: DeskControlCodec.Display, nowMs: Long): Unit =
    require(value.identity == sync.identity && value.revision >= latestDisplay)
    require(value.sourceGeneration >= latestGeneration && value.twitchEpisode >= latestEpisode)
    require(nowMs >= 0 && nowMs <= Long.MaxValue - 5000)
    if value.revision > latestDisplay then
      if pendingControls.size >= historyLimit then controlHistoryOverflow = true
      else pendingControls.enqueue((value.revision, nowMs))
      latestDisplay = value.revision
      latestGeneration = value.sourceGeneration
      latestEpisode = value.twitchEpisode

  def sourceAccepted(weatherRevision: Long, cpuRevision: Long): Unit =
    require(weatherRevision >= latestWeather && weatherRevision <= 0xffffffffL)
    require(cpuRevision >= latestCpu && cpuRevision <= 0xffffffffL)
    latestWeather = weatherRevision
    latestCpu = cpuRevision

  /** Reservation is irrevocable. A failed socket write tears down this connection rather than refunding uncertain delivery. */
  def reserve(seq: Long): Boolean =
    require(seq > handledThrough && seq <= 0xffffffffL, "event reservation must preserve ordered progress")
    if !creditAvailable || reserved == -1L then false
    else
      reserved += 1
      handledThrough = seq
      true

  def barrierReserved(throughSeq: Long): Unit =
    require(throughSeq >= handledThrough && throughSeq <= 0xffffffffL)
    handledThrough = throughSeq

  /** Only complete successful writes establish observation authority. Bounded history exhaustion forces resynchronization. */
  def delivered(
      event: Option[Long],
      barrier: Option[Long],
      control: Option[DeskControlCodec.Display],
      weather: Option[Long],
      cpu: Option[Long]
  ): Boolean =
    event.foreach: seq =>
      require(seq > deliveredThrough && seq <= handledThrough)
      deliveredThrough = seq
      deliveredEvents += 1
      val _ = enqueueCandidates.add(seq)
    barrier.foreach: seq =>
      require(seq >= deliveredThrough && seq <= handledThrough)
      deliveredThrough = seq
    control.foreach: value =>
      val _ = controls.put(value.revision, (value.sourceGeneration, value.twitchEpisode))
    weather.foreach: revision =>
      val _ = weatherRevisions.add(revision)
    cpu.foreach: revision =>
      val _ = cpuRevisions.add(revision)
    enqueueCandidates.size <= historyLimit && controls.size <= historyLimit &&
    weatherRevisions.size <= historyLimit && cpuRevisions.size <= historyLimit

  /** Structural codec checks precede this admission. Rejected reports leave all authority unchanged. */
  def accept(value: DeskReportCodec.Report, nowMs: Long): Either[String, Boolean] =
    if overdue(nowMs).isDefined then Left(overdue(nowMs).get)
    else if initialDeadline.isEmpty then Left("report_before_sync")
    else if value.identity != sync.identity || value.deviceBootId != bootId then Left("report_identity_mismatch")
    else if nowMs < 0 || (value.observedRelayLowerMs != 0 && java.lang.Long.compareUnsigned(value.observedRelayLowerMs, nowMs) > 0) then
      Left("report_observation_in_future")
    else if value.processedSeq > handledThrough || value.enqueuedSeq < baselineEnqueued || value.processedSeq < baselineProcessed then
      Left("report_cursor_out_of_range")
    else if value.processedSeq > deliveredThrough then Left("report_delivery_pending")
    else if !enqueueCandidates.contains(value.enqueuedSeq) &&
      !(bootstrapEnqueued && last.isEmpty && value.enqueuedSeq <= baselineProcessed)
    then Left("report_enqueue_not_delivered")
    else if value.displayRevision > latestDisplay || value.sourceGeneration > latestGeneration || value.twitchEpisode > latestEpisode ||
      value.weatherRevision > latestWeather || value.cpuRevision > latestCpu
    then Left("report_revision_out_of_range")
    else if !controls.get(value.displayRevision).contains((value.sourceGeneration, value.twitchEpisode)) then
      Left("report_control_not_delivered")
    else if !weatherRevisions.contains(value.weatherRevision) || !cpuRevisions.contains(value.cpuRevision) then
      Left("report_source_not_delivered")
    else if exceedsCapacity(value.grantTotal, value.pending.size) then Left("report_credit_out_of_range")
    else
      last match
        case Some(previous) if value.revision < previous.revision => Right(false)
        case Some(previous) if value.revision == previous.revision =>
          if value == previous then Right(false) else Left("report_revision_conflict")
        case Some(previous) if regresses(value, previous) => Left("report_progress_regressed")
        case None
            if value.displayRevision < sync.displayRevision || value.sourceGeneration < sync.sourceGeneration ||
              value.twitchEpisode < sync.twitchEpisode || value.weatherRevision < sync.weatherRevision || value.cpuRevision < sync.cpuRevision =>
          Left("initial_report_sync_mismatch")
        case None if value.grantTotal != 8L - value.pending.size => Left("initial_report_credit_mismatch")
        case _ =>
          if last.isEmpty && bootstrapEnqueued then
            enqueueCandidates.clear()
            val _ = enqueueCandidates.add(value.enqueuedSeq)
          last = Some(value)
          val _ = enqueueCandidates.filterInPlace(_ >= value.enqueuedSeq)
          val _ = controls.filterInPlace((revision, _) => revision >= value.displayRevision)
          val _ = weatherRevisions.filterInPlace(_ >= value.weatherRevision)
          val _ = cpuRevisions.filterInPlace(_ >= value.cpuRevision)
          val _ = pendingControls.dequeueWhile(_._1 <= value.displayRevision)
          Right(true)

  /** Unsigned counters may reach u64 max, but cannot wrap. Capacity addition is saturating for comparison only. */
  private def exceedsCapacity(grant: Long, pending: Int): Boolean =
    val free = 8L - pending
    val ceiling = if java.lang.Long.compareUnsigned(deliveredEvents, -1L - free) > 0 then -1L else deliveredEvents + free
    java.lang.Long.compareUnsigned(grant, ceiling) > 0

  private def regresses(value: DeskReportCodec.Report, previous: DeskReportCodec.Report): Boolean =
    java.lang.Long.compareUnsigned(value.grantTotal, previous.grantTotal) < 0 ||
      java.lang.Long.compareUnsigned(value.observedDeviceMs, previous.observedDeviceMs) < 0 ||
      (previous.observedRelayLowerMs != 0 && value.observedRelayLowerMs != 0 &&
        java.lang.Long.compareUnsigned(value.observedRelayLowerMs, previous.observedRelayLowerMs) < 0) ||
      value.processedSeq < previous.processedSeq || value.enqueuedSeq < previous.enqueuedSeq ||
      value.displayRevision < previous.displayRevision || value.sourceGeneration < previous.sourceGeneration ||
      value.twitchEpisode < previous.twitchEpisode || value.weatherRevision < previous.weatherRevision || value.cpuRevision < previous.cpuRevision ||
      value.expiredTotal < previous.expiredTotal || value.suppressedTotal < previous.suppressedTotal || value.refusedTotal < previous.refusedTotal

  /** Reliable absolute wakeup for application deadlines; duplicate traffic cannot move it. */
  def nextDeadline: Option[Long] =
    (Option.when(!ready)(initialDeadline).flatten.toList ++ oldestControl.map(_ + 5000).toList).minOption

  def overdue(nowMs: Long): Option[String] =
    if controlHistoryOverflow then Some("control_history_overflow")
    else if !ready && initialDeadline.exists(nowMs >= _) then Some("initial_report_timeout")
    else if oldestControl.exists(start => nowMs >= start + 5000) then Some("control_application_timeout")
    else None

  /** Time since receipt is deliberately not substituted for calibrated observation age. Disconnect is immediately historical. */
  def observation(nowMs: Long, connected: Boolean): String = last match
    case None                                                                                 => "unknown"
    case Some(_) if !connected                                                                => "historical"
    case Some(value) if value.observedRelayLowerMs == 0 || nowMs < value.observedRelayLowerMs => "unknown"
    case Some(value) if nowMs - value.observedRelayLowerMs > 15000                            => "stale"
    case Some(_)                                                                              => "fresh"
