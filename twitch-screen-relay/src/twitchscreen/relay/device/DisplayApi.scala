package twitchscreen.relay.device

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.http.{ApiJson, Fail, Http, ServerEndpoints, StrictRequest}
import twitchscreen.relay.settings.{RuntimeSettingsWorker, SettingsPatch}

final case class Display_IN(twitchDisplayEnabled: Option[Boolean] = None, preferredDashboard: Option[String] = None) derives Schema:
  require(twitchDisplayEnabled.isDefined || preferredDashboard.isDefined, "empty display update")
  require(preferredDashboard.forall(Set("generic", "weather", "cpu").contains), "invalid preferred dashboard")

object Display_IN:
  private given optionalBoolean: JsonValueCodec[Option[Boolean]] = StrictRequest.optional(JsonCodecMaker.make[Boolean])
  private given JsonValueCodec[Option[String]] = StrictRequest.optional(JsonCodecMaker.make[String])
  given JsonValueCodec[Display_IN] = JsonCodecMaker.make(ApiJson.config.withSkipUnexpectedFields(false).withCheckFieldDuplication(true))

final case class Readiness_OUT(revision: Long, status: String, reason: String, remainingFreshnessMs: Long) derives Schema
object Readiness_OUT:
  given JsonValueCodec[Readiness_OUT] = JsonCodecMaker.make(ApiJson.config)
  private[device] val missing = Readiness_OUT(1L, "missing", "never_supplied", 0L)
  private[device] def from(value: Cpu_OUT): Readiness_OUT =
    Readiness_OUT(value.revision, value.status, value.reason, value.remainingFreshnessMs)

final case class Display_OUT(
    relayEpoch: String,
    revision: Long,
    twitchDisplayEnabled: Boolean,
    preferredDashboard: String,
    weather: Readiness_OUT,
    cpu: Readiness_OUT
) derives Schema
object Display_OUT:
  given JsonValueCodec[Display_OUT] = JsonCodecMaker.make(ApiJson.config)
  private[device] def from(value: PublicationSnapshot): Either[Fail, Display_OUT] =
    value.control
      .toRight(Fail.Unavailable("display authority unavailable"))
      .map: control =>
        Display_OUT(
          RelayClock(value.relayEpoch, value.sampledRelayMs).epochHex,
          control.revision,
          control.display.twitchDisplayEnabled,
          control.display.preferredDashboard,
          value.weather.fold(Readiness_OUT.missing)(source =>
            Readiness_OUT(source.revision, source.status, source.reason, source.remainingFreshnessMs)
          ),
          value.cpu.fold(Readiness_OUT.missing)(Readiness_OUT.from)
        )

/** Durable desired display intent. Device application and physical pixels remain separate acceptance gates. */
final class DisplayApi private[relay] (hub: DeviceHub, settings: RuntimeSettingsWorker) extends ServerEndpoints:
  private def read(): Either[Fail, Display_OUT] = Display_OUT.from(hub.publicationSnapshot)
  override val endpoints: List[ServerEndpoint[Any, Identity]] = List(
    DisplayApi.getEndpoint.handle(_ => read()),
    DisplayApi.patchEndpoint.handle: request =>
      settings
        .patchAndRead(SettingsPatch(request.twitchDisplayEnabled, request.preferredDashboard))(read())
        .left
        .map(code => Fail.Unavailable(code))
        .flatMap(identity)
  )

object DisplayApi:
  val getEndpoint: PublicEndpoint[Unit, Fail, Display_OUT, Any] = Http.baseEndpoint.get
    .in("display")
    .out(jsonBody[Display_OUT])
    .summary("Read desired display control and current source readiness")
    .tag("display")
  val patchEndpoint: PublicEndpoint[Display_IN, Fail, Display_OUT, Any] = Http.baseEndpoint.patch
    .in("display")
    .in(jsonBody[Display_IN])
    .out(jsonBody[Display_OUT])
    .summary("Persist and merge desired display control")
    .description(
      "Success confirms durable desired intent, not device application. Equivalent writes are no-ops; source readiness reflects accepted external telemetry."
    )
    .tag("display")
