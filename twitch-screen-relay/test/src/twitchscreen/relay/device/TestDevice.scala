package twitchscreen.relay.device

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import java.net.{ServerSocket, Socket}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import ox.Ox
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.{DeviceLinkConfig, Hostname, Port}
import scala.concurrent.duration.DurationInt

/** A stand-in for the ESP32: speaks protocol v2 over a real socket so the tests exercise the actual I/O path. */
private[device] final class TestDevice(port: Int) extends AutoCloseable:
  private val socket = Socket("127.0.0.1", port)
  socket.setSoTimeout(5_000)
  private val reader = BufferedReader(InputStreamReader(socket.getInputStream, UTF_8))
  private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream, UTF_8))

  def send(line: String): Unit =
    writer.write(line)
    writer.write('\n')
    writer.flush()

  def hello(device: String, lastSeq: Long, proto: Int = 2): Unit =
    send(s"""{"op":"hello","device":"$device","proto":$proto,"last_seq":$lastSeq}""")

  /** Blocks until the relay sends a line, or returns None once the relay has closed the connection. */
  def receive(): Option[String] = Option(reader.readLine())

  def receiveMany(count: Int): List[String] = List.fill(count)(receive()).flatten

  override def close(): Unit = socket.close()

private[device] object TestRelay:
  val config: DeviceLinkConfig = DeviceLinkConfig(
    host = Hostname("127.0.0.1").toOption.get,
    port = Port(1).toOption.get, // replaced per test by `start`, which binds an OS-assigned port
    protocolVersion = 2,
    acceptBacklog = 8,
    handshakeTimeout = 2.seconds,
    idleTimeout = 5.seconds,
    pingInterval = 4.seconds,
    outboundQueueCapacity = 16,
    replayBufferSize = 8,
    maxFrameLength = 256
  )

  /** Starts a hub and listener on a free port, returning both. Everything stops when the enclosing scope ends. */
  def start(config: DeviceLinkConfig = config)(using Ox): (DeviceHub, Int) =
    val clock = Clock.systemUTC()
    val hub = DeviceHub.start(config, clock, EventBus(clock, queueCapacity = 64))
    val listener = DeviceLinkServer.start(config.copy(port = freePort()), hub, clock)
    (hub, listener.getLocalPort)

  /** Asks the OS for an unused port and releases it immediately; the listener claims it a moment later. */
  private def freePort(): Port =
    val probe = ServerSocket(0)
    try Port(probe.getLocalPort).toOption.get
    finally probe.close()
