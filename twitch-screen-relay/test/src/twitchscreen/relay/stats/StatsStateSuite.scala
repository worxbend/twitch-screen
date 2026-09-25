package twitchscreen.relay.stats

import java.time.Instant
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.{BusEvent, RelayEvent}
import twitchscreen.relay.config.{ChatNotifications, NotificationsConfig}
import twitchscreen.relay.protocol.{Count, StreamState}
import twitchscreen.relay.twitch.BotFilter

/** The aggregation is a pure fold, so none of this needs a clock, a socket or Twitch. */
class StatsStateSuite extends munit.FunSuite:
  private val start = Instant.ofEpochSecond(1790309000L)
  private val window = 1.minute

  private def at(offsetSeconds: Long) = start.plusSeconds(offsetSeconds)
  private def event(offsetSeconds: Long, event: RelayEvent) = BusEvent(at(offsetSeconds), event)
  private def streamStarted(offsetSeconds: Long) =
    event(offsetSeconds, RelayEvent.StreamStarted("c", "title", "game", Some(at(offsetSeconds))))
  private def chat(offsetSeconds: Long, user: String = "viewer") =
    event(offsetSeconds, RelayEvent.ChatMessaged(user, "hi", None))

  test("a relay that starts while the channel is offline reports offline"):
    assertEquals(StatsState.Initial.toStats(start, window).state, StreamState.Offline)

  test("a stream that started reports live"):
    assertEquals(StatsState.Initial.apply(streamStarted(0), window).toStats(at(30), window).state, StreamState.Live)

  test("uptime is measured from the moment the stream started"):
    assertEquals(StatsState.Initial.apply(streamStarted(0), window).toStats(at(900), window).uptime, 900.seconds)

  test("a viewer observation backdates the start time to Helix's own uptime"):
    val state = StatsState.Initial.apply(event(600, RelayEvent.ViewersObserved(Count.clamp(842), 600.seconds)), window)
    assertEquals(state.toStats(at(600), window).uptime, 600.seconds)

  test("a stream that ended reports no viewers"):
    val live = StatsState.Initial.apply(event(0, RelayEvent.ViewersObserved(Count.clamp(842), 10.seconds)), window)
    val ended = live.apply(event(60, RelayEvent.StreamEnded("c", 60.seconds)), window)
    assertEquals(ended.toStats(at(60), window).viewers, Count.Zero)

  test("the chat rate counts the messages inside the window"):
    val chatty = (0 until 20).foldLeft(StatsState.Initial)((state, index) => state.apply(chat(index.toLong), window))
    assertEquals(chatty.toStats(at(30), window).chatRate.value, 20)

  test("messages that fall out of the window stop counting toward the rate"):
    val chatty = (0 until 20).foldLeft(StatsState.Initial)((state, index) => state.apply(chat(index.toLong), window))
    assertEquals(chatty.toStats(at(600), window).chatRate.value, 0)

  test("pruning drops what the window no longer covers"):
    assertEquals(StatsState.Initial.apply(chat(0), window).prune(at(600), window).recentChatAt, Vector.empty)

  test("follower and subscriber totals are carried through"):
    val counted = StatsState.Initial
      .apply(event(0, RelayEvent.FollowersObserved(Count.clamp(12400))), window)
      .apply(event(1, RelayEvent.SubscribersObserved(Count.clamp(318))), window)
    assertEquals((counted.followers.value, counted.subscribers.value), (12400, 318))

  test("a chat rate measured over a half-minute window is still reported per minute"):
    val chatty = (0 until 10).foldLeft(StatsState.Initial)((state, index) => state.apply(chat(index.toLong), 30.seconds))
    assertEquals(chatty.toStats(at(20), 30.seconds).chatRate.value, 20)

  // ---- §6.5's two new fields ----

  test("msg_total is cumulative for the whole stream, not the one-minute window the rate uses"):
    val chatty = (0 until 20).foldLeft(StatsState.Initial.apply(streamStarted(0), window)): (state, index) =>
      state.apply(chat(index.toLong), window)
    val stats = chatty.toStats(at(600), window)
    assertEquals(stats.messagesTotal.value, 20)
    assertEquals(stats.chatRate.value, 0, "the rate has aged out; the total has not")

  test("msg_total resets when a stream starts"):
    val carried = StatsState.Initial.apply(chat(0), window).apply(chat(1), window)
    assertEquals(carried.apply(streamStarted(2), window).toStats(at(3), window).messagesTotal.value, 0)

  test("msg_total does not reset when a stream ends, so the last figure stays on the idle screen"):
    val chatty = StatsState.Initial.apply(streamStarted(0), window).apply(chat(1), window).apply(chat(2), window)
    assertEquals(chatty.apply(event(3, RelayEvent.StreamEnded("c", 3.seconds)), window).toStats(at(4), window).messagesTotal.value, 2)

  test("stream_started_at is carried absolutely while live and is empty when offline"):
    val live = StatsState.Initial.apply(streamStarted(0), window)
    assertEquals(live.toStats(at(30), window).streamStartedAt, Some(start))
    assertEquals(live.apply(event(60, RelayEvent.StreamEnded("c", 60.seconds)), window).toStats(at(60), window).streamStartedAt, None)

  test("the stream's own start time beats the moment the relay noticed it"):
    val late = BusEvent(at(900), RelayEvent.StreamStarted("c", "title", "game", Some(start)))
    assertEquals(StatsState.Initial.apply(late, window).toStats(at(900), window).uptime, 900.seconds)

  // ---- §13.1, through the fold the device actually reads ----

  test("a bot's message counts toward neither msg_total nor chat_rate, and a human's counts toward both"):
    val filter = BotFilter.from(NotificationsConfig(30.seconds, ChatNotifications.Show))
    val messages = List(
      streamStarted(0),
      chat(1, "sparkplug"),
      chat(2, "Nightbot"),
      chat(3, "StreamElements"),
      chat(4, "vectorvic")
    )
    // The filter runs at the source, so only what it allows ever reaches this fold — which is exactly why both
    // numbers exclude bots without the statistics layer knowing the filter exists.
    val folded = messages.filter(message => filter.allows(message.event)).foldLeft(StatsState.Initial)(_.apply(_, window))
    val stats = folded.toStats(at(5), window)
    assertEquals(stats.messagesTotal.value, 2)
    assertEquals(stats.chatRate.value, 2)
