package twitchscreen.relay.device

import twitchscreen.relay.protocol.{SeqNo, StreamStats}

/** Counters describing the device link as a whole, for the status endpoint. */
final case class HubSnapshot(
    connectedDevices: Int,
    connectionsAccepted: Long,
    notificationsPublished: Long,
    latestSeq: SeqNo,
    replayBuffered: Int,
    latestStats: StreamStats,
    /** §10.1: the last `u32` seq has been assigned; every further EVENT is refused until the relay restarts. */
    sequenceExhausted: Boolean
)
