package twitchscreen.relay.device

import java.io.IOException
import java.net.{InetSocketAddress, ServerSocket, Socket}
import java.time.Clock
import org.slf4j.LoggerFactory
import ox.*
import ox.either.catching
import scala.annotation.tailrec
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
    catch
      // Ending the enclosing scope is how every session stops, and it reaches a session's forks as an interrupt —
      // directly, or wrapped by whichever channel they were blocked on. That is an orderly teardown, so it is logged
      // as one: a WARN and a stack trace per attached device on every shutdown would train an operator to ignore the
      // line that is printed when a session really does fail.
      case Interruption(_) => logger.debug(s"Session for ${socket.getRemoteSocketAddress} stopped: the relay is shutting down")
      case NonFatal(error) => logger.warn(s"Session for ${socket.getRemoteSocketAddress} ended unexpectedly", error)

  /** Matches an interrupt however deeply it has been wrapped, because a fork blocked on a channel is told about it through that channel.
    *
    * The walk is depth-bounded rather than following `getCause` to the end: Ox wraps a channel failure in a cause chain that can point back
    * at itself, and a teardown is not the moment to discover that.
    */
  private object Interruption:
    private val MaxCauseDepth = 8

    @tailrec
    private def isInterrupt(error: Throwable, depth: Int): Boolean = error match
      case _: InterruptedException => true
      case _ =>
        val cause = error.getCause
        if cause == null || (cause eq error) || depth >= MaxCauseDepth then false else isInterrupt(cause, depth + 1)

    def unapply(error: Throwable): Option[Throwable] = Option.when(isInterrupt(error, 0))(error)
