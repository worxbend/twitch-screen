package twitchscreen.relay.protocol

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Path}
import java.util.HexFormat
import scala.jdk.CollectionConverters.*

class DeskControlCodecSuite extends munit.FunSuite:
  import DeskControlCodec.*
  private val hex = HexFormat.of()
  private val identity = Identity(0x0123456789abcdefL, 0xfedcba9876543210L)
  private def frame(bytes: Array[Byte]): Frame = FrameReader(ByteArrayInputStream(bytes)).read().toOption.get
  private def vector(name: String): Array[Byte] =
    val path = Iterator
      .iterate(Path.of("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("protocol/desk-v1-vectors.tsv"))
      .find(Files.isRegularFile(_))
      .get
    hex.parseHex(Files.readAllLines(path).asScala.find(_.startsWith(name + "\t")).get.split("\t")(3))

  test("display sync and barrier match independent canonical vectors"):
    val control = Display(identity, 9, 3, 5, false, Preferred.Cpu)
    val end = Sync(identity, 9, 4, 6, 7, 3, 5)
    val progress = Barrier(identity, 127)
    assertEquals(hex.formatHex(display(control)), hex.formatHex(vector("display_cpu")))
    assertEquals(hex.formatHex(sync(end)), hex.formatHex(vector("sync_end")))
    assertEquals(hex.formatHex(barrier(progress)), hex.formatHex(vector("barrier")))
    assertEquals(decodeDisplay(frame(vector("display_cpu"))), Right(control))
    assertEquals(decodeSync(frame(vector("sync_end"))), Right(end))
    assertEquals(decodeBarrier(frame(vector("barrier"))), Right(progress))

  test("all bounded tails ignored all short inconsistent or oversized payloads rejected"):
    val records: List[(String, Int, Frame => Either[String, Any])] = List(
      ("display_cpu", 32, decodeDisplay),
      ("sync_end", 40, decodeSync),
      ("barrier", 20, decodeBarrier)
    )
    records.foreach: (name, minimum, read) =>
      val original = frame(vector(name))
      (minimum to Tsb3.MaxPayload).foreach: length =>
        val payload = java.util.Arrays.copyOf(original.payload, length)
        java.util.Arrays.fill(payload, minimum, length, 0xa5.toByte)
        assertEquals(read(original.copy(header = original.header.copy(length = length), payload = payload)), read(original))
      (0 until minimum).foreach: length =>
        assert(read(original.copy(header = original.header.copy(length = length), payload = original.payload.take(length))).isLeft)
      assert(read(original.copy(header = original.header.copy(length = minimum + 1))).isLeft)
      assert(read(original.copy(header = original.header.copy(length = 249), payload = new Array[Byte](249))).isLeft)
      assert(read(original.copy(header = original.header.copy(version = ProtocolVersion.fromWire(2)))).isLeft)
      assert(read(original.copy(header = original.header.copy(typeCode = TypeCode.fromWire(0x01.toByte)))).isLeft)
      assertEquals(read(original.copy(header = original.header.copy(flags = FrameFlags.fromWire(0x80.toByte)))), read(original))

  test("zero identities revisions required enums and reserved fields rejected"):
    val original = frame(vector("display_cpu"))
    def mutate(offset: Int, value: Byte, width: Int = 1): Frame =
      val payload = original.payload.clone()
      java.util.Arrays.fill(payload, offset, offset + width, value)
      original.copy(payload = payload)
    List((0, 8), (8, 8), (16, 4), (20, 4), (24, 4)).foreach: (offset, width) =>
      assert(decodeDisplay(mutate(offset, 0, width)).isLeft)
    assert(decodeDisplay(mutate(28, 2)).isLeft)
    assert(decodeDisplay(mutate(29, 3)).isLeft)
    assert(decodeDisplay(mutate(30, 1)).isLeft)
    assert(decodeDisplay(mutate(31, 1)).isLeft)
    List(16, 20, 24, 28, 32, 36).foreach: offset =>
      val end = frame(vector("sync_end"))
      java.util.Arrays.fill(end.payload, offset, offset + 4, 0.toByte)
      assert(decodeSync(end).isLeft)

  test("unsigned limits preserved and producers cannot emit invalid revisions"):
    val high = Display(Identity(Long.MinValue, -1L), 0xffffffffL, 0xffffffffL, 0xffffffffL, true, Preferred.Generic)
    assertEquals(decodeDisplay(frame(display(high))), Right(high))
    List(0L, 0xffffffffL).foreach: sequence =>
      val value = Barrier(identity, sequence)
      assertEquals(decodeBarrier(frame(barrier(value))), Right(value))
    List(0L, -1L, 0x100000000L).foreach: revision =>
      val _ = intercept[IllegalArgumentException](Display(identity, revision, 1, 1, false, Preferred.Weather))
    val _ = intercept[IllegalArgumentException](Barrier(identity, -1))
    val _ = intercept[IllegalArgumentException](Barrier(identity, 0x100000000L))
    val _ = intercept[IllegalArgumentException](Identity(0, 1))
