package twitchscreen.relay.device

import java.io.IOException
import java.net.{InetSocketAddress, ServerSocket, Socket}
import java.time.Clock
import org.slf4j.LoggerFactory
import ox.*
import ox.either.catching
import scala.util.control.NonFatal
import twitchscreen.relay.config.DeviceLinkConfig

/** The listener firmware connects to: one virtual thread per connection, which is what Loom and Ox's structured concurrency are for.
  * Blocking socket I/O on a virtual thread is interruptible, so ending the enclosing scope ends every session without a shutdown flag
  * anywhere.
  */
private[relay] object DeviceLinkServer:
  private val logger = LoggerFactory.getLogger(getClass)

  /** Binds and starts accepting. Both the listener and every session stop when the enclosing scope ends. */
  def start(config: DeviceLinkConfig, hub: DeviceHub, clock: Clock)(using Ox): ServerSocket =
    val listener = useCloseableInScope(ServerSocket())
    listener.setReuseAddress(true)
    listener.bind(InetSocketAddress(config.host.value, config.port.value), config.acceptBacklog)
    logger.info(s"Device link listening on ${config.host.value}:${config.port.value} (protocol v${config.protocolVersion})")
    forkDiscard(acceptLoop(listener, config, hub, clock))
    listener

  private def acceptLoop(listener: ServerSocket, config: DeviceLinkConfig, hub: DeviceHub, clock: Clock)(using Ox): Unit =
    repeatWhile:
      accept(listener) match
        case Right(socket) =>
          forkDiscard(session(socket, config, hub, clock))
          true
        case Left(_) if listener.isClosed =>
          logger.info("Device link listener closed")
          false
        case Left(error) =>
          // A refused connection must not take the listener down with it.
          logger.warn("Accepting a device connection failed", error)
          true

  private def accept(listener: ServerSocket): Either[IOException, Socket] = listener.accept().catching[IOException]

  private def session(socket: Socket, config: DeviceLinkConfig, hub: DeviceHub, clock: Clock): Unit =
    try DeviceSession.run(socket, config, hub, clock)
    catch case NonFatal(error) => logger.warn(s"Session for ${socket.getRemoteSocketAddress} ended unexpectedly", error)
