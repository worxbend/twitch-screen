package twitchscreen.relay.observability

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.instrumentation.runtimemetrics.java17.RuntimeMetrics
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import org.slf4j.LoggerFactory
import ox.{ResourceScope, discard, tap, useCloseableInScope}
import scala.jdk.CollectionConverters.*

/** OpenTelemetry, configured entirely from the standard `OTEL_*` environment variables — the relay has no telemetry settings of its own.
  * With none of them set the SDK is a no-op, so an unconfigured Raspberry Pi pays nothing.
  */
private[relay] object Otel:
  private val logger = LoggerFactory.getLogger(getClass)

  /** Defaults, not settings: the SDK gives environment variables and system properties precedence over these, so telemetry is opt-in.
    * Without them the SDK would try to reach a collector on localhost and fill the log with connection failures on every machine that has
    * none — which is every Raspberry Pi running this by default.
    */
  private val optInDefaults = Map(
    "otel.service.name" -> "twitch-screen-relay",
    "otel.traces.exporter" -> "none",
    "otel.metrics.exporter" -> "none",
    "otel.logs.exporter" -> "none"
  )

  def initialize()(using ResourceScope): OpenTelemetry =
    AutoConfiguredOpenTelemetrySdk
      .builder()
      .addPropertiesSupplier(() => optInDefaults.asJava)
      .build()
      .getOpenTelemetrySdk
      .tap(sdk => useCloseableInScope(sdk).discard)
      .tap(sdk => useCloseableInScope(RuntimeMetrics.create(sdk)).discard) // JVM CPU, heap, GC and thread metrics
      .tap(OpenTelemetryAppender.install) // routes Logback records into the OTLP log exporter
      .tap(_ => logger.info("OpenTelemetry initialised; exporters stay off until OTEL_*_EXPORTER is set"))
