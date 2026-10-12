package twitchscreen.relay.settings

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.io.IOException
import ox.discard
import scala.jdk.CollectionConverters.*
import scala.util.Using

class RuntimeSettingsFileSuite extends munit.FunSuite:
  private val fixture = FunFixture[Path](
    _ => Files.createTempDirectory(Path.of(sys.env.getOrElse("TMPDIR", System.getProperty("java.io.tmpdir"))), "runtime-settings-"),
    directory => Using.resource(Files.walk(directory))(_.iterator().asScala.toList.reverse.foreach(Files.delete(_)))
  )
  private val initial = RuntimeSettings.firstRun("disabled", null)
  private val json =
    """{"schemaVersion":1,"display":{"twitchDisplayEnabled":false,"preferredDashboard":"generic"},"monitoring":{"mode":"disabled","channel":null}}"""

  private def write(path: Path, text: String): Unit =
    Files.writeString(path, text, UTF_8).discard
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")).discard

  fixture.test("only missing files use first-run settings; parent and file permissions are private on save") { directory =>
    val path = directory.resolve("data/runtime-state.json")
    val file = RuntimeSettingsFile(path)
    assertEquals(file.load(), Right(None))
    assertEquals(file.save(initial), Right(()))
    assertEquals(Files.readString(path), json)
    assertEquals(com.github.plokhotnyuk.jsoniter_scala.core.readFromArray[RuntimeSettings](Files.readAllBytes(path)), initial)
    assertEquals(file.load(), Right(Some(initial)))
    assertEquals(PosixFilePermissions.toString(Files.getPosixFilePermissions(path)), "rw-------")
    assertEquals(PosixFilePermissions.toString(Files.getPosixFilePermissions(path.getParent)), "rwx------")
    assert(Files.readString(path).contains("\"channel\":null"))
  }

  fixture.test("every persisted display and monitoring combination survives a new storage owner") { directory =>
    val path = directory.resolve("runtime-state.json")
    for
      enabled <- List(false, true)
      preference <- List("generic", "weather", "cpu")
      mode <- List("disabled", "simulated", "live")
      channel <- List(null, "worxbend")
    do
      val settings = RuntimeSettings(1, DisplaySettings(enabled, preference), MonitoringSettings(mode, ChannelSetting(Option(channel))))
      assertEquals(RuntimeSettingsFile(path).save(settings), Right(()))
      assertEquals(RuntimeSettingsFile(path).load(), Right(Some(settings)))
    assertEquals(Using.resource(Files.list(directory))(_.count()), 1L)
  }

  fixture.test("strict schema rejects unknown, duplicate, missing, null and invalid fields without overwriting content") { directory =>
    val path = directory.resolve("runtime-state.json")
    val invalid = List(
      json.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
      json.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"extra\":true"),
      json.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
      json.replace("\"twitchDisplayEnabled\":false", "\"twitchDisplayEnabled\":false,\"twitchDisplayEnabled\":false"),
      json.replace("\"twitchDisplayEnabled\":false", "\"twitchDisplayEnabled\":null"),
      json.replace("\"twitchDisplayEnabled\":false,", ""),
      json.replace("\"preferredDashboard\":\"generic\"", "\"preferredDashboard\":null"),
      json.replace("\"preferredDashboard\":\"generic\"", "\"preferredDashboard\":\"Generic\""),
      json.replace("\"mode\":\"disabled\"", "\"mode\":null"),
      json.replace("\"mode\":\"disabled\"", "\"mode\":\"unknown\""),
      json.replace("\"channel\":null", "\"channel\":\"https://twitch.tv/worxbend\""),
      json.replace("\"channel\":null", "\"channel\":\"Worxbend\""),
      json.replace("\"channel\":null", "\"channel\":\"\""),
      json.replace("\"mode\":\"disabled\",\"channel\":null", "\"mode\":\"disabled\""),
      json.replace("\"display\":", "\"unknown\":"),
      json.replace("\"display\":{\"twitchDisplayEnabled\":false,\"preferredDashboard\":\"generic\"}", "\"display\":null"),
      "null",
      "[]",
      "{private-looking-content",
      json + "{}"
    )
    invalid.foreach: content =>
      write(path, content)
      val result = RuntimeSettingsFile(path).load()
      assert(result.isLeft, s"expected invalid schema: $content")
      assert(!result.toString.contains("private-looking-content"))
      assertEquals(Files.readString(path), content)
  }

  fixture.test("bounded reads reject oversized state and symlinks, directories and exposed permissions") { directory =>
    val path = directory.resolve("runtime-state.json")
    write(path, " " * (16 * 1024 + 1))
    assertEquals(RuntimeSettingsFile(path).load().left.toOption.map(_.code), Some("size_limit"))
    write(path, json)
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--")).discard
    assert(RuntimeSettingsFile(path).load().isLeft)
    Files.delete(path)
    Files.createDirectory(path).discard
    assert(RuntimeSettingsFile(path).load().isLeft)
    Files.delete(path)
    Files.createSymbolicLink(path, directory.resolve("missing")).discard
    assert(RuntimeSettingsFile(path).load().isLeft)
  }

  fixture.test("unsafe existing parent fails read and write without chmod or treating it as first run") { directory =>
    val path = directory.resolve("data/runtime-state.json")
    Files.createDirectory(path.getParent).discard
    Files.setPosixFilePermissions(path.getParent, PosixFilePermissions.fromString("rwxr-xr-x")).discard
    val file = RuntimeSettingsFile(path)
    assert(file.load().isLeft)
    val failure = file.save(initial).left.toOption.get
    assert(!failure.uncertain)
    assertEquals(failure.reconciliation, None)
    assertEquals(PosixFilePermissions.toString(Files.getPosixFilePermissions(path.getParent)), "rwxr-xr-x")
    assert(!Files.exists(path))
  }

  fixture.test("atomic replacement never follows a final symlink or mutates a hardlinked old inode") { directory =>
    val sentinel = directory.resolve("sentinel.json")
    write(sentinel, "sentinel")
    val path = directory.resolve("runtime-state.json")
    Files.createSymbolicLink(path, sentinel).discard
    assertEquals(RuntimeSettingsFile(path).save(initial), Right(()))
    assertEquals(Files.readString(sentinel), "sentinel")
    Files.delete(path)
    Files.createLink(path, sentinel).discard
    assertEquals(RuntimeSettingsFile(path).save(initial), Right(()))
    assertEquals(Files.readString(sentinel), "sentinel")
    assertEquals(RuntimeSettingsFile(path).load(), Right(Some(initial)))
  }

  fixture.test("post-rename durability failure is uncertain and reconciles actual replacement without claiming success") { directory =>
    val path = directory.resolve("runtime-state.json")
    assertEquals(RuntimeSettingsFile(path).save(initial), Right(()))
    val changed = initial.copy(display = DisplaySettings(true, "cpu"))
    val file = RuntimeSettingsFile(path, _ => throw IOException("synthetic directory fsync failure"))
    val failure = file.save(changed).left.toOption.get
    assert(failure.uncertain)
    assertEquals(failure.reconciliation, Some(Right(Some(changed))))
    assertEquals(RuntimeSettingsFile(path).load(), Right(Some(changed)))
    assertEquals(Using.resource(Files.list(directory))(_.count()), 1L)
  }

  fixture.test("pre-rename failure preserves current state and cleans only its own staging file") { directory =>
    val path = directory.resolve("runtime-state.json")
    Files.createDirectory(path).discard
    val unrelated = directory.resolve(".runtime-state-unrelated.tmp")
    write(unrelated, "do not remove")
    val failure = RuntimeSettingsFile(path).save(initial).left.toOption.get
    assert(failure.uncertain)
    assert(failure.reconciliation.get.isLeft)
    assert(Files.isDirectory(path))
    assertEquals(Files.readString(unrelated), "do not remove")
    assertEquals(Using.resource(Files.list(directory))(_.count()), 2L)
  }
