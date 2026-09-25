package twitchscreen.relay.device

import java.io.{Closeable, IOException}
import java.time.{Clock, Instant}
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.{Actor, ActorRef, Channel, ChannelClosed}
import ox.either.catching
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.{ChatNotifications, DeviceLinkConfig}
import twitchscreen.relay.protocol.*

/** One frame on its way to one device: the message, and the header flags it carries.
  *
  * Domain values rather than bytes, because §10.1 forbids encoding inside the hub — a per-frame buffer there would break the ordering
  * guarantee that makes sequence numbers mean anything. Each session's writer fork encodes with that connection's own text policy, which is
  * also what lets one device be sent UTF-8 verbatim while another is sent a transliteration (§9.3).
  */
private[device] final case class Outbound(message: RelayMessage, flags: FrameFlags = FrameFlags.Empty):
  // Evaluated by session writers, never the actor; same-policy fan-out shares immutable encoded bytes.
  private lazy val verbatim = EncodedFrame(message, flags, TextPolicy.Verbatim)
  private lazy val folded = EncodedFrame(message, flags, TextPolicy.AsciiFolded)
  def encoded(policy: TextPolicy): EncodedFrame = policy match
    case TextPolicy.Verbatim    => verbatim
    case TextPolicy.AsciiFolded => folded

/** §10.1: the `u32` sequence space is spent. The relay never wraps; a restart begins a new sequence space, which is what devices
  * re-baseline on. `latest` is the last seq that was assigned, and remains the high-water mark every `WELCOME` reports.
  */
private[relay] final case class SequenceExhausted(latest: SeqNo)

/** Everything a session hands to the hub when its handshake has succeeded. */
private[device] final case class AttachRequest(
    device: DeviceId,
    remoteAddress: String,
    protocolVersion: ProtocolVersion,
    lastSeq: SeqNo,
    /** The effective capability intersection of this connection (§6.1), already negotiated by the session. Replayed and live events are
      * filtered by it identically, so a device without `CAP_CHAT` is not buried in replayed chat on reconnect.
      */
    caps: Capabilities,
    outbound: Channel[Outbound],
    counters: LinkCounters,
    connection: Closeable,
    drained: Channel[Unit]
)

/** The relay's fan-out point: it assigns sequence numbers, keeps the replay buffers, and knows every attached device.
  *
  * Every operation runs on the actor's single thread, which is what makes sequence numbers monotonic *on the wire*: two events published at
  * the same moment cannot interleave their `EVENT` frames, so a device never sees a lower seq after a higher one and never silently
  * discards a notification it has not shown.
  *
  * The actor therefore must never block. Frames are handed to a device with a non-blocking offer: a full EVENT queue closes that connection
  * before a later EVENT can cross the gap. Retained notifications remain eligible for best-effort replay; replaceable STATS frames may be
  * dropped.
  */
