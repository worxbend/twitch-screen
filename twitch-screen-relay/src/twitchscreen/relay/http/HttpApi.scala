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

/** The management and monitoring API: every feature's endpoints, the Swagger UI that documents them, and the interceptor chain they all run
  * through.
  */
final class HttpApi(apis: List[ServerEndpoints], config: HttpConfig, otel: OpenTelemetry):
  private val logger = LoggerFactory.getLogger(getClass)

  private val apiEndpoints: List[ServerEndpoint[Any, Identity]] = apis.flatMap(_.endpoints).map(ManagementAuth(config.auth).protect)

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
  def start()(using Ox): NettySyncServerBinding = startOnPort(config.port.value)

  private[http] def startOnPort(port: Int)(using Ox): NettySyncServerBinding =
    val netty = NettyConfig.default
      .host(config.host.value)
      .port(port)
      .maxConnections(128)
      .initPipeline: settings =>
        (pipeline, handler) =>
          NettyConfig.defaultInitPipeline(settings)(pipeline, handler)
          pipeline.addAfter("serverCodecHandler", "requestBodyLimit", RequestBodyLimit(65536)).discard
    NettySyncServer(serverOptions, netty)
      .addEndpoints(apiEndpoints ++ docEndpoints)
      .start()
      .tap: binding =>
        logger.info(s"Management API on http://${config.host.value}:${binding.port}/docs (${apiEndpoints.size} endpoints)")
