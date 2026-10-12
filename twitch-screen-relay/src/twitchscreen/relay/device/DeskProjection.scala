package twitchscreen.relay.device

import java.time.Instant
import twitchscreen.relay.config.DeviceLinkConfig
import twitchscreen.relay.protocol.*
import twitchscreen.relay.settings.DisplaySettings

/** Connection-specific projection of one hub transaction. Original deadlines are never reconstructed from relative HTTP lifetimes. */
private[device] object DeskProjection:
  def display(snapshot: PublicationSnapshot, identity: DeskControlCodec.Identity): DeskControlCodec.Display =
    val value = snapshot.control.getOrElse(PublicationControl(1, 1, 1, DisplaySettings(false, "generic")))
    val preferred = value.display.preferredDashboard match
      case "generic" => DeskControlCodec.Preferred.Generic
      case "weather" => DeskControlCodec.Preferred.Weather
      case "cpu"     => DeskControlCodec.Preferred.Cpu
      case _         => throw IllegalArgumentException("invalid preferred dashboard")
    DeskControlCodec.Display(
      identity,
      value.revision,
      value.sourceGeneration,
      value.twitchEpisode,
      value.display.twitchDisplayEnabled,
      preferred
    )

  def weather(snapshot: PublicationSnapshot, identity: DeskControlCodec.Identity): DeskSnapshotCodec.Weather =
    val value = snapshot.weather.get
    val status = availability(value.status)
    if status != DeskSnapshotCodec.Status.Fresh then DeskSnapshotCodec.Weather(identity, value.revision, 0, status)
    else
      val data = value.data.get
      DeskSnapshotCodec.Weather(
        identity,
        value.revision,
        snapshot.weatherDeadlineRelayMs.get,
        status,
        if data.unit == "celsius" then 0 else 1,
        Weather_IN.conditions.indexOf(data.condition),
        centi(data.temperature),
        data.humidityPercent.map(_.toInt),
        label(data.location)
      )

  def cpu(snapshot: PublicationSnapshot, identity: DeskControlCodec.Identity): DeskSnapshotCodec.Cpu =
    val value = snapshot.cpu.get
    val status = availability(value.status)
    if status != DeskSnapshotCodec.Status.Fresh then DeskSnapshotCodec.Cpu(identity, value.revision, 0, status)
    else
      val data = value.data.get
      DeskSnapshotCodec.Cpu(
        identity,
        value.revision,
        snapshot.cpuDeadlineRelayMs.get,
        status,
        centi(data.cpuPercent),
        data.memoryPercent.map(centi),
        data.temperatureCelsius.map(centi),
        label(data.host)
      )

  def stats(snapshot: PublicationSnapshot, identity: DeskControlCodec.Identity): DeskMessageCodec.Stats =
    DeskMessageCodec.fromStats(
      RelayMessage.Stats(snapshot.stats, snapshot.statsContentAt),
      identity,
      display(snapshot, identity).sourceGeneration,
      snapshot.statsRevision,
      snapshot.statsAvailability
    )

  def greeting(
      snapshot: PublicationSnapshot,
      connection: Long,
      probe: Long,
      caps: Capabilities,
      session: SessionId,
      config: DeviceLinkConfig,
      now: Instant
  ): DeskDelivery.Greeting =
    val identity = DeskControlCodec.Identity(snapshot.relayEpoch, connection)
    val control = display(snapshot, identity)
    val weatherValue = weather(snapshot, identity)
    val cpuValue = cpu(snapshot, identity)
    val statsValue = stats(snapshot, identity)
    DeskDelivery.Greeting(
      RelayMessage.Welcome(
        snapshot.latestSeq,
        Some(now),
        session,
        FrameSize.Default,
        config.pingInterval,
        config.idleTimeout,
        ReplayWindow.of(config.replayBufferSize),
        caps
      ),
      DeskGreetingCodec.WelcomeTail(identity.relayEpoch, identity.connectionId, snapshot.sampledRelayMs, probe),
      control,
      weatherValue,
      cpuValue,
      statsValue,
      DeskControlCodec.Sync(
        identity,
        control.revision,
        weatherValue.revision,
        cpuValue.revision,
        statsValue.revision,
        control.sourceGeneration,
        control.twitchEpisode
      )
    )

  def card(value: RetainedEvent, replay: Boolean): DeskDelivery.Card =
    DeskDelivery.Card(
      value.record,
      value.relayEpoch,
      value.sourceGeneration,
      value.twitchEpisode,
      value.deadlineRelayMs,
      value.origin,
      replay
    )

  private def centi(value: Double): Int = (BigDecimal.decimal(value) * 100).setScale(0, BigDecimal.RoundingMode.HALF_UP).toInt
  private def availability(value: String): DeskSnapshotCodec.Status = value match
    case "missing" => DeskSnapshotCodec.Status.Missing
    case "fresh"   => DeskSnapshotCodec.Status.Fresh
    case "stale"   => DeskSnapshotCodec.Status.Stale
    case _         => throw IllegalArgumentException("invalid snapshot status")
  // Canonical-valid control-only Unicode labels can sanitize to empty; diagnostics retain original text.
  private def label(value: String): DeskSnapshotCodec.Label =
    val projected = WireStrings.fold(WireStrings.sanitise(value))
    DeskSnapshotCodec.Label.fromText(if projected.isEmpty then "--" else projected)
