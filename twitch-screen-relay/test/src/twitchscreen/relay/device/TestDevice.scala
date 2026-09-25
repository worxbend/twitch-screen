package twitchscreen.relay.device

import java.io.{BufferedInputStream, OutputStream}
import java.net.Socket
import java.time.Clock
import ox.Ox
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.{ChatNotifications, DeviceLinkConfig, Hostname, Port}
import twitchscreen.relay.protocol.*

/** A stand-in for the ESP32: speaks TSB/3 over a real socket, so the tests exercise the actual I/O path — the frame reader, the encoder,
  * the writer fork and the socket options — rather than a mock of it.
  *
  * It builds its frames with [[Tsb3Encoder]] and reads them with [[FrameReader]] and [[Tsb3Decoder]], which the golden-vector suite has
  * already pinned against §18 byte for byte. A device that hand-coded bytes here would be testing a third implementation.
  */
private[device] final class TestDevice(port: Int, soTimeoutMs: Int = 5_000) extends AutoCloseable:
  private val socket = Socket("127.0.0.1", port)
  socket.setSoTimeout(soTimeoutMs)
  socket.setTcpNoDelay(true)
  private val out: OutputStream = socket.getOutputStream
  private val reader = FrameReader(BufferedInputStream(socket.getInputStream))

  def sendBytes(bytes: Array[Byte]): Unit =
    out.write(bytes)
    out.flush()

  def send(message: DeviceMessage, version: ProtocolVersion = Tsb3.Version): Unit =
    sendBytes(Tsb3Encoder.toRelay(message, version))

  def hello(
      device: String,
      lastSeq: Long,
      caps: Capabilities = TestDevice.FullCaps,
      version: ProtocolVersion = Tsb3.Version,
      rxMax: Int = Tsb3.MinRxMax
  ): Unit =
    send(
      DeviceMessage.Hello(
        deviceId = DeviceId(device).toOption.get,
        lastSeq = SeqNo.fromWire(lastSeq),
        caps = caps,
        rxMax = FrameSize.fromWire(rxMax),
        fwVersion = FirmwareVersion.fromWire("1.0.0")
      ),
      version
    )

  /** Blocks until the relay sends a frame, or returns None once the relay has closed the connection or fallen silent for `soTimeoutMs`. A
    * read timeout is "nothing more is coming" for a test, not a failure, so it ends a drain rather than throwing through it.
    */
  def receive(): Option[Frame] =
    try reader.read().toOption
    catch case _: java.io.IOException => None

  /** The relay's frames as messages, which is how every assertion in the link suite is written. */
  def receiveMessage(): Option[RelayMessage] = receive().flatMap(frame => Tsb3Decoder.fromRelay(frame).toOption)

  def receiveMany(count: Int): List[Frame] = List.fill(count)(receive()).flatten

  /** Everything the relay sends until it closes, bounded so a hung test fails on the assertion rather than on the clock. */
  def drain(limit: Int = 64): List[Frame] =
    LazyList.continually(receive()).take(limit).takeWhile(_.isDefined).flatten.toList

  override def close(): Unit = socket.close()

private[device] object TestDevice:
  /** What a fully featured firmware asks for: acks, chat, the generic kinds and a font beyond US-ASCII. */
  val FullCaps: Capabilities = Capabilities.Ack | Capabilities.Chat | Capabilities.Generic | Capabilities.Utf8Text

  /** A device with no UTF-8 font and no appetite for chat, which is what §6.1's degradation rules are for. */
  val AsciiOnlyCaps: Capabilities = Capabilities.Ack | Capabilities.Generic

private[device] object TestRelay:
  val config: DeviceLinkConfig = DeviceLinkConfig(
    host = Hostname("127.0.0.1").toOption.get,
    port = Port(1).toOption.get, // replaced per test by `start`, which binds an OS-assigned port
    protocolVersion = 3,
    acceptBacklog = 8,
    handshakeTimeout = 2.seconds,
    idleTimeout = 5.seconds,
    pingInterval = 4.seconds,
    outboundQueueCapacity = 32,
    replayBufferSize = 8,
    maxFrameLength = Tsb3.MaxFrame
  )

  /** Starts a hub and listener on a free port, returning both. Everything stops when the enclosing scope ends. */
  def start(config: DeviceLinkConfig = config, chat: ChatNotifications = ChatNotifications.Show)(using Ox): (DeviceHub, Int) =
    val clock = Clock.systemUTC()
    val hub = DeviceHub.start(config, chat, clock, EventBus(clock, queueCapacity = 64))
    val listener = DeviceLinkServer.startOnPort(config, hub, clock, 0)
    (hub, listener.getLocalPort)
