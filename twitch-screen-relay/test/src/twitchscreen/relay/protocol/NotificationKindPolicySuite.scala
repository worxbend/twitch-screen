package twitchscreen.relay.protocol

import java.time.Instant
import scala.concurrent.duration.DurationInt

/** K-034: the per-kind policy lives on [[NotificationKind]]. These tests pin it for every kind as a literal table, then check the
  * placeholder rule by encoding real frames. A table that reads the flags back from the enum would only compare the enum with itself.
  */
class NotificationKindPolicySuite extends munit.FunSuite:
  import NotificationKind.*

  private final case class Policy(isGeneric: Boolean, isDurable: Boolean, foldsToPlaceholder: Boolean, isLifecycle: Boolean)

  private val GenericCard = Policy(isGeneric = true, isDurable = true, foldsToPlaceholder = false, isLifecycle = false)
  private val AudienceEvent = Policy(isGeneric = false, isDurable = true, foldsToPlaceholder = true, isLifecycle = false)
  private val ChatLine = Policy(isGeneric = false, isDurable = false, foldsToPlaceholder = true, isLifecycle = false)
  private val Lifecycle = Policy(isGeneric = false, isDurable = true, foldsToPlaceholder = false, isLifecycle = true)

  private val expected: Map[NotificationKind, Policy] = Map(
    Info -> GenericCard,
    Message -> GenericCard,
    Warning -> GenericCard,
    Alert -> GenericCard,
    Follow -> AudienceEvent,
    Sub -> AudienceEvent,
    Gift -> AudienceEvent,
    Raid -> AudienceEvent,
    Bits -> AudienceEvent,
    Chat -> ChatLine,
    StreamStart -> Lifecycle,
    StreamEnd -> Lifecycle
  )

  /** The kinds whose emoji-only actor must still show something on an ASCII device, written out rather than read from the enum. */
  private val PlaceholderKinds: Set[NotificationKind] = Set(Follow, Sub, Gift, Raid, Chat, Bits)

  test("K-034: every NotificationKind has an expected policy row, so a new kind cannot ship without one"):
    assertEquals(expected.keySet, values.toSet)

  test("K-034: isGeneric, isDurable, foldsToPlaceholder and isLifecycle are pinned for every kind"):
    values.foreach: kind =>
      val actual = Policy(kind.isGeneric, kind.isDurable, kind.foldsToPlaceholder, kind.isLifecycle)
      assertEquals(actual, expected(kind), s"policy of $kind")

  test("K-034: an emoji-only actor folds to the placeholder for audience kinds and to empty for cards and lifecycle kinds"):
    assert(PlaceholderKinds.contains(Follow) && !PlaceholderKinds.contains(Info) && !PlaceholderKinds.contains(StreamStart))
    values.foreach: kind =>
      val record = EventRecords
        .follow(SeqNo.fromWire(1), "🎉🎉🎉", Instant.ofEpochSecond(1790309000L), 6.seconds)
        .copy(kind = kind, actor = "🎉🎉🎉")
      val encoded = Tsb3Encoder.toDevice(RelayMessage.Event(record), text = TextPolicy.AsciiFolded)
      val wanted = if PlaceholderKinds.contains(kind) then WireStrings.FoldedPlaceholder else ""
      Tsb3Decoder.fromRelay(WireBytes.frameOf(encoded).toOption.get) match
        case Right(RelayMessage.Event(decoded)) => assertEquals(decoded.actor, wanted, s"folded actor of $kind")
        case other                              => fail(s"expected an EVENT for $kind, got $other")
