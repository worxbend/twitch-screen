package twitchscreen.relay

import java.time.Clock
import ox.logback.InheritableMDC
import ox.otel.context.PropagatingVirtualThreadFactory
import ox.{Ox, OxApp, discard, tap}
import twitchscreen.relay.config.Config

/** The relay: a bridge between the Twitch API and the round-display firmware.
  *
  * `OxApp` owns the application scope. Everything the relay runs — the TCP listener and its per-device sessions, the Twitch client, the
  * event-bus subscribers, the HTTP server — lives inside it. ApplicationLifetime drains device shutdown messages before cancelling workers.
  */
object Main extends OxApp.Simple:
  // Must run during class initialisation, before anything writes to the MDC.
  InheritableMDC.init

  /** Without this, OpenTelemetry context does not follow Ox's forks and spans from background work are orphaned. */
  override protected def settings: OxApp.Settings =
    OxApp.Settings.Default.copy(threadFactory = Some(PropagatingVirtualThreadFactory()))

  override def run(using Ox): Unit = ApplicationLifetime.run:
    val clock = Clock.systemUTC()
    val startedAt = clock.instant()
    val config = Config.read.tap(Config.log)
    val dependencies = Dependencies.create(config, clock, startedAt)
    dependencies.serve().discard
    () => dependencies.hub.shutdown().discard
