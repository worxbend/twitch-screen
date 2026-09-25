package twitchscreen.relay.device

import java.io.{BufferedInputStream, IOException, OutputStream}
import java.net.{Socket, SocketTimeoutException}
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.{Channel, ChannelClosed, Source}
import ox.either.catching
import scala.annotation.tailrec
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import twitchscreen.relay.config.DeviceLinkConfig
import twitchscreen.relay.protocol.*

/** One device connection, from `HELLO` to teardown — TSB/3 as specified in `twitch-screen-firmware/docs/PROTOCOL.md`.
  *
  * The session owns the socket and both directions of it. The reader runs in the session's own thread and its return value is the reason
  * the link ended; the writer and the heartbeat are forks that the session's scope cancels. The single way either side ends the other is
  * closing the socket: a socket read on a virtual thread is interruptible, so closing it unblocks the reader immediately instead of waiting
  * out the idle timeout.
  *
  * Three things this rewrite is for, beyond the change of encoding:
  *
  *   - **Every refusal now says why.** v2 closed the socket silently, so a device could not tell a version mismatch from a crashed relay
  *     and never applied the 30 s backoff floor that a mismatch needs. §6.7's `BYE` costs thirty-two bytes, once, at teardown, and is
  *     always stamped with the version byte of the frame that provoked it — which is what lets a device read a refusal from a relay whose
  *     version it does not speak.
  *   - **§4.6's two-tier rule, the right way round.** A malformed *frame* must not kill the link (§4.3: skip it, count it, keep reading); a
  *     malformed *stream* must (§4.4/§4.5: resynchronise by one byte until the budget runs out, then `BYE(5)` and close). v2 had this
  *     inverted — it tolerated bad frames forever and closed hard, with no `BYE`, on an over-long one.
  *   - **A `PONG` is never queued.** §6.3 requires it be sent as soon as the reader returns, not coalesced or deferred behind other work.
  *     It is written straight to the socket from the reader thread, so a device that has fallen behind on events still gets its pongs and
  *     does not declare the link dead and re-trigger the burst it was already behind on.
  */
