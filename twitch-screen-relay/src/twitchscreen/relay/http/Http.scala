package twitchscreen.relay.http

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.jsoniter.jsonBody

/** Every error response the relay produces has this body, whether it comes from endpoint logic or from Tapir itself. */
final case class Error_OUT(error: String, code: Option[String] = None) derives ConfiguredJsonValueCodec, Schema

/** Shared endpoint scaffolding. Derived mappings are methods so initialization order cannot expose a null mapping. */
object Http:
  private[relay] val Access: AttributeKey[HttpAccess] = new AttributeKey[HttpAccess]("relay.http.access")
  private def failToResponseData: Fail => (StatusCode, Error_OUT, Option[String]) =
    case Fail.NotFound(what)              => (StatusCode.NotFound, Error_OUT(what), None)
    case Fail.Conflict(msg)               => (StatusCode.Conflict, Error_OUT(msg), None)
    case Fail.IncorrectInput(msg)         => (StatusCode.BadRequest, Error_OUT(msg), None)
    case Fail.Unauthorized(msg)           => (StatusCode.Unauthorized, Error_OUT(msg), None)
    case Fail.Forbidden                   => (StatusCode.Forbidden, Error_OUT("Forbidden"), None)
    case Fail.Unavailable(msg)            => (StatusCode.ServiceUnavailable, Error_OUT(msg), None)
    case Fail.MonitoringUnavailable(code) => (StatusCode.ServiceUnavailable, Error_OUT(code, Some(code)), None)
    case Fail.ConfigurationMissing => (StatusCode.Conflict, Error_OUT("Twitch configuration missing", Some("configuration_missing")), None)
    case Fail.ClockEpochChanged    => (StatusCode.Conflict, Error_OUT("clock epoch changed", Some("clock_epoch_changed")), None)
    case Fail.Expired              => (StatusCode.Gone, Error_OUT("expired", Some("expired")), None)
    case Fail.RateLimited          => (StatusCode.TooManyRequests, Error_OUT("rate limited", Some("rate_limited")), Some("1"))
    case _                         => (StatusCode.InternalServerError, Error_OUT("Internal server error"), None)

  private def responseDataToFail: (StatusCode, Error_OUT, Option[String]) => Fail =
    case (StatusCode.Conflict, Error_OUT(_, Some("configuration_missing")), _) => Fail.ConfigurationMissing
    case (StatusCode.Conflict, Error_OUT(_, Some("clock_epoch_changed")), _)   => Fail.ClockEpochChanged
    case (StatusCode.Gone, Error_OUT(_, Some("expired")), _)                   => Fail.Expired
    case (StatusCode.TooManyRequests, _, _)                                    => Fail.RateLimited
    case (StatusCode.NotFound, error, _)                                       => Fail.NotFound(error.error)
    case (StatusCode.Conflict, error, _)                                       => Fail.Conflict(error.error)
    case (StatusCode.Unauthorized, error, _)                                   => Fail.Unauthorized(error.error)
    case (StatusCode.Forbidden, _, _)                                          => Fail.Forbidden
    case (StatusCode.ServiceUnavailable, error, _)                             => Fail.Unavailable(error.error)
    case (_, error, _)                                                         => Fail.IncorrectInput(error.error)

  val jsonErrorOutOutput: EndpointOutput[Error_OUT] = jsonBody[Error_OUT]

  private def failOutput: EndpointOutput[Fail] =
    statusCode
      .and(jsonErrorOutOutput)
      .and(header[Option[String]]("Retry-After"))
      .map(responseDataToFail.tupled)(failToResponseData)

  /** All relay endpoints start here, so they share the error format and the anti-framing headers. The version lives in the path because
    * devices and dashboards outlive any one build of this server.
    */
  val baseEndpoint: PublicEndpoint[Unit, Fail, Unit, Any] =
    endpoint
      .attribute(Access, HttpAccess.Management)
      .errorOut(failOutput)
      .in("api" / "v1")
      .out(header("X-Frame-Options", "DENY"))
      .out(header("Content-Security-Policy", "frame-ancestors 'none'"))

  val publicEndpoint: PublicEndpoint[Unit, Fail, Unit, Any] = baseEndpoint.attribute(Access, HttpAccess.Public)
  val callbackEndpoint: PublicEndpoint[Unit, Fail, Unit, Any] = baseEndpoint.attribute(Access, HttpAccess.Callback)
