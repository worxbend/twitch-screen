package twitchscreen.relay.device

import java.io.{BufferedReader, BufferedWriter, IOException, InputStreamReader, OutputStreamWriter, Reader, Writer}
import java.net.{Socket, SocketTimeoutException}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.{Channel, ChannelClosed, Source}
import ox.either.catching
import scala.annotation.tailrec
import twitchscreen.relay.config.DeviceLinkConfig
import twitchscreen.relay.protocol.*

/** One device connection, from `hello` to teardown — protocol v2 as specified in `twitch-screen-firmware/docs/PROTOCOL.md`.
  *
  * The session owns the socket and both directions of it. The reader runs in the session's own thread and its return value is the reason
  * the link ended; the writer and the heartbeat are forks that the session's scope cancels. The single way either side ends the other is
  * closing the socket: a socket read on a virtual thread is interruptible, so closing it unblocks the reader immediately instead of waiting
  * out the idle timeout.
  */
private[device] object DeviceSession:
  private val logger = LoggerFactory.getLogger(getClass)

  def run(socket: Socket, config: DeviceLinkConfig, hub: DeviceHub, clock: Clock): Unit =
    val remote = String.valueOf(socket.getRemoteSocketAddress)
    supervised:
      try
        prepare(socket, config)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream, UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream, UTF_8))
        handshake(reader, config) match
          case Left(reason) => logger.info(s"Refused device at $remote: ${reason.describe}")
          case Right(accepted) =>
            socket.setSoTimeout(config.idleTimeout.toMillis.toInt)
            serve(socket, reader, writer, accepted, remote, config, hub, clock)
      finally
        // Closed here in the scope body rather than through a scope finalizer: a finalizer runs only once the scope
        // has joined its forks, and unblocking those forks is exactly what this close is for.
        closeQuietly(socket)

  private def prepare(socket: Socket, config: DeviceLinkConfig): Unit =
    socket.setTcpNoDelay(true) // notifications are small and latency-sensitive; Nagle would add up to 40 ms
    socket.setKeepAlive(true) // second net under the application heartbeat, per the protocol's liveness section
    socket.setSoTimeout(config.handshakeTimeout.toMillis.toInt)

  /** A device must introduce itself before anything else, and must speak the version this relay was built for. */
  /** The accepted `hello`, with the bytes it took, so the handshake is not missing from the link's traffic counters. */
  private final case class Handshake(hello: DeviceCommand.Hello, bytes: Int)

  private def handshake(reader: Reader, config: DeviceLinkConfig): Either[DisconnectReason, Handshake] =
    readNext(reader, config) match
      case Inbound.Ended(DisconnectReason.IdleTimeout) => Left(DisconnectReason.HandshakeTimeout)
      case Inbound.Ended(reason)                       => Left(reason)
      case Inbound.Ignorable(error, _)                 => Left(DisconnectReason.HandshakeRejected(error.describe))
      case Inbound.Frame(hello: DeviceCommand.Hello, bytes) =>
        if hello.protocolVersion == config.protocolVersion then Right(Handshake(hello, bytes))
        else
          Left(
            DisconnectReason.HandshakeRejected(
              s"device speaks protocol ${hello.protocolVersion}, this relay speaks ${config.protocolVersion}"
            )
          )
      case Inbound.Frame(other, _) =>
        Left(DisconnectReason.HandshakeRejected(s"expected hello, got ${other.getClass.getSimpleName.toLowerCase}"))

  private def serve(
      socket: Socket,
      reader: Reader,
      writer: Writer,
      handshake: Handshake,
      remote: String,
      config: DeviceLinkConfig,
      hub: DeviceHub,
      clock: Clock
  )(using Ox): Unit =
    val hello = handshake.hello
    val outbound = Channel.buffered[ServerFrame](config.outboundQueueCapacity)
    val counters = LinkCounters(clock)
    counters.recordReceived(handshake.bytes)
    val connection =
      hub.attach(AttachRequest(hello.device, remote, hello.protocolVersion, hello.lastSeq, outbound, counters, socket))
    logger.info(s"Device ${hello.device.value} attached as #${connection.value} from $remote (last_seq=${hello.lastSeq.value})")
    try
      forkDiscard(writeLoop(socket, writer, outbound, counters))
      forkDiscard(heartbeat(outbound, config, clock))
      hub.detach(connection, readLoop(reader, outbound, counters, config))
    finally outbound.doneOrClosed().discard

  /** Drains the outbound queue onto the socket. A failed write closes the socket, which is what ends the session. */
  private def writeLoop(socket: Socket, writer: Writer, outbound: Source[ServerFrame], counters: LinkCounters): Unit =
    drain(writer, outbound, counters)
      .catching[IOException]
      .left
      .foreach: error =>
        logger.debug(s"Write to ${socket.getRemoteSocketAddress} failed: ${error.getMessage}")
        closeQuietly(socket)

  private def drain(writer: Writer, outbound: Source[ServerFrame], counters: LinkCounters): Unit =
    repeatWhile:
      outbound.receiveOrClosed() match
        case frame: ServerFrame =>
          val line = FrameCodec.encode(frame)
          writer.write(line)
          writer.write('\n')
          writer.flush()
          counters.recordSent(line.length + 1)
          true
        case ChannelClosed.Done         => false
        case ChannelClosed.Error(cause) => throw cause

  /** The relay pings as well as answering pings. The firmware only pings after its own silence, so without this a device that is receiving
    * nothing would be the only party able to notice a half-open connection.
    */
  private def heartbeat(outbound: Channel[ServerFrame], config: DeviceLinkConfig, clock: Clock): Unit =
    forever:
      sleep(config.pingInterval)
      outbound.trySendOrClosed(ServerFrame.ping(clock.instant())).discard

  @tailrec
  private def readLoop(reader: Reader, outbound: Channel[ServerFrame], counters: LinkCounters, config: DeviceLinkConfig): DisconnectReason =
    readNext(reader, config) match
      case Inbound.Ended(reason)           => reason
      case Inbound.Ignorable(error, bytes) =>
        // A frame this relay cannot read is the device's problem, not the link's: log it and keep the session up.
        counters.recordReceived(bytes)
        logger.debug(s"Ignoring inbound frame: ${error.describe}")
        readLoop(reader, outbound, counters, config)
      case Inbound.Frame(command, bytes) =>
        counters.recordReceived(bytes)
        handle(command, outbound) match
          case Some(reason) => reason
          case None         => readLoop(reader, outbound, counters, config)

  private def handle(command: DeviceCommand, outbound: Channel[ServerFrame]): Option[DisconnectReason] = command match
    case DeviceCommand.Ping(clientTimestamp) =>
      outbound.trySendOrClosed(ServerFrame.pong(clientTimestamp)).discard
      None
    case DeviceCommand.Pong(_)  => None // liveness is already recorded by `counters.recordReceived`
    case _: DeviceCommand.Hello => Some(DisconnectReason.ProtocolViolation("a second hello on an established session"))

  private def readNext(reader: Reader, config: DeviceLinkConfig): Inbound =
    readLine(reader, config.maxFrameLength, StringBuilder())
      .catching[IOException]
      .fold(
        {
          case _: SocketTimeoutException => Inbound.Ended(DisconnectReason.IdleTimeout)
          case error                     => Inbound.Ended(DisconnectReason.ReadFailed(String.valueOf(error.getMessage)))
        },
        {
          case LineResult.EndOfStream => Inbound.Ended(DisconnectReason.PeerClosed)
          case LineResult.TooLong =>
            Inbound.Ended(DisconnectReason.ProtocolViolation(ProtocolError.FrameTooLong(config.maxFrameLength).describe))
          case LineResult.Line(line) if line.isBlank => Inbound.Ignorable(ProtocolError.Malformed("blank line"), line.length + 1)
          case LineResult.Line(line) =>
            FrameCodec.decode(line, config.maxFrameLength) match
              case Right(command) => Inbound.Frame(command, line.length + 1)
              case Left(error)    => Inbound.Ignorable(error, line.length + 1)
        }
      )

  /** Reads one `\n`-terminated line, refusing to buffer more than `limit` characters. `BufferedReader.readLine` would happily grow without
    * bound, so a peer that never sends a newline could exhaust the heap.
    */
  @tailrec
  private def readLine(reader: Reader, limit: Int, buffer: StringBuilder): LineResult =
    reader.read() match
      case -1                          => if buffer.isEmpty then LineResult.EndOfStream else LineResult.Line(buffer.toString)
      case '\n'                        => LineResult.Line(buffer.toString)
      case '\r'                        => readLine(reader, limit, buffer)
      case _ if buffer.length >= limit => LineResult.TooLong
      case character                   => readLine(reader, limit, buffer.append(character.toChar))

  private def closeQuietly(socket: Socket): Unit =
    socket.close().catching[IOException].left.foreach(error => logger.debug("Closing a device socket failed", error))

/** What one read attempt produced. */
private enum Inbound:
  case Frame(command: DeviceCommand, bytes: Int)
  case Ignorable(error: ProtocolError, bytes: Int)
  case Ended(reason: DisconnectReason)

private enum LineResult:
  case Line(value: String)
  case EndOfStream
  case TooLong
