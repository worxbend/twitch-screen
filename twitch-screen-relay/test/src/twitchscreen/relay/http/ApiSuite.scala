package twitchscreen.relay.http

import java.time.{Clock, Instant, ZoneOffset}
import ox.supervised
import scala.concurrent.duration.DurationInt
import sttp.client4.*
import sttp.model.StatusCode
import sttp.tapir.client.sttp4.SttpClientInterpreter
import sttp.tapir.server.stub4.TapirSyncStubInterpreter
import twitchscreen.relay.activity.{ActivityApi, ActivityLog}
import twitchscreen.relay.alerts.{AlertRule, AlertStore, AlertsApi}
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.{ChatNotifications, DeviceLinkConfig, Hostname, NotificationsConfig, Port}
import twitchscreen.relay.device.{DeviceApi, DeviceHub, Notification_IN, NotificationApi}
import twitchscreen.relay.health.{HealthApi, Health_OUT, HealthStatus}
import twitchscreen.relay.protocol.NotificationKind

/** The management API, exercised in process through Tapir's stub interpreter — no sockets, no ports. */
class ApiSuite extends munit.FunSuite:
  private val basePath = Some(uri"http://localhost:8080")
  private val clock = Clock.fixed(Instant.ofEpochSecond(1790309000L), ZoneOffset.UTC)

  private val deviceLinkConfig = DeviceLinkConfig(
    host = Hostname("127.0.0.1").toOption.get,
    port = Port(8099).toOption.get,
    protocolVersion = 2,
    acceptBacklog = 8,
    handshakeTimeout = 2.seconds,
    idleTimeout = 5.seconds,
    pingInterval = 4.seconds,
    outboundQueueCapacity = 8,
    replayBufferSize = 8,
    maxFrameLength = 256
  )

  private val notificationsConfig = NotificationsConfig(30.seconds, ChatNotifications.Hide)

  /** Builds the whole management API over a fresh hub and hands the test a backend plus the hub behind it. */
  private def withApi(body: (SyncBackend, DeviceHub, AlertStore) => Unit): Unit =
    supervised:
      val bus = EventBus(clock, queueCapacity = 32)
      val hub = DeviceHub.start(deviceLinkConfig, clock, bus)
      val alerts = AlertStore(10)
      val apis = List(
        HealthApi(),
        DeviceApi(hub),
        NotificationApi(hub, notificationsConfig),
        ActivityApi(ActivityLog.start(twitchscreen.relay.config.ActivityConfig(50), bus), clock),
        AlertsApi(alerts, AlertRule.from(alertsConfig, twitchscreen.relay.config.TwitchMode.Disabled), clock)
      )
      val backend = TapirSyncStubInterpreter().whenServerEndpointsRunLogic(apis.flatMap(_.endpoints)).backend()
      body(backend, hub, alerts)

  private val alertsConfig = twitchscreen.relay.config.AlertsConfig(
    evaluationInterval = 15.seconds,
    bufferSize = 10,
    noDevicesConnectedFor = Some(2.minutes),
    twitchDisconnectedFor = None,
    streamOfflineFor = None,
    errorRateThreshold = 10,
    errorRateWindow = 5.minutes
  )

  test("the liveness endpoint reports up"):
    withApi: (backend, _, _) =>
      val response = SttpClientInterpreter()
        .toRequestThrowDecodeFailures(HealthApi.healthEndpoint, basePath)
        .apply(())
        .send(backend)
      assertEquals(response.body, Right(Health_OUT(HealthStatus.Up)))

  test("publishing a notification returns it with its assigned sequence number"):
    withApi: (backend, _, _) =>
      val response = SttpClientInterpreter()
        .toRequestThrowDecodeFailures(NotificationApi.createEndpoint, basePath)
        .apply(Notification_IN(NotificationKind.Alert, "Rack A", "78C", None))
        .send(backend)
      assertEquals(response.body.map(_.seq.value), Right(1L))

  test("a notification with no title is refused"):
    withApi: (backend, _, _) =>
      val response = SttpClientInterpreter()
        .toRequestThrowDecodeFailures(NotificationApi.createEndpoint, basePath)
        .apply(Notification_IN(NotificationKind.Alert, "   ", "78C", None))
        .send(backend)
      assertEquals(response.code, StatusCode.BadRequest)

  test("a notification with a non-positive time to live is refused"):
    withApi: (backend, _, _) =>
      val response = SttpClientInterpreter()
        .toRequestThrowDecodeFailures(NotificationApi.createEndpoint, basePath)
        .apply(Notification_IN(NotificationKind.Alert, "Rack A", "78C", Some(0L)))
        .send(backend)
      assertEquals(response.code, StatusCode.BadRequest)

  test("an unknown notification type is rejected rather than quietly treated as info"):
    withApi: (backend, _, _) =>
      val response = basicRequest
        .post(uri"http://localhost:8080/api/v1/notifications")
        .contentType("application/json")
        .body("""{"type":"hypetrain","title":"t","body":"b"}""")
        .send(backend)
      assertEquals(response.code, StatusCode.BadRequest)

  test("published notifications are listed most recent first"):
    withApi: (backend, hub, _) =>
      List("one", "two", "three").foreach(title =>
        hub.publish(twitchscreen.relay.protocol.NotificationRequest(NotificationKind.Info, title, "", 1.second))
      )
      val response = SttpClientInterpreter()
        .toRequestThrowDecodeFailures(NotificationApi.listEndpoint, basePath)
        .apply(2)
        .send(backend)
      assertEquals(response.body.map(_.notifications.map(_.title)), Right(List("three", "two")))

  test("no devices are listed when none are attached"):
    withApi: (backend, _, _) =>
      val response = SttpClientInterpreter()
        .toRequestThrowDecodeFailures(DeviceApi.listEndpoint, basePath)
        .apply(())
        .send(backend)
      assertEquals(response.body.map(_.devices), Right(Nil))

  test("an empty collection is still rendered as an empty list, not left out of the body"):
    withApi: (backend, _, _) =>
      val response = basicRequest.get(uri"http://localhost:8080/api/v1/devices").send(backend)
      assertEquals(response.body, Right("""{"devices":[]}"""))

  // Connection ids are minted by the hub alone, so this one is addressed by path rather than by a constructed id.
  test("asking for a device that is not connected gives a 404"):
    withApi: (backend, _, _) =>
      val response = basicRequest.get(uri"http://localhost:8080/api/v1/devices/99").send(backend)
      assertEquals(response.code, StatusCode.NotFound)

  test("disconnecting a device that is not connected gives a 404"):
    withApi: (backend, _, _) =>
      val response = basicRequest.post(uri"http://localhost:8080/api/v1/devices/99:disconnect").send(backend)
      assertEquals(response.code, StatusCode.NotFound)

  test("acknowledging an alert that does not exist gives a 404"):
    withApi: (backend, _, _) =>
      val response = basicRequest.post(uri"http://localhost:8080/api/v1/alerts/99:acknowledge").send(backend)
      assertEquals(response.code, StatusCode.NotFound)

  test("acknowledging a raised alert succeeds"):
    withApi: (backend, _, alerts) =>
      val raised = alerts.raise(AlertRule.NoDevicesConnected(1.minute), "no device", clock.instant()).get
      val response = basicRequest.post(uri"http://localhost:8080/api/v1/alerts/${raised.id}:acknowledge").send(backend)
      assertEquals(response.code, StatusCode.Ok)

  test("the alert rules the relay was configured with are listed"):
    withApi: (backend, _, _) =>
      val response = SttpClientInterpreter()
        .toRequestThrowDecodeFailures(AlertsApi.rulesEndpoint, basePath)
        .apply(())
        .send(backend)
      assertEquals(response.body.map(_.rules.map(_.name)), Right(List("no-devices-connected", "failure-rate")))

  test("the activity report names when it was generated and how many entries it covers"):
    withApi: (backend, _, _) =>
      val response = SttpClientInterpreter()
        .toRequestThrowDecodeFailures(ActivityApi.exportEndpoint, basePath)
        .apply((10, None))
        .send(backend)
      assert(response.body.exists(_.contains("twitch-screen-relay activity report")), response.body.toString)

  test("a path that matches no endpoint answers with the same JSON error shape as everything else"):
    withApi: (backend, _, _) =>
      val response = basicRequest.get(uri"http://localhost:8080/api/v1/nonexistent").send(backend)
      assertEquals(response.code, StatusCode.NotFound)