private[device] object DeviceSession:
  private val logger = LoggerFactory.getLogger(getClass)

  /** §7: a version mismatch needs a reflash, not a retry. Hammering the relay buries the one log line that explains it. */
  private val VersionMismatchBackoff: FiniteDuration = 30.seconds

  def run(socket: Socket, config: DeviceLinkConfig, hub: DeviceHub, clock: Clock): Unit =
    val remote = String.valueOf(socket.getRemoteSocketAddress)
    supervised:
      try
        prepare(socket)
        val counters = LinkCounters(clock)
        val sink = FrameSink(socket.getOutputStream, counters)
        forkDiscard:
          forever:
            sleep(50.millis)
            if sink.writeOverdue(config.idleTimeout) then closeQuietly(socket)
        // Buffered so that a 176-byte EVENT does not cost one syscall per field; the reader still accumulates,
        // because a buffered stream short-counts exactly as an unbuffered one does (§2).
        val source = BufferedInputStream(socket.getInputStream, Tsb3.MaxFrame)
        val reader = FrameReader(source, math.min(config.maxFrameLength - Tsb3.HeaderSize, Tsb3.MaxPayload), counters)
        // §11.1 rule 2 / §12: both timeouts are budgets for one COMPLETE frame, not per-read waits. SO_TIMEOUT alone restarts
        // on every byte, so a peer trickling one byte every few seconds could hold the handshake, or a half-sent frame, open forever.
        def budget(timeout: FiniteDuration) = FrameBudget(timeout, millis => socket.setSoTimeout(millis))
        handshake(reader, budget(config.handshakeTimeout), config, counters) match
          case Left(refusal) =>
            logger.info(s"Refused device at $remote: ${refusal.reason.describe}")
            refuse(sink, refusal)
          case Right(accepted) =>
            serve(socket, reader, budget(config.idleTimeout), sink, accepted, remote, config, hub, clock, counters)
      finally
        // Closed here in the scope body rather than through a scope finalizer: a finalizer runs only once the scope
        // has joined its forks, and unblocking those forks is exactly what this close is for.
        closeQuietly(socket)

  private def prepare(socket: Socket): Unit =
    socket.setTcpNoDelay(true) // notifications are small and latency-sensitive; Nagle would add up to 40 ms
    socket.setKeepAlive(true) // second net under the application heartbeat, per §12

  /** The accepted `HELLO`, the capabilities negotiated from it, and the text policy those capabilities imply. */
  private final case class Handshake(hello: DeviceMessage.Hello, caps: Capabilities, text: TextPolicy)

  /** Why a device was turned away, and the `BYE` it is owed. `version` is the version byte of the frame that provoked the refusal (§6.7),
    * not this relay's — a device speaking v4 must be able to read the refusal.
    */
  private final case class Refusal(
      reason: DisconnectReason,
      bye: Option[(ByeCode, ByeDetail)],
      version: ProtocolVersion,
      retryAfter: FiniteDuration = 0.seconds
  )

  /** §11.2: before `WELCOME` has been exchanged, §4.3's "skip the frame and continue" does not apply. The first inbound frame must be a
    * valid `HELLO`; anything else — wrong type, short payload, wrong version, invalid device id, an `rx_max` below 256 — is fatal and
    * answered with a `BYE` and a close.
    */
  private def handshake(
      reader: FrameReader,
      budget: FrameBudget,
      config: DeviceLinkConfig,
      counters: LinkCounters
  ): Either[Refusal, Handshake] =
    readFrame(reader, budget) match
      case Left(Inbound.Timeout) =>
        Left(Refusal(DisconnectReason.HandshakeTimeout, Some((ByeCode.HandshakeTimeout, ByeDetail.Zero)), Tsb3.Version))
      case Left(Inbound.Closed(reason)) => Left(Refusal(reason, None, Tsb3.Version))
      case Left(Inbound.Broken(error))  => Left(framingRefusal(error, Tsb3.Version))
      case Right(frame) =>
        counters.recordReceived(frame.header.frameSize)
        val version = frame.header.version
        Tsb3Decoder.fromDevice(frame) match
          case Right(hello: DeviceMessage.Hello) =>
            val caps = Capabilities.RelaySupported.intersect(hello.caps)
            logger.debug(
              s"Device ${hello.deviceId.value} on firmware '${WireStrings.sanitise(hello.fwVersion.value)}' asked for caps 0x${hello.caps.value.toHexString}, " +
                s"granted 0x${caps.value.toHexString}, rx_max ${hello.rxMax.value}"
            )
            Right(Handshake(hello, caps, TextPolicy.forCapabilities(caps)))

          case Right(other) =>
            Left(
              Refusal(
                DisconnectReason.HandshakeRejected(s"expected HELLO, got ${other.messageType}"),
                Some((ByeCode.BadHandshake, ByeDetail.of(other.messageType.code))),
                version
              )
            )

          case Left(error @ ProtocolError.UnsupportedVersion(received, _)) =>
            Left(
              Refusal(
                DisconnectReason.HandshakeRejected(error.describe),
                // detail is the version THIS relay speaks, so the device can log the gap from both sides (§6.7).
                Some((ByeCode.UnsupportedVersion, ByeDetail.of(config.protocolVersion))),
                ProtocolVersion.fromWire(received.toByte),
                VersionMismatchBackoff
              )
            )

          case Left(error) =>
            // Anything else in the handshake window is a bad handshake, whatever §4.3 would have made of it later.
            val advice = error.byeAdvice.orElse(Some((ByeCode.BadHandshake, ByeDetail.of(frame.header.typeCode.value))))
            Left(Refusal(DisconnectReason.HandshakeRejected(error.describe), advice, version))

  private def serve(
      socket: Socket,
      reader: FrameReader,
      idle: FrameBudget,
      sink: FrameSink,
      handshake: Handshake,
      remote: String,
      config: DeviceLinkConfig,
      hub: DeviceHub,
      clock: Clock,
      counters: LinkCounters
  )(using Ox): Unit =
    val hello = handshake.hello
    val outbound = Channel.buffered[Outbound](config.outboundQueueCapacity)
    val drained = Channel.buffered[Unit](1)
    val connection = hub.attach(
      AttachRequest(hello.deviceId, remote, config.protocolVersion, hello.lastSeq, handshake.caps, outbound, counters, socket, drained)
    )
    logger.info(s"Device ${hello.deviceId.value} attached as #${connection.value} from $remote (last_seq=${hello.lastSeq.value})")
    try
      forkDiscard:
        try writeLoop(socket, sink, outbound, handshake.text, config, counters)
        finally drained.doneOrClosed().discard
      forkDiscard(heartbeat(outbound, config, clock))
      hub.detach(connection, readLoop(reader, idle, sink, counters, config, handshake.caps))
    finally outbound.doneOrClosed().discard

  /** Drains the outbound queue onto the socket. A failed write closes the socket, which is what ends the session.
    *
    * §10.1 puts the encoding here rather than in the hub: a per-frame buffer inside the hub would break the ordering guarantee that makes
    * sequence numbers mean anything, and the text policy is per connection anyway — one device may have a UTF-8 font while another does not
    * (§9.3).
    */
  private def writeLoop(
      socket: Socket,
      sink: FrameSink,
      outbound: Source[Outbound],
      text: TextPolicy,
      config: DeviceLinkConfig,
      counters: LinkCounters
  ): Unit =
    repeatWhile:
      outbound.receiveOrClosed() match
        case frame: Outbound =>
          val encoded = Tsb3Encoder.toDevice(frame.message, frame.flags, text)
          if encoded.length > config.maxFrameLength then
            // §5: a sender that cannot fit a message drops it and counts it. It never fragments and never emits a
            // partial frame. Unreachable while EVENT is 176 bytes against a 256 byte limit, and kept so that it
            // stays unreachable if either number ever moves.
            counters.recordDropped()
            logger.warn(s"Dropped a ${frame.message.messageType} frame of ${encoded.length} bytes, past the ${config.maxFrameLength} limit")
            true
          else if (
              frame.message match
                case _: RelayMessage.Bye => sink.writeFinal(encoded)
                case _                   => sink.write(encoded)
            )
          then true
          else
            closeQuietly(socket)
            false
        case ChannelClosed.Done =>
          // §6.7: the hub closes the queue after queueing a final `BYE` — a device id reclaimed by a newer connection, or the
          // relay shutting down. Draining it and then closing the socket is what turns that queued frame into a delivered one
          // and ends this session; without the close the reader would sit here until the 90 s idle timeout.
          closeQuietly(socket)
          false
        case ChannelClosed.Error(cause) => throw cause

  /** Both peers ping periodically and answer promptly; either side can detect a half-open connection (§12).
    */
  private def heartbeat(outbound: Channel[Outbound], config: DeviceLinkConfig, clock: Clock): Unit =
    forever:
      sleep(config.pingInterval)
      outbound.trySendOrClosed(Outbound(RelayMessage.Ping(Token.fromWire(clock.instant().getEpochSecond)))).discard

  @tailrec
  private def readLoop(
      reader: FrameReader,
      idle: FrameBudget,
      sink: FrameSink,
      counters: LinkCounters,
      config: DeviceLinkConfig,
      caps: Capabilities
  ): DisconnectReason =
    readFrame(reader, idle) match
      case Left(Inbound.Timeout)        => DisconnectReason.IdleTimeout
      case Left(Inbound.Closed(reason)) => reason
      case Left(Inbound.Broken(error))  =>
        // §4.4/§4.5: the framing invariant itself is broken and the resync budget is spent. Best-effort BYE, then close.
        val refusal = framingRefusal(error, Tsb3.Version)
        refuse(sink, refusal)
        refusal.reason
      case Right(frame) =>
        // §12: any inbound frame of any type resets the idle timer, including one that is about to be skipped.
        counters.recordReceived(frame.header.frameSize)
        handle(frame, sink, counters, config, caps) match
          case Some(reason) => reason
          case None         => readLoop(reader, idle, sink, counters, config, caps)

  private def handle(
      frame: Frame,
      sink: FrameSink,
      counters: LinkCounters,
      config: DeviceLinkConfig,
      caps: Capabilities
  ): Option[DisconnectReason] =
    if !frame.header.version.isCurrent then
      val refusal = Refusal(
        DisconnectReason.ProtocolViolation(s"frame carried version ${frame.header.version.value}"),
        Some((ByeCode.UnsupportedVersion, ByeDetail.of(config.protocolVersion))),
        frame.header.version,
        VersionMismatchBackoff
      )
      refuse(sink, refusal)
      Some(refusal.reason)
    else if frame.header.typeCode.known.contains(MessageType.Hello) then duplicateHello(sink, frame)
    else
      Tsb3Decoder.fromDevice(frame) match
        case Right(DeviceMessage.Ping(token)) =>
          // §6.3: answered as soon as the reader returns, straight onto the socket. Queueing it behind the outbound
          // backlog is how a device that has fallen behind stops getting pongs, declares the link dead, reconnects,
          // and re-triggers the very burst it was behind on.
          sink.write(Tsb3Encoder.toDevice(RelayMessage.Pong(token))).discard
          None

        case Right(DeviceMessage.Pong(_)) => None // liveness is already recorded by `counters.recordReceived`

        case Right(DeviceMessage.Ack(seq)) =>
          // §6.6: informational. Delivery is never conditional on it and the relay never withholds events because of it.
          if caps.contains(Capabilities.Ack) then counters.recordAck(seq.value)
          None

        case Right(_: DeviceMessage.Hello) => duplicateHello(sink, frame)

        case Left(error) =>
          error.disposition match
            case ErrorDisposition.SkipFrame =>
              // §4.3: well-framed but not usable. Discard the payload, count it, keep the link. The resync budget
              // resets too, because the frame was correctly framed.
              counters.recordSkipped(error)
              logger.debug(s"Skipping an inbound frame: ${error.describe}")
              None
            case ErrorDisposition.CloseLink | ErrorDisposition.Resynchronize =>
              val refusal = Refusal(DisconnectReason.ProtocolViolation(error.describe), error.byeAdvice, frame.header.version)
              refuse(sink, refusal)
              Some(refusal.reason)

  private def duplicateHello(sink: FrameSink, frame: Frame): Option[DisconnectReason] =
    val refusal = Refusal(
      DisconnectReason.ProtocolViolation("a second HELLO on an established session"),
      Some((ByeCode.DuplicateHello, ByeDetail.Zero)),
      frame.header.version
    )
    refuse(sink, refusal)
    Some(refusal.reason)

  /** §6.7: `BYE` is advisory, best-effort and always the last frame on the connection. The sender closes immediately after writing it and
    * never waits for a reply, so a failed write here is not worth reporting.
    */
  private def refuse(sink: FrameSink, refusal: Refusal): Unit =
    refusal.bye.foreach: (code, detail) =>
      val bye = RelayMessage.Bye(code, detail, refusal.retryAfter, reasonFor(code))
      sink.writeFinal(Tsb3Encoder.toDevice(bye, version = refusal.version)).discard

  /** §6.7: `reason` is for logs only and is never parsed. Twenty-three content bytes, so each of these is written to fit. */
  private def reasonFor(code: ByeCode): String = code match
    case ByeCode.UnsupportedVersion => "relay speaks v3 only"
    case ByeCode.BadHandshake       => "expected HELLO first"
    case ByeCode.InvalidDeviceId    => "device id not usable"
    case ByeCode.FramingViolation   => "stream out of frame"
    case ByeCode.DuplicateHello     => "second HELLO"
    case ByeCode.ServerShutdown     => "relay shutting down"
    case ByeCode.Replaced           => "device id reclaimed"
    case ByeCode.RateLimit          => "too many frames"
    case ByeCode.InvalidParameter   => "HELLO field rejected"
    case ByeCode.HandshakeTimeout   => "no HELLO in time"
    // §6.7 forbids sending 4 and 6 and no refusal carries them; this arm only keeps the match exhaustive.
    case ByeCode.InvalidSequence | ByeCode.FrameTooLarge => s"reserved code ${code.value}"
    case ByeCode.Unknown(raw)                            => s"refused, code $raw"

  private def framingRefusal(error: ProtocolError, version: ProtocolVersion): Refusal =
    Refusal(DisconnectReason.FramingViolation(error.describe), error.byeAdvice, version)

  /** Turns the reader's blocking I/O into values. A read timeout is the idle timeout, not a failure; end of stream is the peer closing. */
  private def readFrame(reader: FrameReader, budget: FrameBudget): Either[Inbound, Frame] =
    reader
      .read(Some(budget))
      .catching[IOException]
      .fold(
        {
          case _: SocketTimeoutException => Left(Inbound.Timeout)
          case error => Left(Inbound.Closed(DisconnectReason.ReadFailed(Option(error.getMessage).getOrElse(error.getClass.getSimpleName))))
        },
        {
          case Right(frame)                          => Right(frame)
          case Left(_: ProtocolError.FrameTimeout)   => Left(Inbound.Timeout)
          case Left(ProtocolError.EndOfStream)       => Left(Inbound.Closed(DisconnectReason.PeerClosed))
          case Left(_: ProtocolError.TruncatedFrame) => Left(Inbound.Closed(DisconnectReason.PeerClosed))
          case Left(error)                           => Left(Inbound.Broken(error))
        }
      )

  private def closeQuietly(socket: Socket): Unit =
    socket.close().catching[IOException].left.foreach(error => logger.debug("Closing a device socket failed", error))

