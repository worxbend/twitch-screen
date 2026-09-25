package twitchscreen.relay.twitch

import com.github.twitch4j.TwitchClient
import java.util.concurrent.atomic.AtomicBoolean
import twitchscreen.relay.config.{EventSubTransport, TwitchConfig}

/** Owns transport-specific subscription installation. Grant tracking remains local to the session maintenance loop. */
private[twitch] trait EventSubTransportStrategy:
  def maintain(plan: SubscriptionPlan, registeredGrant: Option[String]): Option[String]

private[twitch] object EventSubTransportStrategy:
  def create(
      client: TwitchClient,
      config: TwitchConfig,
      health: TwitchRuntimeHealth,
      restart: AtomicBoolean,
      appTokenRejected: () => Unit
  ): EventSubTransportStrategy = config.eventSub.transport match
    case EventSubTransport.Webhook   => new WebhookTransport(client, config, health, appTokenRejected)
    case EventSubTransport.WebSocket => new SocketTransport(client, health, restart)

  private final class WebhookTransport(
      client: TwitchClient,
      config: TwitchConfig,
      health: TwitchRuntimeHealth,
      appTokenRejected: () => Unit
  ) extends EventSubTransportStrategy:
    override def maintain(plan: SubscriptionPlan, registeredGrant: Option[String]): Option[String] =
      EventSubWebhookApi.reconcileSubscriptions(
        client.getHelix,
        config,
        plan.subscriptions,
        (kind, failure) =>
          HealthComponent
            .subscription(kind)
            .foreach: component =>
              if failure.contains("awaiting callback verification") then health.awaiting(component) else health.observe(component, failure),
        appTokenRejected
      )
      registeredGrant

  private final class SocketTransport(client: TwitchClient, health: TwitchRuntimeHealth, restart: AtomicBoolean)
      extends EventSubTransportStrategy:
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
        (component, failure) =>
          if failure.contains("awaiting subscription confirmation") then health.awaiting(component) else health.observe(component, failure)
      ) match
        case WebSocketStep.Restart => restart.set(true)
        case _                     => ()
      installed
