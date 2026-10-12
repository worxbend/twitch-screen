package twitchscreen.relay.device

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import java.io.IOException
import java.nio.file.{Files, Path}
import java.time.Clock
import ox.{fork, supervised}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import sttp.client4.*
import sttp.model.StatusCode
import sttp.tapir.server.stub4.TapirSyncStubInterpreter
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.ChatNotifications
import twitchscreen.relay.settings.{DisplaySettings, RuntimeSettings, RuntimeSettingsFile, RuntimeSettingsWorker}

class DisplayApiSuite extends munit.FunSuite:
  private val fixture = FunFixture[Path](
    _ => Files.createTempDirectory(Path.of(sys.env.getOrElse("TMPDIR", System.getProperty("java.io.tmpdir"))), "display-api-"),
    directory => Using.resource(Files.walk(directory))(_.iterator().asScala.toList.reverse.foreach(Files.delete(_)))
  )
  private val startup = RuntimeSettings.firstRun("disabled", null)

  private def withApi(directory: Path, sync: Path => Unit = _ => ())(test: (SyncBackend, DeviceHub, RuntimeSettingsWorker) => Unit): Unit =
    supervised:
      val clock = Clock.systemUTC()
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 64))
      val worker =
        RuntimeSettingsWorker.start(RuntimeSettingsFile(directory.resolve("state.json"), sync), startup, hub.installSettings).toOption.get
      val backend = TapirSyncStubInterpreter().whenServerEndpointsRunLogic(DisplayApi(hub, worker).endpoints).backend()
      test(backend, hub, worker)

  private def get(backend: SyncBackend): Display_OUT =
    val response = basicRequest.get(uri"http://localhost/api/v1/display").send(backend)
    assertEquals(response.code, StatusCode.Ok)
    readFromString[Display_OUT](response.body.fold(identity, identity))

  private def patch(backend: SyncBackend, body: String) =
    basicRequest.patch(uri"http://localhost/api/v1/display").contentType("application/json").body(body).send(backend)

  fixture.test("mounted display merges and persists desired fields without consuming card or source authority") { directory =>
    withApi(directory): (backend, hub, worker) =>
      val before = hub.publicationSnapshot
      val initial = get(backend)
      assertEquals(initial.revision, 1L)
      assertEquals(initial.weather, Readiness_OUT.missing)
      assertEquals(initial.cpu, Readiness_OUT.missing)
      val response = patch(backend, """{"preferredDashboard":"cpu"}""")
      assertEquals(response.code, StatusCode.Ok)
      val accepted = readFromString[Display_OUT](response.body.fold(identity, identity))
      assertEquals((accepted.revision, accepted.preferredDashboard, accepted.twitchDisplayEnabled), (2L, "cpu", false))
      assertEquals(get(backend), accepted)
      assertEquals(RuntimeSettingsFile(directory.resolve("state.json")).load(), Right(Some(worker.snapshot.confirmed)))
      val stamp = Files.getLastModifiedTime(directory.resolve("state.json"))
      assertEquals(patch(backend, """{"preferredDashboard":"cpu"}""").code, StatusCode.Ok)
      assertEquals(get(backend).revision, 2L)
      assertEquals(Files.getLastModifiedTime(directory.resolve("state.json")), stamp)
      assertEquals(patch(backend, """{"twitchDisplayEnabled":true}""").code, StatusCode.Ok)
      assertEquals(worker.snapshot.confirmed.display, DisplaySettings(true, "cpu"))
      val after = hub.publicationSnapshot
      assertEquals(after.latestSeq, before.latestSeq)
      assertEquals(after.retained, before.retained)
      assertEquals(after.control.map(_.sourceGeneration), before.control.map(_.sourceGeneration))
      assertEquals(after.control.map(_.twitchEpisode), Some(2L))
  }

  fixture.test("strict malformed partial updates cannot touch persistence or desired control") { directory =>
    withApi(directory): (backend, _, worker) =>
      val before = get(backend)
      List(
        "{}",
        "null",
        """{"twitchDisplayEnabled":null}""",
        """{"twitchDisplayEnabled":1}""",
        """{"preferredDashboard":"CPU"}""",
        """{"preferredDashboard":"cpu","unknown":1}""",
        """{"preferredDashboard":"cpu","preferredDashboard":"generic"}""",
        """{"preferredDashboard":"\ud800"}"""
      ).foreach: body =>
        assertEquals(patch(backend, body).code, StatusCode.BadRequest, body)
      assertEquals(get(backend), before)
      assertEquals(worker.snapshot.confirmed, startup)
      assert(!Files.exists(directory.resolve("state.json")))
  }

  fixture.test("concurrent HTTP patches preserve omitted fields and capture their own projected revision") { directory =>
    withApi(directory): (backend, _, worker) =>
      supervised:
        val enabled = fork(patch(backend, """{"twitchDisplayEnabled":true}"""))
        val preferred = fork(patch(backend, """{"preferredDashboard":"cpu"}"""))
        val replies = List(enabled.join(), preferred.join())
        replies.foreach(response => assertEquals(response.code, StatusCode.Ok))
        assertEquals(
          replies.map(response => readFromString[Display_OUT](response.body.fold(identity, identity)).revision).sorted,
          List(2L, 3L)
        )
      assertEquals(worker.snapshot.confirmed.display, DisplaySettings(true, "cpu"))
      assertEquals(get(backend).revision, 3L)
      assertEquals(RuntimeSettingsFile(directory.resolve("state.json")).load(), Right(Some(worker.snapshot.confirmed)))
  }

  fixture.test("uncertain fsync returns failure and never projects readback as committed desired control") { directory =>
    withApi(directory, _ => throw IOException("synthetic directory sync failure")): (backend, hub, worker) =>
      val before = get(backend)
      assertEquals(patch(backend, """{"preferredDashboard":"cpu"}""").code, StatusCode.ServiceUnavailable)
      assertEquals(get(backend), before)
      assert(worker.snapshot.storageFailure.exists(_.uncertain))
      assertEquals(patch(backend, """{"twitchDisplayEnabled":true}""").code, StatusCode.ServiceUnavailable)
      assertEquals(hub.publicationSnapshot.control.map(_.revision), Some(1L))
      assertEquals(
        RuntimeSettingsFile(directory.resolve("state.json")).load().toOption.flatten.map(_.display),
        Some(DisplaySettings(false, "cpu"))
      )
  }

  fixture.test("display reads include current CPU readiness without copying diagnostic payload") { directory =>
    withApi(directory): (backend, hub, _) =>
      assert(hub.publishCpu(Cpu_IN("synthetic", "host", 0)).isRight)
      val value = get(backend)
      assertEquals((value.cpu.revision, value.cpu.status, value.cpu.reason), (2L, "fresh", "fresh"))
      assert(value.cpu.remainingFreshnessMs > 0)
      assertEquals(value.revision, 1L)
      assertEquals(value.weather, Readiness_OUT.missing)
  }
