package twitchscreen.relay.device

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import java.time.Clock
import ox.supervised
import scala.concurrent.duration.DurationInt
import sttp.client4.*
import sttp.model.StatusCode
import sttp.tapir.server.stub4.TapirSyncStubInterpreter
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.{ChatNotifications, NotificationsConfig}

class IngressContractSuite extends munit.FunSuite:
  private val card = "{\"type\":\"info\",\"title\":\"synthetic\",\"body\":\"\""
  private val cpu = """{"sourceId":"synthetic","host":"host","cpuPercent":1"""

  private def withApi(test: (sttp.client4.SyncBackend, DeviceHub) => Unit): Unit = supervised:
    val clock = Clock.systemUTC()
    val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 64))
    val endpoints = NotificationApi(hub, NotificationsConfig(8.seconds, ChatNotifications.Show)).endpoints ++ CpuApi(hub).endpoints
    test(TapirSyncStubInterpreter().whenServerEndpointsRunLogic(endpoints).backend(), hub)

  test("CPU preserves valid supplementary scalar labels through canonical PUT and GET"):
    withApi: (backend, hub) =>
      List("😀" * 65, "😀" * 128, "a" * 64 + "😀" * 64, "a" * 128).foreach: host =>
        val body = writeToString(Cpu_IN("synthetic", host, 1))
        val response = basicRequest.put(uri"http://localhost/api/v1/cpu").contentType("application/json").body(body).send(backend)
        assertEquals(response.code, StatusCode.Ok)
        assertEquals(readFromString[Cpu_OUT](response.body.fold(identity, identity)).data.map(_.host), Some(host))
        assertEquals(hub.cpuSnapshot.data.map(_.host), Some(host))
      val before = hub.cpuSnapshot
      List("😀" * 129, "a" * 129).foreach: host =>
        val body = s"{\"sourceId\":\"synthetic\",\"host\":\"$host\",\"cpuPercent\":1}"
        val response = basicRequest.put(uri"http://localhost/api/v1/cpu").contentType("application/json").body(body).send(backend)
        assertEquals(response.code, StatusCode.BadRequest)
      val malformed = """{"sourceId":"synthetic","host":"\ud800","cpuPercent":1}"""
      assertEquals(
        basicRequest.put(uri"http://localhost/api/v1/cpu").contentType("application/json").body(malformed).send(backend).code,
        StatusCode.BadRequest
      )
      assertEquals(hub.cpuSnapshot.data, before.data)
      assertEquals(hub.cpuSnapshot.revision, before.revision)

  test("request integers accept exact integral decimal and exponent forms on every mounted ingress field"):
    withApi: (backend, hub) =>
      List("1000", "1e3", "1000.0", "10000e-1").foreach: number =>
        val body = card + s",\"ttlMs\":$number,\"expiresInMs\":6e4}"
        val response =
          basicRequest.post(uri"http://localhost/api/v1/notifications").contentType("application/json").body(body).send(backend)
        assertEquals(response.code, StatusCode.Ok, number)
        val accepted = readFromString[Notification_OUT](response.body.fold(identity, identity))
        assertEquals((accepted.ttlMs, accepted.expiresInMs), (1000L, 60000L))
        val metrics = cpu + s",\"freshnessMs\":$number}"
        val put = basicRequest.put(uri"http://localhost/api/v1/cpu").contentType("application/json").body(metrics).send(backend)
        assertEquals(put.code, StatusCode.Ok, number)
        assertEquals(hub.cpuSnapshot.data.map(_.freshnessMs), Some(1000L))
      val max = readFromString[Deadline_IN]("""{"relayEpoch":"000000000000002a","monotonicMs":9.007199254740991e15}""")
      assertEquals(max.monotonicMs, 9007199254740991L)
      List("1e3", "1000.0").foreach: number =>
        val now = hub.relayClock
        val expired = card + s",\"deadline\":{\"relayEpoch\":\"${now.epochHex}\",\"monotonicMs\":0e3}}"
        assertEquals(
          basicRequest.post(uri"http://localhost/api/v1/notifications").contentType("application/json").body(expired).send(backend).code,
          StatusCode.Gone
        )
        assertEquals(readFromString[Deadline_IN](s"{\"relayEpoch\":\"${now.epochHex}\",\"monotonicMs\":$number}").monotonicMs, 1000L)

  test("fractional boolean quoted null overflow and oversized exponents fail without mutation"):
    withApi: (backend, hub) =>
      List("1000.1", "10001e-1", "true", "\"1000\"", "null", "9223372036854775808", "1e999999", "1e-999999").foreach: number =>
        List(("notifications", card + s",\"ttlMs\":$number}"), ("cpu", cpu + s",\"freshnessMs\":$number}")).foreach: (route, body) =>
          val request =
            if route == "cpu" then basicRequest.put(uri"http://localhost/api/v1/cpu")
            else basicRequest.post(uri"http://localhost/api/v1/notifications")
          assertEquals(request.contentType("application/json").body(body).send(backend).code, StatusCode.BadRequest, number)
      List("9007199254740992", "9.007199254740992e15", "1000.1", "false", "\"1000\"", "null").foreach: number =>
        val body = card + s",\"deadline\":{\"relayEpoch\":\"${hub.relayClock.epochHex}\",\"monotonicMs\":$number}}"
        assertEquals(
          basicRequest.post(uri"http://localhost/api/v1/notifications").contentType("application/json").body(body).send(backend).code,
          StatusCode.BadRequest
        )
      assertEquals(hub.cpuSnapshot.revision, 1L)
      assertEquals(hub.snapshot.notificationsPublished, 0L)

  test("successful POST and PUT carry fresh accepted-epoch samples and rejected writes carry none"):
    withApi: (backend, hub) =>
      List(("notifications", card + "}"), ("cpu", cpu + "}")).foreach: (route, body) =>
        val before = hub.relayClock
        val request =
          if route == "cpu" then basicRequest.put(uri"http://localhost/api/v1/cpu")
          else basicRequest.post(uri"http://localhost/api/v1/notifications")
        val response = request.contentType("application/json").body(body).send(backend)
        val after = hub.relayClock
        assertEquals(response.code, StatusCode.Ok)
        assertEquals(response.header("X-Relay-Epoch"), Some(before.epochHex))
        val sample = response.header("X-Relay-Monotonic-Ms").get.toLong
        assert(sample >= before.monotonicMs && sample <= after.monotonicMs)
        val rejected = request
          .contentType("application/json")
          .body(body.dropRight(1) + ",\"deadline\":{\"relayEpoch\":\"0000000000000001\",\"monotonicMs\":0}}")
          .send(backend)
        assertEquals(rejected.code, StatusCode.Conflict)
        assertEquals(rejected.header("X-Relay-Epoch"), None)
        assertEquals(rejected.header("X-Relay-Monotonic-Ms"), None)

  test("a reset between acceptance and response sampling cannot label old acceptance with a new epoch"):
    assertEquals(
      WriteCalibration.attach("accepted", "000000000000002a", RelayClock(42L, 150L)),
      ("accepted", Some("000000000000002a"), Some("150"))
    )
    assertEquals(WriteCalibration.attach("accepted", "000000000000002a", RelayClock(43L, 1L)), ("accepted", None, None))
