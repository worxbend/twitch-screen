package twitchscreen.relay.http

import io.opentelemetry.api.OpenTelemetry
import org.slf4j.LoggerFactory
import ox.{Ox, discard, tap}
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.metrics.opentelemetry.OpenTelemetryMetrics
import sttp.tapir.server.model.ValuedEndpointOutput
import sttp.tapir.server.netty.NettyConfig
import sttp.tapir.server.netty.sync.{NettySyncServer, NettySyncServerBinding, NettySyncServerOptions}
import sttp.tapir.server.tracing.opentelemetry.OpenTelemetryTracing
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import twitchscreen.relay.RelayVersion
import twitchscreen.relay.config.HttpConfig
import twitchscreen.relay.observability.SetTraceIdInMDCInterceptor
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.channel.ChannelHandlerContext
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** The management and monitoring API: every feature's endpoints, the Swagger UI that documents them, and the interceptor chain they all run
  * through.
  */
final class HttpApi(apis: List[ServerEndpoints], config: HttpConfig, otel: OpenTelemetry):
  private val logger = LoggerFactory.getLogger(getClass)
  private val managementAuth = ManagementAuth(config.auth)

  private val apiEndpoints: List[ServerEndpoint[Any, Identity]] = apis.flatMap(_.endpoints).map(managementAuth.protect)

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
      readTimeout: FiniteDuration = HttpApi.ReadTimeout
  )(using Ox): NettySyncServerBinding =
    if !Set("localhost", "127.0.0.1", "::1", "[::1]").contains(config.host.value.toLowerCase(java.util.Locale.ROOT)) then
      logger.warn(
        "Management credentials are served over plaintext HTTP on a non-loopback interface; use a trusted TLS proxy and restrict direct access"
      )
    val netty = NettyConfig.default
      .host(config.host.value)
      .port(port)
      .maxConnections(HttpApi.MaxConnections)
      .initPipeline: settings =>
        (pipeline, handler) =>
          NettyConfig.defaultInitPipeline(settings)(pipeline, handler)
          val codec = pipeline.context(classOf[HttpServerCodec]).name()
          // After the codec: partial header bytes cannot indefinitely reset this deadline.
          pipeline.addAfter(codec, "requestReadTimeout", RequestReadTimeout(readTimeout)).discard
          pipeline.addAfter("requestReadTimeout", "requestBodyLimit", RequestBodyLimit(HttpApi.MaxBodyBytes)).discard
    NettySyncServer(serverOptions, netty)
      .addEndpoints(apiEndpoints ++ docEndpoints)
      .start()
      .tap: binding =>
        logger.info(s"Management API on http://${config.host.value}:${binding.port}/docs (${apiEndpoints.size} endpoints)")
        afterBind(binding.port)

object HttpApi:
  private[http] val MaxConnections: Int = 128
  private[http] val MaxBodyBytes: Long = 65536
  private[http] val ReadTimeout: FiniteDuration = 30.seconds

/** An expected slow/incomplete request closes quietly instead of producing an exception stack trace per connection. */
private final class RequestReadTimeout(timeout: FiniteDuration) extends ReadTimeoutHandler(timeout.toMillis, TimeUnit.MILLISECONDS):
  override protected def readTimedOut(context: ChannelHandlerContext): Unit = context.close().discard
