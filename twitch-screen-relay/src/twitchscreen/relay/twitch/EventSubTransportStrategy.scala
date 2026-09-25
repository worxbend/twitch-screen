package twitchscreen.relay.twitch

import com.github.philippheuer.events4j.core.EventManager
import com.github.twitch4j.TwitchClient
import com.github.twitch4j.client.websocket.domain.WebsocketConnectionState
import com.github.twitch4j.eventsub.socket.events.{
  EventSocketConnectionStateEvent,
  EventSocketSubscriptionFailureEvent,
  EventSocketSubscriptionSuccessEvent
}
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import ox.discard
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.{EventSubTransport, TwitchConfig}

/** Every transport-dependent decision, resolved once from `config.eventSub.transport`. Nothing else in this package matches on the
  * transport.
  */
private[twitch] sealed trait EventSubTransportPolicy:
  /** Whether a broadcaster grant is needed even when no scope is configured. */
  def requiresGrant: Boolean

  def enablesEventSocket: Boolean

  /** Health components this transport adds to every session's expected set. */
  def expectedHealth: List[HealthComponent]

  /** The callback API, built once before any session starts, so its endpoints can be served for the relay's whole lifetime. */
  def webhookApi(
      config: TwitchConfig,
      bus: EventBus,
      tracker: ChannelStateTracker,
      filter: BotFilter,
      clock: Clock,
      observeSubscription: (String, Option[String]) => Unit
  ): Option[EventSubWebhookApi]

  /** Starts one session's transport. Called before any Helix or chat work, so the socket strategy's listeners see every callback. */
  def session(
      client: TwitchClient,
      config: TwitchConfig,
      health: TwitchRuntimeHealth,
      restart: AtomicBoolean,
      acceptingCallbacks: AtomicBoolean,
      appTokenRejected: () => Unit
  ): EventSubTransportStrategy

private[twitch] object EventSubTransportPolicy:
  def of(transport: EventSubTransport): EventSubTransportPolicy = transport match
    case EventSubTransport.Webhook   => Webhook
    case EventSubTransport.WebSocket => WebSocket

  private object Webhook extends EventSubTransportPolicy:
    override val requiresGrant = false
    override val enablesEventSocket = false
    override val expectedHealth = Nil
    override def webhookApi(
        config: TwitchConfig,
        bus: EventBus,
        tracker: ChannelStateTracker,
        filter: BotFilter,
        clock: Clock,
        observeSubscription: (String, Option[String]) => Unit
    ): Option[EventSubWebhookApi] = Some(EventSubWebhookApi.create(config, bus, tracker, filter, clock, observeSubscription))
    override def session(
        client: TwitchClient,
        config: TwitchConfig,
        health: TwitchRuntimeHealth,
        restart: AtomicBoolean,
        acceptingCallbacks: AtomicBoolean,
        appTokenRejected: () => Unit
    ): EventSubTransportStrategy = EventSubTransportStrategy.WebhookTransport(client, config, health, appTokenRejected)

  private object WebSocket extends EventSubTransportPolicy:
    override val requiresGrant = true
    override val enablesEventSocket = true
    override val expectedHealth = List(HealthComponent.EventSubConnection)
    override def webhookApi(
        config: TwitchConfig,
        bus: EventBus,
        tracker: ChannelStateTracker,
        filter: BotFilter,
        clock: Clock,
        observeSubscription: (String, Option[String]) => Unit
    ): Option[EventSubWebhookApi] = None
    override def session(
        client: TwitchClient,
        config: TwitchConfig,
        health: TwitchRuntimeHealth,
        restart: AtomicBoolean,
        acceptingCallbacks: AtomicBoolean,
        appTokenRejected: () => Unit
    ): EventSubTransportStrategy = EventSubTransportStrategy.SocketTransport(client, health, restart, acceptingCallbacks)

