package twitchscreen.relay.observability

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.metrics.LongCounter
import ox.{Ox, discard, useCloseableInScope}
import twitchscreen.relay.RelayVersion
import twitchscreen.relay.bus.{EventBus, EventCategory, RelayEvent}
import twitchscreen.relay.device.DeviceHub

/** The relay's own metrics, alongside the HTTP ones Tapir's interceptor records.
  *
  * They are fed from the event bus rather than from call sites, so instrumentation never has to be remembered when a new producer is added
  * — anything that reaches the bus is counted. Which counter an event moves is derived from `RelayEvent.category`, refined only by
  * `isObservation` and the device connect/disconnect split, never from a separate list of event cases.
  */
private[relay] final class RelayMetrics(otel: OpenTelemetry):
  private val meter = otel.meterBuilder("twitch-screen-relay").setInstrumentationVersion(RelayVersion.current).build()

  private val notifications: LongCounter = meter
    .counterBuilder("relay.notifications.published")
    .setDescription("Notifications sequenced and pushed to devices")
    .build()

  private val deviceConnections: LongCounter = meter
    .counterBuilder("relay.device.connections")
    .setDescription("Device connections accepted since start")
    .build()

  private val deviceDisconnections: LongCounter = meter
    .counterBuilder("relay.device.disconnections")
    .setDescription("Device connections closed since start")
    .build()

  private val twitchEvents: LongCounter = meter
    .counterBuilder("relay.twitch.events")
    .setDescription("Events received from the configured Twitch source")
    .build()

  private val failures: LongCounter = meter
    .counterBuilder("relay.failures")
    .setDescription("Recoverable failures reported by any relay component")
    .build()

  private val observations: LongCounter =
    meter.counterBuilder("relay.twitch.observations").setDescription("Audience totals observed by polling").build()
  private val twitchLinks: LongCounter =
    meter.counterBuilder("relay.twitch.link.transitions").setDescription("Twitch connectivity state changes").build()

  /** Exhaustive over [[EventCategory]] with no wildcard, so a new category fails to compile here until it is given a counter. */
  private def counterFor(event: RelayEvent): LongCounter = event.category match
    case EventCategory.Notification => notifications
    case EventCategory.Failure      => failures
    case EventCategory.Twitch       => twitchLinks
    case EventCategory.Device =>
      event match
        case _: RelayEvent.DeviceConnected => deviceConnections
        case _                             => deviceDisconnections
    case EventCategory.Channel if event.isObservation   => observations
    case EventCategory.Channel | EventCategory.Audience => twitchEvents

  private[observability] def observe(event: RelayEvent): Unit = counterFor(event).add(1)

private[relay] object RelayMetrics:
  def start(otel: OpenTelemetry, bus: EventBus, hub: DeviceHub)(using Ox): RelayMetrics =
    val metrics = RelayMetrics(otel)
    useCloseableInScope(
      metrics.meter
        .gaugeBuilder("relay.device.connected")
        .setDescription("Devices currently attached to the relay")
        .buildWithCallback(measurement => measurement.record(hub.connectedCount.toDouble))
    ).discard
    useCloseableInScope(
      metrics.meter
        .counterBuilder("relay.device.connections.refused")
        .setDescription("Device connections refused at the session limit since start")
        .buildWithCallback(measurement => measurement.record(hub.connectionsRefused))
    ).discard
    bus.consume("metrics")(message => metrics.observe(message.event))
    metrics
