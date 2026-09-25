package twitchscreen.relay

import java.time.Clock
import ox.logback.InheritableMDC
import ox.otel.context.PropagatingVirtualThreadFactory
import ox.{Ox, OxApp, discard, never, tap}
import twitchscreen.relay.config.Config

/** The relay: a bridge between the Twitch API and the round-display firmware.
  *
  * `OxApp` owns the application scope. Everything the relay runs — the TCP listener and its per-device sessions, the Twitch client, the
  * event-bus subscribers, the HTTP server — lives inside it, so a SIGTERM unwinds the whole thing without a single lifecycle flag.
  */
object Main extends OxApp.Simple:
  // Must run during class initialisation, before anything writes to the MDC.
  InheritableMDC.init

  /** Without this, OpenTelemetry context does not follow Ox's forks and spans from background work are orphaned. */
  override protected def settings: OxApp.Settings =
    OxApp.Settings.Default.copy(threadFactory = Some(PropagatingVirtualThreadFactory()))

  override def run(using Ox): Unit =
    val config = Config.read.tap(Config.log)
    Dependencies.create(config, Clock.systemUTC()).httpApi.start().discard
    // Nothing else to do on this thread; the scope holds every fork open until the process is asked to stop.
    never