/** What one read attempt produced, when it did not produce a frame. */
private enum Inbound:
  /** No complete frame before the deadline: the handshake timeout before `WELCOME`, the idle timeout after it. */
  case Timeout

  /** The stream ended, cleanly or under our feet. There is nobody left to send a `BYE` to. */
  case Closed(reason: DisconnectReason)

  /** §4.4/§4.5: the framing invariant is broken past the resync budget. */
  case Broken(error: ProtocolError)

/** The one place bytes reach the socket, and therefore the one place they are serialised.
  *
  * Two threads legitimately write to a device: the writer fork draining the outbound queue, and the reader thread answering a `PING` the
  * moment it arrives (§6.3). Without this monitor those two could interleave halfway through a frame, which on a binary wire is
  * indistinguishable from corruption and would cost the device its resync budget. The monitor protects frame ordering and the final-BYE
  * guard; deadline observation stays outside it.
  */
private final class FrameSink(target: OutputStream, counters: LinkCounters):
  private val logger = LoggerFactory.getLogger(classOf[FrameSink])

  /** Set once a `BYE` has been written; guarded by this object's monitor like every write. */
  private var closed = false
  private val writeStarted = AtomicReference(Option.empty[Long])

  /** Read without the sink monitor so a blocked writer cannot hold its own deadline hostage. */
  def writeOverdue(limit: FiniteDuration): Boolean =
    writeStarted.get().exists(started => System.nanoTime() - started >= limit.toNanos)

  /** Returns false once the socket is gone, or once a `BYE` has gone out, which is the writer fork's signal to stop. §14: the byte count is
    * the true one, `8 + length`.
    */
  def write(frame: Array[Byte]): Boolean = synchronized:
    if closed then false
    else
      try
        writeStarted.set(Some(System.nanoTime()))
        target.write(frame)
        target.flush()
        counters.recordSent(frame.length)
        true
      catch
        case error: IOException =>
          logger.debug("Write to a device failed", error)
          false
      finally writeStarted.set(None)

  /** §6.7: `BYE` is always the last frame on the connection. Every write after this one is refused, so an `EVENT`, `STATS` or `PING` still
    * queued for the writer fork can never follow it onto the wire; the writer then closes the socket, as a refused write always does.
    */
  def writeFinal(frame: Array[Byte]): Boolean = synchronized:
    val written = write(frame)
    closed = true
    written
