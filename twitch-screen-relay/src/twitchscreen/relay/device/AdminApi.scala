package twitchscreen.relay.device

import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.http.{Fail, Http, ServerEndpoints}
import twitchscreen.relay.twitch.MonitoringController
import twitchscreen.relay.config.{HttpBoundaryConfig, HttpConfig}
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.netty.NettyServerRequest
import sttp.tapir.server.netty.sync.OxStreams
import sttp.model.sse.ServerSentEvent
import ox.{Chunk, discard}
import ox.flow.Flow
import java.net.InetAddress
import java.nio.charset.StandardCharsets.UTF_8
import io.netty.channel.{ChannelFuture, ChannelFutureListener}

/** Canonical read-only admin surface. Every response is encoded and bounded before handing it to HTTP. */
final class AdminApi private[relay] (hub: DeviceHub, monitoring: MonitoringController, config: HttpConfig) extends ServerEndpoints:
  private val proxies = config.trustedProxies.flatMap(HttpBoundaryConfig.ip).toSet

  private def remote(request: ServerRequest): Option[InetAddress] =
    request.connectionInfo.remote
      .flatMap(address => Option(address.getAddress))
      .flatMap: socket =>
        if HttpBoundaryConfig.ip(socket.getHostAddress).exists(proxies) then
          request.header("X-Forwarded-For") match
            case Some(value) => Option(io.netty.util.NetUtil.createByteArrayFromIpAddressString(value)).map(InetAddress.getByAddress)
            case None        => Some(socket)
        else Some(socket)

  private def open(request: ServerRequest): Either[Fail, Flow[Chunk[Byte]]] =
    remote(request)
      .toRight(Fail.Forbidden)
      .flatMap: address =>
        hub
          .observeHub(address)
          .left
          .map:
            case "observer_limit" => Fail.RateLimited
            case code             => Fail.Unavailable(code)
          .flatMap: (_, subscription) =>
            var transferred = false
            try
              request match
                case netty: NettyServerRequest =>
                  netty.ctx
                    .channel()
                    .closeFuture()
                    .addListener(
                      new ChannelFutureListener:
                        override def operationComplete(result: ChannelFuture): Unit = subscription.close()
                    )
                    .discard
                case _ => ()
              capture()
                .flatMap: (initial, snapshot) =>
                  AdminSnapshot
                    .encoded(snapshot)
                    .map: first =>
                      transferred = true
                      feed(initial, first, subscription)
            finally if !transferred then subscription.close()

  private def feed(initial: HubObservation, first: Array[Byte], subscription: HubSubscription): Flow[Chunk[Byte]] = Flow.usingEmit: emit =>
    var epoch = initial.publication.relayEpoch
    var revision = initial.revision
    var lastSnapshotNanos = 0L
    var heartbeatNanos = System.nanoTime()
    def send(bytes: Array[Byte], event: String, id: String): Unit =
      val remaining = 250000000L - (System.nanoTime() - lastSnapshotNanos)
      if remaining > 0 then java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining)
      val frame = ServerSentEvent(data = Some(String(bytes, UTF_8)), eventType = Some(event), id = Some(id), retry = Some(1000))
      emit(Chunk.fromArray((frame.toString + "\n\n").getBytes(UTF_8)))
      lastSnapshotNanos = System.nanoTime()
    try
      send(first, "snapshot", s"${AdminSnapshot.hex(epoch)}:$revision")
      while !subscription.isClosed do
        val untilHeartbeat = math.max(1L, (15000000000L - (System.nanoTime() - heartbeatNanos)) / 1000000L)
        subscription.takeWithin(untilHeartbeat) match
          case Some(_) =>
            // A coalesced wake can predate a monitoring switch. Recapture both owners in settings acceptance order, not that old row.
            capture().flatMap((value, snapshot) => AdminSnapshot.encoded(snapshot).map(bytes => (value, bytes))) match
              case Left(_) => subscription.close()
              case Right((value, bytes)) =>
                if value.publication.relayEpoch != epoch || value.revision > revision then
                  val reset = value.publication.relayEpoch != epoch || value.revision > revision + 1L
                  send(
                    bytes,
                    if reset then "reset" else "snapshot",
                    s"${AdminSnapshot.hex(value.publication.relayEpoch)}:${value.revision}"
                  )
                  epoch = value.publication.relayEpoch
                  revision = value.revision
          case None => ()
        if !subscription.isClosed && System.nanoTime() - heartbeatNanos >= 15000000000L then
          emit(Chunk.fromArray(": heartbeat\n\n".getBytes(UTF_8)))
          heartbeatNanos = System.nanoTime()
    finally subscription.close()

  /** Snapshot construction only: encoding and network emission happen after releasing settings acceptance. */
  private def capture(refresh: Boolean = false): Either[Fail, (HubObservation, AdminSnapshot_OUT)] =
    monitoring
      .captureWithin: selected =>
        if refresh then hub.refreshObservation()
        hub.latestObservation
          .toRight(Fail.Unavailable("admin authority unavailable"))
          .flatMap(value => AdminSnapshot.project(value, selected).map(snapshot => (value, snapshot)))
      .flatMap(identity)

  private[relay] def read(): Either[Fail, AdminSnapshot_OUT] =
    capture(refresh = true).flatMap: (_, value) =>
      AdminSnapshot.encoded(value).map(_ => value)

  override val endpoints: List[ServerEndpoint[Any, Identity]] = List(AdminApi.snapshotEndpoint.handle(_ => read()))
  override val streamingEndpoints: List[ServerEndpoint[OxStreams, Identity]] = List(AdminApi.eventsEndpoint.handle(open))

object AdminApi:
  val eventsEndpoint: PublicEndpoint[ServerRequest, Fail, Flow[Chunk[Byte]], OxStreams] = Http.baseEndpoint.get
    .in("admin" / "events")
    .in(extractFromRequest[ServerRequest](identity))
    .out(streamTextBody(OxStreams)(CodecFormat.TextEventStream(), Some(UTF_8)))
    .out(header("Cache-Control", "no-store"))
    .out(header("X-Accel-Buffering", "no"))
    .summary("Subscribe to coalesced complete canonical admin snapshots")
    .tag("admin")

  val snapshotEndpoint: PublicEndpoint[Unit, Fail, AdminSnapshot_OUT, Any] = Http.baseEndpoint.get
    .in("admin" / "snapshot")
    .out(jsonBody[AdminSnapshot_OUT])
    .out(header("Cache-Control", "no-store"))
    .summary("Read bounded authoritative control, source, firmware observations and charts")
    .description("Firmware observations are not physical pixels. Legacy queues are unsupported; transport backlog is not device FIFO.")
    .tag("admin")
