package twitchscreen.relay.twitch

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential
import scala.collection.mutable.ListBuffer

class WebSocketRegistrationSuite extends munit.FunSuite:
  private val credential = OAuth2Credential("twitch", "user-token")
  private val subscriptions = EventSubWebhookApi.unscopedSubscriptions("123") ++ EventSubWebhookApi.scopedSubscriptions("123")
  private val Follow = "moderator:read:followers"
  private val AwaitingGrant = ("eventsub-connection", Some("awaiting broadcaster authorization and follow scope"))

  /** One interleaved log, so the order of observations, register calls and connect is asserted as a whole. */
  private final class Harness(rejected: Set[String] = Set.empty):
    val log = ListBuffer.empty[String]
    val observations = ListBuffer.empty[(String, Option[String])]
    val registered = ListBuffer.empty[String]
    var connects = 0
    val connectedGrants = ListBuffer.empty[String]

    def step(
        token: Option[String],
        missing: List[String] = Nil,
        registeredGrant: Option[String] = None,
        credential: Option[OAuth2Credential] = Some(WebSocketRegistrationSuite.this.credential)
    ): WebSocketStep =
      WebSocketRegistration.step(
        token,
        missing,
        registeredGrant,
        credential,
        subscriptions,
        (_, subscription) =>
          val kind = subscription.getType.getName
          registered += kind
          log += s"register $kind"
          !rejected.contains(kind)
        ,
        granted =>
          connects += 1
          connectedGrants += granted
          log += "connect"
        ,
        (kind, failure) =>
          observations += kind -> failure
          log += s"observe $kind ${failure.getOrElse("ok")}"
      )

  test("no broadcaster token awaits authorization without registering"):
    val harness = Harness()
    assertEquals(harness.step(token = None), WebSocketStep.Await)
    assertEquals(harness.registered.toList, Nil)
    assertEquals(harness.connects, 0)
    assertEquals(harness.observations.toList, List(AwaitingGrant))

  test("missing moderator:read:followers awaits without registering"):
    val harness = Harness()
    assertEquals(harness.step(token = Some("a"), missing = List(Follow)), WebSocketStep.Await)
    assertEquals(harness.registered.toList, Nil)
    assertEquals(harness.connects, 0)
    assertEquals(harness.observations.toList, List(AwaitingGrant))

  test("a rejected registration reports it, registers all, requests restart and skips connect"):
    val harness = Harness(rejected = Set("channel.update"))
    assertEquals(harness.step(token = Some("a")), WebSocketStep.Restart)
    assertEquals(harness.registered.toList, List("stream.online", "stream.offline", "channel.update", "channel.follow"))
    assertEquals(harness.connects, 0)
    assertEquals(
      harness.log.toList,
      List(
        "observe eventsub-stream.online awaiting subscription confirmation",
        "register stream.online",
        "observe eventsub-stream.offline awaiting subscription confirmation",
        "register stream.offline",
        "observe eventsub-channel.update awaiting subscription confirmation",
        "register channel.update",
        "observe eventsub-channel.update registration rejected; rebuilding connection",
        "observe eventsub-channel.follow awaiting subscription confirmation",
        "register channel.follow"
      )
    )

  test("all accepted connects once and records the grant"):
    val harness = Harness()
    assertEquals(harness.step(token = Some("a")), WebSocketStep.Connected("a"))
    assertEquals(harness.registered.size, 4)
    assertEquals(harness.connects, 1)
    assertEquals(harness.connectedGrants.toList, List("a"))
    assertEquals(harness.log.last, "connect")
    assert(!harness.observations.contains(AwaitingGrant))
    assert(harness.observations.forall(_._2.contains("awaiting subscription confirmation")))

  test("a changed access token after registration requests restart without registering"):
    val harness = Harness()
    assertEquals(harness.step(token = Some("b"), registeredGrant = Some("a")), WebSocketStep.Restart)
    assertEquals(harness.registered.toList, Nil)
    assertEquals(harness.connects, 0)
    assertEquals(harness.observations.toList, Nil)

  test("a lost follow scope or grant after registration requests restart and reports awaiting"):
    List(Some("a") -> List(Follow), None -> Nil).foreach: (token, missing) =>
      val harness = Harness()
      assertEquals(harness.step(token = token, missing = missing, registeredGrant = Some("a")), WebSocketStep.Restart, clue(token))
      assertEquals(harness.registered.toList, Nil)
      assertEquals(harness.connects, 0)
      assertEquals(harness.observations.toList, List(AwaitingGrant))

  test("the same grant after registration does nothing"):
    val harness = Harness()
    assertEquals(
      harness.step(token = Some("a"), missing = List("channel:read:subscriptions"), registeredGrant = Some("a")),
      WebSocketStep.Unchanged
    )
    assertEquals(harness.log.toList, Nil)

  test("a missing credential is a no-op"):
    val harness = Harness()
    assertEquals(harness.step(token = Some("a"), credential = None), WebSocketStep.Unchanged)
    assertEquals(harness.log.toList, Nil)
