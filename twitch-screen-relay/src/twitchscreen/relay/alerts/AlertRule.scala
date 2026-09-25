package twitchscreen.relay.alerts

import scala.concurrent.duration.FiniteDuration
import java.time.Instant
import twitchscreen.relay.config.{AlertsConfig, TwitchMode}

/** The conditions the relay watches. Rules are built from configuration at startup rather than being editable at runtime: on a device with
  * no database, a rule that survives a restart is one that lives in the config file.
  */
enum AlertRule(val name: String, val severity: AlertSeverity):
  case NoDevicesConnected(after: FiniteDuration) extends AlertRule("no-devices-connected", AlertSeverity.Critical)
  case TwitchDisconnected(after: FiniteDuration) extends AlertRule("twitch-disconnected", AlertSeverity.Critical)
  case StreamOffline(after: FiniteDuration) extends AlertRule("stream-offline", AlertSeverity.Warning)
  case FailureRate(threshold: Int, window: FiniteDuration) extends AlertRule("failure-rate", AlertSeverity.Warning)

object AlertRule:
  extension (rule: AlertRule)
    def description: String = rule match
      case NoDevicesConnected(after)      => s"no device has been connected for $after"
      case TwitchDisconnected(after)      => s"the Twitch link has been down for $after"
      case StreamOffline(after)           => s"the channel has been offline for $after"
      case FailureRate(threshold, window) => s"$threshold or more relay failures within $window"

    /** The message to raise for this rule, or `None` while the rule is satisfied. */
    private[alerts] def check(state: MonitorState, now: Instant): Option[String] = rule match
      case AlertRule.NoDevicesConnected(after) =>
        state.devices.absentFor(now).filter(_ >= after).map(elapsed => s"no device connected for ${elapsed.toSeconds}s")
      case AlertRule.TwitchDisconnected(after) =>
        state.twitch.absentFor(now).filter(_ >= after).map(elapsed => s"Twitch link down for ${elapsed.toSeconds}s")
      case AlertRule.StreamOffline(after) =>
        state.stream.absentFor(now).filter(_ >= after).map(elapsed => s"channel offline for ${elapsed.toSeconds}s")
      case AlertRule.FailureRate(threshold, window) =>
        val cutoff = now.minusSeconds(window.toSeconds)
        val recent = state.failuresAt.count(!_.isBefore(cutoff))
        Option.when(recent >= threshold)(s"$recent relay failures within $window")

  /** An unset threshold disables its rule. The Twitch rules are also skipped when Twitch is not configured at all, so a relay running
    * without credentials does not alert about a link it was never asked to open.
    */
  def from(config: AlertsConfig, twitchMode: TwitchMode): List[AlertRule] =
    val twitchWatched = twitchMode != TwitchMode.Disabled
    List(
      config.noDevicesConnectedFor.map(NoDevicesConnected.apply),
      config.twitchDisconnectedFor.filter(_ => twitchWatched).map(TwitchDisconnected.apply),
      config.streamOfflineFor.filter(_ => twitchWatched).map(StreamOffline.apply),
      Option.when(config.errorRateThreshold > 0)(FailureRate(config.errorRateThreshold, config.errorRateWindow))
    ).flatten
