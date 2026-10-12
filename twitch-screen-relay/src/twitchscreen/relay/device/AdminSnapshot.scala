package twitchscreen.relay.device

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToArray}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.tapir.Schema
import twitchscreen.relay.http.{ApiJson, ApiTime, Fail}
import twitchscreen.relay.protocol.{DeskReportCodec, DeskMessageCodec}
import twitchscreen.relay.twitch.Monitoring_OUT

final case class CardIdentity_OUT(originalRelayEpoch: String, seq: Long, kind: Int, origin: String) derives Schema
final case class ActiveCard_OUT(identity: CardIdentity_OUT, phase: String, remainingMs: Long) derives Schema
final case class AdminDevice_OUT(
    deviceId: String,
    connectionId: Option[String],
    deviceBootId: Option[String],
    firmwareVersion: String,
    caps: Long,
    connected: Boolean,
    ready: Boolean,
    desiredDisplayRevision: Long,
    appliedDisplayRevision: Option[Long],
    reportStatus: String,
    reportRevision: Option[Long],
    receivedAt: Option[String],
    observationAgeUpperMs: Option[Long],
    baseScreen: Option[String],
    reason: Option[String],
    pendingCapacity: Int,
    pending: Option[Vector[CardIdentity_OUT]],
    active: Option[ActiveCard_OUT],
    transportQueuedFrames: Int,
    transportReservedEvents: Long,
    expiredTotal: Option[Long],
    suppressedTotal: Option[Long],
    refusedTotal: Option[Long],
    chartAvailable: Boolean
) derives Schema
final case class NotificationPreview_OUT(
    identity: CardIdentity_OUT,
    sourceGeneration: Long,
    twitchEpisode: Long,
    title: String,
    body: String,
    remainingExpiryMs: Long
) derives Schema
final case class ChartSeries_OUT(startAt: String, bucketMs: Int, values: Vector[Option[Double]]) derives Schema
final case class DeviceCharts_OUT(
    deviceId: String,
    queueDepth: ChartSeries_OUT,
    expired: ChartSeries_OUT,
    suppressed: ChartSeries_OUT,
    refused: ChartSeries_OUT
) derives Schema
final case class Charts_OUT(
    sourceGeneration: Long,
    notificationArrivals: ChartSeries_OUT,
    viewers: ChartSeries_OUT,
    chatRate: ChartSeries_OUT,
    devices: Vector[DeviceCharts_OUT]
) derives Schema
final case class AdminSources_OUT(weather: Weather_OUT, cpu: Cpu_OUT) derives Schema
final case class AdminLimits_OUT(
    maxDevices: Int = 64,
    maxChartDevices: Int = 16,
    maxNotifications: Int = 80,
    maxSnapshotBytes: Int = 524288,
    maxSseConnections: Int = 8,
    maxSsePerIp: Int = 2,
    chartBucketMs: Int = 5000,
    chartBuckets: Int = 180
) derives Schema
final case class AdminSnapshot_OUT(
    relayEpoch: String,
    revision: Long,
    capturedAt: String,
    display: Display_OUT,
    monitoring: Monitoring_OUT,
    sources: AdminSources_OUT,
    devices: Vector[AdminDevice_OUT],
    notifications: Vector[NotificationPreview_OUT],
    charts: Charts_OUT,
    limits: AdminLimits_OUT
) derives Schema
object AdminSnapshot_OUT:
  given JsonValueCodec[AdminSnapshot_OUT] = JsonCodecMaker.make(ApiJson.config.withTransientNone(false).withTransientDefault(false))

