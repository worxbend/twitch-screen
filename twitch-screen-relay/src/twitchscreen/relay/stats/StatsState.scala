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
  */
private[stats] final case class StatsState(
    channel: ChannelState,
    viewers: Count,
    followers: Count,
    subscribers: Count,
    recentChatAt: Vector[Instant]
):
  def apply(message: BusEvent, window: FiniteDuration): StatsState = message.event match
    case RelayEvent.StreamStarted(_, _, _) => copy(channel = ChannelState.Live(message.at))
    case RelayEvent.StreamEnded(_)         => copy(channel = ChannelState.Offline, viewers = Count.Zero)

    // A viewer observation only ever comes from a live stream, and Helix's own uptime is a better start time than
    // the moment this relay happened to notice.
    case RelayEvent.ViewersObserved(observed, uptime) =>
      copy(channel = ChannelState.Live(message.at.minusSeconds(uptime.toSeconds)), viewers = observed)

    case RelayEvent.FollowersObserved(total)   => copy(followers = total)
    case RelayEvent.SubscribersObserved(total) => copy(subscribers = total)
    case RelayEvent.ChatMessaged(_, _)         => copy(recentChatAt = pruned(message.at, window) :+ message.at)
    case _                                     => this

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
      chatRate = chatRateAt(now, window)
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
  val Initial: StatsState = StatsState(ChannelState.Offline, Count.Zero, Count.Zero, Count.Zero, Vector.empty)