private[relay] final class DeviceHub private (
    state: ActorRef[DeviceHubState],
    connected: AtomicInteger,
    refused: AtomicLong,
    observed: AtomicReference[HubSnapshot]
):
  // Ox's bounded default mailbox admits 16 pending operations. ask confines operation failures to the caller,
  // while observe refreshes the telemetry snapshot even if an operation fails after changing state.
  private def command[A](operation: DeviceHubState => A): A = state.ask(_.observe(operation))

  /** Sequences an event and pushes it to every attached device whose capabilities allow it. Returns it with its assigned seq and id.
    *
    * Once the `u32` sequence space is spent (§10.1) every publication is refused with [[SequenceExhausted]]. A refusal leaves the actor,
    * the replay rings, `latestSeq`, the observed stats and the attached devices unchanged, and sends no frame; the hub reports the
    * exhaustion to the operator once, itself, so callers may discard the refusal.
    */
  def publish(request: EventRequest): Either[SequenceExhausted, Notification] = command(_.publish(request))

  /** A stream lifecycle card together with the stats the transition produced (§6.5). On a refusal the stats are not recorded either; the
    * caller still owes the devices that `STATS`, via [[broadcastStats]].
    */
  def publishTransition(request: EventRequest, stats: StreamStats): Either[SequenceExhausted, Notification] =
    command(_.publish(request, Some(stats)))

  /** §6.4.3: a posted card keeps its title and body and leaves every numeric field 0, whatever kind it names. */
  def publish(request: NotificationRequest): Either[SequenceExhausted, Notification] = publish(EventRequest.card(request))

  def broadcastStats(stats: StreamStats): Unit = command(_.updateStats(stats))

  def latestStats: StreamStats = state.ask(_.latestStats)

  def links: List[DeviceLink] = state.ask(_.links)

  def link(connection: ConnectionId): Option[DeviceLink] = state.ask(_.link(connection))

  /** Most recent notifications first, out of both replay rings. */
  def recentNotifications(limit: Int): List[Notification] = state.ask(_.recent(limit))

  /** The actor's last published snapshot, overlaid with the refusal count, which the listener records without going through the actor. */
  def snapshot: HubSnapshot = observed.get().copy(connectionsRefused = refused.get())

  /** Safe for telemetry callbacks even after the actor's scope has ended. */
  def connectedCount: Int = connected.get()

  /** Connections the listener closed at its session limit since start. Safe for telemetry callbacks even after the actor's scope has ended.
    */
  def connectionsRefused: Long = refused.get()

  /** Returns the running total. Never goes through the actor: the accept loop must not wait on the hub's mailbox. */
  private[device] def recordRefusedConnection(): Long = refused.incrementAndGet()

  /** §6.7 code 8: tells every attached device why it is about to lose the link, so a relay restart is one log line on the device instead of
    * a silent drop into the blind exponential ramp. `ask` rather than `tell`, so that the caller knows the frames are queued before the
    * application scope starts interrupting the forks that have to write them.
    */
  def shutdown(): Int =
    val devices = command(_.shutdown())
    AttachedDevice.drainThenClose(devices, DeviceHub.DrainLimit)
    devices.size

  /** Closes the socket, which unblocks that session's reader; the session then detaches itself. */
  def disconnect(connection: ConnectionId): Option[DeviceLink] =
    command(_.disconnect(connection)).map: device =>
      device.drainThenClose(DeviceHub.DrainLimit)
      device.link

  private[device] def attach(request: AttachRequest): ConnectionId = command(_.attach(request))

  private[device] def detach(connection: ConnectionId, reason: DisconnectReason): Unit =
    command(_.detach(connection, reason))

private[relay] object DeviceHub:
  /** How long the facade waits for a session's writer to flush its final `BYE` before closing the socket anyway. Shutdown shares one such
    * deadline across every device rather than granting it to each in turn.
    */
  private val DrainLimit: FiniteDuration = 2.seconds

  /** Production source of the §6.2 `session_id`: a fresh random value per hub, which is per relay process start. [[SessionId.fromWire]]
    * masks it to `u32`.
    */
  private[relay] val RandomSessionId: () => Long = () => ThreadLocalRandom.current().nextLong()

  def start(config: DeviceLinkConfig, chat: ChatNotifications, clock: Clock, bus: EventBus, sessionIdSource: () => Long = RandomSessionId)(
      using Ox
  ): DeviceHub =
    startingAt(config, chat, clock, bus, SeqNo.Zero, sessionIdSource)

  /** A hub whose counter already stands at `initialSequence`. Production always starts at zero; this is the seam that lets tests reach
    * §10.1's exhaustion without publishing four billion events. `sessionIdSource` is read exactly once, here.
    */
  private[relay] def startingAt(
      config: DeviceLinkConfig,
      chat: ChatNotifications,
      clock: Clock,
      bus: EventBus,
      initialSequence: SeqNo,
      sessionIdSource: () => Long = RandomSessionId
  )(using Ox): DeviceHub =
    val connected = AtomicInteger(0)
    val sessionId = SessionId.fromWire(sessionIdSource())
    // connectionsRefused is 0 here and in every actor snapshot: the facade overlays the live value.
    val initial = HubSnapshot(0, 0L, 0L, 0L, initialSequence, 0, StreamStats.Unknown, initialSequence.next.isEmpty)
    val observed = AtomicReference(initial)
    new DeviceHub(
      Actor.create(new DeviceHubState(config, chat, clock, bus, connected, initialSequence, sessionId, observed)),
      connected,
      AtomicLong(0),
      observed
    )

/** The hub's mutable state. The `var`s are safe because every method runs inside the actor that owns this instance; nothing else may hold a
  * reference to it.
  */
