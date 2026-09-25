package twitchscreen.relay.device

import java.time.Instant
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.RelayEvent
import twitchscreen.relay.protocol.*

/** The policy that decides what lights up the screen, and — since v3 — which numbers reach the wire as numbers.
  *
  * Every expectation here is read off §6.4.1's field table and the golden vectors that pin it: V7 (`actor = "newfriend"`), V10 (`actor =
  * "streamfriend"`, `value = 128`) and V11 (`actor = "bitsfan"`, `value = 1500`, `text = "take my bits"`).
  */
class NotificationRouterSuite extends munit.FunSuite:
  private def routed(event: RelayEvent): EventRequest =
    NotificationRouter.toRequest(event).getOrElse(fail(s"expected $event to be routed"))

  test("failure cards redact credentials and remove injected control bytes"):
    val request = routed(RelayEvent.RelayFailure("upstream", "Bearer private-token\nforged\u001b[31m"))
    assert(!request.text.contains("private-token"))
    assert(!request.text.exists(_.isControl))

  test("a follow puts the follower in the actor slot and leaves value at 0"):
    val request = routed(RelayEvent.Followed("newfriend"))
    assertEquals((request.kind, request.actor, request.text, request.value.value), (NotificationKind.Follow, "newfriend", "", 0L))

  test("a raid carries the raider's viewer count as a number, not as English"):
    val request = routed(RelayEvent.Raided("streamfriend", 128))
    assertEquals((request.kind, request.actor, request.value.value), (NotificationKind.Raid, "streamfriend", 128L))

  test("a cheer carries the bits count and the cheer message"):
    val request = routed(RelayEvent.BitsCheered("bitsfan", 1500, "take my bits"))
    assertEquals(
      (request.kind, request.actor, request.text, request.value.value),
      (NotificationKind.Bits, "bitsfan", "take my bits", 1500L)
    )

  test("a subscription carries the tier and the month count, and the resub message as text"):
    val request = routed(RelayEvent.Subscribed("loyalviewer", SubTier.Tier2, 14, "fourteen months!"))
    assertEquals(request.actor, "loyalviewer")
    assertEquals(request.text, "fourteen months!")
    assertEquals(request.tier, SubTier.Tier2)
    assertEquals(request.months.value, 14)
    // The raw Twitch plan id never reaches the wire; §6.4 has a tier byte for exactly this.
    assert(!request.text.contains("2000"), request.text)

  test("a gifted sub carries the count in value and the tier in tier"):
    val request = routed(RelayEvent.SubscriptionGifted("generouspal", 5, SubTier.Tier1, anonymous = false))
    assertEquals(
      (request.kind, request.actor, request.value.value, request.tier),
      (NotificationKind.Gift, "generouspal", 5L, SubTier.Tier1)
    )
    assert(!request.flags.contains(EventFlags.Anonymous))

  test("an anonymous gifter is flagged rather than given a made-up name"):
    val request = routed(RelayEvent.SubscriptionGifted("", 3, SubTier.Tier1, anonymous = true))
    assert(request.flags.contains(EventFlags.Anonymous))
    assertEquals(request.actor, "")

  test("a chat message carries the chatter's colour only when there is one"):
    val coloured = routed(RelayEvent.ChatMessaged("Paweł", "hi", Some(ChatColour(0x00ff7f50))))
    assertEquals(coloured.value.value, 0x00ff7f50L)
    assert(coloured.flags.contains(EventFlags.ChatColourPresent))
    // Black is a legal colour, so "no colour" cannot be encoded as 0 without the flag saying so.
    val plain = routed(RelayEvent.ChatMessaged("sparkplug", "o7", None))
    assertEquals(plain.value.value, 0L)
    assert(!plain.flags.contains(EventFlags.ChatColourPresent))

  test("chat is routed whatever the relay's chat policy says, because every EVENT is sequenced"):
    // §10.3: a kind skipped by the sequence space lets a later event hold the high-water mark past a lost durable
    // one. The policy is ANDed with CAP_CHAT at the moment of sending (§6.1), which DeviceLinkSuite pins.
    assertEquals(routed(RelayEvent.ChatMessaged("viewer", "hi", None)).kind, NotificationKind.Chat)

  test("a stream going live is its own kind, carrying the start time and the title"):
    val startedAt = Instant.ofEpochSecond(1790305340L)
    val request = routed(RelayEvent.StreamStarted("w0rxbend", "Round LCD build night", "Science", Some(startedAt)))
    assertEquals(request.kind, NotificationKind.StreamStart)
    assertEquals((request.actor, request.text), ("w0rxbend", "Round LCD build night"))
    assertEquals(request.value.value, 1790305340L)

  test("a stream ending carries its duration in seconds"):
    val request = routed(RelayEvent.StreamEnded("w0rxbend", 3760.seconds))
    assertEquals((request.kind, request.actor, request.text, request.value.value), (NotificationKind.StreamEnd, "w0rxbend", "", 3760L))

  test("channel telemetry belongs on the idle dashboard, not in an event"):
    assertEquals(NotificationRouter.toRequest(RelayEvent.FollowersObserved(Count.clamp(10))), None)

  test("a published notification is not routed back into the hub"):
    val notification = Notification(
      seq = SeqNo(1).toOption.get,
      id = NotificationId.forSeq(SeqNo(1).toOption.get),
      kind = NotificationKind.Info,
      title = "t",
      body = "b",
      at = Some(Instant.EPOCH),
      ttl = 1.second
    )
    assertEquals(NotificationRouter.toRequest(RelayEvent.NotificationPublished(notification)), None)

  test("a relay failure is surfaced as an alert card"):
    assertEquals(routed(RelayEvent.RelayFailure("twitch-poll", "timeout")).kind, NotificationKind.Alert)

  test("§6.4.1: the ttl is the per-kind display time, and reaches the record as deciseconds, which is what the wire carries"):
    def ttlOf(event: RelayEvent) = routed(event).record(SeqNo.fromWire(1L), Instant.EPOCH).ttl.deciseconds
    assertEquals(ttlOf(RelayEvent.Followed("newfriend")), 60)
    assertEquals(ttlOf(RelayEvent.Raided("streamfriend", 128)), 100)

  test("kind 0x18 is unreachable from the HTTP API, and there is no monetary field anywhere"):
    // §8: kind 0x18 is permanently reserved and MUST NOT be sent by a v3 relay. `NotificationKind.parse` is the
    // only path from `POST /api/v1/notifications` to a kind, so this is the whole surface.
    assert(NotificationKind.values.forall(_.code != 0x18), NotificationKind.values.map(_.code).mkString(", "))
    assert(NotificationKind.parse("donation").isLeft)
    assert(NotificationKind.values.forall(kind => !kind.wire.contains("donat")))

  test("a posted card keeps its title and body and leaves every numeric field 0, whatever kind it names"):
    // §6.4.3: otherwise a hand-written test card is silently replaced by "raided with 0 viewers".
    val request = EventRequest.card(NotificationRequest(NotificationKind.Raid, "Relay restarted", "manual card", 8.seconds))
    assertEquals((request.kind, request.actor, request.text), (NotificationKind.Raid, "Relay restarted", "manual card"))
    assertEquals((request.value.value, request.months.value, request.tier), (0L, 0, SubTier.NotApplicable))
