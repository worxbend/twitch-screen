package twitchscreen.relay.http

import io.opentelemetry.api.OpenTelemetry
import java.net.{ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import ox.supervised
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.config.{Hostname, HttpConfig, Port}
import twitchscreen.relay.device.AdminPanelApi

class AdminPanelRouteSuite extends munit.FunSuite:
  ox.logback.InheritableMDC.init

  test("exact root and versioned asset bodies/MIME preserve unknown paths, APIs and Swagger under the browser boundary"):
    val reservation = ServerSocket(0)
    val port = reservation.getLocalPort
    reservation.close()
    val base = s"http://127.0.0.1:$port"
    val config = HttpConfig(
      Hostname("127.0.0.1").toOption.get,
      Port(port).toOption.get,
      allowedHosts = List(s"127.0.0.1:$port"),
      allowedOrigins = List(base)
    )
    val sentinel = new ServerEndpoints:
      val endpoints: List[ServerEndpoint[Any, Identity]] =
        List(Http.publicEndpoint.get.in("panel-sentinel").out(stringBody).handleSuccess(_ => "API retained"))
    supervised:
      val binding = HttpApi(List(new AdminPanelApi, sentinel), config, OpenTelemetry.noop()).start()
      val client = HttpClient.newHttpClient()
      try
        def get(path: String, origin: Option[String] = None): HttpResponse[String] =
          val request = HttpRequest.newBuilder(URI.create(base + path))
          origin.foreach(value => request.header("Origin", value))
          client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        val html = get("/")
        assertEquals(html.statusCode(), 200)
        assert(html.body().contains("Loading authoritative state"))
        val css = get("/admin/assets/v1/panel.css")
        val script = get("/admin/assets/v1/panel.js")
        assertEquals(css.statusCode(), 200)
        assertEquals(script.statusCode(), 200)
        assert(css.headers().firstValue("Content-Type").orElse("").startsWith("text/css"))
        assert(script.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"))
        assert(css.body() != html.body())
        assert(script.body() != html.body())
        assert(script.body().contains("new EventSource"))
        assertEquals(html.headers().firstValue("X-Content-Type-Options").orElse(""), "nosniff")
        assert(html.headers().firstValue("Content-Security-Policy").orElse("").contains("connect-src 'self'"))
        assertEquals(get("/not-a-panel-route").statusCode(), 404)
        assertEquals(get("/admin/assets/v1/missing.js").statusCode(), 404)
        assertEquals(get("/api/v1/panel-sentinel").body(), "API retained")
        val docs = get("/docs")
        assert(Set(200, 301, 302, 307, 308).contains(docs.statusCode()))
        assertEquals(get("/", Some("https://hostile.invalid")).statusCode(), 403)
        assertEquals(get("/admin/assets/v1/panel.js", Some("null")).statusCode(), 403)
      finally
        client.close()
        binding.stop()
