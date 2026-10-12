package twitchscreen.relay.settings

import java.util.Locale
import twitchscreen.relay.config.{TwitchConfig, TwitchMode}

/** Stored desired monitoring intent overrides startup defaults, not deployment secrets or provider configuration. */
private[relay] object RuntimeSettingsStartup:
  def defaults(config: TwitchConfig): RuntimeSettings =
    val channel = config.channel.trim.toLowerCase(Locale.ROOT)
    RuntimeSettings.firstRun(config.mode.toString.toLowerCase(Locale.ROOT), if channel.isEmpty then null else channel)

  final case class Selection(config: TwitchConfig, status: StartupMonitoringStatus)

  def select(config: TwitchConfig, snapshot: SettingsSnapshot): Selection =
    val settings = snapshot.confirmed.monitoring
    val mode = settings.mode match
      case "disabled"  => TwitchMode.Disabled
      case "simulated" => TwitchMode.Simulated
      case "live"      => TwitchMode.Live
      case _           => throw IllegalArgumentException("invalid stored monitoring mode")
    val selected = config.copy(mode = mode, channel = settings.channel.value.getOrElse(""))
    val missing = mode == TwitchMode.Live &&
      (selected.channel.trim.isEmpty || selected.clientId.trim.isEmpty || !selected.clientSecret.isSet)
    // Saved live intent is not a new live mutation. Missing app prerequisites degrade only ingestion, never generic service.
    val degraded = missing && snapshot.settingsSource == "persisted"
    if mode == TwitchMode.Live && !degraded then selected.validateLive()
    val effective = if degraded then selected.copy(mode = TwitchMode.Disabled) else selected
    Selection(
      effective,
      StartupMonitoringStatus(
        snapshot.settingsSource,
        StartupMonitoringIntent(settings.mode, settings.channel.value),
        StartupMonitoringIntent(
          effective.mode.toString.toLowerCase(Locale.ROOT),
          if degraded || mode == TwitchMode.Disabled then None
          else Some(if effective.channel.isEmpty && mode == TwitchMode.Simulated then "demo" else effective.channel)
        ),
        if degraded then "failed" else if mode == TwitchMode.Live then "applying" else "ready",
        Option.when(degraded)("configuration_missing")
      )
    )
