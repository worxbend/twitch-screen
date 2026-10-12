package twitchscreen.relay.device

import ox.{Ox, forkDiscard, repeatWhile, sleep}
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.StatsConfig

/** Original source identity, captured before starting a producer, not inferred when a callback eventually reaches a consumer. */
private[relay] final case class SourceFence(relayEpoch: Long, generation: Long):
  require(relayEpoch != 0 && generation > 0 && generation <= 0xffffffffL)

/** Installs reliable producer-to-hub admission. Activity, metrics and alerts remain optional bounded bus consumers. The timer is only the
  * existing uptime/chat-window publication cadence; incoming events never wait for it. Runtime source replacement still owns
  * cancellation/join and must create a fresh view after a committed generation change.
  */
private[relay] object SourceIngestion:
  /** Application-owned OAuth failures are not callbacks from a replaceable source. Capture current publication authority inside the hub
    * actor for each diagnostic, without folding a failure as an observed Twitch measurement. Admission precedes optional bus fan-out.
    */
  def applicationDiagnostics(bus: EventBus, hub: DeviceHub): EventBus =
    bus.admittedView(hub.acceptApplicationDiagnostic)

  def start(bus: EventBus, hub: DeviceHub, config: StatsConfig)(using Ox): EventBus =
    startAtFence(bus, hub, config, hub.sourceFence)

  /** RuntimeSource captures authority before provider construction; a reset racing construction must not relabel that producer. */
  def startAtFence(bus: EventBus, hub: DeviceHub, config: StatsConfig, fence: SourceFence)(using Ox): EventBus =
    val source = bus.admittedView(
      message => hub.acceptSource(fence, message, config),
      message => message.copy(source = Some(twitchscreen.relay.bus.SourceIdentity(fence.relayEpoch, fence.generation)))
    )
    forkDiscard:
      repeatWhile:
        sleep(config.broadcastInterval)
        hub.refreshSourceStats(fence, config)
    source
