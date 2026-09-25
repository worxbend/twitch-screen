package twitchscreen.relay.twitch

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReaderException, JsonValueCodec, readFromArray, writeToArray}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import ox.discard
import scala.util.Using
import twitchscreen.relay.config.Sensitive

/** Where the user token survives a restart. One JSON document, owner-readable only, replaced atomically so a crash mid-write leaves the
  * previous token rather than half of a new one.
  */
private[twitch] final class TokenFile(path: Path):
  import TokenFile.*

  def location: Path = path

  /** `Right(None)` when there is no file yet, which is the normal state before the first consent. */
  def load(): Either[String, Option[UserToken]] =
    try
      cleanupStaging()
      if !Files.exists(path) then Right(None)
      else if !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then Left(s"$path is not a regular token file")
      else if posix && !OwnerPermissions.containsAll(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)) then
        Left(s"$path must be accessible only by its owner")
      else
        val bytes = Using.resource(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))(_.readNBytes(MaxBytes + 1))
        if bytes.length > MaxBytes then Left(s"$path exceeds the token file size limit")
        else Right(Some(readFromArray[Stored](bytes).toToken))
    catch
      case error: IOException     => Left(s"could not read $path: ${error.getClass.getSimpleName}")
      case _: JsonReaderException => Left(s"$path is not a token file")

  def save(token: UserToken): Unit =
    Option(path.toAbsolutePath.getParent).foreach(Files.createDirectories(_).discard)
    val absolute = path.toAbsolutePath
    val staging =
      if posix then Files.createTempFile(absolute.getParent, s".${path.getFileName}.", ".tmp", OwnerOnly)
      else Files.createTempFile(absolute.getParent, s".${path.getFileName}.", ".tmp")
    try
      Using.resource(Files.newByteChannel(staging, StandardOpenOption.WRITE)): channel =>
        writeFully(channel, ByteBuffer.wrap(writeToArray(Stored.from(token))))
      Files.move(staging, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE).discard
    finally Files.deleteIfExists(staging).discard

  def delete(): Unit =
    Files.deleteIfExists(path).discard
    cleanupStaging()

  /** Only called before startup reads or after serialized sign-out; also removes the legacy fixed .tmp filename. */
  private def cleanupStaging(): Unit =
    val absolute = path.toAbsolutePath
    Files.deleteIfExists(absolute.resolveSibling(s"${path.getFileName}.tmp")).discard
    val parent = absolute.getParent
    if Files.isDirectory(parent) then
      Using.resource(Files.newDirectoryStream(parent)): files =>
        val iterator = files.iterator()
        while iterator.hasNext do
          val candidate = iterator.next()
          val name = candidate.getFileName.toString
          if name.startsWith(s".${path.getFileName}.") && name.endsWith(".tmp") then Files.deleteIfExists(candidate).discard

  private def posix: Boolean = path.toAbsolutePath.getFileSystem.supportedFileAttributeViews.contains("posix")

private[twitch] object TokenFile:
  private val MaxBytes = 64 * 1024
  private val OwnerPermissions = PosixFilePermissions.fromString("rw-------")
  private val OwnerOnly = PosixFilePermissions.asFileAttribute(OwnerPermissions)

  private[twitch] def writeFully(channel: WritableByteChannel, buffer: ByteBuffer): Unit =
    while buffer.hasRemaining do channel.write(buffer).discard

  /** The on-disk shape. Plain strings, because [[Sensitive]] deliberately has no codec. */
  private final case class Stored(
      accessToken: String,
      refreshToken: String,
      expiresAt: Instant,
      scopes: List[String],
      userId: String,
      login: String
  ):
    def toToken: UserToken = UserToken(Sensitive(accessToken), Sensitive(refreshToken), expiresAt, scopes, userId, login)

  private object Stored:
    given JsonValueCodec[Stored] = JsonCodecMaker.make

    def from(token: UserToken): Stored =
      Stored(token.accessToken.value, token.refreshToken.value, token.expiresAt, token.scopes, token.userId, token.login)
