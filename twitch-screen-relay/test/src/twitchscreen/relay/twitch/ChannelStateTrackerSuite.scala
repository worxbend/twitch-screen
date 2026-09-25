package twitchscreen.relay.twitch

import java.time.{Clock, Instant, ZoneOffset}
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.RelayEvent

/** EventSub and the Helix poll both observe the channel; exactly one of them may announce a change.
  *
  * The tracker also owns the two numbers §6.4.1 puts in `EVENT.value` — the stream's start and its duration — because neither is
  * recoverable from the moment the relay happened to notice.
  */
class ChannelStateTrackerSuite extends munit.FunSuite:
  private val now = Instant.ofEpochSecond(1790309000L)
  private val clock = Clock.fixed(now, ZoneOffset.UTC)

  private def tracker(): ChannelStateTracker = ChannelStateTracker("somechannel", clock)

  test("the first sighting of a live channel is announced, anchored on the relay's clock when nothing better is known"):
    assertEquals(
      tracker().wentLive("Soldering", "Science"),
      Some(RelayEvent.StreamStarted("somechannel", "Soldering", "Science", Some(now)))
    )

  test("Twitch's own start time is carried through when the observer has one"):
    val startedAt = now.minusSeconds(3660)
    assertEquals(
      tracker().wentLive("Soldering", "Science", Some(startedAt)),
      Some(RelayEvent.StreamStarted("somechannel", "Soldering", "Science", Some(startedAt)))
    )

  test("§6.4.1: a go-live seen without a title (EventSub) carries the last known title and category"):
    val subject = tracker()
    subject.channelInfo("Round LCD build night", "Science & Technology")
    assertEquals(
      subject.wentLive("", ""),
      Some(RelayEvent.StreamStarted("somechannel", "Round LCD build night", "Science & Technology", Some(now)))
    )

  test("a second observer seeing the same live channel announces nothing"):
    val subject = tracker()
    subject.wentLive("Soldering", "Science").discard
    assertEquals(subject.wentLive("Soldering", "Science"), None)

  test("a relay that starts while the channel is offline does not announce that it ended"):
    assertEquals(tracker().wentOffline(), None)

  test("a channel that was live and then is not announces the end once, with how long it ran"):
    val subject = tracker()
    subject.wentLive("Soldering", "Science", Some(now.minusSeconds(3760))).discard
    assertEquals(subject.wentOffline(), Some(RelayEvent.StreamEnded("somechannel", 3760.seconds)))

  test("a repeated offline observation announces nothing"):
    val subject = tracker()
    subject.wentLive("Soldering", "Science").discard
    subject.wentOffline().discard
    assertEquals(subject.wentOffline(), None)

  extension [T](value: T) private def discard: Unit = ()
