package twitchscreen.relay.device

import java.time.Clock
import ox.*
import ox.channels.Channel
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.ApplicationLifetime
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.ChatNotifications
import twitchscreen.relay.protocol.*

class ApplicationShutdownSuite extends munit.FunSuite:
  test("a cleanup that never completes cannot hold application cancellation indefinitely"):
    supervised:
      val ready = Channel.buffered[Unit](1)
      val application = forkCancellable:
        ApplicationLifetime.run:
          ready.send(())
          () => never
      ready.receive()
      application.cancelNow()
      assert(timeoutOption(5.seconds)(application.joinEither()).exists(_.isLeft))

  test("application cancellation drains SERVER_SHUTDOWN BYE before interrupting the socket scope"):
    supervised:
      val ready = Channel.buffered[Int](1)
      val application = forkCancellable:
        ApplicationLifetime.run:
          val clock = Clock.systemUTC()
          val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 32))
          val listener = DeviceLinkServer.startOnPort(TestRelay.config, hub, clock, 0)
          ready.send(listener.getLocalPort)
          () => hub.shutdown().discard
      val device = useCloseableInScope(TestDevice(ready.receive()))
      device.hello("shutdown-test", 0)
      device.receiveMany(2).discard
      application.cancelNow()
      assertEquals(device.receiveMessage().collect { case RelayMessage.Bye(code, _, _, _) => code }, Some(ByeCode.ServerShutdown))
      assertEquals(device.receive(), None)
      assert(timeoutOption(5.seconds)(application.joinEither()).exists(_.isLeft))
