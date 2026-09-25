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

  private class Movable extends Clock:
    var current = now
    def at(seconds: Long): Unit = current = now.plusSeconds(seconds)
    override def instant(): Instant = current
    override def getZone = ZoneOffset.UTC
    override def withZone(zone: java.time.ZoneId): Clock = this

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

  test("RLY-21: a channel update with no title or category leaves them empty rather than 'null'"):
    supervised:
      val subject = tracker()
      subject.channelInfo(null, null)
      val started = subject.wentLive("", "")
      assertEquals(started, Some(RelayEvent.StreamStarted("somechannel", "", "", Some(now))))
      assert(!started.map(_.summary).getOrElse("").contains("null"), started)

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

  test("RLY-08: absent polls inside the push grace are not counted; END needs two absences after it"):
    supervised:
      val movable = Movable()
      val subject = ChannelStateTracker("somechannel", movable, pollInterval = 30.seconds)
      subject.wentLive("title", "game").discard
      for offset <- List(5L, 35L, 65L) do
        movable.at(offset)
        assertEquals(subject.observedOffline(), None, s"absence at +$offset s is inside the 90 s grace")
      movable.at(95)
      assertEquals(subject.observedOffline(), None)
      movable.at(125)
      assertEquals(subject.observedOffline(), Some(RelayEvent.StreamEnded("somechannel", 125.seconds)))
      assertEquals(subject.observedOffline(), None)

  test("RLY-08: the grace is at least 60 s for a short poll interval"):
    supervised:
      val movable = Movable()
      val subject = ChannelStateTracker("somechannel", movable, pollInterval = 5.seconds)
      subject.wentLive("title", "game").discard
      movable.at(30)
      assertEquals(subject.observedOffline(), None)
      movable.at(61)
      assertEquals(subject.observedOffline(), None)
      movable.at(66)
      assertEquals(subject.observedOffline(), Some(RelayEvent.StreamEnded("somechannel", 66.seconds)))

  test("RLY-08: a stale live poll after the grace cannot undo a pushed ending"):
    supervised:
      val movable = Movable()
      val subject = ChannelStateTracker("somechannel", movable)
      val startedAt = now.minusSeconds(3600)
      subject.wentLive("title", "game", Some(startedAt)).discard
      subject.wentOffline().discard
      movable.at(120)
      assertEquals(subject.observedLive("title", "game", Some(startedAt)), None)
      assertEquals(subject.observedLive("title", "game", Some(startedAt.minusSeconds(10))), None)

  test("RLY-08: a newer started_at after a pushed ending announces a new stream"):
    supervised:
      val movable = Movable()
      val subject = ChannelStateTracker("somechannel", movable)
      subject.wentLive("title", "game", Some(now.minusSeconds(3600))).discard
      subject.wentOffline().discard
      movable.at(120)
      val restarted = now.plusSeconds(100)
      assertEquals(
        subject.observedLive("title", "game", Some(restarted)),
        Some(RelayEvent.StreamStarted("somechannel", "title", "game", Some(restarted)))
      )

  test("RLY-08: a poll during the grace after a push resets the absence count"):
    supervised:
      val movable = Movable()
      val subject = ChannelStateTracker("somechannel", movable, pollInterval = 30.seconds)
      subject.wentLive("title", "game").discard
      movable.at(80)
      assertEquals(subject.observedOffline(), None)
      movable.at(85)
      assertEquals(subject.observedLive("title", "game", Some(now)), None)
      movable.at(95)
      assertEquals(subject.observedOffline(), None)
      movable.at(125)
      assertEquals(subject.observedOffline(), Some(RelayEvent.StreamEnded("somechannel", 125.seconds)))

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
