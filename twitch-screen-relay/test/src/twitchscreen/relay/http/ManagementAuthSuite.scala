package twitchscreen.relay.http

import ch.qos.logback.classic.{Level, Logger as LogbackLogger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.opentelemetry.api.OpenTelemetry
import java.net.{URI, Socket}
import java.io.ByteArrayInputStream
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.slf4j.LoggerFactory
import ox.{discard, fork, supervised}
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.config.*
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.jdk.CollectionConverters.*

class ManagementAuthSuite extends munit.FunSuite:
  ox.logback.InheritableMDC.init
  private val token = "test-token-is-deliberately-at-least-32-bytes"
  private val password = "test-password-only"
  private val salt = "deterministic-test-salt".getBytes(UTF_8)
  private val key =
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray, salt, 600000, 256)).getEncoded
  private val hash = s"pbkdf2-sha256$$600000$$${Base64.getEncoder.encodeToString(salt)}$$${Base64.getEncoder.encodeToString(key)}"
  private val auth = HttpAuthConfig("operator", Some(Sensitive(hash)), Some(Sensitive(token)))
  private val basic = "Basic " + Base64.getEncoder.encodeToString(s"operator:$password".getBytes(UTF_8))
  private val ProtectedPath = "/api/v1/protected"
  private val ErrorBodyPrefix = "{\"error\":"
  private val AuthenticateHeader = "WWW-Authenticate"
  private val Loopback = "127.0.0.1"

  private def withServer(
      readTimeout: FiniteDuration = 30.seconds,
      requestDeadline: FiniteDuration = 30.seconds,
      host: String = Loopback,
      credentials: HttpAuthConfig = auth
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
          .in("callback")
          .in(stringBody)
          .out(stringBody)
          .handle(_ => Left(Fail.Unauthorized("callback signature required"))),
        Http.baseEndpoint.get.in("failure").out(stringBody).handleSuccess(_ => throw IllegalStateException("private exception detail"))
      )
    val config = HttpConfig(Hostname(host).toOption.get, Port(8080).toOption.get, credentials)
    val binding =
      HttpApi(List(api), config, OpenTelemetry.noop()).startOnPort(0, readTimeout = readTimeout, requestDeadline = requestDeadline)
    test(binding.port, calls)

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

  test("both credential alternatives independently authorize before management logic"):
    withServer(): (port, calls) =>
      assertEquals(request(port, ProtectedPath, Some(basic), Some("basic")).statusCode(), 200)
      assertEquals(request(port, ProtectedPath, Some(s"Bearer $token"), Some("token")).statusCode(), 200)
      assertEquals(calls.get(), 2)

  test("K-100: a token-only config rejects Basic and a Basic-only config rejects Bearer"):
    withServer(credentials = HttpAuthConfig(apiToken = Some(Sensitive(token)))): (port, calls) =>
      assertEquals(request(port, ProtectedPath, Some(basic), Some("basic")).statusCode(), 401)
      assertEquals(request(port, ProtectedPath, Some(s"Bearer $token"), Some("bearer")).statusCode(), 200)
      assertEquals(calls.get(), 1)
    withServer(credentials = HttpAuthConfig("operator", basicPasswordHash = Some(Sensitive(hash)))): (port, calls) =>
      assertEquals(request(port, ProtectedPath, Some(s"Bearer $token"), Some("bearer")).statusCode(), 401)
      assertEquals(request(port, ProtectedPath, Some(basic), Some("basic")).statusCode(), 200)
      assertEquals(calls.get(), 1)

  test("missing malformed and incorrect credentials are JSON 401s without executing handlers"):
    withServer(): (port, calls) =>
      List(None, Some("Basic !!!"), Some("Basic"), Some("Bearer wrong"), Some("Unknown foo")).foreach: credential =>
        val result = request(port, ProtectedPath, credential, Some("body"))
        assertEquals(result.statusCode(), 401)
        assert(result.body().startsWith(ErrorBodyPrefix), result.body())
        assert(result.headers().firstValue(AuthenticateHeader).isPresent)
      assertEquals(calls.get(), 0)

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
      assert(protectedSection.contains("security:"), yaml)
      assert(protectedSection.matches("(?s).*security:\\s+- ManagementBasic: \\[\\]\\s+- ManagementToken: \\[\\].*"), protectedSection)
      val publicSection = yaml.linesIterator.dropWhile(_ != "  /api/v1/health:").drop(1).takeWhile(!_.startsWith("  /")).mkString("\n")
      assert(!publicSection.contains("security:"), publicSection)
      assertEquals(request(port, "/api/v1/callback", Some(s"Bearer $token"), Some("unsigned")).statusCode(), 401)

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

  test("absent or incomplete credentials cannot be constructed and password verification rejects a wrong password"):
    intercept[IllegalArgumentException](HttpAuthConfig()).discard
    intercept[IllegalArgumentException](HttpAuthConfig("operator", apiToken = Some(Sensitive(token)))).discard
    intercept[IllegalArgumentException](HttpAuthConfig(apiToken = Some(Sensitive("short")))).discard
    assert(PasswordVerifier.verify(password, hash))
    assert(!PasswordVerifier.verify("wrong", hash))

  test("password verification has a shared nonblocking two-request limit"):
    supervised:
      val entered = java.util.concurrent.CountDownLatch(2)
      val release = java.util.concurrent.CountDownLatch(1)
      val gate = ManagementAuth(
        auth,
        (_, _) =>
          entered.countDown()
          release.await()
          true
      )
      val endpoint = gate.protect(Http.baseEndpoint.post.in("bounded").out(stringBody).handleSuccess(_ => "ok"))
      val backend = sttp.tapir.server.stub4.TapirSyncStubInterpreter().whenServerEndpointRunLogic(endpoint).backend()
      def send() = sttp.client4.basicRequest
        .post(sttp.model.Uri.unsafeParse("http://localhost/api/v1/bounded"))
        .header("Authorization", basic)
        .send(backend)
      val first = fork(send())
      val second = fork(send())
      try
        assert(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val busy = send()
        assertEquals(busy.code.code, 503)
        assert(busy.header(AuthenticateHeader).isEmpty, busy.headers)
        assert(busy.body.fold(identity, identity).startsWith(ErrorBodyPrefix), busy.body)
      finally release.countDown()
      assertEquals(first.join().code.code, 200)
      assertEquals(second.join().code.code, 200)

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
          authorization.fold("")(value => s"Authorization: $value\r\n") + s"Content-Type: text/plain\r\n$framing\r\n\r\n"
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
            s"POST /api/v1/protected HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $token\r\nContent-Length: ${body.length}\r\n\r\n$body"
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

  test("Basic compares usernames without skipping password work and accepts host case differences"):
    val checked = AtomicInteger()
    val gate = ManagementAuth(
      auth,
      (_, _) =>
        checked.incrementAndGet().discard
        true
    )
    val endpoint = gate.protect(Http.baseEndpoint.post.in("probe").out(stringBody).handleSuccess(_ => "ok"))
    val backend = sttp.tapir.server.stub4.TapirSyncStubInterpreter().whenServerEndpointRunLogic(endpoint).backend()
    def send(username: String) = sttp.client4.basicRequest
      .post(sttp.model.Uri.unsafeParse("http://localhost/api/v1/probe"))
      .header("Authorization", "Basic " + Base64.getEncoder.encodeToString(s"$username:password".getBytes(UTF_8)))
      .header("Host", "LOCALHOST")
      .header("Origin", "http://localhost")
      .send(backend)
    assertEquals(send("wrong").code.code, 401)
    assertEquals(send("operator").code.code, 200)
    assertEquals(checked.get(), 2)
