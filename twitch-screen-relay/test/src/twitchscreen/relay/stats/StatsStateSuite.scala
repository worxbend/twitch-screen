package twitchscreen.relay.stats

import java.time.Instant
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.{BusEvent, RelayEvent}
import twitchscreen.relay.protocol.{Count, StreamState}

/** The aggregation is a pure fold, so none of this needs a clock, a socket or Twitch. */
class StatsStateSuite extends munit.FunSuite:
  private val start = Instant.ofEpochSecond(1790309000L)
  private val window = 1.minute

  private def at(offsetSeconds: Long) = start.plusSeconds(offsetSeconds)
  private def event(offsetSeconds: Long, event: RelayEvent) = BusEvent(at(offsetSeconds), event)

  test("a relay that starts while the channel is offline reports offline"):
    assertEquals(StatsState.Initial.toStats(start, window).state, StreamState.Offline)

  test("a stream that started reports live"):
    val state = StatsState.Initial.apply(event(0, RelayEvent.StreamStarted("c", "title", "game")), window)
    assertEquals(state.toStats(at(30), window).state, StreamState.Live)

  test("uptime is measured from the moment the stream started"):
    val state = StatsState.Initial.apply(event(0, RelayEvent.StreamStarted("c", "title", "game")), window)
    assertEquals(state.toStats(at(900), window).uptime, 900.seconds)

  test("a viewer observation backdates the start time to Helix's own uptime"):
    val state = StatsState.Initial.apply(event(600, RelayEvent.ViewersObserved(Count.clamp(842), 600.seconds)), window)
    assertEquals(state.toStats(at(600), window).uptime, 600.seconds)

  test("a stream that ended reports no viewers"):
    val live = StatsState.Initial.apply(event(0, RelayEvent.ViewersObserved(Count.clamp(842), 10.seconds)), window)
    val ended = live.apply(event(60, RelayEvent.StreamEnded("c")), window)
    assertEquals(ended.toStats(at(60), window).viewers, Count.Zero)

  test("the chat rate counts the messages inside the window"):
    val chatty = (0 until 20).foldLeft(StatsState.Initial): (state, index) =>
      state.apply(event(index.toLong, RelayEvent.ChatMessaged("viewer", "hi")), window)
    assertEquals(chatty.toStats(at(30), window).chatRate.value, 20)

  test("messages that fall out of the window stop counting"):
    val chatty = (0 until 20).foldLeft(StatsState.Initial): (state, index) =>
      state.apply(event(index.toLong, RelayEvent.ChatMessaged("viewer", "hi")), window)
    assertEquals(chatty.toStats(at(600), window).chatRate.value, 0)

  test("pruning drops what the window no longer covers"):
    val chatty = StatsState.Initial.apply(event(0, RelayEvent.ChatMessaged("viewer", "hi")), window)
    assertEquals(chatty.prune(at(600), window).recentChatAt, Vector.empty)

  test("follower and subscriber totals are carried through"):
    val counted = StatsState.Initial
      .apply(event(0, RelayEvent.FollowersObserved(Count.clamp(12400))), window)
      .apply(event(1, RelayEvent.SubscribersObserved(Count.clamp(318))), window)
    assertEquals((counted.followers.value, counted.subscribers.value), (12400, 318))

  test("a chat rate measured over a half-minute window is still reported per minute"):
    val chatty = (0 until 10).foldLeft(StatsState.Initial): (state, index) =>
      state.apply(event(index.toLong, RelayEvent.ChatMessaged("viewer", "hi")), 30.seconds)
    assertEquals(chatty.toStats(at(20), 30.seconds).chatRate.value, 20)
