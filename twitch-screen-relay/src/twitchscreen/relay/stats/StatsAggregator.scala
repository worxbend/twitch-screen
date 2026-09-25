package twitchscreen.relay.stats

import java.time.Clock
import org.slf4j.LoggerFactory
import ox.*
import ox.flow.Flow
import twitchscreen.relay.bus.{BusEvent, EventBus, RelayEvent}
import twitchscreen.relay.config.StatsConfig
import twitchscreen.relay.device.DeviceHub
import twitchscreen.relay.protocol.StreamStats

/** What drives one step of the aggregation: an event to fold in, or the moment to publish what has been folded. */
private[stats] enum StatsInput:
  case Observed(message: BusEvent)
  case Publish

/** Turns the event stream into the six numbers on the device's idle dashboard.
  *
  * Events and the publication timer are merged into one flow, so the state is threaded through a single `mapStateful` and never shared
  * between threads — no lock, no actor, no `var` outside the fold.
  */
private[relay] object StatsAggregator:
  private val logger = LoggerFactory.getLogger(getClass)

  /** Starts folding and broadcasting. Stops when the enclosing scope ends. */
  def start(config: StatsConfig, bus: EventBus, hub: DeviceHub, clock: Clock)(using Ox): Unit =
    val events = bus.subscribe("stats")
    logger.info(s"Broadcasting stream stats every ${config.broadcastInterval}, chat rate over ${config.chatRateWindow}")
    forkDiscard:
      Flow
        .fromSource(events)
        .map(StatsInput.Observed(_))
        .merge(Flow.tick[StatsInput](config.broadcastInterval, StatsInput.Publish))
        .mapStateful(StatsState.Initial)(step(config, clock))
        .collect { case Some(stats) => stats }
        .runForeach(hub.broadcastStats)

  /** Folding an event produces no output; a tick produces the frame every device then receives.
    *
    * §6.5 adds one exception: a `STATS` goes out immediately after a `STREAM_START` or `STREAM_END` as well, so the `live` flag, the uptime
    * reset and the `msg_total` reset land with the card the device has just shown instead of up to five seconds behind it. Publishing the
    * stale figures from the hub would not do — the numbers have to be the ones the transition produced.
    */
  private[stats] def step(config: StatsConfig, clock: Clock)(state: StatsState, input: StatsInput): (StatsState, Option[StreamStats]) =
    input match
      case StatsInput.Observed(message) =>
        val folded = state.apply(message, config.chatRateWindow)
        if transitions(message.event) then published(folded, config, clock) else (folded, None)
      case StatsInput.Publish => published(state, config, clock)

  private def transitions(event: RelayEvent): Boolean = event match
    case _: (RelayEvent.StreamStarted | RelayEvent.StreamEnded) => true
    case _                                                      => false

  private def published(state: StatsState, config: StatsConfig, clock: Clock): (StatsState, Option[StreamStats]) =
    val now = clock.instant()
    val pruned = state.prune(now, config.chatRateWindow)
    (pruned, Some(pruned.toStats(now, config.chatRateWindow)))
