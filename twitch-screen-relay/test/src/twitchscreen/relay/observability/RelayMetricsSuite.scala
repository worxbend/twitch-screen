package twitchscreen.relay.observability

import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import java.time.Instant
import ox.{resourceScope, useCloseableInScope}
import scala.compiletime.constValue
import scala.concurrent.duration.DurationInt
import scala.deriving.Mirror
import scala.jdk.CollectionConverters.*
import sttp.tapir.{Codec, CodecFormat, DecodeResult}
import twitchscreen.relay.bus.RelayEvent
import twitchscreen.relay.device.ConnectionId
import twitchscreen.relay.protocol.*

/** K-095: the relay's counters are derived from [[RelayEvent.category]] (plus `isObservation`), not from a third list of event cases. The
  * table pins, for one instance of every case, the single counter it moves; the completeness guard fails the moment a case is added to
  * [[RelayEvent]] without a row here.
  */
class RelayMetricsSuite extends munit.FunSuite:
  private val device = DeviceId("device").toOption.get
  private val connection: ConnectionId = summon[Codec[String, ConnectionId, CodecFormat.TextPlain]].decode("1") match
    case DecodeResult.Value(id) => id
    case other                  => throw IllegalStateException(s"could not decode connection id: $other")
  private val notification = Notification(
    seq = SeqNo(1).toOption.get,
    id = NotificationId.forSeq(SeqNo(1).toOption.get),
    kind = NotificationKind.Info,
    title = "t",
    body = "b",
    at = Some(Instant.EPOCH),
    ttl = 1.second
  )

  private val TwitchEvents = "relay.twitch.events"
  private val Observations = "relay.twitch.observations"

  private val table: List[(RelayEvent, String)] = List(
    RelayEvent.StreamStarted("channel", "title", "game", None) -> TwitchEvents,
    RelayEvent.StreamEnded("channel", 1.minute) -> TwitchEvents,
    RelayEvent.ChannelUpdated("channel", "title", "game") -> TwitchEvents,
    RelayEvent.ViewersObserved(Count.Zero, 1.minute) -> Observations,
    RelayEvent.FollowersObserved(Count.Zero) -> Observations,
    RelayEvent.SubscribersObserved(Count.Zero) -> Observations,
    RelayEvent.Followed("user") -> TwitchEvents,
    RelayEvent.Subscribed("user", SubTier.Tier1, 1, "") -> TwitchEvents,
    RelayEvent.SubscriptionGifted("user", 1, SubTier.Tier1, anonymous = false) -> TwitchEvents,
    RelayEvent.Raided("raider", 5) -> TwitchEvents,
    RelayEvent.BitsCheered("user", 100, "") -> TwitchEvents,
    RelayEvent.ChatMessaged("user", "hello", None) -> TwitchEvents,
    RelayEvent.TwitchLinkUp("eventsub") -> "relay.twitch.link.transitions",
    RelayEvent.TwitchLinkDown("network") -> "relay.twitch.link.transitions",
    RelayEvent.DeviceConnected(device, connection, "127.0.0.1") -> "relay.device.connections",
    RelayEvent.DeviceDisconnected(device, connection, "closed") -> "relay.device.disconnections",
    RelayEvent.NotificationPublished(notification) -> "relay.notifications.published",
    RelayEvent.RelayFailure("source", "message") -> "relay.failures"
  )

  /** The number of cases in a sum type, counted by the compiler from its mirror. */
  private inline def caseCount[T](using mirror: Mirror.SumOf[T]): Int = constValue[Tuple.Size[mirror.MirroredElemLabels]]

  /** Non-zero long sums after observing exactly one event on a fresh SDK. */
  private def countersAfter(event: RelayEvent): Map[String, Long] =
    resourceScope:
      val reader = CollectingReader()
      val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()
      val sdk = useCloseableInScope(OpenTelemetrySdk.builder().setMeterProvider(provider).build())
      RelayMetrics(sdk).observe(event)
      reader
        .collect()
        .map(data => data.getName -> data.getLongSumData.getPoints.asScala.map(_.getValue).sum)
        .filter((_, sum) => sum != 0L)
        .toMap

  test("K-095: the table has one row for every RelayEvent case"):
    val cases = caseCount[RelayEvent]
    assertEquals(table.map((event, _) => event.ordinal).toSet, (0 until cases).toSet)
    assertEquals(table.size, cases)

  test("K-095: every RelayEvent case increments exactly its expected counter"):
    table.foreach: (event, expected) =>
      assertEquals(countersAfter(event), Map(expected -> 1L), s"counters after $event")

  test("K-095: isObservation holds for exactly the polled audience totals"):
    val observed = table.collect { case (event, _) if event.isObservation => event.ordinal }.toSet
    assertEquals(
      observed,
      Set(
        RelayEvent.ViewersObserved(Count.Zero, 1.minute).ordinal,
        RelayEvent.FollowersObserved(Count.Zero).ordinal,
        RelayEvent.SubscribersObserved(Count.Zero).ordinal
      )
    )
