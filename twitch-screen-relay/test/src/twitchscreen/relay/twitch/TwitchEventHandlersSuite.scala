package twitchscreen.relay.twitch

import twitchscreen.relay.bus.RelayEvent

/** RLY-21: twitch4j leaves `ChannelUpdateV2Event` fields null when Twitch omits them. The WebSocket path must map them exactly as the
  * webhook path does, so a missing title never reaches the screen as the word "null".
  */
class TwitchEventHandlersSuite extends munit.FunSuite:
  test("RLY-21: an update with every field missing names the configured channel and leaves the text empty"):
    val update = TwitchEventHandlers.channelUpdated(null, null, null, "somechannel")
    assertEquals(update, RelayEvent.ChannelUpdated("somechannel", "", ""))
    assertEquals(update.summary, "somechannel updated:  ()")
    assert(!update.summary.contains("null"), update.summary)

  test("RLY-21: fields Twitch reports pass through unchanged"):
    assertEquals(
      TwitchEventHandlers.channelUpdated("Streamer", "Soldering", "Science", "somechannel"),
      RelayEvent.ChannelUpdated("Streamer", "Soldering", "Science")
    )

  test("RLY-21: a missing category alone becomes empty and the rest is kept"):
    assertEquals(
      TwitchEventHandlers.channelUpdated("Streamer", "Soldering", null, "somechannel"),
      RelayEvent.ChannelUpdated("Streamer", "Soldering", "")
    )
