package twitchscreen.relay.twitch

import java.time.{Clock, Duration as JDuration, Instant}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import twitchscreen.relay.bus.RelayEvent

/** What the tracker believes about the channel. A separate case per state, so "live without a start time" cannot be represented — which is
  * what lets `StreamEnded` carry a duration that is always either real or absent by construction.
  */
private[twitch] enum ChannelLiveness:
  /** Nothing observed yet. Distinct from `Offline`, because a relay that boots while the channel is dark must not announce an ending. */
  case Unobserved
  case Live(since: Instant)
  case Offline

/** The one place that decides whether the channel just changed state.
  *
  * Both EventSub (push, fast) and the Helix poll (pull, certain) observe the same thing, and either may notice first. Routing both through
  * this tracker means a stream going live produces exactly one `StreamStarted` no matter which of them saw it, without either having to
  * know the other exists.
  *
  * It also owns the two numbers §6.4.1 puts in `EVENT.value`: `STREAM_START.value` is the stream's start in unix seconds and
  * `STREAM_END.value` is its duration in seconds. Neither is recoverable from the moment the relay happened to notice, so the start instant
  * is remembered here — Helix's own `started_at` when it is known, and the moment of first sighting when it is not.
  */
private[twitch] final class ChannelStateTracker(channel: String, clock: Clock):
  private val state = AtomicReference[ChannelLiveness](ChannelLiveness.Unobserved)

  /** Last known (title, game). EventSub's `stream.online` carries neither, and it usually beats the Helix poll that does, so §6.4.1's
    * `STREAM_START.text` (the stream title) is filled from the last `channel.update` or poll instead of going out empty.
    */
  private val lastInfo = AtomicReference[(String, String)](("", ""))

  def channelInfo(title: String, game: String): Unit =
    lastInfo.set((Option(title).getOrElse(""), Option(game).getOrElse("")))

  /** `startedAt` is Twitch's own start time where the observer has one; without it the relay's first sighting is the best anchor it has. */
  def wentLive(title: String, game: String, startedAt: Option[Instant] = None): Option[RelayEvent] =
    val since = startedAt.getOrElse(clock.instant())
    val (knownTitle, knownGame) = lastInfo.get
    state.getAndSet(ChannelLiveness.Live(since)) match
      case ChannelLiveness.Live(_) => None
      case _ =>
        Some(
          RelayEvent.StreamStarted(
            channel,
            if title.isBlank then knownTitle else title,
            if game.isBlank then knownGame else game,
            Some(since)
          )
        )

  /** Startup is not a transition: a relay that boots while the channel is offline should not announce it ended. */
  def wentOffline(): Option[RelayEvent] =
    state.getAndSet(ChannelLiveness.Offline) match
      case ChannelLiveness.Live(since) => Some(RelayEvent.StreamEnded(channel, elapsedSince(since)))
      case _                           => None

  private def elapsedSince(since: Instant): FiniteDuration =
    FiniteDuration(math.max(0L, JDuration.between(since, clock.instant()).toSeconds), SECONDS)
