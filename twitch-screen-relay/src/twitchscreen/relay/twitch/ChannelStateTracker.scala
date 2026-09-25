package twitchscreen.relay.twitch

import java.time.{Clock, Duration as JDuration, Instant}
import scala.concurrent.duration.{Duration, FiniteDuration, SECONDS}
import twitchscreen.relay.bus.RelayEvent
import ox.{Ox, tap}
import ox.channels.{Actor, ActorRef}

private[twitch] enum ChannelLiveness:
  case Unobserved
  case Live(since: Instant)
  case Offline

private final case class ChannelObservation(
    liveness: ChannelLiveness = ChannelLiveness.Unobserved,
    title: String = "",
    game: String = "",
    lastPush: Option[Instant] = None,
    absentPolls: Int = 0,
    pushedEndStartedAt: Option[Instant] = None
)

/** Push transitions take precedence for a grace period; a later offline poll requires two consecutive absences. Initial live observations
  * intentionally initialize statistics and announce the stream, preserving the established startup behavior.
  *
  * RLY-08: Helix lags EventSub, so the grace is `max(pushGrace, 3 × pollInterval)` (60 s by default). Absent polls inside the grace are not
  * counted, so a poll-driven END needs two consecutive absences after it. A pushed offline remembers the ended stream's start, and a later
  * poll reporting live with that same or an earlier `started_at` is ignored even after the grace; a strictly newer start is a new stream.
  */
private final class ChannelTrackerState(channel: String, clock: Clock, pushGrace: JDuration, pollInterval: JDuration):
  private var state = ChannelObservation()
  private val effectiveGrace =
    val polls = pollInterval.multipliedBy(3)
    if polls.compareTo(pushGrace) > 0 then polls else pushGrace

  def channelInfo(title: String, game: String): Unit =
    state = state.copy(title = Option(title).getOrElse(""), game = Option(game).getOrElse(""))

  def wentLive(title: String, game: String, startedAt: Option[Instant] = None): Option[RelayEvent] =
    observeLive(title, game, startedAt, push = true)

  def observedLive(title: String, game: String, startedAt: Option[Instant]): Option[RelayEvent] =
    observeLive(title, game, startedAt, push = false)

  def wentOffline(): Option[RelayEvent] = change: before =>
    val ended = before.liveness match
      case ChannelLiveness.Live(since) => Some(since)
      case _                           => before.pushedEndStartedAt
    offline(before.copy(lastPush = Some(clock.instant()), absentPolls = 0, pushedEndStartedAt = ended))

  def observedOffline(): Option[RelayEvent] = change: before =>
    before.liveness match
      case ChannelLiveness.Live(_) if recentPush(before) => before.copy(absentPolls = 0) -> None
      case liveness =>
        val absent = before.copy(absentPolls = math.min(2, before.absentPolls + 1))
        liveness match
          case ChannelLiveness.Live(_) if absent.absentPolls < 2 => absent -> None
          case _                                                 => offline(absent)

  private def observeLive(title: String, game: String, startedAt: Option[Instant], push: Boolean): Option[RelayEvent] = change: before =>
    if !push && before.liveness == ChannelLiveness.Offline && (recentPush(before) || endedByPush(before, startedAt)) then before -> None
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
    observation.lastPush.exists(sent => clock.instant().isBefore(sent.plus(effectiveGrace)))

  /** A poll carrying the start of a stream EventSub already ended (or an earlier one) is stale Helix data, not a new stream. */
  private def endedByPush(observation: ChannelObservation, startedAt: Option[Instant]): Boolean =
    startedAt.zip(observation.pushedEndStartedAt).exists((started, ended) => !started.isAfter(ended))

  private def offline(before: ChannelObservation): (ChannelObservation, Option[RelayEvent]) =
    val event = before.liveness match
      case ChannelLiveness.Live(since) =>
        Some(RelayEvent.StreamEnded(channel, FiniteDuration(math.max(0L, JDuration.between(since, clock.instant()).toSeconds), SECONDS)))
      case _ => None
    before.copy(liveness = ChannelLiveness.Offline) -> event

  private def change(transition: ChannelObservation => (ChannelObservation, Option[RelayEvent])): Option[RelayEvent] =
    val (after, event) = transition(state)
    state = after
    event

/** The actor owns both state transitions and publication, so concurrent push/poll callbacks cannot publish END before START. */
private[twitch] final class ChannelStateTracker private (state: ActorRef[ChannelTrackerState]):
  def channelInfo(title: String, game: String): Unit = state.ask(_.channelInfo(title, game))
  def wentLive(title: String, game: String, startedAt: Option[Instant] = None, publish: RelayEvent => Unit = _ => ()): Option[RelayEvent] =
    state.ask(_.wentLive(title, game, startedAt).tap(_.foreach(publish)))
  def observedLive(title: String, game: String, startedAt: Option[Instant], publish: RelayEvent => Unit = _ => ()): Option[RelayEvent] =
    state.ask(_.observedLive(title, game, startedAt).tap(_.foreach(publish)))
  def wentOffline(publish: RelayEvent => Unit = _ => ()): Option[RelayEvent] =
    state.ask(_.wentOffline().tap(_.foreach(publish)))
  def observedOffline(publish: RelayEvent => Unit = _ => ()): Option[RelayEvent] =
    state.ask(_.observedOffline().tap(_.foreach(publish)))

private[twitch] object ChannelStateTracker:
  def apply(channel: String, clock: Clock, pushGrace: JDuration = JDuration.ofSeconds(60), pollInterval: FiniteDuration = Duration.Zero)(
      using Ox
  ): ChannelStateTracker =
    new ChannelStateTracker(Actor.create(ChannelTrackerState(channel, clock, pushGrace, JDuration.ofNanos(pollInterval.toNanos))))
