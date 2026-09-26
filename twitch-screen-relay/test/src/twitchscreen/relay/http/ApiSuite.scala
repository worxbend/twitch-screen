package twitchscreen.relay.http

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.typesafe.config.ConfigFactory
import java.time.format.DateTimeFormatter
import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID
import ox.supervised
import pureconfig.ConfigSource
import scala.concurrent.duration.DurationInt
import sttp.client4.*
import sttp.model.{StatusCode, Uri}
import sttp.tapir.client.sttp4.SttpClientInterpreter
import sttp.tapir.server.stub4.TapirSyncStubInterpreter
import twitchscreen.relay.activity.{ActivityApi, ActivityLog, Activity_OUT}
import twitchscreen.relay.alerts.{AlertRule, AlertStore, AlertsApi}
import twitchscreen.relay.bus.{BusEvent, EventBus, EventCategory, RelayEvent}
import twitchscreen.relay.config.{ActivityConfig, ChatNotifications, Config, DeviceLinkConfig, Hostname, NotificationsConfig, Port}
import twitchscreen.relay.device.{DeviceApi, DeviceHub, Notification_IN, NotificationApi}
import twitchscreen.relay.health.{DeviceLink_OUT, HealthApi, Health_OUT, HealthStatus, StatusApi, Status_OUT}
import twitchscreen.relay.protocol.{NotificationKind, SeqNo}
import twitchscreen.relay.observability.{LogBuffer, LogLevel, LogRecord, LogsApi, Logs_OUT}
import twitchscreen.relay.twitch.{BotFilter, TwitchSource}

