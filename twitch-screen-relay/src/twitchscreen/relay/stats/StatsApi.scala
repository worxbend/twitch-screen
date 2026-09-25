package twitchscreen.relay.stats

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.device.DeviceHub
import twitchscreen.relay.http.{ApiJson, Fail, Http, ServerEndpoints}
import twitchscreen.relay.protocol.{Count, MessagesPerMinute, StreamState, StreamStats}

/** Exactly what the devices are rendering, so a dashboard and a screen can be compared without guessing. */
final case class Stats_OUT(
    state: StreamState,
    viewers: Count,
    followers: Count,
    subscribers: Count,
    uptimeSeconds: Long,
    chatRate: MessagesPerMinute
) derives Schema

object Stats_OUT:
  given JsonValueCodec[Stats_OUT] = JsonCodecMaker.make(ApiJson.config)

  def from(stats: StreamStats): Stats_OUT =
    Stats_OUT(stats.state, stats.viewers, stats.followers, stats.subscribers, stats.uptime.toSeconds, stats.chatRate)

final class StatsApi(hub: DeviceHub) extends ServerEndpoints:
  override val endpoints: List[ServerEndpoint[Any, Identity]] =
    List(StatsApi.getEndpoint.handleSuccess(_ => Stats_OUT.from(hub.latestStats)))

object StatsApi:
  val getEndpoint: PublicEndpoint[Unit, Fail, Stats_OUT, Any] =
    Http.publicEndpoint.get
      .in("stats")
      .out(jsonBody[Stats_OUT])
      .summary("The stream figures last broadcast to the devices")
      .tag("stats")
