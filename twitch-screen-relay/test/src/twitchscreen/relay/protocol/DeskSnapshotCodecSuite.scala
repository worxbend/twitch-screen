package twitchscreen.relay.protocol

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Path}
import java.util.HexFormat
import scala.jdk.CollectionConverters.*

class DeskSnapshotCodecSuite extends munit.FunSuite:
  import DeskSnapshotCodec.*
  private val identity = DeskControlCodec.Identity(0x0123456789abcdefL, 0xfedcba9876543210L)
  private val hex = HexFormat.of()
  private def frame(bytes: Array[Byte]): Frame = FrameReader(ByteArrayInputStream(bytes)).read().toOption.get
  private def vector(name: String): Array[Byte] =
    val path = Iterator
      .iterate(Path.of("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("protocol/desk-v1-vectors.tsv"))
      .find(Files.isRegularFile(_))
      .get
    hex.parseHex(Files.readAllLines(path).asScala.find(_.startsWith(name + "\t")).get.split("\t")(3))

  private val weatherCases = List(
    "weather_negative" -> Weather(identity, 4, 605000, Status.Fresh, 0, 7, -1234, Some(0), Label.fromText("North desk")),
    "weather_missing" -> Weather(identity, 1, 0, Status.Missing),
    "weather_stale" -> Weather(identity, 4, 0, Status.Stale),
    "weather_fahrenheit" -> Weather(identity, 5, 605000, Status.Fresh, 1, 1, 21200, None, Label.fromText("South"))
  )
  private val cpuCases = List(
    "cpu_memory_zero" -> Cpu(identity, 6, 15000, Status.Fresh, 10000, Some(0), Some(-2000), Label.fromText("workstation")),
    "cpu_memory_absent" -> Cpu(identity, 7, 15000, Status.Fresh, 0, None, None, Label.fromText("workstation")),
    "cpu_missing" -> Cpu(identity, 1, 0, Status.Missing),
    "cpu_stale" -> Cpu(identity, 7, 0, Status.Stale)
  )

  test("all eight independent snapshot vectors encode exactly and preserve missing versus measured zero"):
    weatherCases.foreach: (name, value) =>
      assertEquals(hex.formatHex(weather(value)), hex.formatHex(vector(name)))
      assertEquals(decodeWeather(frame(vector(name))), Right(value))
    cpuCases.foreach: (name, value) =>
      assertEquals(hex.formatHex(cpu(value)), hex.formatHex(vector(name)))
      assertEquals(decodeCpu(frame(vector(name))), Right(value))

  test("known-prefix bounds permit every unknown tail and reject every truncation mismatch oversize or wrong type"):
    val readers: List[(String, Frame => Either[String, Any])] = List("weather_negative" -> decodeWeather, "cpu_memory_zero" -> decodeCpu)
    readers.foreach: (name, read) =>
      val original = frame(vector(name))
      (68 to Tsb3.MaxPayload).foreach: length =>
        val payload = java.util.Arrays.copyOf(original.payload, length)
        java.util.Arrays.fill(payload, 68, length, 0xa5.toByte)
        assertEquals(read(original.copy(header = original.header.copy(length = length), payload = payload)), read(original))
      (0 until 68).foreach: length =>
        assert(read(original.copy(header = original.header.copy(length = length), payload = original.payload.take(length))).isLeft)
      assert(read(original.copy(header = original.header.copy(length = 69))).isLeft)
      assert(read(original.copy(header = original.header.copy(length = 249), payload = new Array[Byte](249))).isLeft)
      assert(read(original.copy(header = original.header.copy(version = ProtocolVersion.fromWire(2)))).isLeft)
      assert(read(original.copy(header = original.header.copy(typeCode = TypeCode.fromWire(0x01.toByte)))).isLeft)
      assertEquals(read(original.copy(header = original.header.copy(flags = FrameFlags.fromWire(0x80.toByte)))), read(original))

  private def changed(name: String, offset: Int, value: Int, width: Int = 1): Frame =
    val original = frame(vector(name))
    val payload = original.payload.clone()
    (0 until width).foreach(index => payload(offset + index) = (value >>> (8 * index)).toByte)
    original.copy(payload = payload)

  test("invalid identities status presence reserved ranges and absent numeric garbage fail before projection"):
    List("weather_negative" -> decodeWeather, "cpu_memory_zero" -> decodeCpu).foreach: (name, read) =>
      List((0, 8), (8, 8), (16, 4), (20, 8)).foreach: (offset, width) =>
        val original = frame(vector(name))
        val payload = original.payload.clone()
        java.util.Arrays.fill(payload, offset, offset + width, 0.toByte)
        assert(read(original.copy(payload = payload)).isLeft)
      assert(read(changed(name, 28, 3)).isLeft)
      assert(read(changed(name, 36, 0)).isLeft)
    List((29, 2), (30, 9), (31, 2), (34, 101), (35, 1)).foreach: (offset, value) =>
      assert(decodeWeather(changed("weather_negative", offset, value)).isLeft)
    assert(decodeWeather(changed("weather_negative", 32, -10001, 2)).isLeft)
    assert(decodeWeather(changed("weather_fahrenheit", 32, 21201, 2)).isLeft)
    assert(decodeWeather(changed("weather_fahrenheit", 34, 1)).isLeft)
    assert(decodeCpu(changed("cpu_memory_zero", 29, 4)).isLeft)
    List(30, 32).foreach(offset => assert(decodeCpu(changed("cpu_memory_zero", offset, 10001, 2)).isLeft))
    assert(decodeCpu(changed("cpu_memory_zero", 34, -2001, 2)).isLeft)
    assert(decodeCpu(changed("cpu_memory_zero", 34, 15001, 2)).isLeft)
    assert(decodeCpu(changed("cpu_memory_absent", 32, 1, 2)).isLeft)
    assert(decodeCpu(changed("cpu_memory_absent", 34, 1, 2)).isLeft)

  test("every unavailable value byte must be zero except status and unknown appended tails"):
    List("weather_missing" -> decodeWeather, "weather_stale" -> decodeWeather, "cpu_missing" -> decodeCpu, "cpu_stale" -> decodeCpu)
      .foreach: (name, read) =>
        ((20 until 28) ++ (29 until 68)).foreach: offset =>
          assert(read(changed(name, offset, 1)).isLeft, s"$name offset=$offset")

  test("unsigned identity revision deadline bits survive and label folding is bounded without lossy wire comparison"):
    val value = Cpu(
      DeskControlCodec.Identity(Long.MinValue, -1L),
      0xffffffffL,
      -1L,
      Status.Fresh,
      10000,
      Some(10000),
      Some(15000),
      Label.fromText("Long workstation label " * 5)
    )
    assertEquals(decodeCpu(frame(cpu(value))), Right(value))
    assertEquals(value.host.text.length, 31)
    assert(value.host.text.endsWith("..."))
    assertEquals(Label.fromText("Désk\tNorth").text, "Desk North")
    val original = frame(vector("cpu_memory_absent"))
    val payload = original.payload.clone()
    payload(67) = 0x7f.toByte
    val decoded = decodeCpu(original.copy(payload = payload)).toOption.get
    assertEquals(decoded.host.text, cpuCases(1)._2.host.text)
    assertNotEquals(decoded.host, cpuCases(1)._2.host)
    val _ = intercept[IllegalArgumentException](cpu(decoded))
    List(0L, -1L, 0x100000000L).foreach: revision =>
      val _ = intercept[IllegalArgumentException](Cpu(identity, revision, 0, Status.Missing))
    val _ = intercept[IllegalArgumentException](Label.fromText("😀"))
