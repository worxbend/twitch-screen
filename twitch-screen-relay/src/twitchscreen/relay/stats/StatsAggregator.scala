package twitchscreen.relay.stats

import java.time.Clock
import org.slf4j.LoggerFactory
import ox.*
import ox.flow.Flow
import twitchscreen.relay.bus.{BusEvent, EventBus}
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

  /** Folding an event produces no output; a tick produces the frame every device then receives. */
  private[stats] def step(config: StatsConfig, clock: Clock)(state: StatsState, input: StatsInput): (StatsState, Option[StreamStats]) =
    input match
      case StatsInput.Observed(message) => (state.apply(message, config.chatRateWindow), None)
      case StatsInput.Publish =>
        val now = clock.instant()
        val pruned = state.prune(now, config.chatRateWindow)
        (pruned, Some(pruned.toStats(now, config.chatRateWindow)))
