package twitchscreen.relay.device

import java.io.{Closeable, IOException}
import java.time.{Clock, Instant}
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.{Actor, ActorRef, Channel, ChannelClosed}
import ox.either.catching
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.DeviceLinkConfig
import twitchscreen.relay.protocol.*

/** Everything a session hands to the hub when its handshake has succeeded. */
private[device] final case class AttachRequest(
    device: DeviceId,
    remoteAddress: String,
    protocolVersion: Int,
    lastSeq: SeqNo,
    outbound: Channel[ServerFrame],
    counters: LinkCounters,
    connection: Closeable
)

/** The relay's fan-out point: it assigns sequence numbers, keeps the replay buffer, and knows every attached device.
  *
  * Every operation runs on the actor's single thread, which is what makes sequence numbers monotonic *on the wire*: two events published at
  * the same moment cannot interleave their `notify` frames, so a device never sees a lower seq after a higher one and never silently
  * discards a notification it has not shown.
  *
  * The actor therefore must never block. Frames are handed to a device with a non-blocking offer: a device whose queue is full loses that
  * frame — which the replay buffer recovers on its next reconnect — rather than freezing the hub.
  */
private[relay] final class DeviceHub private (state: ActorRef[DeviceHubState]):
  /** Sequences a notification and pushes it to every attached device. Returns it with its assigned seq and id. */
  def publish(request: NotificationRequest): Notification = state.ask(_.publish(request))

  def broadcastStats(stats: StreamStats): Unit = state.tell(_.updateStats(stats))

  def latestStats: StreamStats = state.ask(_.latestStats)

  def links: List[DeviceLink] = state.ask(_.links)

  def link(connection: ConnectionId): Option[DeviceLink] = state.ask(_.link(connection))

  /** Most recent notifications first, out of the replay buffer. */
  def recentNotifications(limit: Int): List[Notification] = state.ask(_.recent(limit))

  def snapshot: HubSnapshot = state.ask(_.snapshot)

  /** Closes the socket, which unblocks that session's reader; the session then detaches itself. */
  def disconnect(connection: ConnectionId): Option[DeviceLink] = state.ask(_.disconnect(connection))

  private[device] def attach(request: AttachRequest): ConnectionId = state.ask(_.attach(request))

  private[device] def detach(connection: ConnectionId, reason: DisconnectReason): Unit =
    state.tell(_.detach(connection, reason))

private[relay] object DeviceHub:
  def start(config: DeviceLinkConfig, clock: Clock, bus: EventBus)(using Ox): DeviceHub =
    new DeviceHub(Actor.create(new DeviceHubState(config, clock, bus)))

/** The hub's mutable state. The `var`s are safe because every method runs inside the actor that owns this instance; nothing else may hold a
  * reference to it.
  */
private[device] final class DeviceHubState(config: DeviceLinkConfig, clock: Clock, bus: EventBus):
  private val logger = LoggerFactory.getLogger(classOf[DeviceHub])

  private var lastConnectionId: Long = 0
  private var latestSequence: SeqNo = SeqNo.Zero
  private var replayBuffer: Vector[Notification] = Vector.empty
  private var attached: Map[ConnectionId, AttachedDevice] = Map.empty
  private var latestObservedStats: StreamStats = StreamStats.Unknown
  private var connectionsAccepted: Long = 0
  private var notificationsPublished: Long = 0

  def attach(request: AttachRequest): ConnectionId =
    val now = clock.instant()
    val connection = nextConnectionId()
    val device = AttachedDevice(connection, request, now)
    attached = attached.updated(connection, device)
    connectionsAccepted += 1
    greet(device, request.lastSeq, now)
    bus.publish(RelayEvent.DeviceConnected(request.device, connection, request.remoteAddress))
    connection

  def detach(connection: ConnectionId, reason: DisconnectReason): Unit =
    attached
      .get(connection)
      .foreach: device =>
        attached = attached.removed(connection)
        logger.info(s"Device ${device.device.value} (#${connection.value}) detached: ${reason.describe}")
        bus.publish(RelayEvent.DeviceDisconnected(device.device, connection, reason.describe))

  def publish(request: NotificationRequest): Notification =
    latestSequence = latestSequence.next
    val notification = Notification(
      seq = latestSequence,
      id = NotificationId.forSeq(latestSequence),
      kind = request.kind,
      title = request.title,
      body = request.body,
      at = clock.instant(),
      ttl = request.ttl
    )
    replayBuffer = (replayBuffer :+ notification).takeRight(config.replayBufferSize)
    notificationsPublished += 1
    broadcast(ServerFrame.notify(notification))
    bus.publish(RelayEvent.NotificationPublished(notification))
    notification

  def updateStats(stats: StreamStats): Unit =
    latestObservedStats = stats
    broadcast(ServerFrame.stats(stats))

  def latestStats: StreamStats = latestObservedStats

  def links: List[DeviceLink] = attached.values.map(_.link).toList.sortBy(_.connection.value)

  def link(connection: ConnectionId): Option[DeviceLink] = attached.get(connection).map(_.link)

  def recent(limit: Int): List[Notification] = replayBuffer.reverse.take(limit).toList

  def snapshot: HubSnapshot =
    HubSnapshot(
      connectedDevices = attached.size,
      connectionsAccepted = connectionsAccepted,
      notificationsPublished = notificationsPublished,
      latestSeq = latestSequence,
      replayBuffered = replayBuffer.size,
      latestStats = latestObservedStats
    )

  def disconnect(connection: ConnectionId): Option[DeviceLink] =
    attached
      .get(connection)
      .map: device =>
        // Only closes the socket. The session's reader wakes with an error and detaches itself, so the bookkeeping
        // stays in one place instead of being duplicated here.
        device.connection
          .close()
          .catching[IOException]
          .left
          .foreach(e => logger.debug(s"Closing connection #${connection.value} failed", e))
        device.link

  /** The opening burst a device receives: where the stream stands, what it missed, and what to render right now. */
  private def greet(device: AttachedDevice, lastSeq: SeqNo, now: Instant): Unit =
    send(device, ServerFrame.welcome(config.protocolVersion, latestSequence, now))
    // A device reporting seq 0 has just booted and wants no history, only a baseline.
    if lastSeq.isAfter(SeqNo.Zero) then
      replayBuffer.filter(_.seq.isAfter(lastSeq)).foreach(notification => send(device, ServerFrame.notify(notification)))
    send(device, ServerFrame.stats(latestObservedStats))

  private def broadcast(frame: ServerFrame): Unit = attached.values.foreach(send(_, frame))

  private def send(device: AttachedDevice, frame: ServerFrame): Unit =
    device.outbound.trySendOrClosed(frame) match
      case accepted: Boolean =>
        if !accepted then
          device.counters.recordDropped()
          logger.warn(
            s"Device ${device.device.value} (#${device.id.value}) is not keeping up; dropped a ${frame.getClass.getSimpleName} frame"
          )
      case _: ChannelClosed => () // the session is tearing down and will detach in a moment

  private def nextConnectionId(): ConnectionId =
    lastConnectionId += 1
    ConnectionId(lastConnectionId)

private final class AttachedDevice(val id: ConnectionId, request: AttachRequest, val connectedAt: Instant):
  val device: DeviceId = request.device
  val outbound: Channel[ServerFrame] = request.outbound
  val counters: LinkCounters = request.counters
  val connection: Closeable = request.connection

  def link: DeviceLink =
    DeviceLink(id, device, request.remoteAddress, connectedAt, request.protocolVersion, request.lastSeq.value, counters.traffic)
