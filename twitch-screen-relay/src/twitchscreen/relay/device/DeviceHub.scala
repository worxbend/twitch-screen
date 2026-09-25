package twitchscreen.relay.device

import java.io.{Closeable, IOException}
import java.time.{Clock, Instant}
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.{Actor, ActorRef, Channel, ChannelClosed}
import ox.either.catching
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.{ChatNotifications, DeviceLinkConfig}
import twitchscreen.relay.protocol.*

/** One frame on its way to one device: the message, and the header flags it carries.
  *
  * Domain values rather than bytes, because §10.1 forbids encoding inside the hub — a per-frame buffer there would break the ordering
  * guarantee that makes sequence numbers mean anything. Each session's writer fork encodes with that connection's own text policy, which is
  * also what lets one device be sent UTF-8 verbatim while another is sent a transliteration (§9.3).
  */
private[device] final case class Outbound(message: RelayMessage, flags: FrameFlags = FrameFlags.Empty)

/** §10.1: the `u32` sequence space is spent. The relay never wraps; a restart begins a new sequence space, which is what devices
  * re-baseline on. `latest` is the last seq that was assigned, and remains the high-water mark every `WELCOME` reports.
  */
private[relay] final case class SequenceExhausted(latest: SeqNo)

/** Everything a session hands to the hub when its handshake has succeeded. */
private[device] final case class AttachRequest(
    device: DeviceId,
    remoteAddress: String,
    protocolVersion: Int,
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
private[relay] final class DeviceHub private (state: ActorRef[DeviceHubState], connected: AtomicInteger):
  /** Sequences an event and pushes it to every attached device whose capabilities allow it. Returns it with its assigned seq and id.
    *
    * Once the `u32` sequence space is spent (§10.1) every publication is refused with [[SequenceExhausted]]. A refusal leaves the actor,
    * the replay rings, `latestSeq`, the observed stats and the attached devices unchanged, and sends no frame; the hub reports the
    * exhaustion to the operator once, itself, so callers may discard the refusal.
    */
  def publish(request: EventRequest): Either[SequenceExhausted, Notification] = state.ask(_.publish(request))

  /** A stream lifecycle card together with the stats the transition produced (§6.5). On a refusal the stats are not recorded either; the
    * caller still owes the devices that `STATS`, via [[broadcastStats]].
    */
  def publishTransition(request: EventRequest, stats: StreamStats): Either[SequenceExhausted, Notification] =
    state.ask(_.publish(request, Some(stats)))

  /** §6.4.3: a posted card keeps its title and body and leaves every numeric field 0, whatever kind it names. */
  def publish(request: NotificationRequest): Either[SequenceExhausted, Notification] = publish(EventRequest.card(request))

  def broadcastStats(stats: StreamStats): Unit = state.tell(_.updateStats(stats))

  def latestStats: StreamStats = state.ask(_.latestStats)

  def links: List[DeviceLink] = state.ask(_.links)

  def link(connection: ConnectionId): Option[DeviceLink] = state.ask(_.link(connection))

  /** Most recent notifications first, out of both replay rings. */
  def recentNotifications(limit: Int): List[Notification] = state.ask(_.recent(limit))

  def snapshot: HubSnapshot = state.ask(_.snapshot)

  /** Safe for telemetry callbacks even after the actor's scope has ended. */
  def connectedCount: Int = connected.get()

  /** §6.7 code 8: tells every attached device why it is about to lose the link, so a relay restart is one log line on the device instead of
    * a silent drop into the blind exponential ramp. `ask` rather than `tell`, so that the caller knows the frames are queued before the
    * application scope starts interrupting the forks that have to write them.
    */
  def shutdown(): Int =
    val devices = state.ask(_.shutdown())
    try
      timeoutOption(2.seconds):
        devices.foreach(_.drained.receiveOrClosed().discard)
      .discard
    finally devices.foreach(_.connection.close().catching[IOException].discard)
    devices.size

  /** Closes the socket, which unblocks that session's reader; the session then detaches itself. */
  def disconnect(connection: ConnectionId): Option[DeviceLink] = state.ask(_.disconnect(connection))

  private[device] def attach(request: AttachRequest): ConnectionId = state.ask(_.attach(request))

  private[device] def detach(connection: ConnectionId, reason: DisconnectReason): Unit =
    state.tell(_.detach(connection, reason))

private[relay] object DeviceHub:
  def start(config: DeviceLinkConfig, chat: ChatNotifications, clock: Clock, bus: EventBus)(using Ox): DeviceHub =
    startingAt(config, chat, clock, bus, SeqNo.Zero)

  /** A hub whose counter already stands at `initialSequence`. Production always starts at zero; this is the seam that lets tests reach
    * §10.1's exhaustion without publishing four billion events.
    */
  private[relay] def startingAt(config: DeviceLinkConfig, chat: ChatNotifications, clock: Clock, bus: EventBus, initialSequence: SeqNo)(
      using Ox
  ): DeviceHub =
    val connected = AtomicInteger(0)
    new DeviceHub(Actor.create(new DeviceHubState(config, chat, clock, bus, connected, initialSequence)), connected)

/** The hub's mutable state. The `var`s are safe because every method runs inside the actor that owns this instance; nothing else may hold a
  * reference to it.
  */
private[device] final class DeviceHubState(
    config: DeviceLinkConfig,
    chat: ChatNotifications,
    clock: Clock,
    bus: EventBus,
    connected: AtomicInteger,
    initialSequence: SeqNo
):
  private val logger = LoggerFactory.getLogger(classOf[DeviceHub])

  /** §6.2. Opaque, and new on every relay process start, because the sequence counter is not persisted across restarts.
    *
    * §10.2 deliberately does **not** baseline on it. An earlier draft had the device re-baseline whenever it changed, but `HELLO` carries
    * no `session_id` echo, so this relay has nothing to compare against and could not implement the matching half: the two sides then used
    * different tests for the same condition and, after a restart, this relay pushed a replay burst that the device discarded frame by frame
    * as duplicates. The baseline is `last_seq` against `latest_seq` on both sides. This value stays for the device's log, which is where
    * "the relay restarted under me" belongs.
    */
  private val sessionId: SessionId = SessionId.fromWire(ThreadLocalRandom.current().nextLong() & 0xffffffffL)

  private var lastConnectionId: Long = 0
  private var latestSequence: SeqNo = initialSequence

  /** Exhaustion is reported once. The report is a `RelayFailure`, which the router turns into an `Alert` card that this hub then refuses;
    * without the latch that refusal would report again, and so on for ever.
    */
  private var exhaustionReported: Boolean = false

  /** §10.3's two rings. The split exists so that a busy chat cannot evict follows, raids and subs from the buffer; it does not create an
    * unsequenced or unreplayable kind, because a kind skipped by replay lets a later event hold the high-water mark past a lost durable
    * one. On greet the two are merged into one ascending `seq` order.
    */
  private var durableReplay: Vector[EventRecord] = Vector.empty
  private var chatReplay: Vector[EventRecord] = Vector.empty

  private var attached: Map[ConnectionId, AttachedDevice] = Map.empty
  private var latestObservedStats: StreamStats = StreamStats.Unknown
  private var connectionsAccepted: Long = 0
  private var notificationsPublished: Long = 0
  private var recentReclaims: Vector[Instant] = Vector.empty
  private var stopping: Boolean = false

  def attach(request: AttachRequest): ConnectionId =
    val now = clock.instant()
    val connection = nextConnectionId()
    recentReclaims = recentReclaims.dropWhile(_.isBefore(now.minusSeconds(60)))
    val replacing = attached.values.exists(_.device == request.device)
    if stopping then
      request.outbound
        .trySendOrClosed(Outbound(RelayMessage.Bye(ByeCode.ServerShutdown, ByeDetail.Zero, 0.seconds, "relay shutting down")))
        .discard
      request.outbound.doneOrClosed().discard
    else if replacing && recentReclaims.size >= 16 then
      request.outbound
        .trySendOrClosed(Outbound(RelayMessage.Bye(ByeCode.RateLimit, ByeDetail.Zero, 60.seconds, "reclaim rate exceeded")))
        .discard
      request.outbound.doneOrClosed().discard
    else
      if replacing then recentReclaims = recentReclaims :+ now
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
        stale.outbound
          .trySendOrClosed(Outbound(RelayMessage.Bye(ByeCode.Replaced, ByeDetail.Zero, 0.seconds, "device id reclaimed")))
          .discard
        stale.outbound.doneOrClosed().discard
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
    latestSequence.next match
      case None => Left(refuse())
      case Some(seq) =>
        latestSequence = seq
        Right(sequenced(request, transition))

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

  private def sequenced(request: EventRequest, transition: Option[StreamStats]): Notification =
    val record = request.record(latestSequence, clock.instant())
    remember(record)
    notificationsPublished += 1
    transition.foreach(stats => latestObservedStats = stats)
    broadcast(RelayMessage.Event(record))
    if (record.kind == NotificationKind.StreamStart || record.kind == NotificationKind.StreamEnd) && (transition.isDefined || latestObservedStats != StreamStats.Unknown)
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
    (durableReplay ++ chatReplay).sortBy(_.seq.value).reverse.take(limit).map(Notification.from).toList

  def snapshot: HubSnapshot =
    HubSnapshot(
      connectedDevices = attached.size,
      connectionsAccepted = connectionsAccepted,
      notificationsPublished = notificationsPublished,
      latestSeq = latestSequence,
      replayBuffered = durableReplay.size + chatReplay.size,
      latestStats = latestObservedStats,
      sequenceExhausted = latestSequence.next.isEmpty
    )

  def disconnect(connection: ConnectionId): Option[DeviceLink] =
    attached
      .get(connection)
      .map: device =>
        detach(connection, DisconnectReason.RequestedByOperator)
        device.connection
          .close()
          .catching[IOException]
          .left
          .foreach(e => logger.debug(s"Closing connection #${connection.value} failed", e))
        device.link

  /** §6.7 code 8. Best effort by definition — `BYE` is advisory and the sender never waits for a reply — but a queued frame on a live
    * socket is drained within a bounded deadline before the facade closes every remaining socket.
    */
  def shutdown(): List[AttachedDevice] =
    stopping = true
    val devices = attached.values.toList
    devices.foreach: device =>
      device.outbound
        .trySendOrClosed(Outbound(RelayMessage.Bye(ByeCode.ServerShutdown, ByeDetail.Zero, 0.seconds, "relay shutting down")))
        .discard
      device.outbound.doneOrClosed().discard
      bus.publish(RelayEvent.DeviceDisconnected(device.device, device.id, DisconnectReason.ListenerStopped.describe))
    if devices.nonEmpty then logger.info(s"Told ${devices.size} attached device(s) that the relay is shutting down")
    attached = Map.empty
    connected.set(0)
    devices

  private def remember(record: EventRecord): Unit =
    if record.kind == NotificationKind.Chat then chatReplay = (chatReplay :+ record).takeRight(DeviceHubState.ChatReplaySize)
    else durableReplay = (durableReplay :+ record).takeRight(config.replayBufferSize)

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
      (durableReplay ++ chatReplay)
        .filter(_.seq.isAfter(lastSeq))
        .sortBy(_.seq.value)
        .foreach(record => send(device, Outbound(RelayMessage.Event(record), FrameFlags.Replay)))
    send(device, Outbound(RelayMessage.Stats(latestObservedStats, Some(now))))

  private def broadcast(message: RelayMessage): Unit = attached.values.foreach(send(_, Outbound(message)))

  /** §6.1: `CAP_CHAT` and the relay's own `notifications.chat` setting are ANDed, and the device's bit can only narrow, never widen.
    * Neither switch affects `STATS.msg_total` or `STATS.chat_rate`, which are folded upstream from every non-bot message.
    */
  private def wants(device: AttachedDevice, message: RelayMessage): Boolean = message match
    case RelayMessage.Event(record) if record.kind == NotificationKind.Chat =>
      chat == ChatNotifications.Show && device.caps.contains(Capabilities.Chat)
    case RelayMessage.Event(record) if DeviceHubState.GenericKinds.contains(record.kind) =>
      device.caps.contains(Capabilities.Generic)
    case _ => true

  private def send(device: AttachedDevice, frame: Outbound): Unit =
    if attached.contains(device.id) && wants(device, frame.message) then
      device.outbound.trySendOrClosed(frame) match
        case accepted: Boolean =>
          if !accepted then
            device.counters.recordDropped()
            frame.message match
              case _: RelayMessage.Event =>
                // Never let a later event move the peer's high-water mark across this gap.
                detach(device.id, DisconnectReason.OutboundOverflow)
                device.outbound.doneOrClosed().discard
                device.connection.close().catching[IOException].discard
                logger.warn(s"Device ${device.device.value} (#${device.id.value}) closed after EVENT queue overflow")
              case _ => () // Telemetry is replaceable. Every drop is counted, without per-frame logging.
        case _: ChannelClosed => () // the session is tearing down and will detach in a moment

  private def nextConnectionId(): ConnectionId =
    lastConnectionId += 1
    ConnectionId(lastConnectionId)

private[device] object DeviceHubState:
  /** §5, §10.3: a ring of its own, so that a busy chat cannot evict a follow, a raid or a sub from the durable one. */
  val ChatReplaySize: Int = DeviceLinkConfig.ChatReplaySize

  /** §6.1's `CAP_GENERIC`: the kinds `0x00`…`0x03` a device may decline to render. */
  val GenericKinds: Set[NotificationKind] =
    Set(NotificationKind.Info, NotificationKind.Message, NotificationKind.Warning, NotificationKind.Alert)

private[device] final class AttachedDevice(val id: ConnectionId, request: AttachRequest, val connectedAt: Instant):
  val device: DeviceId = request.device
  val outbound: Channel[Outbound] = request.outbound
  val counters: LinkCounters = request.counters
  val connection: Closeable = request.connection
  val caps: Capabilities = request.caps
  val drained: Channel[Unit] = request.drained

  def link: DeviceLink =
    DeviceLink(id, device, request.remoteAddress, connectedAt, request.protocolVersion, request.lastSeq.value, counters.traffic)
