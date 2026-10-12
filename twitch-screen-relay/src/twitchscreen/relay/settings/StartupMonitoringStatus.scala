package twitchscreen.relay.settings

import sttp.tapir.Schema

/** Read-only startup diagnostics. This does not implement runtime channel switching or the canonical monitoring mutation API. */
final case class StartupMonitoringIntent(mode: String, channel: Option[String]) derives Schema

final case class StartupMonitoringStatus(
    settingsSource: String,
    desired: StartupMonitoringIntent,
    effective: StartupMonitoringIntent,
    transition: String,
    error: Option[String]
) derives Schema
