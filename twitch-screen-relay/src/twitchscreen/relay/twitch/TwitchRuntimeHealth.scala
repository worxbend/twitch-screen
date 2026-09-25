package twitchscreen.relay.twitch

import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import ox.Ox
import ox.channels.{Actor, ActorRef}
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.TwitchConfig
import twitchscreen.relay.observability.DiagnosticText

/** Serializes foreign callbacks, polling and link transitions so the alert monitor sees the same health as the status endpoint. */
private[twitch] final class TwitchRuntimeHealth private (state: ActorRef[TwitchHealthState], snapshot: AtomicReference[TwitchStatus]):
  def observe(component: HealthComponent, failure: Option[String]): Unit = state.ask(_.observe(component, failure))
  def observeSubscription(kind: String, failure: Option[String]): Unit = HealthComponent.subscription(kind).foreach(observe(_, failure))
  def failure(component: HealthComponent): Option[String] = state.ask(_.failure(component))
  def resetSession(): Unit = state.ask(_.resetSession())
  def awaiting(component: HealthComponent): Unit = state.ask(_.awaiting(component))

  /** The one mapping from a typed EventSub outcome to a health observation. */
  def record(component: HealthComponent, outcome: EventSubOutcome): Unit = outcome match
    case EventSubOutcome.Healthy        => observe(component, None)
    case EventSubOutcome.Awaiting       => awaiting(component)
    case EventSubOutcome.Failed(reason) => observe(component, Some(reason))

  def recordSubscription(kind: String, outcome: EventSubOutcome): Unit = HealthComponent.subscription(kind).foreach(record(_, outcome))
  def status: TwitchStatus = snapshot.get()

private[twitch] object TwitchRuntimeHealth:
  def apply(config: TwitchConfig, bus: EventBus)(using Ox): TwitchRuntimeHealth =
    val snapshot = AtomicReference(TwitchStatus(config.mode, TwitchHealth.Connecting, config.channel, "waiting for listeners"))
    // Ox's default mailbox holds 16 pending operations; when it is full, ask blocks the Twitch callback, poller or HTTP caller
    // (backpressure, not dropping). See docs/reference/architecture.md "Actor mailboxes".
    new TwitchRuntimeHealth(Actor.create(TwitchHealthState(config, bus, snapshot)), snapshot)

private final class TwitchHealthState(config: TwitchConfig, bus: EventBus, snapshot: AtomicReference[TwitchStatus]):
  private enum Observation:
    case Awaiting, Healthy
    case Failed(reason: String)
  import Observation.*

  private val logger = LoggerFactory.getLogger(getClass)
  private var parts = Map(HealthComponent.Startup -> Observation.Awaiting)

  /** A reconnect invalidates old observations without inventing failures or recovery events. */
  def resetSession(): Unit =
    val expected = List(
      HealthComponent.Startup,
      HealthComponent.Authorization,
      HealthComponent.Chat,
      HealthComponent.Streams,
      HealthComponent.EventSubOnline,
      HealthComponent.EventSubOffline,
      HealthComponent.EventSubUpdate
    ) ++
      EventSubTransportPolicy.of(config.eventSub.transport).expectedHealth ++
      Option.when(config.oauth.scopes.contains(TwitchScopes.Followers))(HealthComponent.EventSubFollow).toList
    parts = expected.map(_ -> Awaiting).toMap
    updateSnapshot()

  def awaiting(component: HealthComponent): Unit =
    if !parts.get(component).contains(Awaiting) then
      parts = parts.updated(component, Awaiting)
      updateSnapshot()

  def observe(component: HealthComponent, failure: Option[String]): Unit =
    val next = failure.fold[Observation](Healthy)(reason => Failed(DiagnosticText(reason, 1024)))
    val previous = parts.get(component)
    if !previous.contains(next) then
      val wasConnected = connected
      parts = parts.updated(component, next)
      next match
        case Failed(reason) =>
          logger.warn(s"Twitch ${component.label}: $reason")
          bus.publish(RelayEvent.RelayFailure(s"twitch-${component.label}", reason))
        case Healthy if previous.exists { case Failed(_) => true; case _ => false } =>
          logger.info(s"Twitch ${component.label} recovered")
        case _ => ()
      updateSnapshot()
      if wasConnected && !connected then
        val detail = failure.fold(s"${component.label} unavailable")(reason => s"${component.label}: ${DiagnosticText(reason, 1024)}")
        bus.publish(RelayEvent.TwitchLinkDown(detail))
      else if !wasConnected && connected then bus.publish(RelayEvent.TwitchLinkUp(s"channel ${config.channel}"))

  def failure(component: HealthComponent): Option[String] = parts.get(component).collect { case Failed(reason) => reason }

  private def connected: Boolean = parts.values.forall(_ == Healthy)

  private def updateSnapshot(): Unit =
    val failures = parts.toList.collect { case (part, Failed(reason)) => s"${part.label}: $reason" }.sorted
    val health =
      if connected then TwitchHealth.Connected
      else if failures.isEmpty then TwitchHealth.Connecting
      else if parts.values.exists(_ == Healthy) then TwitchHealth.Degraded
      else TwitchHealth.Disconnected
    snapshot.set(
      TwitchStatus(
        config.mode,
        health,
        config.channel,
        if connected then "all Twitch integrations operational"
        else if failures.isEmpty then "waiting for Twitch observations"
        else failures.mkString("; ")
      )
    )
