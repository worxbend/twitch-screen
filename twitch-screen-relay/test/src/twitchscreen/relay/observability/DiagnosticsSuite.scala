package twitchscreen.relay.observability

import ch.qos.logback.classic.{Level, Logger}
import ch.qos.logback.classic.spi.LoggingEvent
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.{InstrumentType, SdkMeterProvider}
import io.opentelemetry.sdk.metrics.data.{AggregationTemporality, MetricData}
import io.opentelemetry.sdk.metrics.`export`.{CollectionRegistration, MetricReader}
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import ox.*
import scala.jdk.CollectionConverters.*
import twitchscreen.relay.RelayVersion
import twitchscreen.relay.bus.RelayEvent
import twitchscreen.relay.protocol.Count
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.config.{ChatNotifications, DeviceLinkConfig, Hostname, Port}
import twitchscreen.relay.device.DeviceHub
import twitchscreen.relay.bus.EventBus

class DiagnosticsSuite extends munit.FunSuite:
  test("memory log records bound UTF-8 bytes and redact credential headers"):
    val text = "Authorization: Bearer private-token " + "🎉" * 2000
    LogBuffer.record(LogRecord(Instant.EPOCH, LogLevel.Warn, "test", "test", text))
    val entry = LogBuffer.recent(1, LogLevel.Warn).head
    assert(entry.message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 4096)
    assert(!entry.message.contains("private-token"))
    assert(entry.message.contains("Bearer [redacted]"))
    assert(!entry.message.contains("�"))

  test("in-memory exceptions retain class and call site without leaking exception messages"):
    val logger = LoggerFactory.getLogger("diagnostics-test").asInstanceOf[Logger]
    val event = LoggingEvent("test", logger, Level.ERROR, "operation failed", IllegalArgumentException("secret-token"), Array.empty[Object])
    InMemoryLogAppender().append(event)
    val entry = LogBuffer.recent(1, LogLevel.Error).head
    assert(entry.cause.exists(_.contains("IllegalArgumentException")))
    assert(entry.cause.exists(_.contains("DiagnosticsSuite")))
    assert(!entry.cause.exists(_.contains("secret-token")))

  test("log levels filter severe records and tolerate unknown external levels"):
    assert(LogLevel.Error.atLeast(LogLevel.Warn))
    assert(!LogLevel.Debug.atLeast(LogLevel.Info))
    assertEquals(LogLevel.fromLogback("WARN"), LogLevel.Warn)
    assertEquals(LogLevel.fromLogback("unknown"), LogLevel.Info)

  test("Twitch event metrics exclude observations and link transitions"):
    resourceScope:
      val reader = CollectingReader()
      val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()
      val sdk = useCloseableInScope(OpenTelemetrySdk.builder().setMeterProvider(provider).build())
      val metrics = RelayMetrics(sdk)
      metrics.observe(RelayEvent.Followed("viewer"))
      metrics.observe(RelayEvent.FollowersObserved(Count.Zero))
      metrics.observe(RelayEvent.TwitchLinkDown("network"))
      val measured = reader.collect()
      val counts = measured.map(data => data.getName -> data.getLongSumData.getPoints.asScala.map(_.getValue).sum).toMap
      assertEquals(counts.get("relay.twitch.events"), Some(1L))
      assertEquals(counts.get("relay.twitch.observations"), Some(1L))
      assertEquals(counts.get("relay.twitch.link.transitions"), Some(1L))
      assert(measured.forall(_.getInstrumentationScopeInfo.getVersion == RelayVersion.current))

  test("device gauge unregisters with its scope and never asks a stopped hub"):
    resourceScope:
      val reader = CollectingReader()
      val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()
      val sdk = useCloseableInScope(OpenTelemetrySdk.builder().setMeterProvider(provider).build())
      supervised:
        val clock = java.time.Clock.systemUTC()
        val bus = EventBus(clock, 32)
        val config =
          DeviceLinkConfig(Hostname("localhost").toOption.get, Port(8099).toOption.get, 3, 8, 2.seconds, 5.seconds, 4.seconds, 32, 8, 256)
        val hub = DeviceHub.start(config, ChatNotifications.Show, clock, bus)
        RelayMetrics.start(sdk, bus, hub).discard
        assert(reader.collect().exists(_.getName == "relay.device.connected"))
      assert(!reader.collect().exists(_.getName == "relay.device.connected"))

private final class CollectingReader extends MetricReader:
  private val registration = AtomicReference(CollectionRegistration.noop())
  override def register(value: CollectionRegistration): Unit = registration.set(value)
  override def getAggregationTemporality(instrument: InstrumentType): AggregationTemporality = AggregationTemporality.CUMULATIVE
  override def forceFlush(): CompletableResultCode = CompletableResultCode.ofSuccess()
  override def shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
  def collect(): List[MetricData] = registration.get().collectAllMetrics().asScala.toList
