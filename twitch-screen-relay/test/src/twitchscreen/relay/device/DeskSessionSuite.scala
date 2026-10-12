package twitchscreen.relay.device

import java.net.{InetAddress, ServerSocket}
import java.time.Clock
import ox.*
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.ChatNotifications
import twitchscreen.relay.protocol.*
import twitchscreen.relay.settings.*

/** Tests the installed production session, not an alternate codec-only server. */
class DeskSessionSuite extends munit.FunSuite:
  test("actual DeviceSession pairs captured greeting, accepts readiness and delivers publication and TIME"):
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 64))
      hub.installSettings(SettingsSnapshot(RuntimeSettings.firstRun("disabled", "synthetic"), 1L, "startup"))
      val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress)
      val (_, observer) = hub.observeHub(InetAddress.getLoopbackAddress).toOption.get
      try
        val session = fork:
          DeviceSession.run(listener.accept(), TestRelay.config, hub, clock, deskEnabled = true)
        val peer = TestDevice(listener.getLocalPort)
        try
          val hello: DeviceMessage.Hello = DeviceMessage.Hello(
            DeviceId("desk-session").toOption.get,
            SeqNo.Zero,
            TestDevice.FullCaps | Capabilities.fromWire(DeskGreetingCodec.CapabilityBit),
            FrameSize.fromWire(Tsb3.MinRxMax),
            FirmwareVersion.fromWire("synthetic")
          )
          peer.sendBytes(DeskGreetingCodec.hello(hello, DeskGreetingCodec.HelloTail(0, 0, 55, 1000)))
          val frames = peer.receiveMany(6)
          assertEquals(frames.map(_.header.typeCode.value), List(0x20, 0x27, 0x28, 0x29, 0x22, 0x2a))
          val welcome = DeskGreetingCodec.decodeWelcome(frames.head, 1000).toOption.flatten.get
          val display = DeskControlCodec.decodeDisplay(frames(1)).toOption.get
          val weather = DeskSnapshotCodec.decodeWeather(frames(2)).toOption.get
          val cpu = DeskSnapshotCodec.decodeCpu(frames(3)).toOption.get
          val sync = DeskControlCodec.decodeSync(frames(5)).toOption.get
          assertEquals(display.identity, sync.identity)
          assertEquals(welcome.relayEpoch, sync.identity.relayEpoch)
          val report = DeskReportCodec.Report(
            sync.identity,
            55,
            1,
            1001,
            welcome.sampledRelayMs,
            8,
            0,
            0,
            display.revision,
            display.sourceGeneration,
            display.twitchEpisode,
            weather.revision,
            cpu.revision,
            DeskReportCodec.Base.ConnectedOrbit,
            DeskReportCodec.Reason.GenericSelected,
            DeskReportCodec.Phase.None,
            0,
            0,
            0,
            0,
            None,
            Vector.empty
          )
          peer.sendBytes(DeskReportCodec.encode(report))
          val observed = timeoutOption(3.seconds):
            var captured = observer.take().get
            while !captured.devices.exists(_.desk.exists(_.report.contains(report))) do captured = observer.take().get
            captured
          assert(observed.isDefined, "accepted report must refresh without publication or optional bus")
          val observedDevice = observed.get.devices.head
          assertEquals(observedDevice.firmwareVersion, "synthetic")
          assertEquals(observedDevice.caps, hello.caps.unsigned)
          assertEquals(observedDevice.desk.get.report, Some(report))
          assertEquals(observed.get.publication.latestSeq.value, 0L)
          val receipt = hub
            .publish(
              EventRequest.card(
                NotificationRequest(NotificationKind.Info, "paired", "session", scala.concurrent.duration.DurationInt(8).seconds)
              )
            )
            .toOption
            .get
          assertEquals(hub.retainedPublications.last.origin, DeskMessageCodec.Origin.External)
          assert(hub.retainedPublications.last.capableEligible)
          val event = peer.receive().get
          assertEquals(event.header.typeCode.value, 0x2b)
          assertEquals(DeskMessageCodec.decodeEvent(event).toOption.get.seq, receipt.seq.value)
          peer.sendBytes(DeskTimeCodec.request(1234))
          val time = DeskTimeCodec.decodeResponse(peer.receive().get).toOption.get
          assertEquals(time.echoedProbeDeviceMs, 1234L)
          assertEquals(time.connectionId, welcome.connectionId)
          val snapshot = hub.publicationSnapshot
          hub.installSettings(
            SettingsSnapshot(RuntimeSettings.firstRun("disabled", "synthetic").copy(display = DisplaySettings(false, "cpu")), 2L, "test")
          )
          val changed = DeskControlCodec.decodeDisplay(peer.receive().get).toOption.get
          assert(changed.revision > display.revision)
          assertEquals(changed.preferred, DeskControlCodec.Preferred.Cpu)
          assertEquals(hub.publicationSnapshot.statsRevision, snapshot.statsRevision)
        finally peer.close()
        session.join()
        assertEquals(hub.latestObservation.get.devices.count(_.connected), 0)
        val historical = hub.latestObservation.get.devices.head
        assertEquals(historical.link.device.value, "desk-session")
        assertEquals(historical.desk.get.status, "historical")
        assertEquals(historical.desk.get.report.map(_.deviceBootId), Some(55L))
      finally
        observer.close()
        listener.close()
