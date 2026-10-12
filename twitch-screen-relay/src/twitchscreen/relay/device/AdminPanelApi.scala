package twitchscreen.relay.device

import java.nio.charset.StandardCharsets.UTF_8
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.http.{Http, HttpAccess, ServerEndpoints}
import scala.util.Using

/** Whitelisted bundled assets only. No filesystem routing, configuration injection, CDN or offline mutation cache. */
final class AdminPanelApi extends ServerEndpoints:
  private def asset(name: String): String =
    val stream = Option(getClass.getResourceAsStream(s"/admin/$name")).getOrElse(throw IllegalStateException("admin asset missing"))
    Using.resource(stream): input =>
      val bytes = input.readNBytes(262145)
      require(bytes.length <= 262144, "admin asset exceeds bound")
      String(bytes, UTF_8)
  private val html = asset("panel.html")
  private val css = asset("panel.css")
  private val script = asset("panel.js")
  private val base = endpoint.get
    .attribute(Http.Access, HttpAccess.Management)
    .out(header("X-Frame-Options", "DENY"))
    .out(
      header(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"
      )
    )
    .out(header("X-Content-Type-Options", "nosniff"))
    .out(header("Referrer-Policy", "no-referrer"))
    .out(header("Cache-Control", "no-store"))
    .tag("admin")
  override val endpoints: List[ServerEndpoint[Any, Identity]] = List(
    base.in("").out(htmlBodyUtf8).handleSuccess(_ => html),
    base
      .in("admin" / "assets" / "v1" / "panel.css")
      .out(stringBody)
      .out(header("Content-Type", "text/css; charset=UTF-8"))
      .handleSuccess(_ => css),
    base
      .in("admin" / "assets" / "v1" / "panel.js")
      .out(stringBody)
      .out(header("Content-Type", "text/javascript; charset=UTF-8"))
      .handleSuccess(_ => script)
  )
