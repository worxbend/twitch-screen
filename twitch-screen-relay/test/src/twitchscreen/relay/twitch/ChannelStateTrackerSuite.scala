package twitchscreen.relay.twitch

import twitchscreen.relay.bus.RelayEvent

/** EventSub and the Helix poll both observe the channel; exactly one of them may announce a change. */
class ChannelStateTrackerSuite extends munit.FunSuite:
  test("the first sighting of a live channel is announced"):
    assertEquals(
      ChannelStateTracker("somechannel").wentLive("Soldering", "Science"),
      Some(RelayEvent.StreamStarted("somechannel", "Soldering", "Science"))
    )

  test("a second observer seeing the same live channel announces nothing"):
    val tracker = ChannelStateTracker("somechannel")
    tracker.wentLive("Soldering", "Science").discard
    assertEquals(tracker.wentLive("Soldering", "Science"), None)

  test("a relay that starts while the channel is offline does not announce that it ended"):
    assertEquals(ChannelStateTracker("somechannel").wentOffline(), None)

  test("a channel that was live and then is not announces the end once"):
    val tracker = ChannelStateTracker("somechannel")
    tracker.wentLive("Soldering", "Science").discard
    assertEquals(tracker.wentOffline(), Some(RelayEvent.StreamEnded("somechannel")))

  test("a repeated offline observation announces nothing"):
    val tracker = ChannelStateTracker("somechannel")
    tracker.wentLive("Soldering", "Science").discard
    tracker.wentOffline().discard
    assertEquals(tracker.wentOffline(), None)

  extension [T](value: T) private def discard: Unit = ()
