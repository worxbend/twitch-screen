package twitchscreen.relay.alerts

import java.time.{Duration as JDuration, Instant}
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import twitchscreen.relay.bus.{BusEvent, RelayEvent}

/** Whether something the relay depends on is there, and since when it has not been. */
private[alerts] enum Presence:
  case Present
  case Absent(since: Instant)

private[alerts] object Presence:
  extension (presence: Presence)
    /** How long this has been absent, or `None` while it is present. */
    def absentFor(now: Instant): Option[FiniteDuration] = presence match
      case Present       => None
      case Absent(since) => Some(FiniteDuration(math.max(0L, JDuration.between(since, now).toSeconds), SECONDS))

    def sighted: Presence = Present

    def missing(now: Instant): Presence = presence match
      case Absent(since) => Absent(since)
      case Present       => Absent(now)

/** What the alert rules are evaluated against. Pure, so every rule can be tested without a clock or a socket. */
private[alerts] final case class MonitorState(twitch: Presence, stream: Presence, devices: Presence, failuresAt: Vector[Instant]):
  def apply(message: BusEvent): MonitorState = message.event match
    case RelayEvent.TwitchLinkUp(_)           => copy(twitch = twitch.sighted)
    case RelayEvent.TwitchLinkDown(_)         => copy(twitch = twitch.missing(message.at))
    case RelayEvent.StreamStarted(_, _, _, _) => copy(stream = stream.sighted)
    case RelayEvent.StreamEnded(_, _)         => copy(stream = stream.missing(message.at))
    case RelayEvent.ViewersObserved(_, _)     => copy(stream = stream.sighted)
    case RelayEvent.RelayFailure(_, _)        => copy(failuresAt = failuresAt :+ message.at)
    case _                                    => this

  /** Device presence is read from the hub at evaluation time rather than tracked from events, which cannot go stale. */
  def withDevices(connected: Int, now: Instant): MonitorState =
    copy(devices = if connected > 0 then devices.sighted else devices.missing(now))

  def pruneFailures(now: Instant, window: FiniteDuration): MonitorState =
    val cutoff = now.minusSeconds(window.toSeconds)
    copy(failuresAt = failuresAt.dropWhile(_.isBefore(cutoff)))

private[alerts] object MonitorState:
  /** Everything starts absent: the relay has genuinely not seen a device or a Twitch frame when it boots. */
  def initial(startedAt: Instant): MonitorState =
    MonitorState(Presence.Absent(startedAt), Presence.Absent(startedAt), Presence.Absent(startedAt), Vector.empty)