/** Owns transport-specific subscription installation. Grant tracking remains local to the session maintenance loop. */
private[twitch] trait EventSubTransportStrategy:
  def maintain(plan: SubscriptionPlan, registeredGrant: Option[String]): Option[String]

private[twitch] object EventSubTransportStrategy:
  /** Starting a webhook session needs nothing: Twitch posts to the callback API that outlives every session. */
  private[twitch] final class WebhookTransport(
      client: TwitchClient,
      config: TwitchConfig,
      health: TwitchRuntimeHealth,
      appTokenRejected: () => Unit
  ) extends EventSubTransportStrategy:
    override def maintain(plan: SubscriptionPlan, registeredGrant: Option[String]): Option[String] =
      EventSubWebhookApi.reconcileSubscriptions(client.getHelix, config, plan.subscriptions, health.recordSubscription, appTokenRejected)
      registeredGrant

  /** Construction is the session-start hook: it wires this session's socket callbacks into health before anything can emit them. */
  private[twitch] final class SocketTransport(
      client: TwitchClient,
      health: TwitchRuntimeHealth,
      restart: AtomicBoolean,
      acceptingCallbacks: AtomicBoolean
  ) extends EventSubTransportStrategy:
    listenToSocket(client.getEventManager, health, restart, acceptingCallbacks)

    override def maintain(plan: SubscriptionPlan, registeredGrant: Option[String]): Option[String] =
      // A fixed credential captures the same snapshot used by the plan; Twitch4J refreshes via a new session after grant changes.
      val credential = plan.grant.map(TwitchOAuthClient.credentialOf)
      var installed = registeredGrant
      WebSocketRegistration.step(
        plan.token,
        registeredGrant,
        credential,
        plan.subscriptions,
        (user, subscription) => client.getEventSocket.register(user, subscription),
        grant =>
          installed = Some(grant)
          TwitchCall
            .attempt("connect EventSub")(client.getEventSocket.connect())
            .left
            .foreach: error =>
              health.observe(HealthComponent.EventSubConnection, Some(error.message))
              restart.set(true)
        ,
        health.record
      ) match
        case WebSocketStep.Restart => restart.set(true)
        case _                     => ()
      installed

  /** A failed subscription rebuilds the client on the next maintenance pass. Twitch4J may discard permanent failures from its pool, so
    * reconnecting that pool is insufficient. Successful unrelated subscriptions never clear this recovery request. Callbacks arriving after
    * the session stops accepting them are ignored, so a closing client cannot overwrite the next session's health.
    */
  private[twitch] def listenToSocket(
      events: EventManager,
      health: TwitchRuntimeHealth,
      restartRequested: AtomicBoolean,
      acceptingCallbacks: AtomicBoolean
  ): Unit =
    events
      .onEvent(
        classOf[EventSocketConnectionStateEvent],
        event =>
          if acceptingCallbacks.get() then
            health.observe(
              HealthComponent.EventSubConnection,
              if event.getState == WebsocketConnectionState.CONNECTED then None else Some("disconnected; reconnecting")
            )
      )
      .discard
    events
      .onEvent(
        classOf[EventSocketSubscriptionSuccessEvent],
        event => if acceptingCallbacks.get() then subscriptionSucceeded(health, event.getSubscription.getType.getName)
      )
      .discard
    events
      .onEvent(
        classOf[EventSocketSubscriptionFailureEvent],
        event => if acceptingCallbacks.get() then subscriptionFailed(health, restartRequested, event.getSubscription.getType.getName)
      )
      .discard

  private[twitch] def subscriptionFailed(health: TwitchRuntimeHealth, restart: AtomicBoolean, kind: String): Unit =
    health.recordSubscription(kind, EventSubOutcome.Failed("subscription rejected; rebuilding connection"))
    restart.set(true)

  private[twitch] def subscriptionSucceeded(health: TwitchRuntimeHealth, kind: String): Unit =
    health.recordSubscription(kind, EventSubOutcome.Healthy)
