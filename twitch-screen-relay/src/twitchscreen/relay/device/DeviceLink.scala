package twitchscreen.relay.device

import java.time.Instant
import sttp.tapir.Schema
import twitchscreen.relay.protocol.DeviceId

/** An immutable view of one connected device, as served by the management API. */
final case class DeviceLink(
    connection: ConnectionId,
    device: DeviceId,
    remoteAddress: String,
    connectedAt: Instant,
    protocolVersion: Int,
    baselineSeq: Long,
    traffic: LinkTraffic
) derives Schema
