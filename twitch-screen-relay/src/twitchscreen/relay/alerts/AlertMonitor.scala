package twitchscreen.relay.alerts

import java.time.{Clock, Instant}
import org.slf4j.LoggerFactory
import ox.*
import ox.flow.Flow
import twitchscreen.relay.bus.{BusEvent, EventBus}
import twitchscreen.relay.config.AlertsConfig
import twitchscreen.relay.device.DeviceHub

/** What drives one step of the monitor: an event to fold in, or the moment to evaluate every rule. */
private enum MonitorInput:
  case Observed(message: BusEvent)
  case Evaluate

/** Evaluates the configured [[AlertRule]]s on a timer.
  *
  * Like the statistics aggregator, events and the timer are merged into one flow, so the monitor's view of the world is threaded through a
  * single fold rather than shared between threads. The rules themselves are pure functions of that state ([[MonitorState.check]]); this
  * object only decides when to run them and where to put the result.
  */
private[relay] object AlertMonitor:
  private val logger = LoggerFactory.getLogger(getClass)

  def start(config: AlertsConfig, rules: List[AlertRule], bus: EventBus, hub: DeviceHub, clock: Clock)(using Ox): AlertStore =
    val store = AlertStore(config.bufferSize)
    if rules.isEmpty then logger.info("No alert rules are enabled")
    else logger.info(s"Evaluating ${rules.size} alert rules every ${config.evaluationInterval}: ${rules.map(_.name).mkString(", ")}")

    val events = bus.subscribe("alerts")
    val startedAt = clock.instant()
    forkDiscard:
      Flow
        .fromSource(events)
        .map(MonitorInput.Observed(_))
        .merge(Flow.tick[MonitorInput](config.evaluationInterval, MonitorInput.Evaluate))
        .mapStateful(MonitorState.initial(startedAt))(step(config, rules, store, hub, clock))
        .runDrain()
    store

  private def step(config: AlertsConfig, rules: List[AlertRule], store: AlertStore, hub: DeviceHub, clock: Clock)(
      state: MonitorState,
      input: MonitorInput
  ): (MonitorState, Unit) =
    input match
      case MonitorInput.Observed(message) => (state.apply(message), ())
      case MonitorInput.Evaluate =>
        val now = clock.instant()
        val current = state.withDevices(hub.snapshot.connectedDevices, now).pruneFailures(now, config.errorRateWindow)
        rules.foreach(evaluate(_, current, store, now))
        (current, ())

  private def evaluate(rule: AlertRule, state: MonitorState, store: AlertStore, now: Instant): Unit =
    state.check(rule, now) match
      case Some(message) =>
        store.raise(rule, message, now).foreach(alert => logger.warn(s"ALERT ${alert.severity} ${alert.rule}: $message"))
      case None =>
        store.resolve(rule.name, now).foreach(alert => logger.info(s"Alert ${alert.rule} resolved"))
