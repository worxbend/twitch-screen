package twitchscreen.relay.observability

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.metrics.LongCounter
import ox.{Ox, discard, useCloseableInScope}
import twitchscreen.relay.RelayVersion
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.device.DeviceHub

/** The relay's own metrics, alongside the HTTP ones Tapir's interceptor records.
  *
  * They are fed from the event bus rather than from call sites, so instrumentation never has to be remembered when a new producer is added
  * — anything that reaches the bus is counted.
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

  private val observations: LongCounter = meter.counterBuilder("relay.twitch.observations").build()
  private val twitchLinks: LongCounter = meter.counterBuilder("relay.twitch.link.transitions").build()

  private[observability] def observe(event: RelayEvent): Unit = event match
    case _: RelayEvent.NotificationPublished                                                             => notifications.add(1)
    case _: RelayEvent.DeviceConnected                                                                   => deviceConnections.add(1)
    case _: RelayEvent.DeviceDisconnected                                                                => deviceDisconnections.add(1)
    case _: RelayEvent.RelayFailure                                                                      => failures.add(1)
    case _: (RelayEvent.ViewersObserved | RelayEvent.FollowersObserved | RelayEvent.SubscribersObserved) => observations.add(1)
    case _: (RelayEvent.TwitchLinkUp | RelayEvent.TwitchLinkDown)                                        => twitchLinks.add(1)
    case _: (RelayEvent.StreamStarted | RelayEvent.StreamEnded | RelayEvent.ChannelUpdated | RelayEvent.Followed | RelayEvent.Subscribed |
          RelayEvent.SubscriptionGifted | RelayEvent.Raided | RelayEvent.BitsCheered | RelayEvent.ChatMessaged) =>
      twitchEvents.add(1)

private[relay] object RelayMetrics:
  def start(otel: OpenTelemetry, bus: EventBus, hub: DeviceHub)(using Ox): RelayMetrics =
    val metrics = RelayMetrics(otel)
    useCloseableInScope(
      metrics.meter
        .gaugeBuilder("relay.device.connected")
        .setDescription("Devices currently attached to the relay")
        .buildWithCallback(measurement => measurement.record(hub.connectedCount.toDouble))
    ).discard
    bus.consume("metrics")(message => metrics.observe(message.event))
    metrics
