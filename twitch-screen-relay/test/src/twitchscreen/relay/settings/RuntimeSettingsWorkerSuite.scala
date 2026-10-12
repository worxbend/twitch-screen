package twitchscreen.relay.settings

import java.io.IOException
import java.nio.file.{Files, Path}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import ox.{discard, fork, supervised}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

class RuntimeSettingsWorkerSuite extends munit.FunSuite:
  private val fixture = FunFixture[Path](
    _ => Files.createTempDirectory(Path.of(sys.env.getOrElse("TMPDIR", System.getProperty("java.io.tmpdir"))), "settings-worker-"),
    directory => Using.resource(Files.walk(directory))(_.iterator().asScala.toList.reverse.foreach(Files.delete(_)))
  )
  private val startup = RuntimeSettings.firstRun("disabled", null)

  fixture.test("expired queued admission returns boundedly and never writes after the actor resumes") { directory =>
    supervised:
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val writes = AtomicInteger()
      val file = RuntimeSettingsFile(
        directory.resolve("runtime-state.json"),
        _ => {
          writes.incrementAndGet().discard
          entered.countDown()
          assert(release.await(3, TimeUnit.SECONDS))
        }
      )
      val worker = RuntimeSettingsWorker.start(file, startup).toOption.get
      val admitted = fork(worker.patch(SettingsPatch(preferredDashboard = Some("cpu"))))
      try
        assert(entered.await(1, TimeUnit.SECONDS))
        val expired = worker.patchAndRead(SettingsPatch(twitchDisplayEnabled = Some(true)), System.nanoTime() + 100.millis.toNanos)(true)
        assertEquals(expired, Left("application_timeout"))
        assertEquals(worker.snapshot.confirmed, startup)
      finally release.countDown()
      assert(admitted.join().isRight)
      worker.read(_ => ())
      assertEquals(worker.snapshot.confirmed.display, DisplaySettings(false, "cpu"))
      assertEquals(writes.get(), 1)
      assertEquals(file.load().toOption.flatten.get.display, DisplaySettings(false, "cpu"))
  }

  fixture.test("expired validation cannot enter provider preparation, storage or commit") { directory =>
    supervised:
      val worker = RuntimeSettingsWorker.start(RuntimeSettingsFile(directory.resolve("runtime-state.json")), startup).toOption.get
      val result = worker.validatedPatch(SettingsPatch(mode = Some("simulated")), System.nanoTime() - 1L)((_, _) => {
        fail("expired work validated")
        Right(())
      })((_, _) => true)
      assertEquals(result, Left("application_timeout"))
      assertEquals(worker.snapshot.confirmed, startup)
      assert(!Files.exists(directory.resolve("runtime-state.json")))
  }

  fixture.test("persisted settings win over startup and equivalent patches do not write or increment revision") { directory =>
    supervised:
      val path = directory.resolve("runtime-state.json")
      val persisted =
        startup.copy(display = DisplaySettings(true, "weather"), monitoring = MonitoringSettings("live", ChannelSetting(Some("worxbend"))))
      assertEquals(RuntimeSettingsFile(path).save(persisted), Right(()))
      val writes = AtomicInteger(0)
      val file = RuntimeSettingsFile(path, _ => writes.incrementAndGet().discard)
      val worker = RuntimeSettingsWorker.start(file, startup).toOption.get
      val before = worker.snapshot
      assertEquals(before.confirmed, persisted)
      assertEquals(before.settingsSource, "persisted")
      assertEquals(worker.patch(SettingsPatch(preferredDashboard = Some("weather"))), Right(before))
      assertEquals(worker.snapshot.revision, 1L)
      assertEquals(writes.get(), 0)
  }

  fixture.test("concurrent disjoint partial patches preserve every omitted field in memory and actual storage") { directory =>
    supervised:
      val file = RuntimeSettingsFile(directory.resolve("runtime-state.json"))
      val worker = RuntimeSettingsWorker.start(file, startup).toOption.get
      assertEquals(worker.snapshot.settingsSource, "startup")
      val enabled = fork(worker.patch(SettingsPatch(twitchDisplayEnabled = Some(true))))
      val preferred = fork(worker.patch(SettingsPatch(preferredDashboard = Some("cpu"))))
      assert(enabled.join().isRight)
      assert(preferred.join().isRight)
      val after = worker.snapshot
      assertEquals(after.confirmed.display, DisplaySettings(true, "cpu"))
      assertEquals(after.confirmed.monitoring, startup.monitoring)
      assertEquals(after.revision, 3L)
      assertEquals(file.load(), Right(Some(after.confirmed)))
  }

  fixture.test("monitoring partial updates preserve remembered channel and independent display intent") { directory =>
    supervised:
      val file = RuntimeSettingsFile(directory.resolve("runtime-state.json"))
      val worker = RuntimeSettingsWorker.start(file, startup).toOption.get
      assert(worker.patch(SettingsPatch(channel = Some("worxbend"))).isRight)
      assert(worker.patch(SettingsPatch(mode = Some("simulated"))).isRight)
      assertEquals(worker.snapshot.confirmed.monitoring, MonitoringSettings("simulated", ChannelSetting(Some("worxbend"))))
      assertEquals(worker.snapshot.confirmed.display, startup.display)
      assertEquals(file.load(), Right(Some(worker.snapshot.confirmed)))
  }

  fixture.test("reads remain nonblocking and uncommitted state stays hidden while durable storage is blocked") { directory =>
    supervised:
      val writing = CountDownLatch(1)
      val release = CountDownLatch(1)
      val file = RuntimeSettingsFile(
        directory.resolve("runtime-state.json"),
        _ => {
          writing.countDown()
          assert(release.await(5, TimeUnit.SECONDS))
        }
      )
      val worker = RuntimeSettingsWorker.start(file, startup).toOption.get
      val pending = fork(worker.patch(SettingsPatch(preferredDashboard = Some("cpu"))))
      try
        assert(writing.await(5, TimeUnit.SECONDS))
        assertEquals(worker.snapshot.confirmed, startup)
        assertEquals(worker.snapshot.revision, 1L)
      finally release.countDown()
      assert(pending.join().isRight)
      assertEquals(worker.snapshot.confirmed.display.preferredDashboard, "cpu")
  }

  fixture.test("uncertain durability readback is explicit, never committed, and latches further writes until recovery") { directory =>
    supervised:
      val writes = AtomicInteger(0)
      val path = directory.resolve("runtime-state.json")
      val file = RuntimeSettingsFile(
        path,
        _ => {
          writes.incrementAndGet().discard
          throw IOException("synthetic fsync failure")
        }
      )
      val worker = RuntimeSettingsWorker.start(file, startup).toOption.get
      assertEquals(worker.patch(SettingsPatch(preferredDashboard = Some("cpu"))), Left("storage_uncertain"))
      assertEquals(worker.snapshot.confirmed, startup)
      assertEquals(worker.snapshot.revision, 1L)
      val disk = startup.copy(display = DisplaySettings(false, "cpu"))
      assertEquals(worker.snapshot.storageFailure.flatMap(_.reconciliation), Some(Right(Some(disk))))
      assertEquals(worker.patch(SettingsPatch(twitchDisplayEnabled = Some(true))), Left("storage_uncertain"))
      assertEquals(writes.get(), 1)
      assertEquals(RuntimeSettingsFile(path).load(), Right(Some(disk)))
  }

  fixture.test("durable commit waits for runtime projection and projection failure latches further mutations") { directory =>
    supervised:
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val calls = AtomicInteger(0)
      val file = RuntimeSettingsFile(directory.resolve("runtime-state.json"))
      val worker = RuntimeSettingsWorker
        .start(
          file,
          startup,
          value => {
            calls.incrementAndGet().discard
            if value.revision > 1 then
              entered.countDown()
              assert(release.await(5, TimeUnit.SECONDS))
              throw IllegalStateException("synthetic owner failure")
          }
        )
        .toOption
        .get
      val pending = fork(worker.patch(SettingsPatch(preferredDashboard = Some("cpu"))))
      try
        assert(entered.await(5, TimeUnit.SECONDS))
        assertEquals(worker.snapshot.confirmed, startup)
        assertEquals(file.load().toOption.flatten.get.display.preferredDashboard, "cpu")
      finally release.countDown()
      assertEquals(pending.join(), Left("runtime_unavailable"))
      assert(worker.snapshot.projectionFailed)
      assertEquals(worker.patch(SettingsPatch(twitchDisplayEnabled = Some(true))), Left("runtime_unavailable"))
      assertEquals(calls.get(), 2)
  }

  fixture.test("corrupt storage blocks worker startup without replacement; invalid patches cannot mutate") { directory =>
    supervised:
      val path = directory.resolve("runtime-state.json")
      Files.writeString(path, "{synthetic invalid state").discard
      assert(RuntimeSettingsWorker.start(RuntimeSettingsFile(path), startup).isLeft)
      assertEquals(Files.readString(path), "{synthetic invalid state")
      val _ = intercept[IllegalArgumentException](SettingsPatch())
      intercept[IllegalArgumentException](SettingsPatch(preferredDashboard = Some("invalid")))
  }
