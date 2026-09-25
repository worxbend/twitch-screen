package twitchscreen.relay.twitch

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import java.time.Instant
import org.slf4j.LoggerFactory
import sttp.model.{HeaderNames, StatusCode}
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.http.{ApiJson, Fail, Http, ServerEndpoints}

/** What the relay holds for Twitch. Never the token itself. */
final case class TwitchAuthorization_OUT(
    authorized: Boolean,
    login: Option[String],
    userId: Option[String],
    scopes: List[String],
    missingScopes: List[String],
    expiresAt: Option[Instant],
    authorizeUrl: String
) derives Schema

object TwitchAuthorization_OUT:
  given JsonValueCodec[TwitchAuthorization_OUT] = JsonCodecMaker.make(ApiJson.config)

/** The consent flow's endpoints. Mounted in `live` mode only; there is no Twitch account to authorize otherwise. */
private[twitch] final class TwitchAuthApi(auth: TwitchAuth) extends ServerEndpoints:
  private val logger = LoggerFactory.getLogger(getClass)

  override val endpoints: List[ServerEndpoint[Any, Identity]] = List(
    TwitchAuthApi.authorizeEndpoint.handleSuccess(_ => auth.beginAuthorization()),
    TwitchAuthApi.callbackEndpoint.handleSuccess(callback.tupled),
    TwitchAuthApi.getEndpoint.handleSuccess(_ => status()),
    TwitchAuthApi.deleteEndpoint.handle(_ => if auth.signOut() then Right(()) else Left(Fail.NotFound("no Twitch authorization is held")))
  )

  private def callback(
      code: Option[String],
      state: Option[String],
      error: Option[String],
      description: Option[String]
  ): (StatusCode, String) =
    (error, code, state) match
      case (Some(denied), _, _) =>
        // The user pressed Cancel, or Twitch refused the request. The state is left to expire rather than consumed: nothing was issued.
        val reason = description.getOrElse(denied)
        logger.warn(s"Twitch authorization was not granted: $reason")
        (StatusCode.BadRequest, TwitchAuthApi.page("Twitch authorization was not granted", reason))
      case (None, Some(code), Some(state)) =>
        auth.completeAuthorization(code, state) match
          case Right(granted) =>
            val missing = auth.missingScopes
            val caveat = if missing.isEmpty then "" else s" Twitch did not grant ${missing.mkString(", ")}."
            (StatusCode.Ok, TwitchAuthApi.page("Twitch connected", s"Authorized as ${granted.login}.$caveat You can close this window."))
          case Left(reason) =>
            logger.warn(s"Twitch authorization failed: $reason")
            (StatusCode.BadRequest, TwitchAuthApi.page("Twitch authorization failed", reason))
      case _ =>
        (
          StatusCode.BadRequest,
          TwitchAuthApi.page("Twitch authorization failed", "the callback carried neither a code and state nor an error")
        )

  private def status(): TwitchAuthorization_OUT =
    val held = auth.current
    TwitchAuthorization_OUT(
      authorized = held.isDefined,
      login = held.map(_.login),
      userId = held.map(_.userId),
      scopes = held.fold(Nil)(_.scopes),
      missingScopes = auth.missingScopes,
      expiresAt = held.map(_.expiresAt),
      authorizeUrl = TwitchAuth.AuthorizePath
    )

private[twitch] object TwitchAuthApi:
  private val base = Http.baseEndpoint.tag("twitch").in("twitch")

  val authorizeEndpoint: PublicEndpoint[Unit, Fail, String, Any] =
    base.get
      .in("authorize")
      .out(statusCode(StatusCode.Found))
      .out(header[String](HeaderNames.Location))
      .out(header(HeaderNames.CacheControl, "no-store"))
      .summary("Start Twitch authorization: redirects the browser to Twitch's consent screen")
      .description("Open this in a browser. Twitch returns to /api/v1/twitch/callback, and the relay stores the token it is given.")

  val callbackEndpoint: PublicEndpoint[(Option[String], Option[String], Option[String], Option[String]), Fail, (StatusCode, String), Any] =
    base.get
      .in("callback")
      .in(query[Option[String]]("code"))
      .in(query[Option[String]]("state"))
      .in(query[Option[String]]("error"))
      .in(query[Option[String]]("error_description"))
      .out(statusCode)
      .out(header(HeaderNames.CacheControl, "no-store"))
      // The URL carries the authorization code; keep it out of any Referer this page might send.
      .out(header("Referrer-Policy", "no-referrer"))
      .out(htmlBodyUtf8)
      .summary("Twitch's OAuth redirect target")
      .description("Called by the browser on its way back from Twitch, not by you. Must match twitch.oauth.redirect-url.")

  val getEndpoint: PublicEndpoint[Unit, Fail, TwitchAuthorization_OUT, Any] =
    base.get
      .in("authorization")
      .out(jsonBody[TwitchAuthorization_OUT])
      .summary("Whether the relay holds a Twitch user token, whose it is and what it may do")

  val deleteEndpoint: PublicEndpoint[Unit, Fail, Unit, Any] =
    base.delete
      .in("authorization")
      .out(statusCode(StatusCode.NoContent))
      .summary("Revoke and forget the Twitch user token")

  private[twitch] def page(title: String, message: String): String =
    s"""<!doctype html>
       |<html lang="en"><head><meta charset="utf-8"><title>${escape(title)}</title></head>
       |<body style="font-family: system-ui, sans-serif; max-width: 36rem; margin: 4rem auto; padding: 0 1rem">
       |<h1>${escape(title)}</h1>
       |<p>${escape(message)}</p>
       |</body></html>
       |""".stripMargin

  /** Twitch's `error_description` is echoed onto the page, and anyone can put anything in a query string. */
  private def escape(text: String): String =
    text.flatMap:
      case '<'   => "&lt;"
      case '>'   => "&gt;"
      case '&'   => "&amp;"
      case '"'   => "&quot;"
      case '\''  => "&#39;"
      case other => other.toString
