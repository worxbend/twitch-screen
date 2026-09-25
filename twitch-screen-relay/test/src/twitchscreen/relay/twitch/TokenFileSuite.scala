package twitchscreen.relay.twitch

import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import scala.collection.mutable.ListBuffer
import scala.util.Using
import ox.discard
import twitchscreen.relay.config.Sensitive

class TokenFileSuite extends munit.FunSuite:
  private val token = UserToken(Sensitive("access"), Sensitive("refresh"), Instant.MAX, Nil, "123", "channel")
  private val tempDir = FunFixture[Path](
    _ => Files.createTempDirectory("token-file"),
    dir => Using.resource(Files.walk(dir))(_.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_)))
  )

  test("short writes are repeated until the entire token document is written"):
    val bytes = ListBuffer.empty[Byte]
    val channel = new WritableByteChannel:
      override def isOpen: Boolean = true
      override def close(): Unit = ()
      override def write(buffer: ByteBuffer): Int =
        val length = math.min(3, buffer.remaining())
        (0 until length).foreach(_ => bytes += buffer.get())
        length
    TokenFile.writeFully(channel, ByteBuffer.wrap("complete token".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
    assertEquals(String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8), "complete token")

  tempDir.test("oversized on-disk tokens are rejected before JSON parsing"): dir =>
    val path = dir.resolve("token.json")
    Files.write(path, Array.fill[Byte](65537)(32)).discard
    if path.getFileSystem.supportedFileAttributeViews.contains("posix") then
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")).discard
    assert(TokenFile(path).load().left.exists(_.contains("size limit")))

  tempDir.test("load rejects permissions broadened after save"): dir =>
    val path = dir.resolve("token.json")
    val file = TokenFile(path)
    file.save(token)
    if path.getFileSystem.supportedFileAttributeViews.contains("posix") then
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--")).discard
      assert(file.load().left.exists(_.contains("only by its owner")))

  tempDir.test("startup removes abandoned staging copies"): dir =>
    val path = dir.resolve("token.json")
    Files.writeString(dir.resolve("token.json.tmp"), "legacy secret").discard
    Files.writeString(dir.resolve(".token.json.crashed.tmp"), "staged secret").discard
    assertEquals(TokenFile(path).load(), Right(None))
    assert(!Files.exists(dir.resolve("token.json.tmp")))
    assert(!Files.exists(dir.resolve(".token.json.crashed.tmp")))

  tempDir.test("a failed replacement removes the staging copy"): dir =>
    val path = Files.createDirectory(dir.resolve("token.json"))
    Files.writeString(path.resolve("keep"), "directory cannot be replaced").discard
    intercept[java.io.IOException](TokenFile(path).save(token)).discard
    Using.resource(Files.list(dir)): files =>
      assertEquals(files.count(), 1L)
