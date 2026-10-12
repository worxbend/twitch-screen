package twitchscreen.relay.device

import java.time.Clock
import ox.{fork, supervised}
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.ChatNotifications
import twitchscreen.relay.protocol.*

class PublicationOriginSuite extends munit.FunSuite:
  import DeskMessageCodec.Origin

  test("producer provenance is independent of card kind and failure label"):
    NotificationKind.values.foreach: kind =>
      assertEquals(EventRequest.card(NotificationRequest(kind, "Twitch link lost", "manual", 8.seconds)).origin, Origin.External)
    assertEquals(NotificationRouter.toRequest(RelayEvent.Followed("viewer")).get.origin, Origin.Twitch)
    assertEquals(NotificationRouter.toRequest(RelayEvent.TwitchLinkDown("lost")).get.origin, Origin.Twitch)
    assertEquals(NotificationRouter.toRequest(RelayEvent.RelayFailure("twitch-looking-label", "local")).get.origin, Origin.Relay)
    assertEquals(
      NotificationRouter.toRequest(RelayEvent.RelayFailure("unrelated-label", "provider", Origin.Twitch)).get.origin,
      Origin.Twitch
    )

  test("actual hub retains provenance in bounded rings without changing classic records"):
    supervised:
      val clock = Clock.systemUTC()
      val hub =
        DeviceHub.start(TestRelay.config.copy(replayBufferSize = 2), ChatNotifications.Show, clock, EventBus(clock, queueCapacity = 64))
      val manual = EventRequest.card(NotificationRequest(NotificationKind.Raid, "manual", "card", 8.seconds))
      val requests = Vector(manual, EventRequest.of(NotificationKind.Follow, "viewer", ""), manual)
      requests.foreach(request => assert(hub.publish(request).isRight))
      val retained = hub.retainedPublications
      assertEquals(retained.map(_.record.seq.value), Vector(2L, 3L))
      assertEquals(retained.map(_.origin), Vector(Origin.Twitch, Origin.External))
      assertEquals(hub.recentNotifications(10).map(_.seq.value), List(3L, 2L))
      assertEquals(retained.last.record.value.value, 0L)
      assertEquals(retained.last.record.ttl.deciseconds, 80)
      assert(retained.forall(_.relayEpoch != 0))
      assertEquals(retained.map(_.relayEpoch).distinct.size, 1)
      assert(retained.forall(value => value.deadlineRelayMs - value.receivedRelayMs == 60000L))
      assertEquals(hub.retainedPublications, retained)

  test("monotonic domain survives signed nanoTime wrap and rejects zero epochs"):
    var nanos = Long.MaxValue - 1000000L
    val timeline = RelayTimeline(42L, () => nanos)
    assertEquals(timeline.nowMs, 1L)
    nanos = Long.MinValue + 999999L
    assertEquals(timeline.nowMs, 3L)
    intercept[IllegalArgumentException](RelayTimeline(0L, () => nanos))

  test("useful lifetime is independent of hold and validated before publication"):
    val request = EventRequest.card(NotificationRequest(NotificationKind.Info, "manual", "", 8.seconds))
    assertEquals(request.copy(expiresInMs = 1).ttl, 8.seconds)
    val _ = intercept[IllegalArgumentException](request.copy(expiresInMs = 0))
    intercept[IllegalArgumentException](request.copy(expiresInMs = 600001))

  test("snapshot captures sequence, transition stats and bounded history atomically under concurrent publication"):
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, queueCapacity = 64))
      val publisher = fork:
        (1 to 100).foreach: index =>
          val stats = StreamStats.Unknown.copy(viewers = Count.clamp(index))
          assert(hub.publishTransition(EventRequest.of(NotificationKind.StreamStart, "channel", "title"), stats).isRight)
      (1 to 100).foreach: _ =>
        val captured = hub.publicationSnapshot
        assertEquals(captured.latestSeq.value, captured.stats.viewers.value.toLong)
        assert(
          captured.retained.forall(value => value.record.seq.value <= captured.latestSeq.value && value.relayEpoch == captured.relayEpoch)
        )
        assert(captured.retained.forall(_.receivedRelayMs <= captured.sampledRelayMs))
      publisher.join()
      val captured = hub.publicationSnapshot
      assertEquals(captured.latestSeq.value, 100L)
      assertEquals(captured.stats.viewers.value, 100)
      assertEquals(captured.retained.size, TestRelay.config.replayBufferSize)

  test("legacy records remain unknown and chat cannot evict durable provenance"):
    val replay = ReplayBuffers(1, 1)
    def record(seq: Long, kind: NotificationKind) = EventRequest.of(kind, "viewer", "").record(SeqNo.fromWire(seq), java.time.Instant.EPOCH)
    replay.remember(record(1, NotificationKind.Raid))
    replay.remember(record(2, NotificationKind.Chat), Origin.Twitch)
    replay.remember(record(3, NotificationKind.Chat), Origin.External)
    assertEquals(replay.publications.map(_.origin), Vector(Origin.Unknown, Origin.External))
    assertEquals(replay.ordered.map(_.seq.value), Vector(1L, 3L))
