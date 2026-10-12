package twitchscreen.relay.alerts

import java.time.Clock
import ox.{Ox, discard}
import twitchscreen.relay.bus.{BusEvent, EventBus, RelayEvent, SourceIdentity}
import twitchscreen.relay.config.{AlertsConfig, TwitchMode}
import twitchscreen.relay.device.{DeviceHub, SourceFence}

/** Reliable source activation is separate from the optional event bus. Switching retires only Twitch alerts and timers; independent
  * device/failure state remains intact. Queued observations retain producer identity and cannot repaint a replacement generation.
  */
private[relay] final class RuntimeAlertMonitor private[alerts] (
    config: AlertsConfig,
    hub: DeviceHub,
    clock: Clock,
    val store: AlertStore
):
  val rules: List[AlertRule] = AlertRule.from(config, TwitchMode.Live)
  private var state = MonitorState.initial(clock.instant())
  private var source = Option.empty[SourceIdentity]
  private var watching = false

  private def twitchRule(rule: AlertRule): Boolean = rule match
    case _: (AlertRule.TwitchDisconnected | AlertRule.StreamOffline) => true
    case _                                                           => false

  def activate(fence: SourceFence, mode: TwitchMode): Unit = synchronized:
    val identity = SourceIdentity(fence.relayEpoch, fence.generation)
    val enabled = mode != TwitchMode.Disabled
    if fence == hub.sourceFence && (!source.contains(identity) || watching != enabled) then
      val now = clock.instant()
      rules.filter(twitchRule).foreach(rule => store.resolve(rule.name, now).discard)
      state = state.copy(twitch = Presence.Absent(now), stream = Presence.Absent(now))
      source = Some(identity)
      watching = enabled

  private[alerts] def observe(message: BusEvent): Unit = synchronized:
    message.event match
      case _: RelayEvent.RelayFailure =>
        // Source failures queued before replacement are obsolete; stable application failures have no source fence.
        if message.source.isEmpty || (message.source == source && currentFenceMatches) then state = state.apply(message)
      case _ =>
        if watching && message.source == source && message.source.isDefined && currentFenceMatches then state = state.apply(message)

  private def currentFenceMatches: Boolean =
    val fence = hub.sourceFence
    source.contains(SourceIdentity(fence.relayEpoch, fence.generation))

  private[alerts] def evaluate(): Unit = synchronized:
    val now = clock.instant()
    state = state.withDevices(hub.connectedCount, now).pruneFailures(now, config.errorRateWindow)
    rules.foreach: rule =>
      if !twitchRule(rule) || (watching && currentFenceMatches) then
        rule.check(state, now) match
          case Some(message) => store.raise(rule, message, now, if twitchRule(rule) then source else None).discard
          case None          => store.resolve(rule.name, now).discard
      else store.resolve(rule.name, now).discard

private[relay] object RuntimeAlertMonitor:
  def start(config: AlertsConfig, bus: EventBus, hub: DeviceHub, clock: Clock, mode: TwitchMode)(using Ox): RuntimeAlertMonitor =
    val monitor = new RuntimeAlertMonitor(config, hub, clock, AlertStore(config.bufferSize))
    monitor.activate(hub.sourceFence, mode)
    bus.foldTimed("alerts", (), config.evaluationInterval): (_, input) =>
      input match
        case Some(message) => monitor.observe(message)
        case None          => monitor.evaluate()
    monitor
