package twitchscreen.relay.twitch

import java.time.{Clock, Instant, ZoneOffset}
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.RelayEvent
import ox.{Ox, fork, supervised}

/** EventSub and the Helix poll both observe the channel; exactly one of them may announce a change.
  *
  * The tracker also owns the two numbers §6.4.1 puts in `EVENT.value` — the stream's start and its duration — because neither is
  * recoverable from the moment the relay happened to notice.
  */
class ChannelStateTrackerSuite extends munit.FunSuite:
  private val now = Instant.ofEpochSecond(1790309000L)
  private val clock = Clock.fixed(now, ZoneOffset.UTC)

  private def tracker()(using Ox): ChannelStateTracker = ChannelStateTracker("somechannel", clock)

  test("the first sighting of a live channel is announced, anchored on the relay's clock when nothing better is known"):
    supervised:
      assertEquals(
        tracker().wentLive("Soldering", "Science"),
        Some(RelayEvent.StreamStarted("somechannel", "Soldering", "Science", Some(now)))
      )

  test("Twitch's own start time is carried through when the observer has one"):
    supervised:
      val startedAt = now.minusSeconds(3660)
      assertEquals(
        tracker().wentLive("Soldering", "Science", Some(startedAt)),
        Some(RelayEvent.StreamStarted("somechannel", "Soldering", "Science", Some(startedAt)))
      )

  test("§6.4.1: a go-live seen without a title (EventSub) carries the last known title and category"):
    supervised:
      val subject = tracker()
      subject.channelInfo("Round LCD build night", "Science & Technology")
      assertEquals(
        subject.wentLive("", ""),
        Some(RelayEvent.StreamStarted("somechannel", "Round LCD build night", "Science & Technology", Some(now)))
      )

  test("a second observer seeing the same live channel announces nothing"):
    supervised:
      val subject = tracker()
      subject.wentLive("Soldering", "Science").discard
      assertEquals(subject.wentLive("Soldering", "Science"), None)

  test("a relay that starts while the channel is offline does not announce that it ended"):
    supervised:
      assertEquals(tracker().wentOffline(), None)

  test("a channel that was live and then is not announces the end once, with how long it ran"):
    supervised:
      val subject = tracker()
      subject.wentLive("Soldering", "Science", Some(now.minusSeconds(3760))).discard
      assertEquals(subject.wentOffline(), Some(RelayEvent.StreamEnded("somechannel", 3760.seconds)))

  test("a repeated offline observation announces nothing"):
    supervised:
      val subject = tracker()
      subject.wentLive("Soldering", "Science").discard
      subject.wentOffline().discard
      assertEquals(subject.wentOffline(), None)

  extension [T](value: T) private def discard: Unit = ()

  test("a lagging absent poll does not end a newly pushed live stream"):
    supervised:
      val subject = tracker()
      subject.wentLive("title", "game").discard
      assertEquals(subject.observedOffline(), None)
      assertEquals(subject.observedLive("title", "game", Some(now)), None)

  test("two absent polls after the push grace period announce one ending"):
    supervised:
      class Movable extends Clock:
        var current = now
        override def instant(): Instant = current
        override def getZone = ZoneOffset.UTC
        override def withZone(zone: java.time.ZoneId): Clock = this
      val movable = Movable()
      val subject = ChannelStateTracker("somechannel", movable)
      subject.wentLive("title", "game").discard
      movable.current = now.plusSeconds(90)
      assertEquals(subject.observedOffline(), None)
      assertEquals(subject.observedOffline(), Some(RelayEvent.StreamEnded("somechannel", 90.seconds)))
      assertEquals(subject.observedOffline(), None)

  test("a stale live poll cannot undo a pushed stream ending"):
    supervised:
      val subject = tracker()
      subject.wentLive("title", "game").discard
      subject.wentOffline().discard
      assertEquals(subject.observedLive("title", "game", Some(now)), None)

  test("concurrent transitions publish in the same order as their state changes"):
    supervised:
      val subject = tracker()
      val entered = java.util.concurrent.CountDownLatch(1)
      val proceed = java.util.concurrent.CountDownLatch(1)
      val published = java.util.concurrent.ConcurrentLinkedQueue[RelayEvent]()
      val live = fork(
        subject.wentLive(
          "title",
          "game",
          publish = event =>
            entered.countDown()
            proceed.await()
            published.add(event).discard
        )
      )
      try
        assert(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val offline = fork(subject.wentOffline(event => published.add(event).discard))
        assert(published.isEmpty)
        proceed.countDown()
        live.join().discard
        offline.join().discard
      finally proceed.countDown()
      assert(published.poll().isInstanceOf[RelayEvent.StreamStarted])
      assert(published.poll().isInstanceOf[RelayEvent.StreamEnded])
      assert(published.isEmpty)
