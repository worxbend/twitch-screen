package twitchscreen.relay.device

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import java.time.Instant
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.http.{ApiJson, CustomMethod, Fail, Http, ServerEndpoints}
import twitchscreen.relay.protocol.DeviceId

final case class Device_OUT(
    connection: ConnectionId,
    device: DeviceId,
    remoteAddress: String,
    connectedAt: Instant,
    protocolVersion: Int,
    baselineSeq: Long,
    traffic: LinkTraffic
) derives Schema

object Device_OUT:
  given JsonValueCodec[Device_OUT] = JsonCodecMaker.make(ApiJson.config)

  def from(link: DeviceLink): Device_OUT =
    Device_OUT(
      connection = link.connection,
      device = link.device,
      remoteAddress = link.remoteAddress,
      connectedAt = link.connectedAt,
      protocolVersion = link.protocolVersion,
      baselineSeq = link.baselineSeq,
      traffic = link.traffic
    )

/** AIP-132 list responses name their collection, so a page token can be added later without breaking clients. */
final case class Devices_OUT(devices: List[Device_OUT]) derives Schema

object Devices_OUT:
  given JsonValueCodec[Devices_OUT] = JsonCodecMaker.make(ApiJson.config)

/** Management of the connected devices: what is attached, how much traffic each has taken, and a way to cut one off.
  *
  * Disconnecting is an AIP-136 custom method rather than a `DELETE`, because a device link is not a resource the operator created and
  * cannot be deleted — only interrupted, after which the device reconnects by itself.
  */
final class DeviceApi(hub: DeviceHub) extends ServerEndpoints:
  import DeviceApi.*

  override val endpoints: List[ServerEndpoint[Any, Identity]] = List(
    listEndpoint.handleSuccess(_ => Devices_OUT(hub.links.map(Device_OUT.from))),
    getEndpoint.handle(connection => find(connection).map(Device_OUT.from)),
    disconnectEndpoint.handle: connection =>
      hub.disconnect(connection).map(Device_OUT.from).toRight(Fail.NotFound(s"No device is connected as #${connection.value}"))
  )

  private def find(connection: ConnectionId): Either[Fail, DeviceLink] =
    hub.link(connection).toRight(Fail.NotFound(s"No device is connected as #${connection.value}"))

object DeviceApi:
  /** The `{connection}:disconnect` path segment. Passed to `path` explicitly rather than left implicit: the plain `ConnectionId` codec
    * would otherwise win and reject the whole segment, because it cannot see past the verb.
    */
  private val disconnectTarget: Codec[String, ConnectionId, CodecFormat.TextPlain] =
    CustomMethod.on(
      "disconnect",
      raw => raw.toLongOption.map(ConnectionId(_)).toRight(s"Not a connection id: $raw"),
      connection => connection.value.toString
    )

  val listEndpoint: PublicEndpoint[Unit, Fail, Devices_OUT, Any] =
    Http.baseEndpoint.get
      .in("devices")
      .out(jsonBody[Devices_OUT])
      .summary("List the devices currently attached to the relay")
      .description("Returns all attached devices. The TCP listener permits at most 64 simultaneous sessions, including handshakes.")
      .tag("devices")

  val getEndpoint: PublicEndpoint[ConnectionId, Fail, Device_OUT, Any] =
    Http.baseEndpoint.get
      .in("devices" / path[ConnectionId]("connection"))
      .out(jsonBody[Device_OUT])
      .summary("Get one attached device")
      .tag("devices")

  val disconnectEndpoint: PublicEndpoint[ConnectionId, Fail, Device_OUT, Any] =
    Http.baseEndpoint.post
      .in("devices" / path[ConnectionId]("connection:disconnect")(using disconnectTarget))
      .out(jsonBody[Device_OUT])
      .summary("Disconnect a device")
      .description(
        "Closes the socket. The firmware reconnects on its own backoff schedule; retained events are eligible for best-effort replay."
      )
      .tag("devices")
