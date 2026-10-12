package twitchscreen.relay.device

import java.net.{InetAddress, ServerSocket}
import java.nio.file.{Files, Path}
import java.time.Clock
import java.util.concurrent.TimeUnit
import ox.*
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.ChatNotifications
import twitchscreen.relay.protocol.*
import twitchscreen.relay.settings.*

/** Explicit integration entry point; owns only loopback sockets and its child. Production negotiation remains gated. */
object NativeDeskPairing:
  def main(args: Array[String]): Unit =
    require(args.length == 2, "native firmware binary and private evidence directory required")
    val binary = Path.of(args(0)).toAbsolutePath
    val evidence = Path.of(args(1)).toAbsolutePath
    require(Files.isExecutable(binary))
    Files.createDirectories(evidence)
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 64))
      hub.installSettings(SettingsSnapshot(RuntimeSettings.firstRun("disabled", "synthetic"), 1L, "startup"))
      val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress)
      val command = ProcessBuilder(binary.toString)
      val env = command.environment()
      env.clear()
      env.put("PATH", System.getenv("PATH"))
      env.put("FIRMWARE_PEER_PORT", listener.getLocalPort.toString)
      env.put("FIRMWARE_PEER_SECONDS", "10")
      env.put("FIRMWARE_PEER_PROFILE_READY", "1")
      env.put("FIRMWARE_PEER_REQUIRE_DESK", "1")
      command.redirectError(evidence.resolve("native-stderr.log").toFile)
      val child = command.start()
      val output = Files.newBufferedWriter(evidence.resolve("native-observations.jsonl"))
      val watchdog = fork:
        if !child.waitFor(20, TimeUnit.SECONDS) then
          child.destroyForcibly()
          listener.close()
      try
        val session = fork:
          DeviceSession.run(listener.accept(), TestRelay.config, hub, clock, deskEnabled = true)
        val reader = child.inputReader()
        var line = reader.readLine()
        var published = false
        while line != null do
          output.write(line)
          output.newLine()
          output.flush()
          if line.contains("\"event\":\"desk_ready\"") && !published then
            (0 until 2).foreach: index =>
              require(
                hub
                  .publish(
                    EventRequest.card(
                      NotificationRequest(
                        NotificationKind.Info,
                        s"Runtime DESK card $index",
                        "Synthetic firmware runtime pairing",
                        1.second
                      )
                    )
                  )
                  .isRight
              )
            published = true
          line = reader.readLine()
        require(child.waitFor(5, TimeUnit.SECONDS) && child.exitValue() == 0, "native peer did not pass")
        require(published, "native peer never reached DESK readiness")
        session.join()
        watchdog.join()
        println("Actual DeviceSession and native firmware application pairing completed; validate saved report conservation separately.")
      finally
        output.close()
        listener.close()
        if child.isAlive then
          child.destroyForcibly()
          val _ = child.waitFor(5, TimeUnit.SECONDS)
