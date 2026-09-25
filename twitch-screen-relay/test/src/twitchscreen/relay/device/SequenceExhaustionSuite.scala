package twitchscreen.relay.device

import java.time.Clock
import ox.*
import ox.channels.ChannelClosed
import scala.concurrent.duration.DurationInt
import sttp.client4.*
import sttp.model.StatusCode
import sttp.tapir.server.stub4.TapirSyncStubInterpreter
import twitchscreen.relay.bus.{BusEvent, EventBus, RelayEvent}
import twitchscreen.relay.config.{ChatNotifications, NotificationsConfig, StatsConfig}
import twitchscreen.relay.protocol.*
import twitchscreen.relay.stats.StatsAggregator

/** §10.1: the relay never assigns a seq past `0xffffffff` and never wraps. Reaching that takes about 4.29e9 events, so these tests seed the
  * hub's counter through [[DeviceHub.startingAt]] and prove that exhaustion is contained: the refusal is typed, reported once, and leaves
  * the hub, the STATS path and the supervised relay scope running.
  */
class SequenceExhaustionSuite extends munit.FunSuite:
  private val clock = Clock.systemUTC()
  private val LastButOne: SeqNo = SeqNo.fromWire(0xfffffffeL)

  private def follow(actor: String): EventRequest = EventRequest.of(NotificationKind.Follow, actor, "")

  private def eventSeq(message: Option[RelayMessage]): Option[SeqNo] = message.collect { case RelayMessage.Event(record) => record.seq }

  private def statsState(message: Option[RelayMessage]): Option[StreamState] = message.collect { case RelayMessage.Stats(stats, _) =>
    stats.state
  }

  /** The frames up to and including the first STATS reporting `state`, bounded. The aggregator's first timer tick can land a STATS of its
    * own right after start-up, so a lifecycle STATS is looked for rather than assumed to be the very next frame.
    */
  private def untilStats(device: TestDevice, state: StreamState): List[RelayMessage] =
    val frames = LazyList.continually(device.receiveMessage()).take(8).takeWhile(_.isDefined).flatten
    val (before, rest) = frames.span(message => statsState(Some(message)) != Some(state))
    before.toList ++ rest.headOption.toList

  /** Everything on the subscription until it has been quiet for `quiet`, bounded so a publication loop fails the count, not the clock. */
  private def drain(events: ox.channels.Source[BusEvent], quiet: scala.concurrent.duration.FiniteDuration = 500.millis): List[BusEvent] =
    LazyList
      .continually(timeoutOption(quiet)(events.receiveOrClosed()))
      .take(256)
      .takeWhile(_.exists(!_.isInstanceOf[ChannelClosed]))
      .flatten
      .collect { case message: BusEvent => message }
      .toList

  test("the last u32 sequence is assigned and delivered, the next publish is refused without an EVENT or a wrap"):
    supervised:
      val hub = DeviceHub.startingAt(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 64), LastButOne)
      val listener = DeviceLinkServer.startOnPort(TestRelay.config, hub, clock, 0)
      val device = useCloseableInScope(TestDevice(listener.getLocalPort))
      device.hello("test", 0)
      device.receiveMany(2).discard

      val last = hub.publish(follow("last")).fold(refused => fail(s"the last seq must still be assigned, got $refused"), identity)
      assertEquals(last.seq, SeqNo.Max)
      assertEquals(eventSeq(device.receiveMessage()), Some(SeqNo.Max))
      val published = hub.snapshot.notificationsPublished

      assertEquals(hub.publish(follow("one too many")), Left(SequenceExhausted(SeqNo.Max)))
      hub.broadcastStats(StreamStats.Unknown)
      // The refused EVENT would have been queued ahead of this STATS; the next frame is the STATS itself.
      assertEquals(device.receiveMessage().map(_.messageType), Some(MessageType.Stats))
      val snapshot = hub.snapshot
      assertEquals(snapshot.latestSeq, SeqNo.Max)
      assertEquals(snapshot.notificationsPublished, published)
      assert(snapshot.sequenceExhausted)
      assertEquals(hub.recentNotifications(1).map(_.seq), List(SeqNo.Max))

  test("an exhausted hub keeps serving attach, replay, snapshot and STATS"):
    supervised:
      val hub = DeviceHub.startingAt(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 64), LastButOne)
      val listener = DeviceLinkServer.startOnPort(TestRelay.config, hub, clock, 0)
      assert(hub.publish(follow("last")).isRight)
      assert(hub.publish(follow("refused")).isLeft)
      assert(hub.publish(NotificationRequest(NotificationKind.Info, "refused", "", 1.second)).isLeft)

      val fresh = useCloseableInScope(TestDevice(listener.getLocalPort))
      fresh.hello("fresh", 0)
      assertEquals(fresh.receiveMessage().collect { case welcome: RelayMessage.Welcome => welcome.latestSeq }, Some(SeqNo.Max))
      assertEquals(fresh.receiveMessage().map(_.messageType), Some(MessageType.Stats))

      val returning = useCloseableInScope(TestDevice(listener.getLocalPort))
      returning.hello("returning", SeqNo.Max.value - 1)
      assertEquals(returning.receiveMessage().collect { case welcome: RelayMessage.Welcome => welcome.latestSeq }, Some(SeqNo.Max))
      assertEquals(eventSeq(returning.receiveMessage()), Some(SeqNo.Max))
      assertEquals(returning.receiveMessage().map(_.messageType), Some(MessageType.Stats))

      assertEquals(hub.links.map(_.device.value).toSet, Set("fresh", "returning"))
      hub.broadcastStats(StreamStats.Unknown)
      assertEquals(fresh.receiveMessage().map(_.messageType), Some(MessageType.Stats))
      assertEquals(returning.receiveMessage().map(_.messageType), Some(MessageType.Stats))
      assertEquals(hub.snapshot.connectedDevices, 2)
      assert(hub.snapshot.sequenceExhausted)

  test("exhaustion is reported to the operator exactly once"):
    supervised:
      val bus = EventBus(clock, 256)
      val events = bus.subscribe("test")
      val hub = DeviceHub.startingAt(TestRelay.config, ChatNotifications.Show, clock, bus, LastButOne)
      // The router would turn the RelayFailure into an Alert card, which the exhausted hub refuses in turn; that must not loop.
      NotificationRouter.start(NotificationsConfig(30.seconds, ChatNotifications.Show), bus, hub)
      assert(hub.publish(follow("last")).isRight)
      assert(hub.publish(follow("refused")).isLeft)
      assert(hub.publish(NotificationRequest(NotificationKind.Info, "refused", "", 1.second)).isLeft)
      assert(hub.publishTransition(follow("refused"), StreamStats.Unknown).isLeft)
      bus.publish(RelayEvent.Followed("routed"))

      val failures = drain(events).map(_.event).collect { case failure @ RelayEvent.RelayFailure("device-hub", _) => failure }
      assertEquals(failures.size, 1, failures)
      assertEquals(hub.snapshot.notificationsPublished, 1L)

  test("a stream lifecycle transition on an exhausted hub still delivers STATS and does not end the relay scope"):
    supervised:
      val bus = EventBus(clock, 128)
      val hub = DeviceHub.startingAt(TestRelay.config, ChatNotifications.Show, clock, bus, SeqNo.Max)
      NotificationRouter.start(NotificationsConfig(30.seconds, ChatNotifications.Show), bus, hub)
      StatsAggregator.start(StatsConfig(1.hour, 1.minute), bus, hub, clock)
      val listener = DeviceLinkServer.startOnPort(TestRelay.config, hub, clock, 0)
      val device = useCloseableInScope(TestDevice(listener.getLocalPort))
      device.hello("test", 0)
      device.receiveMany(2).discard

      bus.publish(RelayEvent.StreamStarted("channel", "title", "game", Some(clock.instant())))
      val started = untilStats(device, StreamState.Live)
      assertEquals(started.lastOption.flatMap(message => statsState(Some(message))), Some(StreamState.Live), started)
      assert(started.forall(_.messageType == MessageType.Stats), started)
      bus.publish(RelayEvent.StreamEnded("channel", 30.seconds))
      val ended = untilStats(device, StreamState.Offline)
      assertEquals(ended.lastOption.flatMap(message => statsState(Some(message))), Some(StreamState.Offline), ended)
      assert(ended.forall(_.messageType == MessageType.Stats), ended)
      assertEquals(hub.snapshot.latestStats.state, StreamState.Offline)
      assertEquals(hub.snapshot.notificationsPublished, 0L)

  test("POST /notifications on an exhausted hub is a documented 503"):
    supervised:
      val hub = DeviceHub.startingAt(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 64), SeqNo.Max)
      val api = NotificationApi(hub, NotificationsConfig(30.seconds, ChatNotifications.Show))
      val backend = TapirSyncStubInterpreter().whenServerEndpointsRunLogic(api.endpoints).backend()
      val response = basicRequest
        .post(uri"http://localhost:8080/api/v1/notifications")
        .contentType("application/json")
        .body("""{"type":"info","title":"t","body":"b"}""")
        .send(backend)
      assertEquals(response.code, StatusCode.ServiceUnavailable)
      val body = response.body.fold(identity, identity)
      assert(body.contains("\"error\""), body)
      assert(body.contains("exhausted"), body)
      assertEquals(hub.snapshot.notificationsPublished, 0L)
      assertEquals(hub.snapshot.latestSeq, SeqNo.Max)
