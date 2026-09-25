package twitchscreen.relay.twitch

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReaderException, JsonValueCodec, readFromArray, writeToArray}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
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
    if !Files.exists(path) then Right(None)
    else
      try Right(Some(readFromArray[Stored](Files.readAllBytes(path)).toToken))
      catch
        case error: IOException         => Left(s"could not read $path: ${error.getMessage}")
        case error: JsonReaderException => Left(s"$path is not a token file: ${error.getMessage}")

  def save(token: UserToken): Unit =
    Option(path.toAbsolutePath.getParent).foreach(Files.createDirectories(_).discard)
    val staging = path.resolveSibling(s"${path.getFileName}.tmp")
    Files.deleteIfExists(staging).discard
    // Created with owner-only permissions rather than chmod-ed afterwards, so the token is never briefly world-readable.
    val channel =
      if posix then Files.newByteChannel(staging, java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), OwnerOnly)
      else Files.newByteChannel(staging, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    Using.resource(channel)(_.write(ByteBuffer.wrap(writeToArray(Stored.from(token)))).discard)
    Files.move(staging, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE).discard

  def delete(): Unit = Files.deleteIfExists(path).discard

  private def posix: Boolean = path.toAbsolutePath.getFileSystem.supportedFileAttributeViews.contains("posix")

private[twitch] object TokenFile:
  private val OwnerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

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
