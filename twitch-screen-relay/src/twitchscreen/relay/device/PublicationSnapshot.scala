package twitchscreen.relay.device

import twitchscreen.relay.protocol.{SeqNo, StreamStats}

/** Immutable actor transaction for publication history, classic statistics and committed desired-control fences. This is not a DESK
  * greeting: producer callback fencing, source revisions and connection identity must join this authority before negotiation is enabled.
  */
private[device] final case class PublicationSnapshot(
    relayEpoch: Long,
    sampledRelayMs: Long,
    latestSeq: SeqNo,
    stats: StreamStats,
    retained: Vector[RetainedEvent],
    settings: Option[twitchscreen.relay.settings.SettingsSnapshot] = None,
    control: Option[PublicationControl] = None,
    cpu: Option[Cpu_OUT] = None,
    weather: Option[Weather_OUT] = None,
    cpuDeadlineRelayMs: Option[Long] = None,
    weatherDeadlineRelayMs: Option[Long] = None,
    statsRevision: Long = 1L,
    statsObservedAt: Option[java.time.Instant] = None,
    statsAvailability: twitchscreen.relay.protocol.DeskMessageCodec.Availability =
      twitchscreen.relay.protocol.DeskMessageCodec.Availability.Unknown,
    statsContentAt: Option[java.time.Instant] = None
)

/** Committed desired-state fences owned by the hub actor. Source callbacks still require producer-side generation tagging. */
private[device] final case class PublicationControl(
    revision: Long,
    sourceGeneration: Long,
    twitchEpisode: Long,
    display: twitchscreen.relay.settings.DisplaySettings
)