private[device] final class DeviceHubState(
    config: DeviceLinkConfig,
    chat: ChatNotifications,
    clock: Clock,
    bus: EventBus,
    connected: AtomicInteger,
    initialSequence: SeqNo,
    /** §6.2. Opaque, and new on every relay process start, because the sequence counter is not persisted across restarts.
      *
      * §10.2 deliberately does **not** baseline on it. An earlier draft had the device re-baseline whenever it changed, but `HELLO` carries
      * no `session_id` echo, so this relay has nothing to compare against and could not implement the matching half: the two sides then
      * used different tests for the same condition and, after a restart, this relay pushed a replay burst that the device discarded frame
      * by frame as duplicates. The baseline is `last_seq` against `latest_seq` on both sides. This value stays for the device's log, which
      * is where "the relay restarted under me" belongs.
      */
    sessionId: SessionId,
    observed: AtomicReference[HubSnapshot]
):
  private val logger = LoggerFactory.getLogger(classOf[DeviceHub])

  private var lastConnectionId: Long = 0
  private val sequences = SequenceAllocator(initialSequence)
  private def latestSequence: SeqNo = sequences.latest

  /** Exhaustion is reported once. The report is a `RelayFailure`, which the router turns into an `Alert` card that this hub then refuses;
    * without the latch that refusal would report again, and so on for ever.
    */
  private var exhaustionReported: Boolean = false

  private val replay = ReplayBuffers(config.replayBufferSize, DeviceLinkConfig.ChatReplaySize)
  private val reclaims = ReclaimPolicy()

  def observe[A](operation: DeviceHubState => A): A =
    try operation(this)
    finally observed.set(snapshot)

  private var attached: Map[ConnectionId, AttachedDevice] = Map.empty
  private var latestObservedStats: StreamStats = StreamStats.Unknown
  private var connectionsAccepted: Long = 0
  private var notificationsPublished: Long = 0
  private var stopping: Boolean = false

  def attach(request: AttachRequest): ConnectionId =
    val now = clock.instant()
    val connection = nextConnectionId()
    val replacing = attached.values.exists(_.device == request.device)
    if stopping then queueFinalBye(request.outbound, ByeCode.ServerShutdown, "relay shutting down")
    else if replacing && !reclaims.allow(request.device, now) then
      queueFinalBye(request.outbound, ByeCode.RateLimit, "reclaim rate exceeded", 60.seconds)
    else
      reclaim(request.device)
      val device = AttachedDevice(connection, request, now)
      attached = attached.updated(connection, device)
      connected.set(attached.size)
      connectionsAccepted += 1
      greet(device, request.lastSeq, now)
      bus.publish(RelayEvent.DeviceConnected(request.device, connection, request.remoteAddress))
    connection

  /** §6.7 code 9: one device id, one live connection.
    *
    * After a half-open socket — a Wi-Fi drop with no FIN — the device reconnects long before this relay's 90 s idle timeout notices the
    * corpse. Without this, both sessions sit in `attached` for the rest of that window: `GET /api/v1/devices` shows two rows for one
    * physical screen, every `EVENT` is encoded and written twice, and the dead one's 128-frame outbound queue fills up and starts counting
    * drops against a device that is working perfectly. Device IDs are unauthenticated claims on a trusted LAN; bounded reclaims prevent one
    * faulty or conflicting identity from replacing sessions indefinitely.
    */
  private def reclaim(device: DeviceId): Unit =
    attached.values
      .filter(_.device == device)
      .foreach: stale =>
        logger.info(s"Device ${device.value} reconnected; reclaiming it from connection #${stale.id.value}")
        attached = attached.removed(stale.id)
        connected.set(attached.size)
        // Queued rather than written here: §10.1 forbids the hub blocking, and this runs on the actor's single thread. The
        // session's writer fork drains the BYE and then closes the socket on `Done`, which is what ends the stale session.
        stale.queueFinalBye(ByeCode.Replaced, "device id reclaimed")
        bus.publish(RelayEvent.DeviceDisconnected(device, stale.id, DisconnectReason.Replaced.describe))

  def detach(connection: ConnectionId, reason: DisconnectReason): Unit =
    attached
      .get(connection)
      .foreach: device =>
        attached = attached.removed(connection)
        connected.set(attached.size)
        logger.info(s"Device ${device.device.value} (#${connection.value}) detached: ${reason.describe}")
        bus.publish(RelayEvent.DeviceDisconnected(device.device, connection, reason.describe))

  def publish(request: EventRequest, transition: Option[StreamStats] = None): Either[SequenceExhausted, Notification] =
    sequences.allocate() match
      case None => Left(refuse())
      case Some(seq) =>
        Right(sequenced(request, seq, transition))

  /** §10.1: never wrap, and never assign past `0xffffffff`. Nothing is mutated and nothing is sent. */
  private def refuse(): SequenceExhausted =
    if !exhaustionReported then
      exhaustionReported = true
      logger.error(
        s"Sequence space exhausted at ${latestSequence.value}; refusing EVENT publication until the relay is restarted (§10.1)"
      )
      bus.publish(RelayEvent.RelayFailure("device-hub", s"sequence space exhausted at ${latestSequence.value}; restart the relay"))
    else logger.debug(s"EVENT refused: sequence space exhausted at ${latestSequence.value}")
    SequenceExhausted(latestSequence)

  private def sequenced(request: EventRequest, seq: SeqNo, transition: Option[StreamStats]): Notification =
    val record = request.record(seq, clock.instant())
    replay.remember(record)
    notificationsPublished += 1
    transition.foreach(stats => latestObservedStats = stats)
    broadcast(RelayMessage.Event(record))
    if record.kind.isLifecycle && (transition.isDefined || latestObservedStats != StreamStats.Unknown)
    then
      // The stats fold supplies the lifecycle card and post-transition snapshot in this same actor operation.
      // Manually posted lifecycle cards leave the observed state unchanged.
      broadcast(RelayMessage.Stats(latestObservedStats, Some(clock.instant())))
    val notification = Notification.from(record)
    bus.publish(RelayEvent.NotificationPublished(notification))
    notification

  def updateStats(stats: StreamStats): Unit =
    latestObservedStats = stats
    broadcast(RelayMessage.Stats(stats, Some(clock.instant())))

  def latestStats: StreamStats = latestObservedStats

  def links: List[DeviceLink] = attached.values.map(_.link).toList.sortBy(_.connection.value)

  def link(connection: ConnectionId): Option[DeviceLink] = attached.get(connection).map(_.link)

  def recent(limit: Int): List[Notification] =
    replay.ordered.reverse.take(limit).map(Notification.from).toList

  def snapshot: HubSnapshot =
    HubSnapshot(
      connectedDevices = attached.size,
      connectionsAccepted = connectionsAccepted,
      connectionsRefused = 0L, // owned by the facade, which overlays the live value
      notificationsPublished = notificationsPublished,
      latestSeq = latestSequence,
      replayBuffered = replay.size,
      latestStats = latestObservedStats,
      sequenceExhausted = latestSequence.next.isEmpty
    )

  def disconnect(connection: ConnectionId): Option[AttachedDevice] =
    attached
      .get(connection)
      .map: device =>
        detach(connection, DisconnectReason.RequestedByOperator)
        device.queueFinalBye(ByeCode.ServerShutdown, "operator disconnected")
        device

  /** §6.7 code 8. Best effort by definition — `BYE` is advisory and the sender never waits for a reply — but a queued frame on a live
    * socket is drained within a bounded deadline before the facade closes every remaining socket.
    */
  def shutdown(): List[AttachedDevice] =
    stopping = true
    val devices = attached.values.toList
    devices.foreach: device =>
      device.queueFinalBye(ByeCode.ServerShutdown, "relay shutting down")
      bus.publish(RelayEvent.DeviceDisconnected(device.device, device.id, DisconnectReason.ListenerStopped.describe))
    if devices.nonEmpty then logger.info(s"Told ${devices.size} attached device(s) that the relay is shutting down")
    attached = Map.empty
    connected.set(0)
    devices

  /** §11.1's fixed greeting burst: `WELCOME`, then the replayed events in ascending `seq`, then exactly one `STATS`.
    *
    * §10.3's precondition is `0 < last_seq <= latest_seq`, which is the exact complement of §10.2's re-baseline test on the device. That is
    * the point of stating it as two numbers: both sides compute the same predicate from the same two values, so the relay replays on
    * precisely the greets for which the device kept its mark. A device reporting `last_seq = 0` has just booted and receives exactly two
    * frames; a device whose `last_seq` is ahead of anything this relay ever assigned is asking about a sequence space that no longer
    * exists, re-baselines on this `WELCOME`, and gets no replay it would only have thrown away.
    */
  private def greet(device: AttachedDevice, lastSeq: SeqNo, now: Instant): Unit =
    send(
      device,
      Outbound(
        RelayMessage.Welcome(
          latestSeq = latestSequence,
          serverTime = Some(now),
          sessionId = sessionId,
          maxFrame = FrameSize.Default,
          pingInterval = config.pingInterval,
          idleTimeout = config.idleTimeout,
          replayWindow = ReplayWindow.of(config.replayBufferSize),
          caps = device.caps
        )
      )
    )
    if lastSeq.isAfter(SeqNo.Zero) && !lastSeq.isAfter(latestSequence) then
      replay
        .after(lastSeq)
        .foreach(record => send(device, Outbound(RelayMessage.Event(record), FrameFlags.Replay)))
    send(device, Outbound(RelayMessage.Stats(latestObservedStats, Some(now))))

  private def broadcast(message: RelayMessage): Unit =
    val frame = Outbound(message)
    attached.values.foreach(send(_, frame))

  private def send(device: AttachedDevice, frame: Outbound): Unit =
    if attached.contains(device.id) && device.wants(frame.message, chat) then
      device.offer(frame) match
        case Offer.Overflowed =>
          // The device has already closed its queue and transport. The session's own detach arrives through the mailbox after this
          // operation and finds nothing to remove, so OutboundOverflow is the reason the bus reports.
          detach(device.id, DisconnectReason.OutboundOverflow)
          logger.warn(s"Device ${device.device.value} (#${device.id.value}) closed after EVENT queue overflow")
        case Offer.Accepted | Offer.DroppedReplaceable | Offer.Closed => ()

  private def nextConnectionId(): ConnectionId =
    lastConnectionId += 1
    ConnectionId(lastConnectionId)

