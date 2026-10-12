package twitchscreen.relay.http

import ch.qos.logback.classic.{Level, Logger as LogbackLogger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.opentelemetry.api.OpenTelemetry
import java.net.{URI, Socket}
import java.io.ByteArrayInputStream
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import ox.{discard, supervised}
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.config.*
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.jdk.CollectionConverters.*

class ManagementAuthSuite extends munit.FunSuite:
  ox.logback.InheritableMDC.init
  private val token = "ignored-test-header"
  private val basic = "Basic ignored"
  private val ProtectedPath = "/api/v1/protected"
  private val ErrorBodyPrefix = "{\"error\":"
  private val AuthenticateHeader = "WWW-Authenticate"
  private val Loopback = "127.0.0.1"

  private def withServer(
      readTimeout: FiniteDuration = 30.seconds,
      requestDeadline: FiniteDuration = 30.seconds,
      host: String = Loopback
  )(
      test: (Int, AtomicInteger) => Unit
  ): Unit = supervised:
    val calls = AtomicInteger()
    val api = new ServerEndpoints:
      override val endpoints: List[ServerEndpoint[Any, Identity]] = List(
        Http.baseEndpoint.post
          .in("protected")
          .in(stringBody)
          .out(stringBody)
          .handleSuccess: body =>
            calls.incrementAndGet()
            body
        ,
        Http.publicEndpoint.get.in("health").out(stringBody).handleSuccess(_ => "up"),
        Http.callbackEndpoint.post
          .in("twitch" / "callback")
          .in(stringBody)
          .out(stringBody)
          .handle(_ => Left(Fail.Unauthorized("callback signature required"))),
        Http.baseEndpoint.get.in("failure").out(stringBody).handleSuccess(_ => throw IllegalStateException("private exception detail"))
      )
    val reservation = java.net.ServerSocket(0)
    val port = reservation.getLocalPort
    reservation.close()
    val config = HttpConfig(
      Hostname(host).toOption.get,
      Port(port).toOption.get,
      allowedHosts = List(s"127.0.0.1:$port"),
      allowedOrigins = List(s"http://127.0.0.1:$port")
    )
    val binding =
      HttpApi(List(api), config, OpenTelemetry.noop()).startOnPort(port, readTimeout = readTimeout, requestDeadline = requestDeadline)
    try test(binding.port, calls)
    finally binding.stop()

  private def request(
      port: Int,
      path: String,
      authorization: Option[String] = None,
      body: Option[String] = None,
      headers: List[(String, String)] = Nil,
      chunked: Boolean = false
  ): HttpResponse[String] =
    val builder = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
    authorization.foreach(builder.header("Authorization", _))
    headers.foreach((name, value) => builder.header(name, value))
    body.foreach: text =>
      builder.header("Content-Type", "application/json").discard
      val publisher =
        if chunked then HttpRequest.BodyPublishers.ofInputStream(() => ByteArrayInputStream(text.getBytes(UTF_8)))
        else HttpRequest.BodyPublishers.ofString(text)
      builder.POST(publisher).discard
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    try client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    finally client.close()

  /** Starts and stops the API on `host` and returns the WARN messages that say management credentials travel in plaintext. */
  private def plaintextWarnings(host: String): List[String] =
    val logger = LoggerFactory.getLogger(classOf[HttpApi]).asInstanceOf[LogbackLogger]
    val appender = ListAppender[ILoggingEvent]()
    appender.start()
    logger.addAppender(appender)
    try withServer(host = host)((_, _) => ())
    finally
      logger.detachAppender(appender).discard
      appender.stop()
    appender.list.asScala.toList
      .filter(_.getLevel == Level.WARN)
      .map(_.getFormattedMessage)
      .filter(_.contains("plaintext HTTP on a non-loopback interface"))

  /** Writes `head`, then `piece` every `interval` until the server closes the socket or `cap` passes. Returns the time the server took to
    * close the connection, or None if it never did within `cap`.
    */
  private def trickleUntilClosed(
      port: Int,
      head: String,
      piece: String,
      interval: FiniteDuration,
      cap: FiniteDuration = 5.seconds
  ): Option[FiniteDuration] =
    val socket = Socket(Loopback, port)
    try
      socket.setSoTimeout(cap.toMillis.toInt)
      val started = System.nanoTime()
      socket.getOutputStream.write(head.getBytes(UTF_8))
      val stop = java.util.concurrent.atomic.AtomicBoolean(false)
      val writer = Thread
        .ofVirtual()
        .start: () =>
          try
            while !stop.get() && System.nanoTime() - started < cap.toNanos do
              Thread.sleep(interval.toMillis)
              socket.getOutputStream.write(piece.getBytes(UTF_8))
              socket.getOutputStream.flush()
          catch case _: java.io.IOException | _: InterruptedException => ()
      val closed =
        try
          val in = socket.getInputStream
          while in.read() != -1 do ()
          true
        catch
          case _: java.net.SocketTimeoutException => false
          case _: java.io.IOException             => true
      val elapsed = (System.nanoTime() - started).nanos
      stop.set(true)
      writer.join()
      Option.when(closed)(elapsed)
    finally socket.close()

  /** Reads one HTTP/1.1 response with a Content-Length body from a raw socket and returns its status code and body. */
  private def readResponse(in: java.io.InputStream): (Int, String) =
    val head = StringBuilder()
    while !head.endsWith("\r\n\r\n") do
      val byte = in.read()
      assert(byte != -1, s"connection closed while reading a response: $head")
      head.append(byte.toChar).discard
    val lines = head.toString.split("\r\n").toList
    val status = lines.head.split(" ")(1).toInt
    val length = lines
      .collectFirst {
        case line if line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:") =>
          line.drop("content-length:".length).trim.toInt
      }
      .getOrElse(0)
    (status, String(in.readNBytes(length), UTF_8))

  test("credential-free management ignores Authorization but never emits a challenge"):
    withServer(): (port, calls) =>
      List(None, Some(basic), Some(s"Bearer $token"), Some("Basic !!!")).foreach: credential =>
        val result = request(port, ProtectedPath, credential, Some("body"))
        assertEquals(result.statusCode(), 200)
        assert(!result.headers().firstValue(AuthenticateHeader).isPresent)
      assertEquals(calls.get(), 4)

  test("Basic browser cross-site management is rejected; same-origin succeeds"):
    withServer(): (port, calls) =>
      val rejected = request(port, ProtectedPath, Some(basic), Some("bad"), List("Origin" -> "https://evil.example"))
      assertEquals(rejected.statusCode(), 403)
      assert(!rejected.headers().firstValue(AuthenticateHeader).isPresent)
      assert(rejected.body().startsWith(ErrorBodyPrefix))
      assertEquals(request(port, ProtectedPath, Some(basic), Some("bad"), List("Sec-Fetch-Site" -> "cross-site")).statusCode(), 403)
      assertEquals(
        request(port, ProtectedPath, Some(basic), Some("ok"), List("Origin" -> s"http://127.0.0.1:$port")).statusCode(),
        200
      )
      assertEquals(calls.get(), 1)

  test("public routes and docs require no credentials; management tokens cannot bypass callback checks"):
    withServer(): (port, _) =>
      assertEquals(request(port, "/api/v1/health").statusCode(), 200)
      val missing = request(port, "/unknown-route")
      assertEquals(missing.statusCode(), 404)
      assert(missing.body().startsWith(ErrorBodyPrefix))
      val docs = request(port, "/docs/docs.yaml")
      assertEquals(docs.statusCode(), 200)
      val yaml = docs.body()
      val protectedSection =
        yaml.linesIterator.dropWhile(_ != "  /api/v1/protected:").drop(1).takeWhile(!_.startsWith("  /")).mkString("\n")
      assert(!protectedSection.contains("security:"), yaml)
      val publicSection = yaml.linesIterator.dropWhile(_ != "  /api/v1/health:").drop(1).takeWhile(!_.startsWith("  /")).mkString("\n")
      assert(!publicSection.contains("security:"), publicSection)
      assertEquals(request(port, "/api/v1/twitch/callback", Some(s"Bearer $token"), Some("unsigned")).statusCode(), 401)
      val foreign = request(
        port,
        "/api/v1/twitch/callback",
        body = Some("unsigned"),
        headers = List("Origin" -> "https://id.twitch.tv", "Sec-Fetch-Site" -> "cross-site")
      )
      assertEquals(foreign.statusCode(), 401)
      List("/api/v1/health", "/docs/docs.yaml", "/unknown-route").foreach: path =>
        assertEquals(request(port, path, headers = List("Origin" -> "null")).statusCode(), 403)
        assertEquals(request(port, path, headers = List("Origin" -> "https://evil.example")).statusCode(), 403)
      assertEquals(
        request(port, ProtectedPath, Some(s"Bearer $token"), Some("bad"), List("Origin" -> "https://evil.example")).statusCode(),
        403
      )
      val missingType = Socket(Loopback, port)
      try
        missingType.setSoTimeout(3000)
        missingType.getOutputStream.write(
          s"POST $ProtectedPath HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: 0\r\n\r\n".getBytes(UTF_8)
        )
        assertEquals(readResponse(missingType.getInputStream)._1, 415)
      finally missingType.close()

  test("raw Host boundary rejects wrong duplicate malformed authorities before any route"):
    withServer(): (port, calls) =>
      List("Host: evil.example", "Host: user@localhost", "Host: localhost:", s"Host: 127.0.0.1:$port\r\nHost: 127.0.0.1:$port").foreach:
        headers =>
          List("/api/v1/twitch/callback", "/docs/docs.yaml", "/unknown-route").foreach: path =>
            val socket = Socket(Loopback, port)
            try
              socket.setSoTimeout(3000)
              socket.getOutputStream.write(s"GET $path HTTP/1.1\r\n$headers\r\nConnection: close\r\n\r\n".getBytes(UTF_8))
              val (status, _) = readResponse(socket.getInputStream)
              assert(Set(400, 403).contains(status), s"$path: $status")
            finally socket.close()
      assertEquals(calls.get(), 0)

  test("oversized bodies are bounded before handling and exception responses preserve JSON without details"):
    withServer(): (port, calls) =>
      List(false, true).foreach: chunked =>
        val oversized = request(port, ProtectedPath, Some(s"Bearer $token"), Some("x" * 65537), chunked = chunked)
        assertEquals(oversized.statusCode(), 413)
        assert(oversized.body().startsWith(ErrorBodyPrefix))
      assertEquals(calls.get(), 0)
      val failure = request(port, "/api/v1/failure", Some(s"Bearer $token"))
      assertEquals(failure.statusCode(), 500)
      assertEquals(failure.body(), "{\"error\":\"Internal server error\"}")
      assert(failure.headers().firstValue("Content-Type").orElse("").contains("application/json"))

  test("a non-loopback bind warns once that management credentials travel in plaintext"):
    assertEquals(plaintextWarnings("0.0.0.0").size, 1)

  test("loopback binds do not warn about plaintext management credentials"):
    List(Loopback, "LOCALHOST").foreach: host =>
      assertEquals(plaintextWarnings(host), Nil, host)

  test("loopback classification is case-insensitive and covers IPv4, IPv6 and bracketed IPv6"):
    def loopback(host: String) = HttpApi.isLoopback(Hostname(host).toOption.get)
    List("localhost", "LOCALHOST", Loopback, "::1", "[::1]", " localhost ").foreach(host => assert(loopback(host), host))
    List("0.0.0.0", "::", "192.168.1.10", "relay.example.com").foreach(host => assert(!loopback(host), host))

  test("silent connections and incomplete HTTP headers hit a read deadline"):
    withServer(readTimeout = 200.millis): (port, calls) =>
      List("", "GET /api/v1/health HTTP/1.1\r\nHost: local").foreach: prefix =>
        val socket = Socket(Loopback, port)
        try
          socket.setSoTimeout(3000)
          socket.getOutputStream.write(prefix.getBytes(UTF_8))
          assertEquals(socket.getInputStream.read(), -1)
        finally socket.close()
      assertEquals(calls.get(), 0)

  test("a slowly trickled body cannot extend the whole-request deadline"):
    // The trickle interval is shorter than readTimeout, so the read deadline alone would never fire.
    withServer(readTimeout = 400.millis, requestDeadline = 1.second): (port, calls) =>
      def head(authorization: Option[String], framing: String) =
        s"POST /api/v1/protected HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
          authorization.fold("")(value => s"Authorization: $value\r\n") + s"Content-Type: application/json\r\n$framing\r\n\r\n"
      val cases = List(
        "authenticated fixed-length" -> (head(Some(s"Bearer $token"), "Content-Length: 1000"), "A"),
        "authenticated chunked" -> (head(Some(s"Bearer $token"), "Transfer-Encoding: chunked"), "1\r\nA\r\n"),
        "unauthenticated fixed-length" -> (head(None, "Content-Length: 1000"), "A")
      )
      cases.foreach:
        case (name, (requestHead, piece)) =>
          trickleUntilClosed(port, requestHead, piece, 100.millis) match
            case None => fail(s"$name: a trickled body held the connection open past the cap")
            case Some(elapsed) =>
              assert(elapsed >= 800.millis, s"$name closed after $elapsed, before the whole-request deadline")
              assert(elapsed < 3.seconds, s"$name closed after $elapsed, well past the whole-request deadline")
      assertEquals(calls.get(), 0)

  test("the whole-request deadline restarts for each keep-alive request and ends with the body"):
    withServer(readTimeout = 5.seconds, requestDeadline = 500.millis): (port, calls) =>
      val socket = Socket(Loopback, port)
      try
        socket.setSoTimeout(3000)
        val out = socket.getOutputStream
        val in = socket.getInputStream
        def send(body: String) =
          out.write(
            s"POST /api/v1/protected HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\nAuthorization: Bearer $token\r\nContent-Length: ${body.length}\r\n\r\n$body"
              .getBytes(UTF_8)
          )
          out.flush()
        send("first")
        assertEquals(readResponse(in), (200, "first"))
        // Idle for longer than the whole-request deadline: a completed request must not leave its timer running.
        Thread.sleep(900)
        send("second")
        assertEquals(readResponse(in), (200, "second"))
      finally socket.close()
      assertEquals(calls.get(), 2)
