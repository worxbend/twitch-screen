package twitchscreen.relay.http

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.*
import io.opentelemetry.api.OpenTelemetry
import java.net.{ServerSocket, Socket}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicInteger
import ox.{discard, supervised}
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.config.{Hostname, HttpConfig, Port}
import twitchscreen.relay.device.NotificationApi
import scala.concurrent.duration.DurationInt

class RequestCompletionSuite extends munit.FunSuite:
  ox.logback.InheritableMDC.init
  private val prefix = """{"type":"info","title":"Synthetic completion","body":""}"""

  test("the bounded completion fence releases only a decoded final content and discards partial bodies on close"):
    List(false, true).foreach: complete =>
      val channel = EmbeddedChannel(HttpObjectAggregator(HttpApi.MaxBodyBytes.toInt), CompletedRequestAdapter())
      try
        val request = DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/v1/notifications")
        request.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, prefix.length).discard
        assert(!channel.writeInbound(request))
        assert(!channel.writeInbound(DefaultHttpContent(Unpooled.copiedBuffer(prefix, UTF_8))))
        if complete then
          assert(channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT))
          val head = channel.readInbound[HttpRequest]()
          assertEquals(head.uri(), "/api/v1/notifications")
          val last = channel.readInbound[LastHttpContent]()
          try assertEquals(last.content().toString(UTF_8), prefix)
          finally last.release().discard
        else
          channel.close().discard
          assertEquals(channel.readInbound[Object](), null)
      finally channel.finishAndReleaseAll().discard

  private def withServer(idle: Boolean, expectedCalls: Int = 0)(test: (Int, AtomicInteger) => Unit): Unit =
    val calls = AtomicInteger()
    supervised:
      val api = new ServerEndpoints:
        override val endpoints: List[ServerEndpoint[Any, Identity]] = List(
          NotificationApi.createEndpoint.handle: _ =>
            calls.incrementAndGet().discard
            Left(Fail.Unavailable("Synthetic handler reached"))
        )
      val reservation = ServerSocket(0)
      val port = reservation.getLocalPort
      reservation.close()
      val config = HttpConfig(
        Hostname("127.0.0.1").toOption.get,
        Port(port).toOption.get,
        allowedHosts = List(s"127.0.0.1:$port"),
        allowedOrigins = List(s"http://127.0.0.1:$port")
      )
      val binding = HttpApi(List(api), config, OpenTelemetry.noop()).startOnPort(
        port,
        readTimeout = if idle then 150.millis else 2.seconds,
        requestDeadline = if idle then 2.seconds else 150.millis
      )
      try test(port, calls)
      finally binding.stop()
    // Assert after the structured server scope has joined its workers, not only after the client's EOF.
    assertEquals(calls.get(), expectedCalls)

  private def send(port: Int, framing: String, body: String, disconnect: Boolean): String =
    val socket = Socket("127.0.0.1", port)
    try
      socket.setSoTimeout(3000)
      socket.getOutputStream.write(
        s"POST /api/v1/notifications HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\n$framing\r\n\r\n$body"
          .getBytes(UTF_8)
      )
      socket.getOutputStream.flush()
      if disconnect then socket.shutdownOutput()
      String(socket.getInputStream.readAllBytes(), UTF_8)
    finally socket.close()

  test("complete typed JSON bodies dispatch once with fixed-length or terminated chunked framing"):
    withServer(idle = true, expectedCalls = 2): (port, calls) =>
      val fixed = send(port, s"Content-Length: ${prefix.length}", prefix, disconnect = false)
      assert(fixed.startsWith("HTTP/1.1 503"), fixed)
      val chunk = s"${prefix.length.toHexString}\r\n$prefix\r\n0\r\n\r\n"
      val chunked = send(port, "Transfer-Encoding: chunked", chunk, disconnect = false)
      assert(chunked.startsWith("HTTP/1.1 503"), chunked)
      assertEquals(calls.get(), 2)

  test("valid typed JSON prefixes never dispatch after deadline idle-timeout or client EOF for either framing"):
    List(false, true).foreach: idle =>
      withServer(idle): (port, calls) =>
        for
          disconnect <- List(false, true)
          chunked <- List(false, true)
          _ <- 0 until 12
        do
          val framing = if chunked then "Transfer-Encoding: chunked" else "Content-Length: 1000"
          val body = if chunked then s"${prefix.length.toHexString}\r\n$prefix\r\n" else prefix
          send(port, framing, body, disconnect).discard
        assertEquals(calls.get(), 0)

  test("body-limit closure cannot dispatch a valid JSON prefix or an unterminated oversized chunk"):
    withServer(idle = false): (port, calls) =>
      val declared = send(port, "Content-Length: 65537", prefix, disconnect = false)
      assert(declared.startsWith("HTTP/1.1 413"), declared)
      val oversized = prefix + (" " * 65537)
      val chunk = s"${oversized.length.toHexString}\r\n$oversized\r\n"
      val streamed = send(port, "Transfer-Encoding: chunked", chunk, disconnect = false)
      assert(streamed.startsWith("HTTP/1.1 413"), streamed)
      assertEquals(calls.get(), 0)

  test("ordinary ingress accepts complete 16–64 KiB bodies and the exact 64 KiB boundary in both framings"):
    withServer(idle = true, expectedCalls = 6): (port, calls) =>
      // Each text field has 4096 UTF-16 units; escaped JSON bytes are independently bounded.
      val escaped = "\\u00e9" * 4096
      val large = s"{\"type\":\"info\",\"title\":\"$escaped\",\"body\":\"$escaped\"}"
      List(prefix + (" " * 16385), large, prefix + (" " * (65536 - prefix.length))).foreach: body =>
        val fixed = send(port, s"Content-Length: ${body.length}", body, disconnect = false)
        assert(fixed.startsWith("HTTP/1.1 503"), fixed)
        val chunk = s"${body.length.toHexString}\r\n$body\r\n0\r\n\r\n"
        val streamed = send(port, "Transfer-Encoding: chunked", chunk, disconnect = false)
        assert(streamed.startsWith("HTTP/1.1 503"), streamed)
      assertEquals(calls.get(), 6)
