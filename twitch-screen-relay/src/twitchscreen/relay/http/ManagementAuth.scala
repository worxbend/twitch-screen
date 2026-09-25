package twitchscreen.relay.http

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Semaphore
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
private[relay] final class ManagementAuth(config: HttpAuthConfig, verifyPassword: (String, String) => Boolean = PasswordVerifier.verify):
  // Excess concurrent PBKDF2 requests receive 503 so expensive logins cannot starve the API; Bearer bypasses this budget.
  private val passwordChecks = Semaphore(2)

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
            .and(header[Option[String]]("WWW-Authenticate"))
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
    else if bearer.exists(token => config.apiToken.exists(expected => equal(token, expected.value))) then Right(())
    else
      basic match
        case Some(encoded) =>
          checkBasic(encoded).flatMap: _ =>
            if crossSite(request) then
              Left(ManagementRejection(StatusCode.Forbidden, None, Error_OUT("Cross-site management request rejected")))
            else Right(())
        case None => Left(ManagementRejection.unauthorized)

  private def checkBasic(encoded: String): Either[ManagementRejection, Unit] =
    config.basicPasswordHash match
      case Some(hash) if encoded.length <= 2048 =>
        if !passwordChecks.tryAcquire() then
          Left(ManagementRejection(StatusCode.ServiceUnavailable, None, Error_OUT("Password verification busy; retry request")))
        else
          try
            val valid =
              Try(String(Base64.getDecoder.decode(encoded), UTF_8)).toOption.exists: decoded =>
                val separator = decoded.indexOf(':')
                if separator < 0 then false
                else
                  val usernameMatches = equal(decoded.take(separator), config.basicUsername)
                  val passwordMatches = verifyPassword(decoded.drop(separator + 1), hash.value)
                  usernameMatches && passwordMatches
            Either.cond(valid, (), ManagementRejection.unauthorized)
          finally passwordChecks.release()
      case _ => Left(ManagementRejection.unauthorized)

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
        !uri.exists(u =>
          Option(u.getScheme).exists(s => Set("http", "https").contains(s.toLowerCase(java.util.Locale.ROOT))) &&
            host.exists(_.equalsIgnoreCase(u.getRawAuthority)) && u.getRawUserInfo == null
        )
    foreignFetch || foreignOrigin

  private def equal(a: String, b: String): Boolean =
    MessageDigest.isEqual(
      MessageDigest.getInstance("SHA-256").digest(a.getBytes(UTF_8)),
      MessageDigest.getInstance("SHA-256").digest(b.getBytes(UTF_8))
    )

private final case class ManagementRejection(status: StatusCode, challenge: Option[String], body: Error_OUT)
private object ManagementRejection:
  val unauthorized: ManagementRejection = ManagementRejection(
    StatusCode.Unauthorized,
    Some("Basic realm=\"relay\", Bearer realm=\"relay\""),
    Error_OUT("Invalid management credentials")
  )
