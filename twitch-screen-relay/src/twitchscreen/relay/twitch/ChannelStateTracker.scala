package twitchscreen.relay.twitch

import java.time.{Clock, Duration as JDuration, Instant}
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import twitchscreen.relay.bus.RelayEvent

private[twitch] enum ChannelLiveness:
  case Unobserved
  case Live(since: Instant)
  case Offline

private final case class ChannelObservation(
    liveness: ChannelLiveness = ChannelLiveness.Unobserved,
    title: String = "",
    game: String = "",
    lastPush: Option[Instant] = None,
    absentPolls: Int = 0
)

/** Push transitions take precedence for a grace period; a later offline poll requires two consecutive absences. Initial live observations
  * intentionally initialize statistics and announce the stream, preserving the established startup behavior.
  */
private[twitch] final class ChannelStateTracker(channel: String, clock: Clock, pushGrace: JDuration = JDuration.ofSeconds(60)):
  private val state = AtomicReference(ChannelObservation())

  def channelInfo(title: String, game: String): Unit =
    state.updateAndGet(_.copy(title = Option(title).getOrElse(""), game = Option(game).getOrElse("")))
    ()

  def wentLive(title: String, game: String, startedAt: Option[Instant] = None): Option[RelayEvent] =
    observeLive(title, game, startedAt, push = true)

  def observedLive(title: String, game: String, startedAt: Option[Instant]): Option[RelayEvent] =
    observeLive(title, game, startedAt, push = false)

  def wentOffline(): Option[RelayEvent] = change: before =>
    offline(before.copy(lastPush = Some(clock.instant()), absentPolls = 0))

  def observedOffline(): Option[RelayEvent] = change: before =>
    val absent = before.copy(absentPolls = math.min(2, before.absentPolls + 1))
    before.liveness match
      case ChannelLiveness.Live(_) if recentPush(before) || absent.absentPolls < 2 => absent -> None
      case _                                                                       => offline(absent)

  private def observeLive(title: String, game: String, startedAt: Option[Instant], push: Boolean): Option[RelayEvent] = change: before =>
    if !push && before.liveness == ChannelLiveness.Offline && recentPush(before) then before -> None
    else
      val since = startedAt.getOrElse(before.liveness match
        case ChannelLiveness.Live(existing) => existing
        case _                              => clock.instant())
      val next = before.copy(
        liveness = ChannelLiveness.Live(since),
        lastPush = if push then Some(clock.instant()) else before.lastPush,
        absentPolls = 0
      )
      val event = before.liveness match
        case ChannelLiveness.Live(_) => None
        case _ =>
          Some(
            RelayEvent.StreamStarted(
              channel,
              Option(title).filterNot(_.isBlank).getOrElse(before.title),
              Option(game).filterNot(_.isBlank).getOrElse(before.game),
              Some(since)
            )
          )
      next -> event

  private def recentPush(observation: ChannelObservation): Boolean =
    observation.lastPush.exists(sent => clock.instant().isBefore(sent.plus(pushGrace)))

  private def offline(before: ChannelObservation): (ChannelObservation, Option[RelayEvent]) =
    val event = before.liveness match
      case ChannelLiveness.Live(since) =>
        Some(RelayEvent.StreamEnded(channel, FiniteDuration(math.max(0L, JDuration.between(since, clock.instant()).toSeconds), SECONDS)))
      case _ => None
    before.copy(liveness = ChannelLiveness.Offline) -> event

  @tailrec private def change(transition: ChannelObservation => (ChannelObservation, Option[RelayEvent])): Option[RelayEvent] =
    val before = state.get()
    val (after, event) = transition(before)
    if state.compareAndSet(before, after) then event else change(transition)
