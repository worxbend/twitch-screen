package twitchscreen.relay.settings

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReaderException, JsonValueCodec, readFromArray, writeToArray}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, LinkOption, NoSuchFileException, Path, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import ox.discard
import scala.util.Using

/** Schema 1 contains desired intent only. OAuth, availability, revisions and notification content are not persisted. */
private[relay] final case class RuntimeSettings(schemaVersion: Int, display: DisplaySettings, monitoring: MonitoringSettings):
  require(schemaVersion == 1, "unsupported runtime settings schema")
  require(display != null && monitoring != null, "runtime settings sections are required")

private[relay] final case class DisplaySettings(twitchDisplayEnabled: Boolean, preferredDashboard: String):
  require(Set("generic", "weather", "cpu").contains(preferredDashboard), "invalid preferred dashboard")

private[relay] final case class ChannelSetting(value: Option[String]):
  require(value.forall(_.matches("[a-z0-9_]{1,25}")), "invalid monitoring channel")

private[relay] object ChannelSetting:
  given JsonValueCodec[ChannelSetting] with
    def nullValue: ChannelSetting = null
    def decodeValue(in: com.github.plokhotnyuk.jsoniter_scala.core.JsonReader, default: ChannelSetting): ChannelSetting =
      if in.isNextToken('n'.toByte) then in.readNullOrError(ChannelSetting(None), "expected channel string or null")
      else
        in.rollbackToken()
        ChannelSetting(Some(in.readString(null)))
    def encodeValue(value: ChannelSetting, out: com.github.plokhotnyuk.jsoniter_scala.core.JsonWriter): Unit =
      value.value match
        case Some(channel) => out.writeVal(channel)
        case None          => out.writeNull()

private[relay] final case class MonitoringSettings(mode: String, channel: ChannelSetting):
  require(Set("disabled", "simulated", "live").contains(mode), "invalid monitoring mode")
  require(channel != null, "monitoring channel field is required")

private[relay] object RuntimeSettings:
  def firstRun(mode: String, channel: String): RuntimeSettings =
    RuntimeSettings(1, DisplaySettings(false, "generic"), MonitoringSettings(mode, ChannelSetting(Option(channel))))

  given JsonValueCodec[RuntimeSettings] = JsonCodecMaker.make(
    CodecMakerConfig.withSkipUnexpectedFields(false).withCheckFieldDuplication(true).withTransientNull(false)
  )

/** Blocking storage prerequisite for the future settings worker; never invoke it inside DeviceHub's actor. A missing final file is first
  * run. Other read failures are fatal and never trigger replacement. Writes use a private same-directory staging file, file fsync, atomic
  * rename and directory fsync. This class is not mounted in startup or mutation APIs yet.
  */
private[relay] final class RuntimeSettingsFile(path: Path, syncDirectory: Path => Unit = RuntimeSettingsFile.syncDirectory):
  import RuntimeSettingsFile.*
  private val absolute = path.toAbsolutePath.normalize()
  private val parent = absolute.getParent

  def load(): Either[ReadFailure, Option[RuntimeSettings]] =
    try
      if Files.exists(parent, LinkOption.NOFOLLOW_LINKS) then privateDirectory(parent)
      val attributes = Files.readAttributes(absolute, classOf[java.nio.file.attribute.BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      if !attributes.isRegularFile then throw IOException("not a regular file")
      privatePermissions(absolute, FilePermissions)
      val bytes = Using.resource(Files.newInputStream(absolute, LinkOption.NOFOLLOW_LINKS))(_.readNBytes(MaxBytes + 1))
      if bytes.length > MaxBytes then Left(ReadFailure(absolute, "size_limit"))
      else
        val decoded = readFromArray[RuntimeSettings](bytes)
        require(decoded != null, "runtime settings document is required")
        Right(Some(decoded))
    catch
      case _: NoSuchFileException =>
        // Only absence of the final path is first run. A dangling symlink or failed parent admission is not absence.
        if !Files.exists(absolute, LinkOption.NOFOLLOW_LINKS) &&
          (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
        then Right(None)
        else Left(ReadFailure(absolute, "read_failed"))
      case _: JsonReaderException | _: IllegalArgumentException => Left(ReadFailure(absolute, "invalid_schema"))
      case _: IOException | _: SecurityException                => Left(ReadFailure(absolute, "read_failed"))

  /** Once rename is attempted, failures are uncertain even if readback matches. Directory-sync failure is not a durable success. */
  def save(settings: RuntimeSettings): Either[WriteFailure, Unit] =
    var staging: Option[Path] = None
    var renameAttempted = false
    def failure: WriteFailure = WriteFailure(absolute, renameAttempted, Option.when(renameAttempted)(load()))
    val result =
      try
        require(settings != null, "runtime settings are required")
        val bytes = writeToArray(settings)
        require(bytes.length <= MaxBytes, "runtime settings size limit")
        Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(DirectoryPermissions)).discard
        privateDirectory(parent)
        val temporary = Files.createTempFile(parent, ".runtime-state-", ".tmp", PosixFilePermissions.asFileAttribute(FilePermissions))
        staging = Some(temporary)
        Using.resource(FileChannel.open(temporary, StandardOpenOption.WRITE)): channel =>
          val buffer = ByteBuffer.wrap(bytes)
          while buffer.hasRemaining do channel.write(buffer).discard
          channel.force(true)
        renameAttempted = true
        Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING).discard
        syncDirectory(parent)
        Right(())
      catch case _: IOException | _: SecurityException | _: IllegalArgumentException => Left(failure)
    try
      staging.foreach(Files.deleteIfExists(_).discard)
      result
    catch case _: IOException | _: SecurityException => Left(failure)

private[relay] object RuntimeSettingsFile:
  private val MaxBytes = 16 * 1024
  private val FilePermissions = PosixFilePermissions.fromString("rw-------")
  private val DirectoryPermissions = PosixFilePermissions.fromString("rwx------")

  final case class ReadFailure(path: Path, code: String)
  final case class WriteFailure(path: Path, uncertain: Boolean, reconciliation: Option[Either[ReadFailure, Option[RuntimeSettings]]])

  private def privatePermissions(path: Path, allowed: java.util.Set[java.nio.file.attribute.PosixFilePermission]): Unit =
    if !allowed.containsAll(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)) then
      throw IOException("runtime settings permissions are not private")
    val owner = path.getFileSystem.getUserPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
    if Files.getOwner(path, LinkOption.NOFOLLOW_LINKS) != owner then throw IOException("runtime settings owner mismatch")

  private def privateDirectory(path: Path): Unit =
    if !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) then throw IOException("runtime settings parent is not a directory")
    privatePermissions(path, DirectoryPermissions)

  private def syncDirectory(path: Path): Unit =
    Using.resource(FileChannel.open(path, StandardOpenOption.READ))(_.force(true))
