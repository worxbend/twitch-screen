package twitchscreen.relay.twitch

import java.time.Instant
import scala.concurrent.duration.*
import twitchscreen.relay.config.*

class SubscriptionPlanSuite extends munit.FunSuite:
  private val token = UserToken(Sensitive("access"), Sensitive("refresh"), Instant.MAX, Nil, "123", "channel")
  private def config(transport: EventSubTransport, scopes: List[String]): TwitchConfig = TwitchConfig(
    TwitchMode.Live,
    "channel",
    "client",
    Sensitive("long-enough-secret"),
    TwitchOAuthConfig("http://localhost/api/v1/twitch/callback", scopes, "unused.json", 15.minutes),
    EventSubConfig(transport, "https://relay.example/api/v1/twitch/eventsub", Sensitive("long-enough-secret")),
    30.seconds,
    SimulationConfig(10.seconds, 2.seconds)
  )

  test("a deliberately reduced scope grant keeps stream subscriptions healthy on both transports"):
    EventSubTransport.values.foreach: transport =>
      val plan = SubscriptionPlan.build(config(transport, Nil), "123", AuthorizationView(Some(token), Some(token), Nil))
      assertEquals(plan.subscriptions.map(_._1.getName), List("stream.online", "stream.offline", "channel.update"))
      assertEquals(plan.authorizationFailure, None)
      assertEquals(plan.followFailure, None)
      assertEquals(plan.token, Some("access"))

  test("a requested but absent follower scope only withholds the follow subscription"):
    val plan = SubscriptionPlan.build(
      config(EventSubTransport.WebSocket, List(TwitchScopes.Followers)),
      "123",
      AuthorizationView(Some(token), Some(token), List(TwitchScopes.Followers))
    )
    assertEquals(plan.subscriptions.size, 3)
    assert(plan.authorizationFailure.exists(_.contains(TwitchScopes.Followers)))
    assert(plan.followFailure.isDefined)

  test("a broadcaster grant with requested follow scope enables all four subscriptions"):
    val granted = token.copy(scopes = List(TwitchScopes.Followers))
    val plan = SubscriptionPlan.build(
      config(EventSubTransport.WebSocket, granted.scopes),
      "123",
      AuthorizationView(Some(granted), Some(granted), Nil)
    )
    assertEquals(plan.subscriptions.size, 4)
    assertEquals(plan.authorizationFailure, None)

  test("a grant from another account cannot authorize broadcaster subscriptions"):
    val other = token.copy(login = "other", scopes = List(TwitchScopes.Followers))
    val plan =
      SubscriptionPlan.build(config(EventSubTransport.WebSocket, other.scopes), "123", AuthorizationView(Some(other), Some(other), Nil))
    assertEquals(plan.token, None)
    assertEquals(plan.subscriptions.size, 3)
    assert(plan.authorizationFailure.isDefined)

  test("unscoped webhooks work without a user grant"):
    val plan = SubscriptionPlan.build(config(EventSubTransport.Webhook, Nil), "123", AuthorizationView(None, None, Nil))
    assertEquals(plan.subscriptions.size, 3)
    assertEquals(plan.authorizationFailure, None)