/** The management API, exercised in process through Tapir's stub interpreter — no sockets, no ports. */
class ApiSuite extends munit.FunSuite:
  private val basePath = Some(uri"http://localhost:8080")
  private val clock = Clock.fixed(Instant.ofEpochSecond(1790309000L), ZoneOffset.UTC)

  private val deviceLinkConfig = DeviceLinkConfig(
    host = Hostname("127.0.0.1").toOption.get,
    port = Port(8099).toOption.get,
    protocolVersion = 3,
    acceptBacklog = 8,
    handshakeTimeout = 2.seconds,
    idleTimeout = 5.seconds,
    pingInterval = 4.seconds,
    outboundQueueCapacity = 32,
    replayBufferSize = 8,
    maxFrameLength = 256
  )

  private val notificationsConfig = NotificationsConfig(30.seconds, ChatNotifications.Hide)
  private val AlertTitle = "Rack A"

  /** Builds the whole management API over a fresh hub and hands the test a backend plus the hub behind it. */
  private def withApi(body: (SyncBackend, DeviceHub, AlertStore) => Unit): Unit =
    supervised:
      val bus = EventBus(clock, queueCapacity = 32)
      val hub = DeviceHub.start(deviceLinkConfig, ChatNotifications.Hide, clock, bus)
      val alerts = AlertStore(10)
      val apis = List(
        HealthApi(),
        DeviceApi(hub),
        NotificationApi(hub, notificationsConfig),
        ActivityApi(ActivityLog.start(twitchscreen.relay.config.ActivityConfig(50), bus), clock),
        LogsApi(),
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

  /** `deviceLink.sequenceExhausted` from the status endpoint, over a hub whose counter starts at `initial`. */
  private def statusSequenceExhausted(initial: SeqNo): Either[Unit, Boolean] =
    statusDeviceLink(initial).map(_.sequenceExhausted)

  /** `deviceLink` from the status endpoint, over a hub whose counter starts at `initial`. */
  private def statusDeviceLink(initial: SeqNo): Either[Unit, DeviceLink_OUT] =
    statusOf(initial).map(_.deviceLink)

  /** The status endpoint's body, over a hub whose counter starts at `initial`, for a relay started at `startedAt`. */
  private def statusOf(initial: SeqNo = SeqNo.Zero, startedAt: Instant = clock.instant()): Either[Unit, Status_OUT] =
    supervised:
      val config = ConfigSource
        .fromConfig(
          ConfigFactory.parseString("http.auth.api-token = \"status-test-token-at-least-32-bytes-long\"").withFallback(ConfigFactory.load())
        )
        .loadOrThrow[Config]
      val bus = EventBus(clock, queueCapacity = 32)
      val hub = DeviceHub.startingAt(deviceLinkConfig, ChatNotifications.Hide, clock, bus, initial)
      val twitch = TwitchSource.start(config.twitch, bus, BotFilter.from(config.notifications), clock)
      val status = StatusApi(twitch, hub, bus, ActivityLog.start(ActivityConfig(50), bus), AlertStore(10), clock, startedAt)
      val backend = TapirSyncStubInterpreter().whenServerEndpointsRunLogic(status.endpoints).backend()
      SttpClientInterpreter()
        .toRequestThrowDecodeFailures(StatusApi.getEndpoint, basePath)
        .apply(())
        .send(backend)
        .body
        .left
        .map(_ => ())

  test("the status endpoint reports whether the device link's sequence space is exhausted"):
    assertEquals(statusSequenceExhausted(SeqNo.Zero), Right(false))
    assertEquals(statusSequenceExhausted(SeqNo.Max), Right(true))

  test("the status endpoint reports device connections refused at the session limit"):
    assertEquals(statusDeviceLink(SeqNo.Zero).map(_.connectionsRefused), Right(0L))

  test("K-160: the status endpoint clamps uptime to 0 when the wall clock has stepped back before startedAt"):
    val startedAt = clock.instant().plusSeconds(60)
    val status = statusOf(startedAt = startedAt)
    assertEquals(status.map(_.uptimeSeconds), Right(0L))
    assertEquals(status.map(_.startedAt), Right(startedAt))
    assertEquals(statusOf(startedAt = clock.instant().minusSeconds(90)).map(_.uptimeSeconds), Right(90L))

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
        .apply(Notification_IN(NotificationKind.Alert, AlertTitle, "78C", None))
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
        .apply(Notification_IN(NotificationKind.Alert, AlertTitle, "78C", Some(0L)))
        .send(backend)
      assertEquals(response.code, StatusCode.BadRequest)

  test("a huge positive TTL returns a typed input error without publishing"):
    withApi: (backend, hub, _) =>
      val response = SttpClientInterpreter()
        .toRequestThrowDecodeFailures(NotificationApi.createEndpoint, basePath)
        .apply(Notification_IN(NotificationKind.Alert, AlertTitle, "78C", Some(Long.MaxValue)))
        .send(backend)
      assertEquals(response.code, StatusCode.BadRequest)
      assert(response.body.left.exists(_.isInstanceOf[Fail.IncorrectInput]))
      assertEquals(hub.snapshot.notificationsPublished, 0L)

  test("the largest wire TTL is accepted without wrapping"):
    withApi: (backend, _, _) =>
      val response = SttpClientInterpreter()
        .toRequestThrowDecodeFailures(NotificationApi.createEndpoint, basePath)
        .apply(Notification_IN(NotificationKind.Alert, AlertTitle, "78C", Some(6553500L)))
        .send(backend)
      assertEquals(response.body.map(_.ttlMs), Right(6553500L))

  test("oversized notification text is rejected before publication"):
    withApi: (backend, hub, _) =>
      val response = SttpClientInterpreter()
        .toRequestThrowDecodeFailures(NotificationApi.createEndpoint, basePath)
        .apply(Notification_IN(NotificationKind.Alert, AlertTitle, "x" * 4097, None))
        .send(backend)
      assertEquals(response.code, StatusCode.BadRequest)
      assertEquals(hub.snapshot.notificationsPublished, 0L)

  test("all diagnostic list routes reject page sizes outside the shared bounds"):
    withApi: (backend, _, _) =>
      for route <- List("activity", "alerts", "logs", "notifications"); size <- List(0, -1, 501) do
        val response = basicRequest.get(uri"http://localhost:8080/api/v1/$route?pageSize=$size").send(backend)
        assertEquals(response.code, StatusCode.BadRequest, s"$route pageSize=$size")

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

  private val activityEvents = List(
    RelayEvent.Followed("alice"),
    RelayEvent.TwitchLinkDown("network"),
    RelayEvent.RelayFailure("poller", "boom")
  )

  /** The activity API over a log filled synchronously — one second apart, oldest first — so no bus or fork timing is involved. */
  private def withActivityApi(events: Seq[RelayEvent])(body: SyncBackend => Unit): Unit =
    val log = ActivityLog(50)
    events.zipWithIndex.foreach((event, i) => log.record(BusEvent(clock.instant().plusSeconds(i.toLong), event)))
    body(TapirSyncStubInterpreter().whenServerEndpointsRunLogic(ActivityApi(log, clock).endpoints).backend())

  private val reportTimestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

  test("the activity report names when it was generated, its filter and how many entries it covers"):
    withActivityApi(activityEvents): backend =>
      val all = SttpClientInterpreter()
        .toRequestThrowDecodeFailures(ActivityApi.exportEndpoint, basePath)
        .apply((10, None))
        .send(backend)
        .body
        .getOrElse(fail("export failed"))
      assert(all.contains("twitch-screen-relay activity report"), all)
      assert(all.contains(s"generated  ${reportTimestamp.format(clock.instant())}"), all)
      assert(all.contains("filter     all categories"), all)
      assert(all.contains("entries    3"), all)
      val failures = SttpClientInterpreter()
        .toRequestThrowDecodeFailures(ActivityApi.exportEndpoint, basePath)
        .apply((10, Some(EventCategory.Failure)))
        .send(backend)
        .body
        .getOrElse(fail("export failed"))
      assert(failures.contains("filter     Failure"), failures)
      assert(failures.contains("entries    1"), failures)

  test("the activity list renders id, ISO instant, category name and summary, most recent first"):
    withActivityApi(activityEvents): backend =>
      val body = basicRequest.get(uri"http://localhost:8080/api/v1/activity").send(backend).body.getOrElse(fail("list failed"))
      val failure = RelayEvent.RelayFailure("poller", "boom")
      val newest =
        s"""{"id":3,"at":"${clock.instant().plusSeconds(2)}","category":"Failure","summary":"${failure.summary}"}"""
      assert(body.startsWith(s"""{"entries":[$newest,"""), body)
      val entries = readFromString[Activity_OUT](body).entries
      assertEquals(entries.map(_.id), List(3L, 2L, 1L))
      assertEquals(entries.map(_.category), List(EventCategory.Failure, EventCategory.Twitch, EventCategory.Audience))
      assertEquals(entries.map(_.summary), activityEvents.reverse.map(_.summary))
      val instants = """"at":"([^"]+)"""".r.findAllMatchIn(body).map(_.group(1)).toList
      assertEquals(instants, List(2L, 1L, 0L).map(clock.instant().plusSeconds(_).toString))
      instants.foreach(at => assert(at.matches("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z"""), at))

  test("the activity list filters by category and refuses an unknown one"):
    withActivityApi(activityEvents): backend =>
      val twitch = basicRequest.get(uri"http://localhost:8080/api/v1/activity?category=Twitch").send(backend)
      val entries = readFromString[Activity_OUT](twitch.body.getOrElse(fail("list failed"))).entries
      assertEquals(entries.map(e => (e.category, e.summary)), List(EventCategory.Twitch -> "Twitch link down: network"))
      val nonsense = basicRequest.get(uri"http://localhost:8080/api/v1/activity?category=Nonsense").send(backend)
      assertEquals(nonsense.code, StatusCode.BadRequest)

  /** A logger name no other test or relay component uses, so records from the global [[LogBuffer]] can be told apart. */
  private def logMarker(): String = s"ApiSuite.logs.${UUID.randomUUID()}"

  private def recordLog(marker: String, level: LogLevel, message: String): Unit =
    LogBuffer.record(LogRecord(clock.instant(), level, marker, "test", message))

  private def logTail(backend: SyncBackend, query: String): List[LogRecord] =
    val response = basicRequest.get(Uri.unsafeParse(s"http://localhost:8080/api/v1/logs?$query")).send(backend)
    assertEquals(response.code, StatusCode.Ok, response.body.toString)
    readFromString[Logs_OUT](response.body.getOrElse(fail("logs failed"))).records

  test("the log tail filters by minimum level, most recent first"):
    withApi: (backend, _, _) =>
      val marker = logMarker()
      List(LogLevel.Info -> "i1", LogLevel.Warn -> "w1", LogLevel.Info -> "i2", LogLevel.Warn -> "w2").foreach((level, message) =>
        recordLog(marker, level, message)
      )
      val warnings = logTail(backend, "minLevel=Warn&pageSize=500").filter(_.logger == marker)
      assertEquals(warnings.map(_.message), List("w2", "w1"))
      assert(warnings.forall(_.level == LogLevel.Warn), warnings.toString)
      // Pinned current behaviour: Tapir's `defaultStringBased` enum codec matches level names case-insensitively.
      assertEquals(logTail(backend, "minLevel=warn&pageSize=500").filter(_.logger == marker), warnings)

  test("the log tail defaults to Info and above, leaving Debug out"):
    withApi: (backend, _, _) =>
      val marker = logMarker()
      recordLog(marker, LogLevel.Debug, "d1")
      recordLog(marker, LogLevel.Info, "i1")
      assertEquals(logTail(backend, "pageSize=500").filter(_.logger == marker).map(_.message), List("i1"))

  test("pageSize truncates the log tail to the most recent records"):
    withApi: (backend, _, _) =>
      val marker = logMarker()
      List("e1", "e2", "e3").foreach(recordLog(marker, LogLevel.Error, _))
      val tail = logTail(backend, "minLevel=Error&pageSize=2")
      assertEquals(tail.map(r => (r.logger, r.message)), List(marker -> "e3", marker -> "e2"))

  // The stub has no `defaultHandlers`, so its decode-failure body is Tapir's plain text rather than the production
  // `{"error":...}` envelope; ManagementAuthSuite pins that envelope through the real HttpApi. Only the status is pinned here.
  test("an unknown minLevel is a 400"):
    withApi: (backend, _, _) =>
      val response = basicRequest.get(uri"http://localhost:8080/api/v1/logs?minLevel=verbose").send(backend)
      assertEquals(response.code, StatusCode.BadRequest)

  // The stub has no `defaultHandlers`, so it cannot produce the JSON error envelope. ManagementAuthSuite's "/unknown-route"
  // request asserts the `{"error":` body through the real HttpApi.
  test("an unmatched path gives 404"):
    withApi: (backend, _, _) =>
      val response = basicRequest.get(uri"http://localhost:8080/api/v1/nonexistent").send(backend)
      assertEquals(response.code, StatusCode.NotFound)
