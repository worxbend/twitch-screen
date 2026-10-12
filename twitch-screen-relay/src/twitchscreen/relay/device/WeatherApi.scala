package twitchscreen.relay.device

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.http.{ApiJson, ApiTime, Fail, Http, ServerEndpoints, StrictRequest}

final case class Weather_IN(
    sourceId: String,
    location: String,
    temperature: Double,
    condition: String,
    unit: String = "celsius",
    humidityPercent: Option[Long] = None,
    observedAt: Option[String] = None,
    freshnessMs: Long = 600000L,
    deadline: Option[Deadline_IN] = None
) derives Schema:
  require(sourceId != null && sourceId.matches("[A-Za-z0-9._-]{1,31}"), "invalid sourceId")
  require(
    location != null && location.trim.nonEmpty && StrictRequest.validUnicode(location) &&
      location.codePointCount(0, location.length) <= 128,
    "invalid location"
  )
  require(unit == "celsius" || unit == "fahrenheit", "invalid unit")
  require(Weather_IN.conditions.contains(condition), "invalid condition")
  require(
    temperature.isFinite && temperature >= (if unit == "celsius" then -100 else -148) &&
      temperature <= (if unit == "celsius" then 100 else 212),
    "invalid temperature"
  )
  require(humidityPercent.forall(value => value >= 0 && value <= 100), "invalid humidityPercent")
  require(observedAt.forall(ApiTime.valid), "observedAt must be a millisecond UTC timestamp")
  require(freshnessMs >= 1000 && freshnessMs <= 1800000, "freshnessMs must be between 1000 and 1800000")

object Weather_IN:
  private[device] val conditions = Vector("unknown", "clear", "partly_cloudy", "cloudy", "fog", "drizzle", "rain", "snow", "thunderstorm")
  private given JsonValueCodec[String] = StrictRequest.nonNullString
  private given JsonValueCodec[Long] = StrictRequest.integralLong
  private given optionalLong: JsonValueCodec[Option[Long]] = StrictRequest.optional(StrictRequest.integralLong)
  private given optionalString: JsonValueCodec[Option[String]] = StrictRequest.optional(JsonCodecMaker.make[String])
  private given optionalDeadline: JsonValueCodec[Option[Deadline_IN]] = StrictRequest.optional(summon[JsonValueCodec[Deadline_IN]])
  given JsonValueCodec[Weather_IN] = JsonCodecMaker.make(ApiJson.config.withSkipUnexpectedFields(false).withCheckFieldDuplication(true))

final case class WeatherData_OUT(
    sourceId: String,
    location: String,
    temperature: Double,
    condition: String,
    unit: String,
    humidityPercent: Option[Long],
    observedAt: String,
    freshnessMs: Long
) derives Schema
object WeatherData_OUT:
  given JsonValueCodec[WeatherData_OUT] = JsonCodecMaker.make(ApiJson.config.withTransientNone(false))

final case class Weather_OUT(
    relayEpoch: String,
    revision: Long,
    status: String,
    reason: String,
    receivedAt: Option[String],
    observedAtSource: Option[String],
    remainingFreshnessMs: Long,
    data: Option[WeatherData_OUT]
) derives Schema
object Weather_OUT:
  given JsonValueCodec[Weather_OUT] = JsonCodecMaker.make(ApiJson.config.withTransientNone(false))

/** Externally supplied latest weather. No provider requests, dashboard selection or card publication. */
final class WeatherApi(hub: DeviceHub) extends ServerEndpoints:
  override val endpoints: List[ServerEndpoint[Any, Identity]] = List(
    WeatherApi.getEndpoint.handleSuccess(_ => hub.weatherSnapshot),
    WeatherApi.putEndpoint.handle(request =>
      hub.acceptWeather(request).left.map {
        case IngressFailure.EpochChanged  => Fail.ClockEpochChanged
        case IngressFailure.Expired       => Fail.Expired
        case IngressFailure.RateLimited   => Fail.RateLimited
        case IngressFailure.RevisionSpent => Fail.Unavailable("weather revision exhausted; restart required")
        case IngressFailure.SequenceSpent => Fail.Unavailable("publication sequence exhausted")
      }
    )
  )

object WeatherApi:
  val getEndpoint: PublicEndpoint[Unit, Fail, Weather_OUT, Any] = Http.baseEndpoint.get
    .in("weather")
    .out(jsonBody[Weather_OUT])
    .summary("Read the relay-owned latest weather snapshot")
    .tag("weather")
  val putEndpoint: PublicEndpoint[Weather_IN, Fail, (Weather_OUT, Option[String], Option[String]), Any] = Http.baseEndpoint.put
    .in("weather")
    .in(jsonBody[Weather_IN])
    .out(jsonBody[Weather_OUT])
    .out(header[Option[String]]("X-Relay-Epoch"))
    .out(header[Option[String]]("X-Relay-Monotonic-Ms"))
    .summary("Replace externally supplied weather with a calibrated expiry")
    .description(
      "Global 10/s admission with burst 20. One latest value, no history or provider collection. Receipt-based lifetime is capped by the supplied deadline. Acceptance is not device application; complete DESK wire delivery remains pending."
    )
    .tag("weather")

/** Actor-confined latest value; reconnect and wall metadata cannot renew the absolute deadline. */
private[device] final class WeatherSlot(initialRevision: Long = 1L):
  require(initialRevision >= 1 && initialRevision <= 0xffffffffL)
  private var revision = initialRevision
  private var accepted: Option[(WeatherData_OUT, String, String, Long)] = None
  private val rate = IngressRateLimit()

  /** Original admission deadline for atomic hub-to-session capture, including expired diagnostic state. */
  def deadlineRelayMs: Option[Long] = accepted.map(_._4)

  def snapshot(now: RelayClock): Weather_OUT = accepted match
    case None => Weather_OUT(now.epochHex, revision, "missing", "never_supplied", None, None, 0L, None)
    case Some((data, receivedAt, source, deadline)) =>
      val remaining = math.max(0L, deadline - now.monotonicMs)
      Weather_OUT(
        now.epochHex,
        revision,
        if remaining > 0 then "fresh" else "stale",
        if remaining > 0 then "fresh" else "expired",
        Some(receivedAt),
        Some(source),
        remaining,
        Some(data)
      )

  def put(request: Weather_IN, now: RelayClock, receivedAt: String): Either[IngressFailure, Weather_OUT] =
    val supplied = request.deadline.map(_.clock)
    if supplied.exists(_.relayEpoch != now.relayEpoch) then Left(IngressFailure.EpochChanged)
    else
      val deadline = math.min(Math.addExact(now.monotonicMs, request.freshnessMs), supplied.fold(Long.MaxValue)(_.monotonicMs))
      if deadline <= now.monotonicMs then Left(IngressFailure.Expired)
      else if revision == 0xffffffffL then Left(IngressFailure.RevisionSpent)
      else if !rate.admit(now.monotonicMs) then Left(IngressFailure.RateLimited)
      else
        val data = WeatherData_OUT(
          request.sourceId,
          request.location,
          BigDecimal.decimal(request.temperature).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble,
          request.condition,
          request.unit,
          request.humidityPercent,
          request.observedAt.getOrElse(receivedAt),
          request.freshnessMs
        )
        revision += 1L
        accepted = Some((data, receivedAt, if request.observedAt.isDefined then "producer" else "relay_receipt", deadline))
        Right(snapshot(now))
