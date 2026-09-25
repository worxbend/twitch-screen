package twitchscreen.relay.device

import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.RelayEvent
import twitchscreen.relay.config.{ChatNotifications, NotificationsConfig}
import twitchscreen.relay.protocol.NotificationKind

/** The policy that decides what lights up the screen. */
class NotificationRouterSuite extends munit.FunSuite:
  private val quiet = NotificationsConfig(30.seconds, ChatNotifications.Hide)
  private val chatty = NotificationsConfig(30.seconds, ChatNotifications.Show)

  test("a follow becomes a follow notification"):
    assertEquals(NotificationRouter.toRequest(RelayEvent.Followed("pixelpainter"), quiet).map(_.kind), Some(NotificationKind.Follow))

  test("a raid names who is raiding and with how many"):
    assertEquals(
      NotificationRouter.toRequest(RelayEvent.Raided("streamfriend", 128), quiet).map(_.body),
      Some("streamfriend with 128 viewers")
    )

  test("chat stays off the screen by default"):
    assertEquals(NotificationRouter.toRequest(RelayEvent.ChatMessaged("viewer", "hi"), quiet), None)

  test("chat reaches the screen when it is configured to"):
    assertEquals(NotificationRouter.toRequest(RelayEvent.ChatMessaged("viewer", "hi"), chatty).map(_.kind), Some(NotificationKind.Chat))

  test("channel telemetry belongs on the idle dashboard, not in a notification"):
    assertEquals(NotificationRouter.toRequest(RelayEvent.FollowersObserved(twitchscreen.relay.protocol.Count.clamp(10)), quiet), None)

  test("a published notification is not routed back into the hub"):
    val notification = twitchscreen.relay.protocol.Notification(
      seq = twitchscreen.relay.protocol.SeqNo(1).toOption.get,
      id = twitchscreen.relay.protocol.NotificationId.forSeq(twitchscreen.relay.protocol.SeqNo(1).toOption.get),
      kind = NotificationKind.Info,
      title = "t",
      body = "b",
      at = java.time.Instant.EPOCH,
      ttl = 1.second
    )
    assertEquals(NotificationRouter.toRequest(RelayEvent.NotificationPublished(notification), quiet), None)

  test("a relay failure is surfaced as an alert"):
    assertEquals(
      NotificationRouter.toRequest(RelayEvent.RelayFailure("twitch-poll", "timeout"), quiet).map(_.kind),
      Some(NotificationKind.Alert)
    )

  test("notification bodies stay ASCII, because the display's font is not guaranteed to have anything else"):
    val bodies = List(
      RelayEvent.Subscribed("nightowl", "1000", 3),
      RelayEvent.SubscriptionGifted("generouspanda", 5, "1000"),
      RelayEvent.Raided("streamfriend", 128),
      RelayEvent.BitsCheered("bitbaron", 500)
    ).flatMap(NotificationRouter.toRequest(_, quiet)).flatMap(request => List(request.title, request.body))
    assert(bodies.forall(_.forall(_ < 128.toChar)), bodies.mkString(" | "))
