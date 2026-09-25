package twitchscreen.relay.http

import io.opentelemetry.api.OpenTelemetry
import java.net.URI
import java.io.ByteArrayInputStream
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import ox.{discard, supervised}
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.config.*

class ManagementAuthSuite extends munit.FunSuite:
  ox.logback.InheritableMDC.init
  private val token = "test-token-is-deliberately-at-least-32-bytes"
  private val password = "test-password-only"
  private val salt = "deterministic-test-salt".getBytes(UTF_8)
  private val key =
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray, salt, 600000, 256)).getEncoded
  private val hash = s"pbkdf2-sha256$$600000$$${Base64.getEncoder.encodeToString(salt)}$$${Base64.getEncoder.encodeToString(key)}"
  private val auth = HttpAuthConfig("operator", Sensitive(hash), Sensitive(token))
  private val basic = "Basic " + Base64.getEncoder.encodeToString(s"operator:$password".getBytes(UTF_8))

  private def withServer(test: (Int, AtomicInteger) => Unit): Unit = supervised:
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
    val config = HttpConfig(Hostname("127.0.0.1").toOption.get, Port(8080).toOption.get, auth)
    val binding = HttpApi(List(api), config, OpenTelemetry.noop()).startOnPort(0)
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

  test("both credential alternatives independently authorize before management logic"):
    withServer: (port, calls) =>
      assertEquals(request(port, "/api/v1/protected", Some(basic), Some("basic")).statusCode(), 200)
      assertEquals(request(port, "/api/v1/protected", Some(s"Bearer $token"), Some("token")).statusCode(), 200)
      assertEquals(calls.get(), 2)

  test("missing malformed and incorrect credentials are JSON 401s without executing handlers"):
    withServer: (port, calls) =>
      List(None, Some("Basic !!!"), Some("Basic"), Some("Bearer wrong"), Some("Unknown foo")).foreach: credential =>
        val result = request(port, "/api/v1/protected", credential, Some("body"))
        assertEquals(result.statusCode(), 401)
        assert(result.body().startsWith("{\"error\":"), result.body())
        assert(result.headers().firstValue("WWW-Authenticate").isPresent)
      assertEquals(calls.get(), 0)

  test("Basic browser cross-site management is rejected; same-origin succeeds"):
    withServer: (port, calls) =>
      assertEquals(request(port, "/api/v1/protected", Some(basic), Some("bad"), List("Origin" -> "https://evil.example")).statusCode(), 403)
      assertEquals(request(port, "/api/v1/protected", Some(basic), Some("bad"), List("Sec-Fetch-Site" -> "cross-site")).statusCode(), 403)
      assertEquals(
        request(port, "/api/v1/protected", Some(basic), Some("ok"), List("Origin" -> s"http://127.0.0.1:$port")).statusCode(),
        200
      )
      assertEquals(calls.get(), 1)

  test("public routes and docs require no credentials; management tokens cannot bypass callback checks"):
    withServer: (port, _) =>
      assertEquals(request(port, "/api/v1/health").statusCode(), 200)
      val docs = request(port, "/docs/docs.yaml")
      assertEquals(docs.statusCode(), 200)
      assert(docs.body().contains("ManagementBasic") && docs.body().contains("ManagementToken"), docs.body())
      assertEquals(request(port, "/api/v1/callback", Some(s"Bearer $token"), Some("unsigned")).statusCode(), 401)

  test("oversized bodies are bounded before handling and exception responses preserve JSON without details"):
    withServer: (port, calls) =>
      assertEquals(request(port, "/api/v1/protected", Some(s"Bearer $token"), Some("x" * 65537)).statusCode(), 413)
      assertEquals(request(port, "/api/v1/protected", Some(s"Bearer $token"), Some("x" * 65537), chunked = true).statusCode(), 413)
      assertEquals(calls.get(), 0)
      val failure = request(port, "/api/v1/failure", Some(s"Bearer $token"))
      assertEquals(failure.statusCode(), 500)
      assertEquals(failure.body(), "{\"error\":\"Internal server error\"}")
      assert(failure.headers().firstValue("Content-Type").orElse("").contains("application/json"))

  test("startup rejects absent or incomplete credentials and password verification rejects a wrong password"):
    intercept[IllegalArgumentException](ManagementAuth(HttpAuthConfig())).discard
    intercept[IllegalArgumentException](ManagementAuth(HttpAuthConfig("operator", apiToken = Sensitive(token)))).discard
    intercept[IllegalArgumentException](ManagementAuth(HttpAuthConfig(apiToken = Sensitive("short")))).discard
    assert(PasswordVerifier.verify(password, hash))
    assert(!PasswordVerifier.verify("wrong", hash))
