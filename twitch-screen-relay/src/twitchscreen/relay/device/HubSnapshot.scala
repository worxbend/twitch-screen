package twitchscreen.relay.device

import twitchscreen.relay.protocol.{SeqNo, StreamStats}

/** Counters describing the device link as a whole, for the status endpoint. */
final case class HubSnapshot(
    connectedDevices: Int,
    connectionsAccepted: Long,
    notificationsPublished: Long,
    latestSeq: SeqNo,
    replayBuffered: Int,
    latestStats: StreamStats
)
