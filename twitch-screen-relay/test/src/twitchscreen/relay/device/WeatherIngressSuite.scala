package twitchscreen.relay.device

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import java.time.Clock
import ox.supervised
import sttp.client4.*
import sttp.model.StatusCode
import sttp.tapir.server.stub4.TapirSyncStubInterpreter
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.ChatNotifications
import twitchscreen.relay.settings.{DisplaySettings, RuntimeSettings, SettingsSnapshot}

class WeatherIngressSuite extends munit.FunSuite:
  private val timestamp = "2026-10-11T19:00:00.000Z"
  private val request = Weather_IN("synthetic", "desk", -0.005, "clear")

  test("weather is latest-only, rounded and stale at its original absolute deadline"):
    val slot = WeatherSlot()
    val now = RelayClock(42L, 100L)
    assertEquals(slot.snapshot(now).status, "missing")
    val value = slot.put(request.copy(deadline = Some(Deadline_IN(now.epochHex, 500L))), now, timestamp).toOption.get
    assertEquals((value.revision, value.status, value.remainingFreshnessMs), (2L, "fresh", 400L))
    assertEquals(value.data.map(_.temperature), Some(-0.01))
    assertEquals(value.data.flatMap(_.humidityPercent), None)
    assertEquals(value.observedAtSource, Some("relay_receipt"))
    val stale = slot.snapshot(RelayClock(42L, 500L))
    assertEquals((stale.status, stale.remainingFreshnessMs), ("stale", 0L))
    assertEquals(stale.data, value.data)
    assertEquals(stale.revision, value.revision)
    val next = slot.put(request.copy(unit = "fahrenheit", temperature = 212, humidityPercent = Some(0)), now, timestamp).toOption.get
    assertEquals(next.data.flatMap(_.humidityPercent), Some(0L))
    assertEquals(next.data.map(_.unit), Some("fahrenheit"))
    assertEquals(next.revision, 3L)

  test("weather deadline failures, quota and revision exhaustion preserve state"):
    val now = RelayClock(42L, 10L)
    val slot = WeatherSlot()
    assertEquals(
      slot.put(request.copy(deadline = Some(Deadline_IN("000000000000002b", 0))), now, timestamp),
      Left(IngressFailure.EpochChanged)
    )
    assertEquals(slot.put(request.copy(deadline = Some(Deadline_IN(now.epochHex, 10))), now, timestamp), Left(IngressFailure.Expired))
    (1 to 20).foreach(_ => assert(slot.put(request, now, timestamp).isRight))
    val before = slot.snapshot(now)
    assertEquals(slot.put(request, now, timestamp), Left(IngressFailure.RateLimited))
    assertEquals(slot.snapshot(now), before)
    assertEquals(slot.put(request, RelayClock(42L, 109L), timestamp), Left(IngressFailure.RateLimited))
    assert(slot.put(request, RelayClock(42L, 110L), timestamp).isRight)
    val spent = WeatherSlot(0xffffffffL)
    assertEquals(spent.put(request, now, timestamp), Left(IngressFailure.RevisionSpent))
    assertEquals(spent.snapshot(now).data, None)

  test("weather strict request decoding covers integer numeric forms and scalar labels"):
    val base = """{"sourceId":"synthetic","location":"desk","temperature":1,"condition":"clear"}"""
    val invalid = List(
      "\"x\":1",
      "\"temperature\":2",
      "\"humidityPercent\":null",
      "\"humidityPercent\":1.1",
      "\"humidityPercent\":101",
      "\"freshnessMs\":999",
      "\"freshnessMs\":1800001",
      "\"deadline\":null",
      "\"unit\":\"Celsius\"",
      "\"observedAt\":null",
      "\"condition\":\"CLEAR\"",
      "\"location\":\"\ud800\""
    )
    invalid.foreach(value => intercept[Exception](readFromString[Weather_IN](base.dropRight(1) + "," + value + "}")))
    val integral = readFromString[Weather_IN](base.dropRight(1) + ",\"humidityPercent\":1e2,\"freshnessMs\":1e3}")
    assertEquals(integral.humidityPercent, Some(100L))
    assertEquals(integral.freshnessMs, 1000L)
    assertEquals(readFromString[Weather_IN](writeToString(request.copy(location = "😀" * 128))).location.codePointCount(0, 256), 128)
    val _ = intercept[IllegalArgumentException](request.copy(location = "😀" * 129))
    val _ = intercept[IllegalArgumentException](request.copy(temperature = Double.NaN))
    val _ = intercept[IllegalArgumentException](request.copy(temperature = 100.001))
    val _ = intercept[IllegalArgumentException](request.copy(unit = "fahrenheit", temperature = -148.001))

  test("omitted weather unit defaults but explicit null cannot replace the latest value"):
    val base = """{"sourceId":"synthetic","location":"desk","temperature":1,"condition":"clear"}"""
    assertEquals(readFromString[Weather_IN](base).unit, "celsius")
    val _ = intercept[Exception](readFromString[Weather_IN](base.dropRight(1) + ",\"unit\":null}"))
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 64))
      val backend = TapirSyncStubInterpreter().whenServerEndpointsRunLogic(WeatherApi(hub).endpoints).backend()
      val put = basicRequest.put(uri"http://localhost/api/v1/weather").contentType("application/json")
      assertEquals(put.body(base).send(backend).code, StatusCode.Ok)
      val before = hub.weatherSnapshot
      assertEquals(put.body(base.dropRight(1) + ",\"unit\":null}").send(backend).code, StatusCode.BadRequest)
      assertEquals(hub.weatherSnapshot.revision, before.revision)
      assertEquals(hub.weatherSnapshot.data, before.data)

  test("CPU and weather preserve bounded Unicode control whitespace in HTTP diagnostics"):
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 64))
      val endpoints = WeatherApi(hub).endpoints ++ CpuApi(hub).endpoints
      val backend = TapirSyncStubInterpreter().whenServerEndpointsRunLogic(endpoints).backend()
      List("Desk\tNorth", "Desk\nNorth", "Desk\rNorth", "Desk\u0000North", "Desk\u007fNorth", "😀" * 128).foreach: label =>
        val weatherBody = writeToString(request.copy(location = label))
        val cpuBody = writeToString(Cpu_IN("synthetic", label, 1))
        assertEquals(readFromString[Weather_IN](weatherBody).location, label)
        assertEquals(readFromString[Cpu_IN](cpuBody).host, label)
        val weatherResponse =
          basicRequest.put(uri"http://localhost/api/v1/weather").contentType("application/json").body(weatherBody).send(backend)
        val cpuResponse = basicRequest.put(uri"http://localhost/api/v1/cpu").contentType("application/json").body(cpuBody).send(backend)
        assertEquals(weatherResponse.code, StatusCode.Ok)
        assertEquals(cpuResponse.code, StatusCode.Ok)
        assertEquals(hub.weatherSnapshot.data.map(_.location), Some(label))
        assertEquals(hub.cpuSnapshot.data.map(_.host), Some(label))
      val _ = intercept[IllegalArgumentException](request.copy(location = "\t\n"))
      val _ = intercept[IllegalArgumentException](Cpu_IN("synthetic", "\t\n", 1))

  test("atomic hub capture preserves original CPU/weather deadlines without renewal"):
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 64))
      val initial = hub.publicationSnapshot
      assertEquals(initial.cpuDeadlineRelayMs, None)
      assertEquals(initial.weatherDeadlineRelayMs, None)
      val now = hub.relayClock
      val deadline = now.monotonicMs + 10000L
      val calibrated = Some(Deadline_IN(now.epochHex, deadline))
      assert(hub.publishCpu(Cpu_IN("synthetic", "host", 1, deadline = calibrated)).isRight)
      assert(hub.acceptWeather(request.copy(deadline = calibrated)).isRight)
      val first = hub.publicationSnapshot
      val second = hub.publicationSnapshot
      List(first, second).foreach: captured =>
        assertEquals(captured.cpuDeadlineRelayMs, Some(deadline))
        assertEquals(captured.weatherDeadlineRelayMs, Some(deadline))
        assertEquals(captured.cpu.map(_.remainingFreshnessMs), Some(deadline - captured.sampledRelayMs))
        assertEquals(captured.weather.map(_.remainingFreshnessMs), Some(deadline - captured.sampledRelayMs))
        assertEquals(captured.latestSeq, initial.latestSeq)
        assertEquals(captured.retained, initial.retained)
      assertEquals(first.cpu.map(_.revision), second.cpu.map(_.revision))
      assertEquals(first.weather.map(_.revision), second.weather.map(_.revision))
      val cpu = CpuSlot()
      val weather = WeatherSlot()
      val syntheticNow = RelayClock(42L, 100L)
      val syntheticDeadline = Some(Deadline_IN(syntheticNow.epochHex, 500L))
      assert(cpu.put(Cpu_IN("synthetic", "host", 1, deadline = syntheticDeadline), syntheticNow, timestamp).isRight)
      assert(weather.put(request.copy(deadline = syntheticDeadline), syntheticNow, timestamp).isRight)
      assertEquals(cpu.snapshot(RelayClock(42L, 501L)).status, "stale")
      assertEquals(weather.snapshot(RelayClock(42L, 501L)).status, "stale")
      assertEquals(cpu.deadlineRelayMs, Some(500L))
      assertEquals(weather.deadlineRelayMs, Some(500L))

  test("producer wall metadata cannot renew freshness"):
    val slot = WeatherSlot()
    val supplied = request.copy(observedAt = Some("2099-01-01T00:00:00.000Z"), deadline = Some(Deadline_IN("000000000000002a", 50)))
    val value = slot.put(supplied, RelayClock(42L, 1L), timestamp).toOption.get
    assertEquals(value.observedAtSource, Some("producer"))
    assertEquals(value.data.map(_.observedAt), supplied.observedAt)
    assertEquals(slot.snapshot(RelayClock(42L, 50L)).status, "stale")

  test("mounted weather logic returns calibration and joins display without mutating intent or history"):
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 64))
      hub.installSettings(
        SettingsSnapshot(RuntimeSettings.firstRun("disabled", null).copy(display = DisplaySettings(true, "cpu")), 1L, "startup")
      )
      val before = hub.publicationSnapshot
      val backend = TapirSyncStubInterpreter().whenServerEndpointsRunLogic(WeatherApi(hub).endpoints).backend()
      val response =
        basicRequest.put(uri"http://localhost/api/v1/weather").contentType("application/json").body(writeToString(request)).send(backend)
      assertEquals(response.code, StatusCode.Ok)
      val accepted = readFromString[Weather_OUT](response.body.fold(identity, identity))
      assertEquals(response.header("X-Relay-Epoch"), Some(accepted.relayEpoch))
      assert(response.header("X-Relay-Monotonic-Ms").exists(_.toLong >= 0))
      val read = basicRequest.get(uri"http://localhost/api/v1/weather").send(backend)
      assertEquals(readFromString[Weather_OUT](read.body.fold(identity, identity)).data, accepted.data)
      val after = hub.publicationSnapshot
      assertEquals(after.control, before.control)
      assertEquals(after.latestSeq, before.latestSeq)
      assertEquals(after.retained, before.retained)
      assertEquals(after.cpu, before.cpu)
      assertEquals(after.weather.map(_.data), Some(accepted.data))
      val display = Display_OUT.from(after).toOption.get
      assertEquals((display.twitchDisplayEnabled, display.preferredDashboard, display.weather.status), (true, "cpu", "fresh"))
      val invalid = basicRequest.put(uri"http://localhost/api/v1/weather").contentType("application/json").body("{}").send(backend)
      assertEquals(invalid.code, StatusCode.BadRequest)
      assertEquals(hub.weatherSnapshot.revision, accepted.revision)
