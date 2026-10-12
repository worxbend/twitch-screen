package twitchscreen.relay.http

import io.opentelemetry.api.OpenTelemetry
import org.slf4j.LoggerFactory
import ox.{Ox, discard, tap, useInScope}
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.metrics.opentelemetry.OpenTelemetryMetrics
import sttp.tapir.server.model.ValuedEndpointOutput
import sttp.tapir.server.netty.NettyConfig
import sttp.tapir.server.netty.sync.{NettySyncServer, NettySyncServerBinding, NettySyncServerOptions}
import sttp.tapir.server.tracing.opentelemetry.OpenTelemetryTracing
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import twitchscreen.relay.RelayVersion
import twitchscreen.relay.config.{Hostname, HttpConfig}
import twitchscreen.relay.observability.SetTraceIdInMDCInterceptor
import io.netty.handler.codec.http.{HttpObjectAggregator, HttpServerCodec}
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.channel.ChannelHandlerContext
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** The management and monitoring API: every feature's endpoints, the Swagger UI that documents them, and the interceptor chain they all run
  * through.
  */
final class HttpApi(apis: List[ServerEndpoints], config: HttpConfig, otel: OpenTelemetry):
  private val logger = LoggerFactory.getLogger(getClass)
  private val apiEndpoints: List[ServerEndpoint[sttp.tapir.server.netty.sync.OxStreams, Identity]] = apis
    .flatMap(api => api.endpoints ++ api.streamingEndpoints)
    .map: endpoint =>
      require(endpoint.attribute(Http.Access).isDefined, s"Endpoint lacks an access classification: ${endpoint.show}")
      endpoint

  private val docEndpoints: List[ServerEndpoint[Any, Identity]] =
    SwaggerInterpreter().fromServerEndpoints[Identity](apiEndpoints, "twitch-screen-relay", RelayVersion.current)

  private val serverOptions: NettySyncServerOptions = NettySyncServerOptions.customiseInterceptors
    // Tracing first: it extracts `traceparent` and opens the span every later interceptor and log line belongs to.
    .prependInterceptor(OpenTelemetryTracing(otel))
    .prependInterceptor(SetTraceIdInMDCInterceptor)
    // Decode failures and unmatched routes get the same JSON error shape as endpoint failures.
    .defaultHandlers(message => ValuedEndpointOutput(Http.jsonErrorOutOutput, Error_OUT(message)), notFoundWhenRejected = true)
    .metricsInterceptor(OpenTelemetryMetrics.default[Identity](otel).metricsInterceptor())
    .options

  /** Binds and serves. The server is registered in the enclosing scope and stops with it. */
  def start(afterBind: Int => Unit = _ => ())(using Ox): NettySyncServerBinding = startOnPort(config.port.value, afterBind)

  private[http] def startOnPort(
      port: Int,
      afterBind: Int => Unit = _ => (),
      readTimeout: FiniteDuration = HttpApi.ReadTimeout,
      requestDeadline: FiniteDuration = HttpApi.WholeRequestTimeout
  )(using Ox): NettySyncServerBinding =
    if !HttpApi.isLoopback(config.host) then logger.warn(HttpApi.PlaintextNonLoopbackWarning)
    val netty = NettyConfig.default
      .host(config.host.value)
      .port(port)
      .maxConnections(HttpApi.MaxConnections)
      .socketConfig(NettyConfig.default.socketConfig.withReuseAddress)
      .initPipeline: settings =>
        (pipeline, handler) =>
          NettyConfig.defaultInitPipeline(settings)(pipeline, handler)
          val codec = pipeline.context(classOf[HttpServerCodec]).name()
          // Both deadlines sit after the codec, so partial header bytes cannot reset them. The whole-request deadline comes first so it
          // also sees the body chunks RequestBodyLimit drops after a rejection; decoded body chunks never extend it.
          pipeline.addAfter(codec, "requestDeadline", RequestDeadline(requestDeadline)).discard
          pipeline.addAfter("requestDeadline", "requestReadTimeout", RequestReadTimeout(readTimeout)).discard
          pipeline.addAfter("requestReadTimeout", "browserBoundary", BrowserBoundary(config)).discard
          pipeline.addAfter("browserBoundary", "sseLifecycle", SseLifecycle(readTimeout)).discard
          pipeline.addAfter("sseLifecycle", "requestBodyLimit", RequestBodyLimit(HttpApi.MaxBodyBytes)).discard
          // Tapir's streaming adapter can interpret channel EOF as body completion. Only release a request to it after Netty has
          // decoded LastHttpContent. Timeout, disconnect and size-limit closure then discard the bounded partial body instead of
          // allowing a syntactically valid JSON prefix to reach mutation logic. Response streaming is unaffected.
          pipeline.addAfter("requestBodyLimit", "completeRequest", HttpObjectAggregator(HttpApi.MaxBodyBytes.toInt)).discard
          pipeline.addAfter("completeRequest", "completedRequestAdapter", CompletedRequestAdapter()).discard
    val raw = NettySyncServer(serverOptions, netty).addEndpoints(apiEndpoints ++ docEndpoints).start()
    val stopLock = new Object()
    var stopped = false
    val binding = useInScope(
      raw.copy(stop =
        () =>
          stopLock.synchronized:
            if !stopped then
              stopped = true
              raw.stop()
      )
    )(_.stop())
    binding.tap: binding =>
      logger.info(s"Management API on http://${config.host.value}:${binding.port}/docs (${apiEndpoints.size} endpoints)")
      afterBind(binding.port)

object HttpApi:
  private val LoopbackHosts: Set[String] = Set("localhost", "127.0.0.1", "::1", "[::1]")

  /** True when `host` binds only the loopback interface, so management credentials never leave the machine in plaintext. */
  private[http] def isLoopback(host: Hostname): Boolean = LoopbackHosts.contains(host.value.toLowerCase(java.util.Locale.ROOT))

  private[http] val PlaintextNonLoopbackWarning: String =
    "Private management is unauthenticated over plaintext HTTP on a non-loopback interface; restrict access to a trusted LAN/VPN"

  private[http] val MaxConnections: Int = 128
  private[http] val MaxBodyBytes: Long = 65536
  private[http] val ReadTimeout: FiniteDuration = 30.seconds

  /** Headers plus body, however slowly the body arrives. */
  private[http] val WholeRequestTimeout: FiniteDuration = 30.seconds

/** An expected slow/incomplete request closes quietly instead of producing an exception stack trace per connection. */
private final class RequestReadTimeout(timeout: FiniteDuration) extends ReadTimeoutHandler(timeout.toMillis, TimeUnit.MILLISECONDS):
  override protected def readTimedOut(context: ChannelHandlerContext): Unit = context.close().discard
