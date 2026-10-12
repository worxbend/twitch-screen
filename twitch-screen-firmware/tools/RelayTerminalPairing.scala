package twitchscreen.relay.device

import java.io.IOException
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

/** Explicit internal-owner test copied into a private relay snapshot, never production sources. */
object RelayTerminalPairing:
  def main(args: Array[String]): Unit =
    require(args.length == 2)
    val evidence = Path.of(args(1))
    Files.createDirectories(evidence)
    supervised:
      val clock = Clock.systemUTC()
      val config = TestRelay.config.copy(outboundQueueCapacity = TestRelay.config.replayBufferSize + 18)
      val hub = DeviceHub.start(config, ChatNotifications.Show, clock, EventBus(clock, 64))
      val initial = RuntimeSettings.firstRun("disabled", "synthetic").copy(display = DisplaySettings(true, "cpu"))
      hub.installSettings(SettingsSnapshot(initial, 1L, "test"))
      val listener = ServerSocket(0, 8, InetAddress.getLoopbackAddress)
      val port = listener.getLocalPort
      val command = ProcessBuilder(args(0))
      val env = command.environment()
      env.clear()
      env.put("PATH", System.getenv("PATH"))
      env.put("FIRMWARE_PEER_PORT", port.toString)
      env.put("FIRMWARE_PEER_SECONDS", "18")
      env.put("FIRMWARE_PEER_PROFILE_READY", "1")
      env.put("FIRMWARE_PEER_REQUIRE_DESK", "1")
      env.put("FIRMWARE_PEER_MAX_WRITE_BYTES", "13")
      env.put("FIRMWARE_PEER_MAX_READ_BYTES", "7")
      command.redirectError(evidence.resolve("native-stderr.log").toFile)
      val child = command.start()
      forkDiscard:
        try
          forever:
            val socket = listener.accept()
            forkDiscard(DeviceSession.run(socket, config, hub, clock, deskEnabled = true))
        catch case _: IOException => ()
      forkDiscard:
        if !child.waitFor(28, TimeUnit.SECONDS) then child.destroyForcibly().discard
        listener.close()
      val output = Files.newBufferedWriter(evidence.resolve("native-observations.jsonl"))
      var stage = 0
      var resumedOwner = Option.empty[DeskConnection]
      try
        val reader = child.inputReader()
        var line = reader.readLine()
        while line != null do
          output.write(line)
          output.newLine()
          output.flush()
          if stage == 0 && line.contains("\"event\":\"desk_ready\"") then
            require(hub.publish(EventRequest(NotificationKind.Info, "Active external", "Synthetic", 5.seconds)).isRight)
            stage = 1
          else if stage == 1 && line.contains("\"event\":\"device_report_written\"") &&
            line.contains("\"activeSeq\":1,") && line.contains("\"phase\":1,") then
            require(hub.publish(EventRequest(NotificationKind.Info, "Waiting expiry", "Synthetic", 100.millis,
              expiresInMs = 1500)).isRight)
            require(hub.publish(EventRequest(NotificationKind.Info, "Waiting Twitch", "Synthetic", 100.millis,
              origin = DeskMessageCodec.Origin.Twitch)).isRight)
            (0 until 6).foreach: index =>
              require(hub.publish(EventRequest.card(NotificationRequest(NotificationKind.Info,
                s"Retained external $index", "Synthetic", 100.millis))).isRight)
            stage = 2
          else if stage == 2 && line.contains("\"event\":\"device_report_written\"") && line.contains("\"pending\":8,") then
            hub.installSettings(SettingsSnapshot(initial.copy(display = DisplaySettings(false, "cpu")), 2L, "test"))
            hub.installSettings(SettingsSnapshot(initial, 3L, "test"))
            hub.installSettings(SettingsSnapshot(initial.copy(monitoring = initial.monitoring.copy(mode = "simulated")), 4L, "test"))
            require(hub.publishCpu(Cpu_IN("fixture", "Synthetic host", 42, freshnessMs = 1000)).isRight)
            require(hub.acceptWeather(Weather_IN("fixture", "Synthetic desk", 21, "clear", freshnessMs = 1000)).isRight)
            // This current-epoch history entry is ineligible at publication. A barrier, not EVENT ACK, covers it.
            hub.installSettings(SettingsSnapshot(initial.copy(display = DisplaySettings(false, "cpu")), 5L, "test"))
            require(hub.publish(EventRequest(NotificationKind.Warning, "Ineligible Twitch", "Synthetic", 100.millis,
              origin = DeskMessageCodec.Origin.Twitch)).exists(_.seq.value == 10L))
            hub.installSettings(SettingsSnapshot(initial.copy(display = DisplaySettings(false, "cpu")), 6L, "test"))
            stage = 3
          else if stage == 3 && line.contains("\"event\":\"device_report_written\"") &&
            line.contains("\"expiredTotal\":1,") && line.contains("\"suppressedTotal\":1,") &&
            line.contains("\"processedSeq\":10,") && line.contains("\"enqueuedSeq\":9,") then
            require(hub.links.size == 1)
            require(hub.disconnect(hub.links.head.connection).isDefined)
            stage = 4
          else if stage == 4 && line.contains("\"event\":\"desk_ready\"") then
            require(hub.links.size == 1)
            resumedOwner = hub.desk(hub.links.head.connection)
            require(resumedOwner.isDefined)
            require(hub.publish(EventRequest.card(NotificationRequest(NotificationKind.Follow,
              "New external Twitch-looking", "Synthetic", 100.millis))).exists(_.seq.value == 11L))
            stage = 5
          line = reader.readLine()
        require(child.waitFor(5, TimeUnit.SECONDS) && child.exitValue() == 0)
        require(stage == 5, s"terminal pairing stopped at stage $stage")
        val accepted = resumedOwner.flatMap(_.delivery.report).getOrElse(throw IllegalStateException("no remotely accepted final report"))
        require(accepted.pending.isEmpty && accepted.active.isEmpty && accepted.phase == DeskReportCodec.Phase.None)
        require(accepted.enqueuedSeq == 11L && accepted.processedSeq == 11L && accepted.grantTotal == 9L)
        require(accepted.expiredTotal == 1L && accepted.suppressedTotal == 1L && accepted.refusedTotal == 0L)
        require(accepted.base == DeskReportCodec.Base.ConnectedOrbit && accepted.reason == DeskReportCodec.Reason.DataStale)
        Files.writeString(evidence.resolve("remote-final-report.hex"),
          java.util.HexFormat.of().formatHex(DeskReportCodec.encode(accepted)) + "\n").discard
      finally
        output.close()
        listener.close()
        if child.isAlive then
          child.destroyForcibly()
          require(child.waitFor(5, TimeUnit.SECONDS))
      val probe = new java.net.Socket()
      var closed = false
      try probe.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress, port), 500)
      catch case _: IOException => closed = true
      finally probe.close()
      require(closed && !child.isAlive)
      Files.writeString(evidence.resolve("owner-result.json"),
        s"{\"stage\":$stage,\"minimumOutboundCapacity\":${config.outboundQueueCapacity},\"ownedChildReaped\":true,\"ownedListenerClosed\":true,\"port\":$port,\"internalProfileOptIn\":true}\n").discard
      println("Actual owner terminal/full-queue/partial-read/reconnect execution finished; independently verify observations.")
