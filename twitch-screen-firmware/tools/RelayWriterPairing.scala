package twitchscreen.relay.device

import java.io.{IOException, OutputStream}
import java.net.{InetAddress, ServerSocket, Socket}
import java.nio.file.{Files, Path}
import java.time.Clock
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicLong
import ox.*
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.ChatNotifications
import twitchscreen.relay.protocol.*
import twitchscreen.relay.settings.*

/** Firmware-owned integration main, compiled only into a private immutable relay snapshot. */
object RelayWriterPairing:
  def main(args: Array[String]): Unit =
    require(args.length == 2)
    val evidence = Path.of(args(1))
    Files.createDirectories(evidence)
    kernelDeadline(evidence)
    continuousControl(args(0), evidence)

  private def kernelDeadline(evidence: Path): Unit = supervised:
    val clock = Clock.systemUTC()
    val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 64))
    val longestWriteNs = AtomicLong(0)
    val failedWrites = AtomicLong(0)
    class ObservedSocket extends Socket:
      override def getOutputStream: OutputStream =
        val underlying = super.getOutputStream
        new OutputStream:
          override def write(value: Int): Unit = underlying.write(value)
          override def write(bytes: Array[Byte], offset: Int, length: Int): Unit =
            val start = System.nanoTime()
            try underlying.write(bytes, offset, length)
            catch
              case error: IOException =>
                failedWrites.incrementAndGet().discard
                throw error
            finally
              val elapsed = System.nanoTime() - start
              longestWriteNs.accumulateAndGet(elapsed, (a, b) => math.max(a, b)).discard
          override def flush(): Unit = underlying.flush()
    class Listener extends ServerSocket(0, 1, InetAddress.getLoopbackAddress):
      def accepted(): ObservedSocket =
        val socket = ObservedSocket()
        implAccept(socket)
        socket.setSendBufferSize(1024)
        socket
    val listener = useCloseableInScope(Listener())
    val accepted = AtomicLong(0)
    val ended = AtomicLong(0)
    val session = fork:
      val socket = listener.accepted()
      accepted.set(socket.getSendBufferSize.toLong)
      DeviceSession.run(socket, TestRelay.config.copy(idleTimeout = 30.seconds, pingInterval = 20.seconds), hub, clock, deskEnabled = true)
      ended.set(System.nanoTime())
    val peer = useCloseableInScope(Socket())
    peer.setReceiveBufferSize(1024)
    peer.setSoTimeout(5000)
    peer.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress, listener.getLocalPort))
    val hello: DeviceMessage.Hello = DeviceMessage.Hello(DeviceId("kernel-writer-fixture").toOption.get, SeqNo.Zero,
      TestDevice.FullCaps | Capabilities.fromWire(DeskGreetingCodec.CapabilityBit),
      FrameSize.fromWire(Tsb3.MinRxMax), FirmwareVersion.fromWire("synthetic"))
    peer.getOutputStream.write(DeskGreetingCodec.hello(hello, DeskGreetingCodec.HelloTail(0, 0, 55, 1000)))
    val reader = FrameReader(peer.getInputStream)
    val frames = (0 until 6).map(_ => reader.read().toOption.get).toList
    require(frames.map(_.header.typeCode.value) == List(0x20, 0x27, 0x28, 0x29, 0x22, 0x2a))
    val welcome = DeskGreetingCodec.decodeWelcome(frames.head, 1000).toOption.flatten.get
    val display = DeskControlCodec.decodeDisplay(frames(1)).toOption.get
    val sync = DeskControlCodec.decodeSync(frames(5)).toOption.get
    val report = DeskReportCodec.Report(sync.identity, 55, 1, 1001, welcome.sampledRelayMs, 8, 0, 0,
      display.revision, display.sourceGeneration, display.twitchEpisode, sync.weatherRevision, sync.cpuRevision,
      DeskReportCodec.Base.ConnectedOrbit, DeskReportCodec.Reason.GenericSelected, DeskReportCodec.Phase.None,
      0, 0, 0, 0, None, Vector.empty)
    peer.getOutputStream.write(DeskReportCodec.encode(report))
    // TIME replies prove the initial report/reader is installed before the test
    // stops reading. No injected delay or fabricated write failure is used.
    peer.getOutputStream.write(DeskTimeCodec.request(1234))
    require(DeskTimeCodec.decodeResponse(reader.read().toOption.get).toOption.get.echoedProbeDeviceMs == 1234)
    val start = System.nanoTime()
    val sender = fork:
      try
        val ping = Tsb3Encoder.toRelay(DeviceMessage.Ping(Token.fromWire(99)))
        while ended.get() == 0 do peer.getOutputStream.write(ping)
      catch case _: IOException => ()
    try
      require(timeoutOption(8.seconds)(session.join()).isDefined, "kernel-blocked capable writer did not close")
      val longestMs = longestWriteNs.get() / 1000000
      require(failedWrites.get() > 0 && longestMs >= 2800 && longestMs <= 4500,
        s"no real three-second kernel-blocked write: $longestMs ms, ${failedWrites.get()} failures")
      require(hub.links.isEmpty)
      Files.writeString(evidence.resolve("kernel-writer.json"),
        s"{\"passed\":true,\"actualDeviceSession\":true,\"actualKernelWrite\":true,\"initialReportAccepted\":true,\"sendBufferBytes\":${accepted.get()},\"receiveBufferBytes\":${peer.getReceiveBufferSize},\"longestWriteMs\":$longestMs,\"failedWrites\":${failedWrites.get()},\"sessionElapsedMs\":${(ended.get()-start)/1000000},\"ownedSessionJoined\":true,\"injectedWriteFailure\":false,\"physicalVerified\":false}\n").discard
    finally peer.close()
    require(timeoutOption(2.seconds)(sender.join()).isDefined)
    val port = listener.getLocalPort
    listener.close()
    require(listenerClosed(port))

  private def continuousControl(binary: String, evidence: Path): Unit = supervised:
    val clock = Clock.systemUTC()
    val config = TestRelay.config.copy(outboundQueueCapacity = TestRelay.config.replayBufferSize + 18)
    val hub = DeviceHub.start(config, ChatNotifications.Show, clock, EventBus(clock, 64))
    val initial = RuntimeSettings.firstRun("disabled", "synthetic").copy(display = DisplaySettings(true, "cpu"))
    hub.installSettings(SettingsSnapshot(initial, 1L, "test"))
    val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress)
    val port = listener.getLocalPort
    val command = ProcessBuilder(binary)
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
    val session = fork(DeviceSession.run(listener.accept(), config, hub, clock, deskEnabled = true))
    val watchdog = fork:
      if !child.waitFor(28, TimeUnit.SECONDS) then
        child.destroyForcibly().discard
        listener.close()
    val output = Files.newBufferedWriter(evidence.resolve("native-observations.jsonl"))
    val remoteObservations = Files.newBufferedWriter(evidence.resolve("remote-report-observations.jsonl"))
    var stage = 0
    var owner = Option.empty[DeskConnection]
    val controlsDone = CountDownLatch(1)
    try
      val reader = child.inputReader()
      var line = reader.readLine()
      while line != null do
        output.write(line)
        output.newLine()
        output.flush()
        if line.contains("\"event\":\"device_report_written\"") then
          val accepted = owner.flatMap(_.delivery.report).map(value =>
            "\"" + java.util.HexFormat.of().formatHex(DeskReportCodec.encode(value)) + "\"").getOrElse("null")
          remoteObservations.write(s"{\"sampledRelayMs\":${hub.publicationSnapshot.sampledRelayMs},\"reportHex\":$accepted}\n")
          remoteObservations.flush()
        if stage == 0 && line.contains("\"event\":\"desk_ready\"") then
          require(hub.links.size == 1)
          owner = hub.desk(hub.links.head.connection)
          require(hub.publish(EventRequest(NotificationKind.Info, "Active external", "Synthetic", 10.seconds)).isRight)
          stage = 1
        else if stage == 1 && line.contains("\"event\":\"device_report_written\"") && line.contains("\"activeSeq\":1,") then
          require(hub.publish(EventRequest(NotificationKind.Follow, "Waiting Twitch", "Synthetic", 100.millis,
            origin = DeskMessageCodec.Origin.Twitch)).isRight)
          require(hub.publish(EventRequest(NotificationKind.Follow, "Waiting external Twitch-looking", "Synthetic", 100.millis)).isRight)
          stage = 2
        else if stage == 2 && line.contains("\"event\":\"device_report_written\"") && line.contains("\"pending\":2,") then
          forkDiscard:
            val schedule = Files.newBufferedWriter(evidence.resolve("control-schedule.jsonl"))
            try
              (2L to 121L).foreach: revision =>
                val enabled = revision % 2 == 1
                val mode = if revision % 4 < 2 then "disabled" else "simulated"
                val value = initial.copy(display = DisplaySettings(enabled, "cpu"), monitoring = initial.monitoring.copy(mode = mode))
                val before = hub.publicationSnapshot.sampledRelayMs
                hub.installSettings(SettingsSnapshot(value, revision, "test"))
                val committed = hub.publicationSnapshot.control.get
                schedule.write(s"{\"revision\":${committed.revision},\"sourceGeneration\":${committed.sourceGeneration},\"twitchEpisode\":${committed.twitchEpisode},\"enabled\":$enabled,\"acceptedRelayMs\":$before}\n")
                schedule.flush()
                sleep(50.millis)
              hub.installSettings(SettingsSnapshot(initial.copy(display = DisplaySettings(false, "cpu")), 122L, "test"))
              val finalControl = hub.publicationSnapshot.control.get
              schedule.write(s"{\"revision\":${finalControl.revision},\"sourceGeneration\":${finalControl.sourceGeneration},\"twitchEpisode\":${finalControl.twitchEpisode},\"enabled\":false,\"acceptedRelayMs\":${hub.publicationSnapshot.sampledRelayMs}}\n")
            finally
              schedule.close()
              controlsDone.countDown()
          stage = 3
        line = reader.readLine()
      require(child.waitFor(5, TimeUnit.SECONDS) && child.exitValue() == 0 && stage == 3)
      require(controlsDone.await(2, TimeUnit.SECONDS))
      val finalReport = owner.flatMap(_.delivery.report).getOrElse(throw IllegalStateException("no accepted final report"))
      val selected = hub.publicationSnapshot.control.get
      remoteObservations.write(s"{\"sampledRelayMs\":${hub.publicationSnapshot.sampledRelayMs},\"reportHex\":\"${java.util.HexFormat.of().formatHex(DeskReportCodec.encode(finalReport))}\"}\n")
      remoteObservations.flush()
      require(finalReport.displayRevision == selected.revision && finalReport.sourceGeneration == selected.sourceGeneration &&
        finalReport.twitchEpisode == selected.twitchEpisode)
      require(finalReport.pending.isEmpty && finalReport.active.isEmpty && finalReport.grantTotal == 11L)
      require(finalReport.suppressedTotal == 1L && finalReport.expiredTotal == 0L && finalReport.refusedTotal == 0L)
      Files.writeString(evidence.resolve("remote-final-report.hex"), java.util.HexFormat.of().formatHex(DeskReportCodec.encode(finalReport))+"\n").discard
      session.join()
      watchdog.join()
    finally
      output.close()
      remoteObservations.close()
      listener.close()
      if child.isAlive then
        child.destroyForcibly()
        require(child.waitFor(5, TimeUnit.SECONDS))
    require(listenerClosed(port) && !child.isAlive)
    Files.writeString(evidence.resolve("owner-result.json"),
      s"{\"passed\":true,\"stage\":$stage,\"controlWrites\":121,\"minimumOutboundCapacity\":${config.outboundQueueCapacity},\"ownedChildReaped\":true,\"ownedListenerClosed\":true,\"port\":$port,\"internalProfileOptIn\":true}\n").discard

  private def listenerClosed(port: Int): Boolean =
    val probe = Socket()
    try
      probe.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress, port), 500)
      false
    catch case _: IOException => true
    finally probe.close()
