package twitchscreen.relay.protocol

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Path}
import java.util.HexFormat
import scala.jdk.CollectionConverters.*

class DeskGreetingCodecSuite extends munit.FunSuite:
  private val hex = HexFormat.of()
  private val helloTail = DeskGreetingCodec.HelloTail(0x0123456789abcdefL, 120L, 0x8877665544332211L, 1000L)
  private val welcomeTail = DeskGreetingCodec.WelcomeTail(0x0123456789abcdefL, 0xfedcba9876543210L, 5000L, 1000L)

  private def vector(name: String): Array[Byte] =
    val path = Iterator
      .iterate(Path.of("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("protocol/desk-v1-vectors.tsv"))
      .find(Files.isRegularFile(_))
      .get
    hex.parseHex(Files.readAllLines(path).asScala.find(_.startsWith(name + "\t")).get.split("\t")(3))

  private def frame(bytes: Array[Byte]): Frame = FrameReader(ByteArrayInputStream(bytes)).read().toOption.get
  private def hello: Frame = frame(vector("hello_resume"))
  private def welcome: Frame = frame(vector("welcome"))

  private def sized(original: Frame, length: Int): Frame =
    original.copy(header = original.header.copy(length = length), payload = java.util.Arrays.copyOf(original.payload, length))

  private def changed(original: Frame, offset: Int, value: Byte): Frame =
    val payload = original.payload.clone()
    payload(offset) = value
    original.copy(payload = payload)

  test("greeting encoders and decoders match independent full-frame vectors"):
    val baseHello = Tsb3Decoder.fromDevice(hello).toOption.get.asInstanceOf[DeviceMessage.Hello]
    val baseWelcome = Tsb3Decoder.fromRelay(welcome).toOption.get.asInstanceOf[RelayMessage.Welcome]
    assertEquals(DeskGreetingCodec.decodeHello(hello), Right(Some(helloTail)))
    assertEquals(DeskGreetingCodec.decodeWelcome(welcome, 1000L), Right(Some(welcomeTail)))
    assertEquals(hex.formatHex(DeskGreetingCodec.hello(baseHello, helloTail)), hex.formatHex(vector("hello_resume")))
    assertEquals(hex.formatHex(DeskGreetingCodec.welcome(baseWelcome, welcomeTail)), hex.formatHex(vector("welcome")))

  test("every truncated HELLO tail falls back while negotiated WELCOME truncation fails"):
    (60 until 92).foreach: length =>
      assertEquals(DeskGreetingCodec.decodeHello(sized(hello, length)), Right(None))
    (24 until 60).foreach: length =>
      assert(DeskGreetingCodec.decodeWelcome(sized(welcome, length), 1000L).isLeft)

  test("unknown nonzero appended tails are ignored at all permitted lengths"):
    (92 to Tsb3.MaxPayload).foreach: length =>
      val extended = sized(hello, length)
      java.util.Arrays.fill(extended.payload, 92, length, 0xa5.toByte)
      assertEquals(DeskGreetingCodec.decodeHello(extended), Right(Some(helloTail)))
    (60 to Tsb3.MaxPayload).foreach: length =>
      val extended = sized(welcome, length)
      java.util.Arrays.fill(extended.payload, 60, length, 0xa5.toByte)
      assertEquals(DeskGreetingCodec.decodeWelcome(extended, 1000L), Right(Some(welcomeTail)))

  test("invalid HELLO profile reserved or boot identity clears profile only"):
    List((60, 2.toByte), (62, 1.toByte)).foreach: (offset, value) =>
      assertEquals(DeskGreetingCodec.decodeHello(changed(hello, offset, value)), Right(None))
    val bootZero = hello.payload.clone()
    java.util.Arrays.fill(bootZero, 76, 84, 0.toByte)
    assertEquals(DeskGreetingCodec.decodeHello(hello.copy(payload = bootZero)), Right(None))
    assertEquals(DeskGreetingCodec.decodeHello(changed(hello, Tsb3.Hello.Caps, 7.toByte)), Right(None))
    assertEquals(DeskGreetingCodec.decodeHello(changed(hello, Tsb3.Hello.Reserved0, 1.toByte)), Right(Some(helloTail)))

  test("WELCOME with selected profile rejects invalid identities profile reserved and probe"):
    List((24, 2.toByte), (26, 1.toByte)).foreach: (offset, value) =>
      assert(DeskGreetingCodec.decodeWelcome(changed(welcome, offset, value), 1000L).isLeft)
    List(28, 36).foreach: offset =>
      val payload = welcome.payload.clone()
      java.util.Arrays.fill(payload, offset, offset + 8, 0.toByte)
      assert(DeskGreetingCodec.decodeWelcome(welcome.copy(payload = payload), 1000L).isLeft)
    assert(DeskGreetingCodec.decodeWelcome(welcome, 1001L).isLeft)
    assertEquals(DeskGreetingCodec.decodeWelcome(changed(welcome, Tsb3.Welcome.Caps, 7.toByte), 999L), Right(None))

  test("greetings preserve classic validation and reject bad frame boundaries or direction"):
    assert(DeskGreetingCodec.decodeHello(changed(hello, Tsb3.Hello.DeviceId, 0.toByte)).isLeft)
    assert(DeskGreetingCodec.decodeHello(changed(hello, Tsb3.Hello.RxMax + 1, 0.toByte)).isLeft)
    assert(DeskGreetingCodec.decodeHello(welcome).isLeft)
    assert(DeskGreetingCodec.decodeWelcome(hello, 1000L).isLeft)
    assert(DeskGreetingCodec.decodeHello(hello.copy(header = hello.header.copy(version = ProtocolVersion.fromWire(2)))).isLeft)
    assert(DeskGreetingCodec.decodeWelcome(welcome.copy(header = welcome.header.copy(version = ProtocolVersion.fromWire(2))), 1000L).isLeft)
    assert(DeskGreetingCodec.decodeHello(hello.copy(payload = Array.emptyByteArray)).isLeft)
    assert(DeskGreetingCodec.decodeWelcome(welcome.copy(payload = Array.emptyByteArray), 1000L).isLeft)
    assert(DeskGreetingCodec.decodeHello(sized(hello, Tsb3.MaxPayload + 1)).isLeft)
    assert(DeskGreetingCodec.decodeWelcome(sized(welcome, Tsb3.MaxPayload + 1), 1000L).isLeft)

  test("encoding invalid typed profile values fails before publication"):
    val baseHello = Tsb3Decoder.fromDevice(hello).toOption.get.asInstanceOf[DeviceMessage.Hello]
    val baseWelcome = Tsb3Decoder.fromRelay(welcome).toOption.get.asInstanceOf[RelayMessage.Welcome]
    val _ = intercept[IllegalArgumentException](DeskGreetingCodec.hello(baseHello, helloTail.copy(deviceBootId = 0)))
    val _ = intercept[IllegalArgumentException](DeskGreetingCodec.hello(baseHello, helloTail.copy(processedSeq = -1)))
    val _ = intercept[IllegalArgumentException](DeskGreetingCodec.hello(baseHello, helloTail.copy(processedSeq = 0x100000000L)))
    val _ = intercept[IllegalArgumentException](DeskGreetingCodec.welcome(baseWelcome, welcomeTail.copy(relayEpoch = 0)))
    val _ = intercept[IllegalArgumentException](DeskGreetingCodec.welcome(baseWelcome, welcomeTail.copy(connectionId = 0)))
