package twitchscreen.relay.device

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import java.time.Clock
import ox.supervised
import sttp.client4.*
import sttp.model.StatusCode
import sttp.tapir.server.stub4.TapirSyncStubInterpreter
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.ChatNotifications
import twitchscreen.relay.settings.{MonitoringSettings, RuntimeSettings, SettingsSnapshot}

class CpuIngressSuite extends munit.FunSuite:
  private val timestamp = "2026-10-11T19:00:00.000Z"
  private val request = Cpu_IN("synthetic", "host", 25.005, Some(50.005), Some(-0.005))

  test("CPU starts missing and accepts rounded complete latest values with monotonic expiry"):
    val slot = CpuSlot()
    assertEquals(slot.snapshot(RelayClock(42L, 0L)).status, "missing")
    assertEquals(slot.snapshot(RelayClock(42L, 0L)).revision, 1L)
    val accepted =
      slot.put(request.copy(deadline = Some(Deadline_IN("000000000000002a", 500L))), RelayClock(42L, 100L), timestamp).toOption.get
    assertEquals((accepted.status, accepted.reason, accepted.revision, accepted.remainingFreshnessMs), ("fresh", "fresh", 2L, 400L))
    assertEquals(accepted.data.map(_.cpuPercent), Some(25.01))
    assertEquals(accepted.data.flatMap(_.memoryPercent), Some(50.01))
    assertEquals(accepted.data.flatMap(_.temperatureCelsius), Some(-0.01))
    assertEquals(accepted.observedAtSource, Some("relay_receipt"))
    assertEquals(accepted.data.map(_.observedAt), Some(timestamp))
    val stale = slot.snapshot(RelayClock(42L, 500L))
    assertEquals((stale.status, stale.reason, stale.remainingFreshnessMs), ("stale", "expired", 0L))
    assertEquals(stale.revision, accepted.revision)
    assertEquals(stale.data, accepted.data)
    assertEquals(slot.snapshot(RelayClock(42L, 5000L)).remainingFreshnessMs, 0L)

  test("CPU calibrated rejection and global burst do not mutate the last value or revision"):
    val slot = CpuSlot()
    val now = RelayClock(42L, 10L)
    assertEquals(
      slot.put(request.copy(deadline = Some(Deadline_IN("000000000000002b", 1000L))), now, timestamp),
      Left(IngressFailure.EpochChanged)
    )
    assertEquals(
      slot.put(request.copy(deadline = Some(Deadline_IN("000000000000002a", 10L))), now, timestamp),
      Left(IngressFailure.Expired)
    )
    (1 to 20).foreach: index =>
      assertEquals(slot.put(request.copy(host = s"host-$index"), now, timestamp).toOption.map(_.revision), Some(index.toLong + 1L))
    val before = slot.snapshot(now)
    assertEquals(slot.put(request.copy(host = "refused"), now, timestamp), Left(IngressFailure.RateLimited))
    assertEquals(slot.snapshot(now), before)
    assertEquals(slot.put(request, RelayClock(42L, 109L), timestamp), Left(IngressFailure.RateLimited))
    assert(slot.put(request, RelayClock(42L, 110L), timestamp).isRight)
    assertEquals(slot.snapshot(RelayClock(42L, 110L)).revision, 22L)
    assertEquals(slot.put(request, RelayClock(42L, 110L), timestamp), Left(IngressFailure.RateLimited))

  test("exhausted CPU revision never wraps or uses notification sequence"):
    val slot = CpuSlot(0xffffffffL)
    assertEquals(slot.put(request, RelayClock(42L, 0L), timestamp), Left(IngressFailure.RevisionSpent))
    assertEquals(slot.snapshot(RelayClock(42L, 0L)).revision, 0xffffffffL)
    assertEquals(slot.snapshot(RelayClock(42L, 0L)).data, None)

  test("producer wall timestamps remain labels and cannot renew calibrated deadlines"):
    val slot = CpuSlot()
    val supplied = request.copy(
      observedAt = Some("2099-01-01T00:00:00.000Z"),
      freshnessMs = 60000L,
      deadline = Some(Deadline_IN("000000000000002a", 50L))
    )
    val accepted = slot.put(supplied, RelayClock(42L, 1L), timestamp).toOption.get
    assertEquals(accepted.observedAtSource, Some("producer"))
    assertEquals(accepted.data.map(_.observedAt), supplied.observedAt)
    assertEquals(slot.snapshot(RelayClock(42L, 50L)).remainingFreshnessMs, 0L)

  test("strict CPU JSON rejects unknown duplicate null ranges and timestamp ambiguity without admission"):
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 64))
      val backend = TapirSyncStubInterpreter().whenServerEndpointsRunLogic(CpuApi(hub).endpoints).backend()
      val body = """{"sourceId":"synthetic","host":"host","cpuPercent":1}"""
      val additions = List(
        "\"x\":1",
        "\"cpuPercent\":2",
        "\"memoryPercent\":null",
        "\"observedAt\":null",
        "\"deadline\":null",
        "\"freshnessMs\":null",
        "\"freshnessMs\":999",
        "\"freshnessMs\":60001",
        "\"memoryPercent\":100.01",
        "\"temperatureCelsius\":151",
        "\"observedAt\":\"2026-10-11T19:00:00Z\"",
        "\"host\":\"\"",
        "\"sourceId\":\"has space\""
      )
      additions.foreach: value =>
        val response = basicRequest
          .put(uri"http://localhost/api/v1/cpu")
          .contentType("application/json")
          .body(body.dropRight(1) + "," + value + "}")
          .send(backend)
        assertEquals(response.code, StatusCode.BadRequest, value)
        assertEquals(hub.cpuSnapshot.revision, 1L)
      val absent = basicRequest
        .put(uri"http://localhost/api/v1/cpu")
        .contentType("application/json")
        .body("""{"sourceId":"synthetic","host":"host"}""")
        .send(backend)
      assertEquals(absent.code, StatusCode.BadRequest)
      val missing = basicRequest.get(uri"http://localhost/api/v1/cpu").send(backend)
      val initial = readFromString[Cpu_OUT](missing.body.fold(identity, identity))
      assertEquals(initial.status, "missing")
      assert(missing.body.fold(identity, identity).contains("\"data\":null"))
      assertEquals(hub.snapshot.latestSeq.value, 0L)

  test("CPU admission joins atomic publication snapshot and survives monitoring intent changes"):
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 64))
      val desired = SettingsSnapshot(RuntimeSettings.firstRun("disabled", "synthetic"), 1L, "startup")
      hub.installSettings(desired)
      val accepted = hub.publishCpu(request).toOption.get
      val before = hub.publicationSnapshot
      assertEquals(before.cpu.flatMap(_.data), accepted.data)
      assertEquals(before.cpu.map(_.revision), Some(accepted.revision))
      assertEquals(before.cpu.map(_.relayEpoch), Some(f"${before.relayEpoch}%016x"))
      hub.installSettings(
        desired.copy(
          revision = 2L,
          confirmed = desired.confirmed.copy(monitoring = MonitoringSettings("simulated", desired.confirmed.monitoring.channel))
        )
      )
      assertEquals(hub.cpuSnapshot.data, accepted.data)
      assertEquals(hub.cpuSnapshot.revision, accepted.revision)
      assertEquals(hub.snapshot.latestSeq.value, 0L)
      assertEquals(hub.snapshot.notificationsPublished, 0L)
      val backend = TapirSyncStubInterpreter().whenServerEndpointsRunLogic(CpuApi(hub).endpoints).backend()
      val response = basicRequest.get(uri"http://localhost/api/v1/cpu").send(backend)
      assertEquals(readFromString[Cpu_OUT](response.body.fold(identity, identity)).data, accepted.data)
