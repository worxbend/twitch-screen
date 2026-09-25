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
    case RelayEvent.TwitchLinkUp(_)        => copy(twitch = twitch.sighted)
    case RelayEvent.TwitchLinkDown(_)      => copy(twitch = twitch.missing(message.at))
    case RelayEvent.StreamStarted(_, _, _) => copy(stream = stream.sighted)
    case RelayEvent.StreamEnded(_)         => copy(stream = stream.missing(message.at))
    case RelayEvent.ViewersObserved(_, _)  => copy(stream = stream.sighted)
    case RelayEvent.RelayFailure(_, _)     => copy(failuresAt = failuresAt :+ message.at)
    case _                                 => this

  /** Device presence is read from the hub at evaluation time rather than tracked from events, which cannot go stale. */
  def withDevices(connected: Int, now: Instant): MonitorState =
    copy(devices = if connected > 0 then devices.sighted else devices.missing(now))

  def pruneFailures(now: Instant, window: FiniteDuration): MonitorState =
    val cutoff = now.minusSeconds(window.toSeconds)
    copy(failuresAt = failuresAt.dropWhile(_.isBefore(cutoff)))

  /** The message to raise for this rule, or `None` while the rule is satisfied. */
  def check(rule: AlertRule, now: Instant): Option[String] = rule match
    case AlertRule.NoDevicesConnected(after) =>
      devices.absentFor(now).filter(_ >= after).map(elapsed => s"no device connected for ${elapsed.toSeconds}s")
    case AlertRule.TwitchDisconnected(after) =>
      twitch.absentFor(now).filter(_ >= after).map(elapsed => s"Twitch link down for ${elapsed.toSeconds}s")
    case AlertRule.StreamOffline(after) =>
      stream.absentFor(now).filter(_ >= after).map(elapsed => s"channel offline for ${elapsed.toSeconds}s")
    case AlertRule.FailureRate(threshold, window) =>
      val cutoff = now.minusSeconds(window.toSeconds)
      val recent = failuresAt.count(!_.isBefore(cutoff))
      Option.when(recent >= threshold)(s"$recent relay failures within $window")

private[alerts] object MonitorState:
  /** Everything starts absent: the relay has genuinely not seen a device or a Twitch frame when it boots. */
  def initial(startedAt: Instant): MonitorState =
    MonitorState(Presence.Absent(startedAt), Presence.Absent(startedAt), Presence.Absent(startedAt), Vector.empty)
