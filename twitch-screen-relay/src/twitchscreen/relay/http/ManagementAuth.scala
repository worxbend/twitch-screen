package twitchscreen.relay.http

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import scala.util.Try
import sttp.model.StatusCode
import sttp.model.headers.WWWAuthenticateChallenge
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.config.{HttpAuthConfig, PasswordVerifier}

private[relay] enum HttpAccess:
  case Management, Public, Callback

/** Runs before body decoding or any management operation. */
private[relay] final class ManagementAuth(config: HttpAuthConfig):
  config.validate()

  def protect(endpoint: ServerEndpoint[Any, Identity]): ServerEndpoint[Any, Identity] =
    endpoint.attribute(Http.Access) match
      case Some(HttpAccess.Public | HttpAccess.Callback) => endpoint
      case Some(HttpAccess.Management) =>
        endpoint.prependSecurity(
          credentialInput("Basic")
            .securitySchemeName("ManagementBasic")
            .and(credentialInput("Bearer").securitySchemeName("ManagementToken"))
            .and(extractFromRequest(identity)),
          statusCode
            .and(header("WWW-Authenticate", "Basic realm=\"relay\", Bearer realm=\"relay\""))
            .and(Http.jsonErrorOutOutput)
            .mapTo[ManagementRejection]
        )(authorize.tupled)
      case None => throw IllegalArgumentException(s"Endpoint lacks an access classification: ${endpoint.show}")

  private def credentialInput(scheme: String): EndpointInput.Auth[Option[String], EndpointInput.AuthType.Http] =
    val prefix = scheme + " "
    val raw = header[List[String]]("Authorization")
      .map(values => values.find(_.regionMatches(true, 0, prefix, 0, prefix.length)).map(_.drop(prefix.length)))(
        _.toList.map(prefix + _)
      )
      .schema(summon[Schema[Option[String]]])
    auth.http[Option[String]](scheme, WWWAuthenticateChallenge(scheme)).copy(input = raw)

  private def authorize(basic: Option[String], bearer: Option[String], request: ServerRequest): Either[ManagementRejection, Unit] =
    val oneHeader = request.headers.count(_.name.equalsIgnoreCase("Authorization")) == 1
    if !oneHeader then Left(ManagementRejection.unauthorized)
    else if bearer.exists(token => config.apiToken.isSet && equal(token, config.apiToken.value)) then Right(())
    else if basic.exists(checkBasic) then
      if crossSite(request) then Left(ManagementRejection(StatusCode.Forbidden, Error_OUT("Cross-site management request rejected")))
      else Right(())
    else Left(ManagementRejection.unauthorized)

  private def checkBasic(encoded: String): Boolean =
    if !config.basicPasswordHash.isSet || encoded.length > 2048 then false
    else
      Try(String(Base64.getDecoder.decode(encoded), UTF_8)).toOption.exists: decoded =>
        val separator = decoded.indexOf(':')
        if separator < 0 then false
        else
          val usernameMatches = equal(decoded.take(separator), config.basicUsername)
          val passwordMatches = PasswordVerifier.verify(decoded.drop(separator + 1), config.basicPasswordHash.value)
          usernameMatches && passwordMatches

  /** Browsers attach Basic credentials automatically. Restrict all management requests, including the OAuth authorize GET. Non-browser
    * clients omit Origin/Fetch Metadata. A TLS proxy must preserve public Host; Forwarded headers are not trusted.
    */
  private def crossSite(request: ServerRequest): Boolean =
    val foreignFetch = request.header("Sec-Fetch-Site").exists(site => site != "same-origin" && site != "none")
    val foreignOrigin = request
      .header("Origin")
      .exists: origin =>
        val uri = Try(java.net.URI(origin)).toOption
        val host = request.header("Host")
        !uri.exists(u => Set("http", "https").contains(u.getScheme) && host.contains(u.getRawAuthority) && u.getRawUserInfo == null)
    foreignFetch || foreignOrigin

  private def equal(a: String, b: String): Boolean =
    MessageDigest.isEqual(
      MessageDigest.getInstance("SHA-256").digest(a.getBytes(UTF_8)),
      MessageDigest.getInstance("SHA-256").digest(b.getBytes(UTF_8))
    )

private final case class ManagementRejection(status: StatusCode, body: Error_OUT)
private object ManagementRejection:
  val unauthorized: ManagementRejection = ManagementRejection(StatusCode.Unauthorized, Error_OUT("Invalid management credentials"))
