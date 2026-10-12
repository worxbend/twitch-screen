package twitchscreen.relay.device

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.http.{ApiJson, Fail, Http, ServerEndpoints}

final case class Clock_OUT(relayEpoch: String, monotonicMs: Long) derives Schema
object Clock_OUT:
  given JsonValueCodec[Clock_OUT] = JsonCodecMaker.make(ApiJson.config)
  private[device] def from(value: RelayClock): Clock_OUT = Clock_OUT(value.epochHex, value.monotonicMs)

final class ClockApi(hub: DeviceHub) extends ServerEndpoints:
  override val endpoints: List[ServerEndpoint[Any, Identity]] =
    List(ClockApi.getEndpoint.handleSuccess(_ => Clock_OUT.from(hub.relayClock)))

object ClockApi:
  val getEndpoint: PublicEndpoint[Unit, Fail, Clock_OUT, Any] =
    Http.baseEndpoint.get
      .in("clock")
      .out(header("Cache-Control", "no-store"))
      .out(jsonBody[Clock_OUT])
      .summary("Sample the relay's current monotonic calibration domain")
      .description("Calibrate producer deadlines. This is not a health-polling API; wall-clock time never defines freshness.")
      .tag("clock")
