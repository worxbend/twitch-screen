package twitchscreen.relay.http

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.jsoniter.jsonBody

/** Every error response the relay produces has this body, whether it comes from endpoint logic or from Tapir itself. */
final case class Error_OUT(error: String) derives ConfiguredJsonValueCodec, Schema

/** Shared endpoint scaffolding. The `val`s below are order-sensitive: each one reads the previous, so moving a definition after its user
  * leaves it `null` and the server fails to start.
  */
object Http:
  private val failToResponseData: Fail => (StatusCode, String) =
    case Fail.NotFound(what)      => (StatusCode.NotFound, what)
    case Fail.Conflict(msg)       => (StatusCode.Conflict, msg)
    case Fail.IncorrectInput(msg) => (StatusCode.BadRequest, msg)
    case Fail.Unauthorized(msg)   => (StatusCode.Unauthorized, msg)
    case Fail.Forbidden           => (StatusCode.Forbidden, "Forbidden")
    case Fail.Unavailable(msg)    => (StatusCode.ServiceUnavailable, msg)
    case _                        => (StatusCode.InternalServerError, "Internal server error")

  private val responseDataToFail: (StatusCode, String) => Fail =
    case (StatusCode.NotFound, what)          => Fail.NotFound(what)
    case (StatusCode.Conflict, msg)           => Fail.Conflict(msg)
    case (StatusCode.Unauthorized, msg)       => Fail.Unauthorized(msg)
    case (StatusCode.Forbidden, _)            => Fail.Forbidden
    case (StatusCode.ServiceUnavailable, msg) => Fail.Unavailable(msg)
    case (_, msg)                             => Fail.IncorrectInput(msg)

  val jsonErrorOutOutput: EndpointOutput[Error_OUT] = jsonBody[Error_OUT]

  private val failOutput: EndpointOutput[Fail] =
    statusCode
      .and(jsonErrorOutOutput.map(_.error)(Error_OUT.apply))
      .map(responseDataToFail.tupled)(failToResponseData)

  /** All relay endpoints start here, so they share the error format and the anti-framing headers. The version lives in the path because
    * devices and dashboards outlive any one build of this server.
    */
  val baseEndpoint: PublicEndpoint[Unit, Fail, Unit, Any] =
    endpoint
      .errorOut(failOutput)
      .in("api" / "v1")
      .out(header("X-Frame-Options", "DENY"))
      .out(header("Content-Security-Policy", "frame-ancestors 'none'"))
