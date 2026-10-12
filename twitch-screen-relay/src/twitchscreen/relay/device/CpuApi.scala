package twitchscreen.relay.device

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.http.{ApiJson, ApiTime, Fail, Http, ServerEndpoints, StrictRequest}

final case class Cpu_IN(
    sourceId: String,
    host: String,
    cpuPercent: Double,
    memoryPercent: Option[Double] = None,
    temperatureCelsius: Option[Double] = None,
    observedAt: Option[String] = None,
    freshnessMs: Long = 10000L,
    deadline: Option[Deadline_IN] = None
) derives Schema:
  require(sourceId != null && sourceId.matches("[A-Za-z0-9._-]{1,31}"), "invalid sourceId")
  require(
    host != null && host.trim.nonEmpty && StrictRequest.validUnicode(host) && host.codePointCount(0, host.length) <= 128,
    "invalid host"
  )
  require(cpuPercent.isFinite && cpuPercent >= 0 && cpuPercent <= 100, "invalid cpuPercent")
  require(memoryPercent.forall(value => value.isFinite && value >= 0 && value <= 100), "invalid memoryPercent")
  require(temperatureCelsius.forall(value => value.isFinite && value >= -20 && value <= 150), "invalid temperatureCelsius")
  require(observedAt.forall(ApiTime.valid), "observedAt must be a millisecond UTC timestamp")
  require(freshnessMs >= 1000 && freshnessMs <= 60000, "freshnessMs must be between 1000 and 60000")

object Cpu_IN:
  private given JsonValueCodec[Long] = StrictRequest.integralLong
  private given optionalDouble: JsonValueCodec[Option[Double]] = StrictRequest.optional(JsonCodecMaker.make[Double])
  private given optionalString: JsonValueCodec[Option[String]] = StrictRequest.optional(JsonCodecMaker.make[String])
  private given optionalDeadline: JsonValueCodec[Option[Deadline_IN]] = StrictRequest.optional(summon[JsonValueCodec[Deadline_IN]])
  given JsonValueCodec[Cpu_IN] = JsonCodecMaker.make(ApiJson.config.withSkipUnexpectedFields(false).withCheckFieldDuplication(true))

final case class CpuData_OUT(
    sourceId: String,
    host: String,
    cpuPercent: Double,
    memoryPercent: Option[Double],
    temperatureCelsius: Option[Double],
    observedAt: String,
    freshnessMs: Long
) derives Schema
object CpuData_OUT:
  given JsonValueCodec[CpuData_OUT] = JsonCodecMaker.make(ApiJson.config.withTransientNone(false))

final case class Cpu_OUT(
    relayEpoch: String,
    revision: Long,
    status: String,
    reason: String,
    receivedAt: Option[String],
    observedAtSource: Option[String],
    remainingFreshnessMs: Long,
    data: Option[CpuData_OUT]
) derives Schema
object Cpu_OUT:
  given JsonValueCodec[Cpu_OUT] = JsonCodecMaker.make(ApiJson.config.withTransientNone(false))

final class CpuApi(hub: DeviceHub) extends ServerEndpoints:
  override val endpoints: List[ServerEndpoint[Any, Identity]] = List(
    CpuApi.getEndpoint.handleSuccess(_ => hub.cpuSnapshot),
    CpuApi.putEndpoint.handle(request =>
      hub.acceptCpu(request).left.map {
        case IngressFailure.EpochChanged  => Fail.ClockEpochChanged
        case IngressFailure.Expired       => Fail.Expired
        case IngressFailure.RateLimited   => Fail.RateLimited
        case IngressFailure.RevisionSpent => Fail.Unavailable("CPU revision exhausted; restart required")
        case IngressFailure.SequenceSpent => Fail.Unavailable("publication sequence exhausted")
      }
    )
  )

object CpuApi:
  val getEndpoint: PublicEndpoint[Unit, Fail, Cpu_OUT, Any] = Http.baseEndpoint.get
    .in("cpu")
    .out(jsonBody[Cpu_OUT])
    .summary("Read the relay-owned latest CPU snapshot")
    .tag("cpu")
  val putEndpoint: PublicEndpoint[Cpu_IN, Fail, (Cpu_OUT, Option[String], Option[String]), Any] = Http.baseEndpoint.put
    .in("cpu")
    .in(jsonBody[Cpu_IN])
    .out(jsonBody[Cpu_OUT])
    .out(header[Option[String]]("X-Relay-Epoch"))
    .out(header[Option[String]]("X-Relay-Monotonic-Ms"))
    .summary("Replace the latest CPU sample with a calibrated expiry")
    .description(
      "Global 10/s admission with burst 20. No history and no upstream host contact. Missing/stale values are explicit; wall timestamps do not define freshness. Each valid PUT advances the CPU revision. DESK wire delivery remains unavailable until complete session negotiation."
    )
    .tag("cpu")

/** Actor-confined latest value. No event sequence, notification slot or reconnect renewal is used for telemetry. */
private[device] final class CpuSlot(initialRevision: Long = 1L):
  require(initialRevision >= 1 && initialRevision <= 0xffffffffL)
  private var revision = initialRevision
  private var accepted: Option[(CpuData_OUT, String, String, Long)] = None
  private val rate = IngressRateLimit()

  /** Original admission deadline for atomic hub-to-session capture, including expired diagnostic state. */
  def deadlineRelayMs: Option[Long] = accepted.map(_._4)

  def snapshot(now: RelayClock): Cpu_OUT = accepted match
    case None => Cpu_OUT(now.epochHex, revision, "missing", "never_supplied", None, None, 0L, None)
    case Some((data, receivedAt, source, deadline)) =>
      val remaining = math.max(0L, deadline - now.monotonicMs)
      Cpu_OUT(
        now.epochHex,
        revision,
        if remaining > 0 then "fresh" else "stale",
        if remaining > 0 then "fresh" else "expired",
        Some(receivedAt),
        Some(source),
        remaining,
        Some(data)
      )

  def put(request: Cpu_IN, now: RelayClock, receivedAt: String): Either[IngressFailure, Cpu_OUT] =
    val supplied = request.deadline.map(_.clock)
    if supplied.exists(_.relayEpoch != now.relayEpoch) then Left(IngressFailure.EpochChanged)
    else
      val deadline = math.min(Math.addExact(now.monotonicMs, request.freshnessMs), supplied.fold(Long.MaxValue)(_.monotonicMs))
      if deadline <= now.monotonicMs then Left(IngressFailure.Expired)
      else if revision == 0xffffffffL then Left(IngressFailure.RevisionSpent)
      else if !rate.admit(now.monotonicMs) then Left(IngressFailure.RateLimited)
      else
        def rounded(value: Double): Double = BigDecimal.decimal(value).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
        val data = CpuData_OUT(
          request.sourceId,
          request.host,
          rounded(request.cpuPercent),
          request.memoryPercent.map(rounded),
          request.temperatureCelsius.map(rounded),
          request.observedAt.getOrElse(receivedAt),
          request.freshnessMs
        )
        revision += 1L
        accepted = Some((data, receivedAt, if request.observedAt.isDefined then "producer" else "relay_receipt", deadline))
        Right(snapshot(now))
