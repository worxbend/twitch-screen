package twitchscreen.relay.http

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.slf4j.MDC
import ox.{fork, supervised}
import sttp.client4.*
import sttp.client4.testing.SyncBackendStub
import sttp.model.StatusCode
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.stub4.TapirSyncStubInterpreter
import twitchscreen.relay.config.*
import twitchscreen.relay.observability.SetTraceIdInMDCInterceptor

/** The trace id a request's log lines carry: taken from the request's span, visible in forks, and gone once the request is answered. */
class TraceIdMdcSuite extends munit.FunSuite:
  ox.logback.InheritableMDC.init
  private val MDCKey = SetTraceIdInMDCInterceptor.MDCKey
  private val InvalidTraceId = "0" * 32
  private val incomingTraceId = "4bf92f3577b34da6a3ce929d0e0e4736"
  private val traceparent = s"00-$incomingTraceId-00f067aa0ba902b7-01"

  private val salt = "deterministic-test-salt".getBytes(UTF_8)
  private val key =
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec("password".toCharArray, salt, 600000, 256)).getEncoded
  private val hash = s"pbkdf2-sha256$$600000$$${Base64.getEncoder.encodeToString(salt)}$$${Base64.getEncoder.encodeToString(key)}"
  private val auth = HttpAuthConfig("operator", Some(Sensitive(hash)), Some(Sensitive("test-token-is-deliberately-at-least-32-bytes")))

  /** What one request saw: the MDC in its handler, the MDC in a fork of that handler, and the handler's current span. */
  private final case class Seen(handlerMdc: String, forkMdc: String, spanTraceId: String)

  private final class Capture:
    private val seen = AtomicReference[Option[Seen]](None)

    def run(): Unit =
      val forkMdc = AtomicReference[String](null)
      supervised(fork(forkMdc.set(MDC.get(MDCKey))).join())
      seen.set(Some(Seen(MDC.get(MDCKey), forkMdc.get(), Span.current().getSpanContext.getTraceId)))

    def take(): Seen = seen.getAndSet(None).getOrElse(fail("the handler did not run"))

  private def endpoint(capture: Capture): ServerEndpoint[Any, Identity] =
    Http.publicEndpoint.get
      .in("trace")
      .out(stringBody)
      .handleSuccess: _ =>
        capture.run()
        "ok"

  private def withSdk(body: OpenTelemetrySdk => Unit): Unit =
    val sdk = OpenTelemetrySdk
      .builder()
      .setTracerProvider(SdkTracerProvider.builder().build())
      .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
      .build()
    try body(sdk)
    finally sdk.close()

  private def get(port: Int, headers: List[(String, String)]): HttpResponse[String] =
    val builder = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/api/v1/trace"))
    headers.foreach((name, value) => builder.header(name, value))
    val client = HttpClient.newHttpClient()
    try client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    finally client.close()

  private def assertValidTraceId(id: String): Unit =
    assert(id != null && id.matches("[0-9a-f]{32}") && id != InvalidTraceId, s"not a valid trace id: $id")

  test("a request through the real server carries the active span's trace id in the MDC, including in forks"):
    withSdk: sdk =>
      supervised:
        val capture = Capture()
        val api = new ServerEndpoints:
          override val endpoints: List[ServerEndpoint[Any, Identity]] = List(endpoint(capture))
        val config = HttpConfig(Hostname("127.0.0.1").toOption.get, Port(8080).toOption.get, auth)
        val binding = HttpApi(List(api), config, sdk).startOnPort(0)

        assertEquals(get(binding.port, List("traceparent" -> traceparent)).statusCode(), 200)
        val traced = capture.take()
        assertEquals(traced.spanTraceId, incomingTraceId)
        assertEquals(traced.handlerMdc, incomingTraceId)
        assertEquals(traced.forkMdc, incomingTraceId)

        assertEquals(get(binding.port, Nil).statusCode(), 200)
        val fresh = capture.take()
        assertValidTraceId(fresh.handlerMdc)
        assertEquals(fresh.handlerMdc, fresh.spanTraceId)
        assertEquals(fresh.forkMdc, fresh.spanTraceId)
        assertNotEquals(fresh.spanTraceId, incomingTraceId)

  test("the trace id is cleared once the request completes"):
    withSdk: sdk =>
      val capture = Capture()
      // The stub runs the interceptor and handler on the calling thread, so the MDC left behind is observable here.
      val backend = TapirSyncStubInterpreter(List(SetTraceIdInMDCInterceptor), SyncBackendStub)
        .whenServerEndpointsRunLogic(List(endpoint(capture)))
        .backend()
      val span = sdk.getTracer("test").spanBuilder("t").startSpan()
      try
        val scope = span.makeCurrent()
        try
          assertEquals(MDC.get(MDCKey), null)
          val response = basicRequest.get(uri"http://localhost:8080/api/v1/trace").send(backend)
          assertEquals(response.code, StatusCode.Ok)
          val seen = capture.take()
          assertValidTraceId(seen.handlerMdc)
          assertEquals(seen.handlerMdc, span.getSpanContext.getTraceId)
          assertEquals(seen.forkMdc, span.getSpanContext.getTraceId)
          assertEquals(MDC.get(MDCKey), null)
        finally scope.close()
      finally span.end()
