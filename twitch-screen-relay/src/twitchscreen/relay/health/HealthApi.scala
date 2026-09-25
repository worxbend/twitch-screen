package twitchscreen.relay.health

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.http.{ApiJson, Fail, Http, ServerEndpoints}

enum HealthStatus:
  case Up

object HealthStatus:
  given Schema[HealthStatus] = Schema.derivedEnumeration[HealthStatus].defaultStringBased

final case class Health_OUT(status: HealthStatus) derives Schema

object Health_OUT:
  given JsonValueCodec[Health_OUT] = JsonCodecMaker.make(ApiJson.config)

/** Liveness only: it answers as long as the process can serve HTTP, and says nothing about Twitch or devices — that is
  * [[twitchscreen.relay.health.StatusApi]]'s job. Keeping them apart means a container health check does not restart a perfectly good relay
  * because Twitch is down.
  */
final class HealthApi extends ServerEndpoints:
  override val endpoints: List[ServerEndpoint[Any, Identity]] =
    List(HealthApi.healthEndpoint.handleSuccess(_ => Health_OUT(HealthStatus.Up)))

object HealthApi:
  val healthEndpoint: PublicEndpoint[Unit, Fail, Health_OUT, Any] =
    Http.baseEndpoint.get
      .in("health")
      .out(jsonBody[Health_OUT])
      .summary("Liveness check")
      .tag("health")
