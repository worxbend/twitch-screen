package twitchscreen.relay.twitch

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential
import com.github.twitch4j.eventsub.EventSubSubscription
import com.github.twitch4j.eventsub.condition.EventSubCondition
import com.github.twitch4j.eventsub.subscriptions.SubscriptionType

/** What one maintenance pass asks the session to do about its WebSocket EventSub registration. */
private[twitch] enum WebSocketStep:
  /** No usable broadcaster grant; nothing was registered. */
  case Await

  /** Already registered with this grant, or the credential vanished before registering. */
  case Unchanged

  /** The grant changed or was lost after registration, or Twitch rejected a registration: the client must be rebuilt. */
  case Restart

  /** Every subscription was accepted and the socket was connected with this grant. */
  case Connected(grant: String)

private[twitch] object WebSocketRegistration:

  /** RLY-11: the registration decision of one WebSocket maintenance pass.
    *
    * The grant is the broadcaster-owned access token (`token`, only defined for the configured broadcaster); subscriptions have already
    * been selected by [[SubscriptionPlan]]. Once registered, losing/changing a grant requests a restart. Before registration, a grant
    * registers every subscription in order, reporting each as awaiting confirmation before its `register` call and as rejected when
    * `register` returns false. Registration never short-circuits, so every kind's health is current. Only when all are accepted is
    * `connect` called with the grant, which the caller records before connecting (so a throwing connect is not followed by a second
    * registration on the next pass), and `Connected` returned; otherwise a restart is requested, because Twitch4J may discard permanently
    * failed subscriptions from its pool and only a rebuilt client registers them again. Without a grant the connection is reported as
    * awaiting authorization, on the restart path too.
    */
  def step(
      token: Option[String],
      registeredGrant: Option[String],
      credential: Option[OAuth2Credential],
      subscriptions: List[(SubscriptionType[?, ?, ?], EventSubCondition)],
      register: (OAuth2Credential, EventSubSubscription) => Boolean,
      connect: String => Unit,
      observe: (HealthComponent, Option[String]) => Unit
  ): WebSocketStep =
    val grant = token
    val result =
      if registeredGrant.isDefined && grant != registeredGrant then WebSocketStep.Restart
      else
        (grant, registeredGrant, credential) match
          case (Some(granted), None, Some(user)) =>
            val accepted = subscriptions.map: (kind, condition) =>
              HealthComponent.subscription(kind.getName).foreach(observe(_, Some("awaiting subscription confirmation")))
              val success = register(user, EventSubFactory.webSocketSubscription(kind, condition))
              if !success then
                HealthComponent.subscription(kind.getName).foreach(observe(_, Some("registration rejected; rebuilding connection")))
              success
            if accepted.forall(identity) then
              connect(granted)
              WebSocketStep.Connected(granted)
            else WebSocketStep.Restart
          case (None, _, _) => WebSocketStep.Await
          case _            => WebSocketStep.Unchanged
    if grant.isEmpty then observe(HealthComponent.EventSubConnection, Some("awaiting broadcaster authorization"))
    result