/** What happened to one frame offered to one device's outbound queue. */
private[device] enum Offer:
  /** Queued for the session's writer. */
  case Accepted

  /** The queue was full and the frame was replaceable telemetry: counted as dropped, and the link is kept. */
  case DroppedReplaceable

  /** The queue was full and the frame was an `EVENT`: counted as dropped, the queue is done and the transport is closed. */
  case Overflowed

  /** The session is already tearing down and will detach in a moment. Nothing is recorded. */
  case Closed

/** One live connection as the hub sees it. It owns its outbound queue, counters and transport, so the hub only asks it to take a frame and
  * the facade only asks it to drain and close.
  */
private[device] final class AttachedDevice(val id: ConnectionId, request: AttachRequest, val connectedAt: Instant):
  val device: DeviceId = request.device
  val caps: Capabilities = request.caps
  private val outbound: Channel[Outbound] = request.outbound
  private val counters: LinkCounters = request.counters
  private val connection: Closeable = request.connection
  private val drained: Channel[Unit] = request.drained

  def link: DeviceLink =
    DeviceLink(id, device, request.remoteAddress, connectedAt, request.protocolVersion.value, request.lastSeq.value, counters.traffic)

  def wants(message: RelayMessage, chat: ChatNotifications): Boolean = message match
    case RelayMessage.Event(record) if !record.kind.isDurable =>
      chat == ChatNotifications.Show && caps.contains(Capabilities.Chat)
    case RelayMessage.Event(record) if record.kind.isGeneric => caps.contains(Capabilities.Generic)
    case _                                                   => true

  /** Non-blocking, because it runs on the hub's actor thread (§10.1). */
  def offer(frame: Outbound): Offer =
    outbound.trySendOrClosed(frame) match
      case true => Offer.Accepted
      case false =>
        counters.recordDropped()
        frame.message match
          case _: RelayMessage.Event =>
            // Never let a later event move the peer's high-water mark across this gap.
            outbound.doneOrClosed().discard
            closeTransport()
            Offer.Overflowed
          case _ => Offer.DroppedReplaceable // Telemetry is replaceable. Every drop is counted, without per-frame logging.
      case _: ChannelClosed => Offer.Closed

  def queueFinalBye(code: ByeCode, reason: String): Unit =
    twitchscreen.relay.device.queueFinalBye(outbound, code, reason)

  /** Waits up to `limit` for the session's writer to flush what is queued, then closes the transport whether or not it did. */
  def drainThenClose(limit: FiniteDuration): Unit = AttachedDevice.drainThenClose(List(this), limit)

  private def closeTransport(): Unit = connection.close().catching[IOException].discard

private[device] object AttachedDevice:
  /** One deadline shared by every device, not one per device, then every transport is closed even if the deadline expired. */
  def drainThenClose(devices: Seq[AttachedDevice], limit: FiniteDuration): Unit =
    try timeoutOption(limit)(devices.foreach(_.drained.receiveOrClosed().discard)).discard
    finally devices.foreach(_.closeTransport())

private def queueFinalBye(
    outbound: Channel[Outbound],
    code: ByeCode,
    reason: String,
    retry: FiniteDuration = 0.seconds
): Unit =
  outbound.trySendOrClosed(Outbound(RelayMessage.Bye(code, ByeDetail.Zero, retry, reason))).discard
  outbound.doneOrClosed().discard
