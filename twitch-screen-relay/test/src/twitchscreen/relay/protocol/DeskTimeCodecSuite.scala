package twitchscreen.relay.protocol

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Path}
import java.util.HexFormat
import scala.jdk.CollectionConverters.*

class DeskTimeCodecSuite extends munit.FunSuite:
  private val hex = HexFormat.of()
  private def frame(bytes: Array[Byte]): Frame = FrameReader(ByteArrayInputStream(bytes)).read().toOption.get

  /** Read independent canonical vectors rather than reproducing expected bytes from the codec. */
  private def vector(name: String): Array[Byte] =
    val path = Iterator
      .iterate(Path.of("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("protocol/desk-v1-vectors.tsv"))
      .find(Files.isRegularFile(_))
      .get
    val line = Files.readAllLines(path).asScala.find(_.startsWith(name + "\t")).get
    hex.parseHex(line.split("\t")(3))

  test("request matches independent canonical bytes and decodes unsigned bits"):
    val expected = vector("time_request")
    assertEquals(hex.formatHex(DeskTimeCodec.request(4294967298L)), hex.formatHex(expected))
    assertEquals(DeskTimeCodec.decodeRequest(frame(expected)), Right(4294967298L))
    List(0L, Long.MaxValue, Long.MinValue, -1L).foreach: value =>
      assertEquals(DeskTimeCodec.decodeRequest(frame(DeskTimeCodec.request(value))), Right(value))

  test("response matches independent canonical bytes including unsigned connection identity"):
    val expected = vector("time_response")
    val value = DeskTimeCodec.Response(0x0123456789abcdefL, 0xfedcba9876543210L, 4294967298L, 5000L)
    assertEquals(hex.formatHex(DeskTimeCodec.response(value)), hex.formatHex(expected))
    assertEquals(DeskTimeCodec.decodeResponse(frame(expected)), Right(value))

  test("profile time decoder rejects wrong direction and version"):
    val request = frame(vector("time_request"))
    val response = frame(vector("time_response"))
    assert(DeskTimeCodec.decodeResponse(request).isLeft)
    assert(DeskTimeCodec.decodeRequest(response).isLeft)
    assert(DeskTimeCodec.decodeRequest(request.copy(header = request.header.copy(version = ProtocolVersion.fromWire(2)))).isLeft)
    assert(DeskTimeCodec.decodeResponse(response.copy(header = response.header.copy(version = ProtocolVersion.fromWire(2)))).isLeft)

  private def extended(bytes: Array[Byte], length: Int): Frame =
    val result = java.util.Arrays.copyOf(bytes, Tsb3.HeaderSize + length)
    java.util.Arrays.fill(result, bytes.length, result.length, 0xa5.toByte)
    result(4) = length.toByte
    result(5) = (length >>> 8).toByte
    result(7) = Tsb3.headerCheck(result)
    frame(result)

  test("time records accept every bounded unknown tail through the real frame reader"):
    val request = vector("time_request")
    val response = vector("time_response")
    (8 to Tsb3.MaxPayload).foreach: length =>
      assertEquals(DeskTimeCodec.decodeRequest(extended(request, length)), DeskTimeCodec.decodeRequest(frame(request)))
    (32 to Tsb3.MaxPayload).foreach: length =>
      assertEquals(DeskTimeCodec.decodeResponse(extended(response, length)), DeskTimeCodec.decodeResponse(frame(response)))

  test("both time decoders reject every truncated prefix inconsistent lengths and oversize"):
    val request = frame(vector("time_request"))
    val response = frame(vector("time_response"))
    (0 until 8).foreach: length =>
      assert(
        DeskTimeCodec.decodeRequest(request.copy(header = request.header.copy(length = length), payload = new Array[Byte](length))).isLeft
      )
    (0 until 32).foreach: length =>
      assert(
        DeskTimeCodec
          .decodeResponse(response.copy(header = response.header.copy(length = length), payload = new Array[Byte](length)))
          .isLeft
      )
    List(0, 7, 9, 248, 249).foreach: length =>
      assert(DeskTimeCodec.decodeRequest(request.copy(header = request.header.copy(length = length))).isLeft)
      assert(DeskTimeCodec.decodeResponse(response.copy(header = response.header.copy(length = length))).isLeft)
    val oversize = Tsb3.MaxPayload + 1
    assert(
      DeskTimeCodec.decodeRequest(request.copy(header = request.header.copy(length = oversize), payload = new Array[Byte](oversize))).isLeft
    )
    assert(
      DeskTimeCodec
        .decodeResponse(response.copy(header = response.header.copy(length = oversize), payload = new Array[Byte](oversize)))
        .isLeft
    )
    assert(DeskTimeCodec.decodeRequest(request.copy(payload = Array.emptyByteArray)).isLeft)
    assert(DeskTimeCodec.decodeResponse(response.copy(payload = Array.emptyByteArray)).isLeft)