/** Sanitized immutable projection. Asynchronous monitoring must match the captured authoritative epoch, generation and desired intent. */
private[relay] object AdminSnapshot:
  def hex(value: Long): String = f"$value%016x"
  private def name(value: String): String = value.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT)
  private def origin(value: DeskMessageCodec.Origin): String = name(value.toString)
  private def identity(value: DeskReportCodec.Entry): CardIdentity_OUT =
    CardIdentity_OUT(hex(value.originalRelayEpoch), value.seq, value.kind, origin(value.origin))

  /** Bound Unicode scalar values, retaining supplementary characters without broken surrogates. Consumers still render as text. */
  def preview(value: String): String =
    val end = value.offsetByCodePoints(0, math.min(160, value.codePointCount(0, value.length)))
    value.substring(0, end)

  def project(value: HubObservation, monitoring: Monitoring_OUT): Either[Fail, AdminSnapshot_OUT] =
    val publication = value.publication
    val epoch = hex(publication.relayEpoch)
    val coherent = for
      control <- publication.control
      settings <- publication.settings
    yield monitoring.relayEpoch == epoch && monitoring.sourceGeneration == control.sourceGeneration &&
      monitoring.desired.mode == settings.confirmed.monitoring.mode &&
      monitoring.desired.channel == settings.confirmed.monitoring.channel.value
    if !coherent.contains(true) then Left(Fail.Unavailable("admin monitoring capture superseded"))
    else
      for
        display <- Display_OUT.from(publication)
        cpu <- publication.cpu.toRight(Fail.Unavailable("CPU authority unavailable"))
        weather <- publication.weather.toRight(Fail.Unavailable("weather authority unavailable"))
        charts <- value.charts.toRight(Fail.Unavailable("chart authority unavailable"))
      yield
        val chartIds = charts.devices.map(_.deviceId).toSet
        AdminSnapshot_OUT(
          epoch,
          value.revision,
          ApiTime.format(value.capturedAt),
          display,
          monitoring,
          AdminSources_OUT(weather, cpu),
          value.devices.take(64).map(device(value, _, display.revision, chartIds)),
          publication.retained.reverse
            .take(80)
            .map: card =>
              NotificationPreview_OUT(
                CardIdentity_OUT(hex(card.relayEpoch), card.record.seq.value, card.record.kind.code, origin(card.origin)),
                card.sourceGeneration,
                card.twitchEpisode,
                preview(card.record.actor),
                preview(card.record.text),
                math.min(600000L, math.max(0L, card.deadlineRelayMs - publication.sampledRelayMs))
              ),
          charts,
          AdminLimits_OUT()
        )

  def encoded(value: AdminSnapshot_OUT): Either[Fail, Array[Byte]] =
    val bytes = writeToArray(value)
    if bytes.length <= 524288 then Right(bytes) else Left(Fail.Unavailable("admin snapshot exceeds encoded limit"))

  private def device(value: HubObservation, input: HubDeviceObservation, desired: Long, chartIds: Set[String]): AdminDevice_OUT =
    val now = value.publication.sampledRelayMs
    val report = input.desk.flatMap(_.report)
    val age = report.filter(_.observedRelayLowerMs > 0).map(row => math.max(0L, now - row.observedRelayLowerMs))
    val status =
      if !input.connected then "historical"
      else if input.desk.isEmpty then "unsupported"
      else if report.isEmpty || age.isEmpty then "unknown"
      else if age.exists(_ > 15000L) then "stale"
      else "fresh"
    // Missing calibration cannot establish application or queue observations. Keep admission metadata, not counterfeit empty state.
    val observed = report.filter(_ => age.isDefined)
    AdminDevice_OUT(
      input.link.device.value,
      report.map(row => hex(row.identity.connectionId)),
      report.map(row => hex(row.deviceBootId)),
      input.firmwareVersion.take(15),
      input.caps,
      input.connected,
      status == "fresh",
      desired,
      observed.map(_.displayRevision),
      status,
      report.map(_.revision),
      input.desk.flatMap(_.receivedRelayMs).map(at => ApiTime.format(value.capturedAt.minusMillis(math.max(0L, now - at)))),
      age,
      observed.map(row => name(row.base.toString)),
      observed.map(row => name(row.reason.toString)),
      8,
      observed.map(_.pending.map(identity)),
      observed.flatMap(row => row.active.map(card => ActiveCard_OUT(identity(card), name(row.phase.toString), row.activeRemainingMs))),
      if input.connected then input.desk.flatMap(_.transport).fold(input.classicQueuedFrames)(_.queuedFrames) else 0,
      input.desk.flatMap(_.transport).fold(0L)(_.reservedEvents),
      observed.map(_.expiredTotal),
      observed.map(_.suppressedTotal),
      observed.map(_.refusedTotal),
      chartIds.contains(input.link.device.value)
    )
