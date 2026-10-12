package twitchscreen.relay.http

import io.opentelemetry.api.OpenTelemetry
import java.net.{ServerSocket, Socket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import ox.supervised
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.config.{Hostname, HttpConfig, Port}

class HttpRestartSuite extends munit.FunSuite:
  ox.logback.InheritableMDC.init

  test("actual HTTP binding reuses its original port after keepalive and server-closed traffic"):
    val reservation = ServerSocket(0)
    val port = reservation.getLocalPort
    reservation.close()
    val config = HttpConfig(
      Hostname("127.0.0.1").toOption.get,
      Port(port).toOption.get,
      allowedHosts = List(s"127.0.0.1:$port"),
      allowedOrigins = List(s"http://127.0.0.1:$port")
    )
    val api = new ServerEndpoints:
      val endpoints: List[ServerEndpoint[Any, Identity]] =
        List(Http.publicEndpoint.get.in("restart").out(stringBody).handleSuccess(_ => "up"))
    (0 until 3).foreach: _ =>
      supervised:
        val binding = HttpApi(List(api), config, OpenTelemetry.noop()).start()
        try
          val client = HttpClient.newHttpClient()
          try
            val response = client.send(
              HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/api/v1/restart")).build(),
              HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(response.statusCode(), 200)
            assertEquals(response.body(), "up")
          finally client.close()
          val socket = Socket("127.0.0.1", port)
          try
            socket.setSoTimeout(3000)
            socket.getOutputStream.write(
              s"GET /api/v1/restart HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".getBytes(UTF_8)
            )
            socket.getOutputStream.flush()
            assert(String(socket.getInputStream.readAllBytes(), UTF_8).startsWith("HTTP/1.1 200"))
          finally socket.close()
        finally binding.stop()
    val check = new Socket()
    try
      val refused = intercept[java.io.IOException](check.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500))
      assert(refused != null)
    finally check.close()
