package twitchscreen.relay.twitch

import com.github.twitch4j.eventsub.condition.EventSubCondition
import com.github.twitch4j.eventsub.subscriptions.SubscriptionType
import twitchscreen.relay.config.{EventSubTransport, TwitchConfig}

/** Both transports consume the same grant/scopes observation and therefore select the same eligible subscription kinds. */
private[twitch] final case class SubscriptionPlan(
    grant: Option[UserToken],
    subscriptions: List[(SubscriptionType[?, ?, ?], EventSubCondition)],
    authorizationFailure: Option[String],
    followFailure: Option[String]
):
  def token: Option[String] = grant.map(_.accessToken.value)

private[twitch] object SubscriptionPlan:
  def build(config: TwitchConfig, broadcasterId: String, view: AuthorizationView): SubscriptionPlan =
    val broadcaster = view.broadcaster(config.channel)
    val missing = config.oauth.scopes.diff(broadcaster.toList.flatMap(_.scopes))
    val followRequested = config.oauth.scopes.contains(TwitchScopes.Followers)
    val canFollow = followRequested && broadcaster.exists(_.scopes.contains(TwitchScopes.Followers))
    val needsGrant = config.eventSub.transport == EventSubTransport.WebSocket || config.oauth.scopes.nonEmpty
    SubscriptionPlan(
      broadcaster,
      EventSubWebhookApi.unscopedSubscriptions(broadcasterId) ++ (if canFollow then EventSubWebhookApi.scopedSubscriptions(broadcasterId)
                                                                  else Nil),
      if needsGrant && broadcaster.isEmpty then Some("authorize the configured broadcaster or refresh the expired grant")
      else if missing.nonEmpty then Some(s"missing scopes: ${missing.mkString(", ")}")
      else None,
      Option.when(followRequested && !canFollow)("awaiting broadcaster follow scope")
    )
