package twitchscreen.relay.device

import java.net.{InetAddress, ServerSocket, SocketException}
import java.nio.file.{Files, Path}
import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import java.util.concurrent.atomic.AtomicLong
import com.typesafe.config.ConfigFactory
import pureconfig.ConfigSource
import twitchscreen.relay.config.Config
import twitchscreen.relay.twitch.{BotFilter, RuntimeRecovery, RuntimeSource, TwitchOAuthOwner}
import java.util.concurrent.TimeUnit
import ox.*
import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.protocol.*
import twitchscreen.relay.settings.*

/** Actual native application reconnect gate; owns its loopback listener and child only. */
object NativeDeskReconnect:
  def main(args: Array[String]): Unit =
    require(args.length == 2 || args.length == 3, "native firmware binary, private evidence directory and optional pressure/reset required")
    require(args.length == 2 || Set("pressure", "reset", "automatic").contains(args(2)), "unknown pairing mode")
    val pressure = args.length == 3 && args(2) == "pressure"
    val automatic = args.length == 3 && args(2) == "automatic"
    val reset = args.length == 3 && (args(2) == "reset" || automatic)
    val evidence = Path.of(args(1))
    Files.createDirectories(evidence)
    supervised:
      val wallCorrection = AtomicLong(0)
      val clock = new Clock:
        override def getZone: ZoneId = ZoneOffset.UTC
        override def withZone(zone: ZoneId): Clock = this
        override def instant(): Instant = Instant.now().plusMillis(wallCorrection.get())
      val bus = EventBus(clock, 64)
      val reasons = mutable.ArrayBuffer.empty[String]
      bus.consume("native-reconnect"):
        case message =>
          message.event match
            case RelayEvent.DeviceDisconnected(_, _, reason) => reasons.synchronized { reasons += reason; () }
            case _                                           => ()
      val config = TestRelay.config.copy(outboundQueueCapacity = TestRelay.config.replayBufferSize + 18)
      val hub = DeviceHub.start(config, twitchscreen.relay.config.ChatNotifications.Show, clock, bus)
      hub.installSettings(SettingsSnapshot(RuntimeSettings.firstRun("disabled", "synthetic"), 1L, "startup"))
      if automatic then
        val defaults = ConfigSource.fromConfig(ConfigFactory.load()).loadOrThrow[Config]
        val oauth = TwitchOAuthOwner.start(defaults.twitch, SourceIngestion.applicationDiagnostics(bus, hub), clock)
        val source =
          RuntimeSource.start(defaults.twitch.copy(channel = "synthetic"), bus, hub, defaults.stats, BotFilter(Set.empty), clock, oauth)
        source.startIngestion()
        RuntimeRecovery(hub, source, ClockDiscontinuity(() => clock.millis())).start()
      val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress)
      val port = listener.getLocalPort
      val command = ProcessBuilder(args(0))
      val env = command.environment()
      env.clear()
      env.put("PATH", System.getenv("PATH"))
      env.put("FIRMWARE_PEER_PORT", port.toString)
      env.put("FIRMWARE_PEER_SECONDS", if pressure then "24" else if reset then "14" else "10")
      env.put("FIRMWARE_PEER_PROFILE_READY", "1")
      env.put("FIRMWARE_PEER_REQUIRE_DESK", "1")
      if !reset then env.put("FIRMWARE_PEER_RECONNECT_SECONDS", "4").discard
      command.redirectError(evidence.resolve("native-stderr.log").toFile)
      val child = command.start()
      val output = Files.newBufferedWriter(evidence.resolve("native-observations.jsonl"))
      val watchdog = fork:
        if !child.waitFor(if pressure then 35 else 20, TimeUnit.SECONDS) then child.destroyForcibly().discard
        listener.close()
      val accepting = fork:
        try
          while !listener.isClosed do
            val socket = listener.accept()
            forkDiscard(DeviceSession.run(socket, config, hub, clock, deskEnabled = true))
        catch case _: SocketException if listener.isClosed => ()
      try
        val reader = child.inputReader()
        var line = reader.readLine()
        var readyCount = 0
        var resetObserved = false
        while line != null do
          output.write(line)
          output.newLine()
          output.flush()
          if reset && !resetObserved && line.contains("\"event\":\"device_report_written\"") && line.contains("\"phase\":2") then
            val before = hub.publicationSnapshot
            val replacement = if before.relayEpoch == 42L then 43L else 42L
            if automatic then
              wallCorrection.set(10000L)
              timeout(2.seconds):
                repeatUntil:
                  hub.relayClock.relayEpoch != before.relayEpoch || { sleep(10.millis); false }
            else require(hub.resetEpoch(before.relayEpoch, replacement))
            val after = hub.publicationSnapshot
            require(after.settings == before.settings && after.control.map(_.display) == before.control.map(_.display))
            require(after.latestSeq == SeqNo.Zero && after.retained.isEmpty && after.stats == StreamStats.Unknown)
            require(after.cpu.get.status == "missing" && after.weather.get.status == "missing")
            Files
              .writeString(
                evidence.resolve("reset-state.json"),
                s"""{"oldEpoch":"${f"${before.relayEpoch}%016x"}","newEpoch":"${f"${after.relayEpoch}%016x"}","intentPreserved":true,"latestSeq":0,"replayEmpty":true,"cpuMissing":true,"weatherMissing":true,"automaticDetectorObserved":$automatic,"testedLayer":"${
                    if automatic then "scheduled application detector/source owner plus internal-gated TCP/application"
                    else "explicit hub reset plus actual internal-gated TCP/application; not automatic detector"
                  }"}\n"""
              )
              .discard
            resetObserved = true
          if pressure && line.contains("\"event\":\"reconnect_requested\"") then
            (0 until 2).foreach: index =>
              require(
                hub
                  .publish(
                    EventRequest.card(NotificationRequest(NotificationKind.Info, s"Replay prefix $index", "Synthetic only", 100.millis))
                  )
                  .isRight
              )
          if line.contains("\"event\":\"desk_ready\"") then
            readyCount += 1
            if reset && readyCount == 1 then
              hub.installSettings(
                SettingsSnapshot(
                  RuntimeSettings.firstRun("disabled", "synthetic").copy(display = DisplaySettings(false, "cpu")),
                  2L,
                  "test"
                )
              )
              require(hub.publishCpu(Cpu_IN("native", "Synthetic host", 42)).isRight)
              require(hub.acceptWeather(Weather_IN("native", "Synthetic desk", 21, "clear")).isRight)
              (0 until 2).foreach: index =>
                require(
                  hub
                    .publish(
                      EventRequest.card(
                        NotificationRequest(
                          NotificationKind.Info,
                          s"Before reset $index",
                          "Synthetic only",
                          if index == 0 then 3.seconds else 100.millis
                        )
                      )
                    )
                    .isRight
                )
            else if reset && readyCount == 2 then
              require(
                hub
                  .publish(EventRequest.card(NotificationRequest(NotificationKind.Info, "After epoch reset", "Synthetic only", 100.millis)))
                  .isRight
              )
            else if pressure && readyCount == 1 then
              (0 until 9).foreach: index =>
                require(
                  hub
                    .publish(
                      EventRequest.card(
                        NotificationRequest(
                          NotificationKind.Info,
                          s"Retained $index",
                          "Synthetic only",
                          if index == 0 then 8.seconds else 100.millis
                        )
                      )
                    )
                    .isRight
                )
            else if pressure && readyCount == 2 then
              hub.installSettings(
                SettingsSnapshot(
                  RuntimeSettings.firstRun("disabled", "synthetic").copy(display = DisplaySettings(false, "cpu")),
                  2L,
                  "test"
                )
              )
              require(hub.publishCpu(Cpu_IN("native", "Synthetic host", 42, Some(17), freshnessMs = 1000)).isRight)
              require(hub.acceptWeather(Weather_IN("native", "Synthetic desk", 21, "clear", freshnessMs = 1000)).isRight)
              (0 until 2).foreach: index =>
                require(
                  hub
                    .publish(
                      EventRequest.card(NotificationRequest(NotificationKind.Info, s"After resume $index", "Synthetic only", 100.millis))
                    )
                    .isRight
                )
            else if readyCount <= 2 then
              require(
                hub
                  .publish(
                    EventRequest.card(NotificationRequest(NotificationKind.Info, s"Reconnect $readyCount", "Synthetic only", 1.second))
                  )
                  .isRight
              )
          line = reader.readLine()
        require(child.waitFor(5, TimeUnit.SECONDS) && child.exitValue() == 0)
        watchdog.join()
        accepting.join()
        val captured = reasons.synchronized(reasons.toVector)
        Files.writeString(evidence.resolve("disconnect-reasons.txt"), captured.mkString("\n") + "\n").discard
        require(readyCount == 2, s"expected exactly two ready sessions, got $readyCount")
        require(!reset || resetObserved, "explicit hub reset was not exercised")
        require(!captured.exists(_.contains("protocol violation")), s"invalid reconnect: $captured")
        val check = new java.net.Socket()
        var closed = false
        try check.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress, port), 500)
        catch case _: java.io.IOException => closed = true
        finally check.close()
        require(closed, "owned listener still reachable")
        println("Actual capable reconnect completed two ready sessions and post-reconnect delivery; child reaped and listener closed.")
      finally
        output.close()
        listener.close()
        if child.isAlive then
          child.destroyForcibly()
          require(child.waitFor(5, TimeUnit.SECONDS))
