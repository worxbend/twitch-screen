package twitchscreen.relay.http

import io.netty.handler.codec.http.{DefaultHttpRequest, HttpMethod, HttpVersion}
import io.opentelemetry.api.OpenTelemetry
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicInteger
import ox.{discard, supervised}
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.config.*

class ProxyBoundarySuite extends munit.FunSuite:
  ox.logback.InheritableMDC.init
  private val publicOrigin = "https://desk.example"
  private val config = HttpConfig(
    Hostname("127.0.0.1").toOption.get,
    Port(8080).toOption.get,
    allowedHosts = List("desk.example", "127.0.0.1:8080"),
    allowedOrigins = List(publicOrigin, "http://127.0.0.1:8080"),
    publicOrigin = publicOrigin,
    trustedProxies = List("127.0.0.1", "::1")
  )

  private def resolve(headers: List[(String, String)], remote: String = "127.0.0.1"): Option[(String, String)] =
    val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")
    headers.foreach((name, value) => request.headers().add(name, value).discard)
    ProxyBoundary(config).resolve(request, Some(InetSocketAddress(remote, 12345)))

  private val proxyHeaders = List("Host" -> "DESK.EXAMPLE:443", "X-Forwarded-Proto" -> "https", "X-Forwarded-For" -> "192.168.1.23")

  test("exact trusted immediate hop supplies HTTPS; direct requests remain HTTP"):
    assertEquals(resolve(proxyHeaders), Some("https" -> "desk.example:443"))
    assertEquals(resolve(proxyHeaders, "::1"), Some("https" -> "desk.example:443"))
    assertEquals(resolve(List("Host" -> "127.0.0.1:8080")), Some("http" -> "127.0.0.1:8080"))
    assertEquals(resolve(proxyHeaders, "127.0.0.2"), None)
    assertEquals(resolve(proxyHeaders, "192.168.1.23"), None)

  test("ambiguous forwarded chains duplicate fields and host rewriting fail closed"):
    List(
      "Forwarded" -> "proto=https",
      "X-Forwarded-Host" -> "desk.example",
      "X-Forwarded-Port" -> "443",
      "X-Forwarded-Proto" -> "https",
      "X-Forwarded-For" -> "192.168.1.24",
      "Host" -> "desk.example"
    ).foreach(header => assertEquals(resolve(proxyHeaders :+ header), None))
    List("https,http", "http", "HTTPS").foreach: scheme =>
      assertEquals(resolve(proxyHeaders.filterNot(_._1 == "X-Forwarded-Proto") :+ ("X-Forwarded-Proto" -> scheme)), None)
    List("192.168.1.23, 192.168.1.24", "client.example", "unknown", "[::1]", "::1%eth0", "").foreach: client =>
      assertEquals(resolve(proxyHeaders.filterNot(_._1 == "X-Forwarded-For") :+ ("X-Forwarded-For" -> client)), None)
    assertEquals(resolve(proxyHeaders.filterNot(_._1 == "Host") :+ ("Host" -> "evil.example")), None)

  test("configuration refuses incomplete trust and nonliteral or wildcard addresses"):
    List("localhost", "127.0.0.0/8", "*", "[::1]", "::1%eth0", "").foreach: address =>
      val _ = intercept[IllegalArgumentException](config.copy(trustedProxies = List(address)))
    val _ = intercept[IllegalArgumentException](config.copy(trustedProxies = Nil))
    val _ = intercept[IllegalArgumentException](config.copy(publicOrigin = ""))
    val _ = intercept[IllegalArgumentException](config.copy(publicOrigin = "http://desk.example"))
    val _ = intercept[IllegalArgumentException](config.copy(allowedOrigins = List("http://desk.example")))
    val _ = intercept[IllegalArgumentException](config.copy(allowedHosts = List("other.example")))
    assertEquals(HttpBoundaryConfig.ip("::1"), HttpBoundaryConfig.ip("0:0:0:0:0:0:0:1"))

  test("actual HTTP pipeline accepts trusted HTTPS origin and retains callback and hostile-request defenses"):
    supervised:
      val calls = AtomicInteger()
      val api = new ServerEndpoints:
        override val endpoints: List[ServerEndpoint[Any, Identity]] = List(
          Http.baseEndpoint.post
            .in("probe")
            .in(stringBody)
            .out(stringBody)
            .handleSuccess: body =>
              calls.incrementAndGet()
              body
          ,
          Http.callbackEndpoint.get
            .in("twitch" / "callback")
            .out(stringBody)
            .handle(_ => Left(Fail.Unauthorized("callback state required")))
        )
      val binding = HttpApi(List(api), config, OpenTelemetry.noop()).startOnPort(0)
      def exchange(path: String, headers: List[(String, String)], method: String = "POST"): Int =
        val socket = Socket("127.0.0.1", binding.port)
        try
          socket.setSoTimeout(3000)
          val lines = headers.map((name, value) => s"$name: $value\r\n").mkString
          socket.getOutputStream.write(s"$method $path HTTP/1.1\r\n${lines}Connection: close\r\nContent-Length: 0\r\n\r\n".getBytes(UTF_8))
          val line = StringBuilder()
          var byte = socket.getInputStream.read()
          while byte != -1 && byte != 10 do
            line.append(byte.toChar).discard
            byte = socket.getInputStream.read()
          line.toString.split(" ")(1).toInt
        finally socket.close()
      try
        val browser =
          proxyHeaders ++ List("Origin" -> publicOrigin, "Sec-Fetch-Site" -> "same-origin", "Content-Type" -> "application/json")
        assertEquals(exchange("/api/v1/probe", browser), 200)
        assertEquals(exchange("/api/v1/probe", browser.filterNot(_._1 == "X-Forwarded-Proto")), 403)
        assertEquals(exchange("/api/v1/probe", browser.filterNot(_._1 == "Origin") :+ ("Origin" -> "https://evil.example")), 403)
        assertEquals(exchange("/api/v1/probe", browser :+ ("Forwarded" -> "proto=https")), 403)
        assertEquals(exchange("/api/v1/probe", browser.filterNot(_._1 == "Content-Type")), 415)
        assertEquals(exchange("/docs/docs.yaml", proxyHeaders :+ ("Origin" -> publicOrigin), "GET"), 200)
        val callback = proxyHeaders ++ List("Origin" -> "https://id.twitch.tv", "Sec-Fetch-Site" -> "cross-site")
        assertEquals(exchange("/api/v1/twitch/callback", callback, "GET"), 401)
        assertEquals(exchange("/api/v1/twitch/callback", callback :+ ("X-Forwarded-Host" -> "evil.example"), "GET"), 403)
        assertEquals(calls.get(), 1)
      finally binding.stop()
