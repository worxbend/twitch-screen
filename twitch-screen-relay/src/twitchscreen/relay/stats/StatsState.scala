package twitchscreen.relay.stats

import java.time.{Duration as JDuration, Instant}
import scala.concurrent.duration.{Duration, FiniteDuration, SECONDS}
import twitchscreen.relay.bus.{BusEvent, RelayEvent}
import twitchscreen.relay.protocol.*

/** Whether the channel is streaming, and since when. A separate type per state, so "live without a start time" cannot exist. */
private[stats] enum ChannelState:
  case Offline
  case Live(since: Instant)

/** Everything the relay knows about the channel right now, folded from the event bus. A pure value with pure transitions, so the
  * aggregation is testable without Twitch, a clock or a socket.
  *
  * Bot filtering happens upstream, at the source (§13.1), so every `ChatMessaged` that reaches this fold is one a human wrote. That is what
  * makes `messagesTotal` and the chat rate count exactly the same population of messages — if they counted different ones, both would lie.
  */
private[stats] final case class StatsState(
    channel: ChannelState,
    viewers: Count,
    followers: Count,
    subscribers: Count,
    recentChatAt: Vector[Instant],
    /** §6.5's `msg_total`: cumulative non-bot chat messages since the current stream started. It is **not** derived from `recentChatAt`,
      * which is a one-minute sliding window; the device shows a running total for the whole stream.
      */
    messagesTotal: Long
):
  def apply(message: BusEvent, window: FiniteDuration): StatsState = message.event match
    // §6.5: `msg_total` resets to 0 when a stream starts, and deliberately not when one ends — the last figure
    // stays on the idle screen until the next stream begins. Twitch's own start time beats the moment the relay
    // happened to notice, which is why `StreamStarted` carries one.
    case RelayEvent.StreamStarted(_, _, _, startedAt) =>
      copy(channel = ChannelState.Live(startedAt.getOrElse(message.at)), messagesTotal = 0L)

    case RelayEvent.StreamEnded(_, _) => copy(channel = ChannelState.Offline, viewers = Count.Zero)

    // A viewer observation only ever comes from a live stream, and Helix's own uptime is a better start time than
    // the moment this relay happened to notice.
    case RelayEvent.ViewersObserved(observed, uptime) =>
      copy(channel = ChannelState.Live(message.at.minusSeconds(uptime.toSeconds)), viewers = observed)

    case RelayEvent.FollowersObserved(total)   => copy(followers = total)
    case RelayEvent.SubscribersObserved(total) => copy(subscribers = total)
    case RelayEvent.ChatMessaged(_, _, _) =>
      copy(recentChatAt = pruned(message.at, window) :+ message.at, messagesTotal = messagesTotal + 1L)
    case _ => this

  def toStats(now: Instant, window: FiniteDuration): StreamStats =
    StreamStats(
      state = channel match
        case ChannelState.Live(_) => StreamState.Live
        case ChannelState.Offline => StreamState.Offline
      ,
      viewers = viewers,
      followers = followers,
      subscribers = subscribers,
      uptime = uptimeAt(now),
      chatRate = chatRateAt(now, window),
      messagesTotal = Count.clamp(math.min(messagesTotal, Int.MaxValue.toLong).toInt),
      streamStartedAt = channel match
        case ChannelState.Live(since) => Some(since)
        case ChannelState.Offline     => None
    )

  /** Drops timestamps that have fallen out of the sliding window, so the buffer stays proportional to the rate. */
  def prune(now: Instant, window: FiniteDuration): StatsState = copy(recentChatAt = pruned(now, window))

  private def pruned(now: Instant, window: FiniteDuration): Vector[Instant] =
    val cutoff = now.minusSeconds(window.toSeconds)
    recentChatAt.dropWhile(_.isBefore(cutoff))

  private def uptimeAt(now: Instant): FiniteDuration = channel match
    case ChannelState.Offline     => Duration.Zero
    case ChannelState.Live(since) => FiniteDuration(math.max(0L, JDuration.between(since, now).toSeconds), SECONDS)

  /** Messages per minute, extrapolated from however long the window is. */
  private def chatRateAt(now: Instant, window: FiniteDuration): MessagesPerMinute =
    val seconds = math.max(1L, window.toSeconds)
    MessagesPerMinute.clamp(((pruned(now, window).size.toLong * 60L) / seconds).toInt)

private[stats] object StatsState:
  val Initial: StatsState = StatsState(ChannelState.Offline, Count.Zero, Count.Zero, Count.Zero, Vector.empty, 0L)
