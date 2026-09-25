package twitchscreen.relay.twitch

import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import ox.Ox
import ox.channels.{Actor, ActorRef}
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.TwitchConfig

/** Serializes foreign callbacks, polling and link transitions so the alert monitor sees the same health as the status endpoint. */
private[twitch] final class TwitchRuntimeHealth private (state: ActorRef[TwitchHealthState], snapshot: AtomicReference[TwitchStatus]):
  def observe(component: String, failure: Option[String]): Unit = state.ask(_.observe(component, failure))
  def failure(component: String): Option[String] = state.ask(_.failure(component))
  def status: TwitchStatus = snapshot.get()

private[twitch] object TwitchRuntimeHealth:
  def apply(config: TwitchConfig, bus: EventBus)(using Ox): TwitchRuntimeHealth =
    val snapshot = AtomicReference(TwitchStatus(config.mode, TwitchHealth.Connecting, config.channel, "waiting for listeners"))
    new TwitchRuntimeHealth(Actor.create(TwitchHealthState(config, bus, snapshot)), snapshot)

private final class TwitchHealthState(config: TwitchConfig, bus: EventBus, snapshot: AtomicReference[TwitchStatus]):
  private val logger = LoggerFactory.getLogger(getClass)
  private var parts = Map("startup" -> Option("waiting for listeners"))

  def observe(component: String, failure: Option[String]): Unit =
    val previous = parts
    parts = parts.updated(component, failure)
    if previous.get(component) != Some(failure) then
      failure match
        case Some(reason) =>
          logger.warn(s"Twitch $component: $reason")
          bus.publish(RelayEvent.RelayFailure(s"twitch-$component", reason))
        case None => logger.info(s"Twitch $component recovered")
      val wasConnected = previous.values.forall(_.isEmpty)
      val isConnected = parts.values.forall(_.isEmpty)
      val failures = parts.toList.collect { case (part, Some(reason)) => s"$part: $reason" }.sorted
      val health =
        if isConnected then TwitchHealth.Connected
        else if parts.values.exists(_.isEmpty) then TwitchHealth.Degraded
        else TwitchHealth.Disconnected
      snapshot.set(
        TwitchStatus(
          config.mode,
          health,
          config.channel,
          if failures.isEmpty then "all Twitch integrations operational" else failures.mkString("; ")
        )
      )
      if wasConnected && !isConnected then bus.publish(RelayEvent.TwitchLinkDown(s"$component unavailable"))
      else if !wasConnected && isConnected then bus.publish(RelayEvent.TwitchLinkUp(s"channel ${config.channel}"))

  def failure(component: String): Option[String] = parts.get(component).flatten
