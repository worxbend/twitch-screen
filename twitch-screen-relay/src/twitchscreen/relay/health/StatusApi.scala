package twitchscreen.relay.health

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import java.time.{Clock, Duration as JDuration, Instant}
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.RelayVersion
import twitchscreen.relay.activity.ActivityLog
import twitchscreen.relay.alerts.AlertStore
import twitchscreen.relay.bus.{EventBus, SubscriberStats}
import twitchscreen.relay.device.DeviceHub
import twitchscreen.relay.http.{ApiJson, Fail, Http, ServerEndpoints}
import twitchscreen.relay.observability.LogBuffer
import twitchscreen.relay.protocol.SeqNo
import twitchscreen.relay.twitch.{TwitchSource, TwitchStatus}

final case class DeviceLink_OUT(
    connectedDevices: Int,
    connectionsAccepted: Long,
    notificationsPublished: Long,
    latestSeq: SeqNo,
    replayBuffered: Int
) derives Schema

/** Readiness, as opposed to [[HealthApi]]'s liveness: everything an operator needs to decide whether the relay is doing its job, in one
  * request.
  */
final case class Status_OUT(
    version: String,
    startedAt: Instant,
    uptimeSeconds: Long,
    twitch: TwitchStatus,
    deviceLink: DeviceLink_OUT,
    subscribers: List[SubscriberStats],
    activityEntries: Int,
    activeAlerts: Int,
    bufferedLogRecords: Int
) derives Schema

object Status_OUT:
  given JsonValueCodec[Status_OUT] = JsonCodecMaker.make(ApiJson.config)

final class StatusApi(
    twitch: TwitchSource,
    hub: DeviceHub,
    bus: EventBus,
    activity: ActivityLog,
    alerts: AlertStore,
    clock: Clock,
    startedAt: Instant
) extends ServerEndpoints:

  override val endpoints: List[ServerEndpoint[Any, Identity]] = List(StatusApi.getEndpoint.handleSuccess(_ => status()))

  private def status(): Status_OUT =
    val snapshot = hub.snapshot
    Status_OUT(
      version = RelayVersion.current,
      startedAt = startedAt,
      uptimeSeconds = JDuration.between(startedAt, clock.instant()).toSeconds,
      twitch = twitch.status,
      deviceLink = DeviceLink_OUT(
        connectedDevices = snapshot.connectedDevices,
        connectionsAccepted = snapshot.connectionsAccepted,
        notificationsPublished = snapshot.notificationsPublished,
        latestSeq = snapshot.latestSeq,
        replayBuffered = snapshot.replayBuffered
      ),
      subscribers = bus.subscriberStats,
      activityEntries = activity.size,
      activeAlerts = alerts.activeCount,
      bufferedLogRecords = LogBuffer.size
    )

object StatusApi:
  val getEndpoint: PublicEndpoint[Unit, Fail, Status_OUT, Any] =
    Http.baseEndpoint.get
      .in("status")
      .out(jsonBody[Status_OUT])
      .summary("Readiness: Twitch, the device link, the internal bus and the alert state")
      .tag("health")
