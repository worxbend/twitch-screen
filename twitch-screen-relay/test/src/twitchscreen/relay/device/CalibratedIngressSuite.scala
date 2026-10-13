package twitchscreen.relay.device

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import java.time.Clock
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import ox.supervised
import scala.concurrent.duration.DurationInt
import sttp.client4.*
import sttp.model.StatusCode
import sttp.tapir.server.stub4.TapirSyncStubInterpreter
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.{ChatNotifications, NotificationsConfig}
import twitchscreen.relay.protocol.*

class CalibratedIngressSuite extends munit.FunSuite:
  private val body = """{"type":"message","title":"Synthetic card","body":""}"""

  private def withApi(test: (sttp.client4.SyncBackend, DeviceHub) => Unit): Unit = supervised:
    val clock = Clock.systemUTC()
    val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 64))
    val endpoints = ClockApi(hub).endpoints ++ NotificationApi(hub, NotificationsConfig(30.seconds, ChatNotifications.Show)).endpoints
    val backend = TapirSyncStubInterpreter().whenServerEndpointsRunLogic(endpoints).backend()
    test(backend, hub)

  test("calibration clock has fixed-width process identity and is never cacheable"):
    withApi: (backend, hub) =>
      val response = basicRequest.get(uri"http://localhost/api/v1/clock").send(backend)
      assertEquals(response.code, StatusCode.Ok)
      assertEquals(response.header("Cache-Control"), Some("no-store"))
      val clock = readFromString[Clock_OUT](response.body.fold(identity, identity))
      assert(clock.relayEpoch.matches("(?!0000000000000000$)[0-9a-f]{16}"))
      assertEquals(clock.relayEpoch, hub.relayClock.epochHex)
      assert(clock.monotonicMs <= hub.relayClock.monotonicMs)

  test("canonical notification defaults, zero fences, effective hold and millisecond UTC metadata"):
    withApi: (backend, hub) =>
      val response = basicRequest.post(uri"http://localhost/api/v1/notifications").contentType("application/json").body(body).send(backend)
      assertEquals(response.code, StatusCode.Ok)
      val raw = response.body.fold(identity, identity)
      val result = readFromString[Notification_OUT](raw)
      assertEquals(result.ttlMs, 8000L)
      assertEquals(result.expiresInMs, 60000L)
      assertEquals(result.origin, "external")
      assertEquals((result.sourceGeneration, result.twitchEpisode), (0L, 0L))
      assertEquals(result.relayEpoch, hub.relayClock.epochHex)
      assert(result.remainingExpiryMs > 0 && result.remainingExpiryMs <= 60000L)
      assert(raw.matches("(?s).*\\\"at\\\":\\\"[0-9:T-]+\\.[0-9]{3}Z\\\".*"), raw)
      val rounded = basicRequest
        .post(uri"http://localhost/api/v1/notifications")
        .contentType("application/json")
        .body(body.dropRight(1) + ",\"ttlMs\":101,\"expiresInMs\":1}")
        .send(backend)
      assertEquals(readFromString[Notification_OUT](rounded.body.fold(identity, identity)).ttlMs, 200L)

  test("manual HTTP chat preserves default and explicit holds instead of inheriting Twitch pacing"):
    withApi: (backend, hub) =>
      for (field, expected) <- List(("", 8000L), (",\"ttlMs\":101", 200L), (",\"ttlMs\":6000", 6000L)) do
        val response = basicRequest
          .post(uri"http://localhost/api/v1/notifications")
          .contentType("application/json")
          .body(s"{\"type\":\"chat\",\"title\":\"manual\",\"body\":\"fixed hold\"$field}")
          .send(backend)
        assertEquals(response.code, StatusCode.Ok)
        val accepted = readFromString[Notification_OUT](response.body.fold(identity, identity))
        assertEquals(accepted.ttlMs, expected)
        assertEquals(accepted.origin, "external")
      val invalid = basicRequest
        .post(uri"http://localhost/api/v1/notifications")
        .contentType("application/json")
        .body("""{"type":"chat","title":"manual","body":"","ttlMs":0}""")
        .send(backend)
      assertEquals(invalid.code, StatusCode.BadRequest)
      assertEquals(hub.snapshot.notificationsPublished, 3L)

  test("unknown duplicate null and malformed deadline fields cannot mutate authority"):
    withApi: (backend, hub) =>
      val additions = List(
        "\"origin\":\"twitch\"",
        "\"ttlMs\":null",
        "\"expiresInMs\":null",
        "\"deadline\":null",
        "\"ttlMs\":100,\"ttlMs\":200",
        "\"expiresInMs\":600001",
        "\"ttlMs\":99",
        "\"ttlMs\":6553500",
        "\"deadline\":{\"relayEpoch\":\"0000000000000000\",\"monotonicMs\":10}",
        "\"deadline\":{\"relayEpoch\":\"0123456789ABCDEF\",\"monotonicMs\":10}",
        "\"deadline\":{\"relayEpoch\":\"0123456789abcdef\",\"monotonicMs\":9007199254740992}",
        "\"deadline\":{\"relayEpoch\":\"0123456789abcdef\",\"monotonicMs\":10,\"x\":1}"
      )
      additions.foreach: addition =>
        val response = basicRequest
          .post(uri"http://localhost/api/v1/notifications")
          .contentType("application/json")
          .body(body.dropRight(1) + "," + addition + "}")
          .send(backend)
        assertEquals(response.code, StatusCode.BadRequest, addition)
        assertEquals(hub.snapshot.notificationsPublished, 0L)
      val malformed = basicRequest
        .post(uri"http://localhost/api/v1/notifications")
        .contentType("application/json")
        .body("""{"type":"info","title":"\ud800","body":""}""")
        .send(backend)
      assertEquals(malformed.code, StatusCode.BadRequest)
      assertEquals(hub.snapshot.notificationsPublished, 0L)

  test("canonical stream notification names encode and decode without accepting repaired aliases"):
    withApi: (backend, hub) =>
      List("stream_start", "stream_end").foreach: kind =>
        val response = basicRequest
          .post(uri"http://localhost/api/v1/notifications")
          .contentType("application/json")
          .body(s"{\"type\":\"$kind\",\"title\":\"synthetic\",\"body\":\"\"}")
          .send(backend)
        assertEquals(response.code, StatusCode.Ok)
        assert(response.body.fold(identity, identity).contains(s"\"type\":\"$kind\""))
      List("streamstart", "streamend", "StreamStart", "stream-start").foreach: kind =>
        val response = basicRequest
          .post(uri"http://localhost/api/v1/notifications")
          .contentType("application/json")
          .body(s"{\"type\":\"$kind\",\"title\":\"synthetic\",\"body\":\"\"}")
          .send(backend)
        assertEquals(response.code, StatusCode.BadRequest)
      assertEquals(hub.snapshot.notificationsPublished, 2L)

  test("wrong epoch and expired calibrated deadline return canonical errors without sequencing"):
    withApi: (backend, hub) =>
      val actual = hub.relayClock
      List(
        ("0000000000000001", 9007199254740991L, StatusCode.Conflict, "clock_epoch_changed"),
        (actual.epochHex, 0L, StatusCode.Gone, "expired")
      ).foreach: (epoch, deadline, status, code) =>
        val response = basicRequest
          .post(uri"http://localhost/api/v1/notifications")
          .contentType("application/json")
          .body(body.dropRight(1) + s",\"deadline\":{\"relayEpoch\":\"$epoch\",\"monotonicMs\":$deadline}}")
          .send(backend)
        assertEquals(response.code, status)
        assert(response.body.fold(identity, identity).contains(s"\"code\":\"$code\""))
        assertEquals(hub.snapshot.latestSeq.value, 0L)

  test("original absolute deadline is not renewed by a second clock sample or response delay"):
    var nanos = 0L
    val clock = Clock.systemUTC()
    val timeline = RelayTimeline(42L, () => { nanos += 1000000L; nanos })
    val initial = HubSnapshot(0, 0L, 0L, 0L, SeqNo.Zero, 0, StreamStats.Unknown, false)
    val state = DeviceHubState(
      TestRelay.config,
      ChatNotifications.Show,
      clock,
      EventBus(clock, 64),
      AtomicInteger(),
      SeqNo.Zero,
      SessionId.fromWire(1L),
      AtomicReference(initial),
      timeline
    )
    val request = EventRequest.card(NotificationRequest(NotificationKind.Info, "synthetic", "", 1.second))
    val accepted = state.publishExternal(request, Some(RelayClock(42L, 11L))).toOption.get
    assertEquals(accepted.deadlineRelayMs, 11L)
    assert(accepted.receivedRelayMs < 11L)
    assertEquals(state.publishExternal(request, Some(RelayClock(43L, 1000L))), Left(IngressFailure.EpochChanged))
    assertEquals(state.publishExternal(request, Some(RelayClock(42L, 0L))), Left(IngressFailure.Expired))
    assertEquals(state.snapshot.latestSeq.value, 1L)

  test("global notification rate limit preserves sequence and emits 429 Retry-After"):
    val limiter = IngressRateLimit()
    (1 to 20).foreach(_ => assert(limiter.admit(1L)))
    assert(!limiter.admit(1L))
    assert(!limiter.admit(100L))
    assert(limiter.admit(101L))
    assert(!limiter.admit(101L))
    assert(limiter.admit(Long.MaxValue))
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 64))
      val backend = TapirSyncStubInterpreter()
        .whenServerEndpointsRunLogic(NotificationApi(hub, NotificationsConfig(30.seconds, ChatNotifications.Show)).endpoints)
        .backend()
      val responses = (1 to 25).map(_ =>
        basicRequest.post(uri"http://localhost/api/v1/notifications").contentType("application/json").body(body).send(backend)
      )
      val refused = responses.filter(_.code == StatusCode.TooManyRequests)
      assert(refused.nonEmpty)
      assert(refused.forall(_.header("Retry-After").contains("1")))
      assert(refused.forall(_.body.fold(identity, identity).contains("\"code\":\"rate_limited\"")))
      assertEquals(hub.snapshot.notificationsPublished, responses.count(_.code == StatusCode.Ok).toLong)

  test("history returns original metadata from the same bounded replay rings"):
    withApi: (backend, hub) =>
      assert(hub.publish(EventRequest.of(NotificationKind.Follow, "synthetic", "")).isRight)
      val response = basicRequest.get(uri"http://localhost/api/v1/notifications").send(backend)
      val values = readFromString[Notifications_OUT](response.body.fold(identity, identity)).notifications
      assertEquals(values.size, 1)
      assertEquals(values.head.origin, "twitch")
      assertEquals((values.head.sourceGeneration, values.head.twitchEpisode), (1L, 1L))
      assertEquals(values.head.relayEpoch, hub.relayClock.epochHex)
