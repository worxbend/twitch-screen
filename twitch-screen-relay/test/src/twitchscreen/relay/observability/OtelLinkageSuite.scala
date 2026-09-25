package twitchscreen.relay.observability

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.`export`.SimpleLogRecordProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.{InMemoryLogRecordExporter, InMemoryMetricReader}
import java.util.UUID
import org.slf4j.LoggerFactory
import ox.supervised
import scala.jdk.CollectionConverters.*

/** Exercises the same `Otel.instrument` call startup makes against a real SDK, so a runtime-telemetry or logback-appender release that no
  * longer links against the pinned SDK fails here rather than at relay startup.
  */
class OtelLinkageSuite extends munit.FunSuite:
  private final case class Harness(sdk: OpenTelemetrySdk, metrics: InMemoryMetricReader, logs: InMemoryLogRecordExporter)

  /** The logback appender is global, so it must never be left pointing at a closed SDK for later suites. */
  private def withInstrumentedSdk[A](body: Harness => A): A =
    val metrics = InMemoryMetricReader.create()
    val logs = InMemoryLogRecordExporter.create()
    val sdk = OpenTelemetrySdk
      .builder()
      .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
      .setLoggerProvider(SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(logs)).build())
      .build()
    try
      supervised:
        Otel.instrument(sdk)
        body(Harness(sdk, metrics, logs))
    finally
      OpenTelemetryAppender.install(OpenTelemetry.noop())
      sdk.close()

  test("runtime telemetry exports JVM metrics through SDK 1.66"):
    withInstrumentedSdk: harness =>
      val names = harness.metrics.collectAllMetrics().asScala.map(_.getName).toList.sorted
      assert(names.exists(_.startsWith("jvm.")), s"no jvm.* metric among collected metrics: ${names.mkString(", ")}")

  test("logback appender exports relay log records"):
    withInstrumentedSdk: harness =>
      val marker = s"otel-linkage-${UUID.randomUUID()}"
      LoggerFactory.getLogger("twitchscreen.relay.observability.OtelLinkageSuite").info(marker)
      val bodies = harness.logs.getFinishedLogRecordItems.asScala.flatMap(record => Option(record.getBodyValue)).map(_.asString).toList
      assert(bodies.exists(_.contains(marker)), s"marker $marker not among exported log records: ${bodies.mkString(" | ")}")
