package twitchscreen.relay.twitch

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import ox.{Ox, discard, forkDiscard, supervised, timeoutOption}
import scala.concurrent.duration.*
import scala.util.Try
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.config.{EventSubTransport, TwitchConfig, TwitchMode}
import twitchscreen.relay.device.DeviceHub
import twitchscreen.relay.http.{ApiJson, Error_OUT, Fail, Http, ServerEndpoints, StrictRequest}
import twitchscreen.relay.settings.*

final case class Monitoring_IN(mode: Option[String] = None, channel: Option[String] = None) derives Schema:
  require(mode.isDefined || channel.isDefined, "empty monitoring update")
  require(mode.forall(Set("disabled", "simulated", "live").contains), "invalid monitoring mode")
  require(channel.forall(value => value.matches("[a-zA-Z0-9_]{1,25}")), "invalid monitoring channel")

object Monitoring_IN:
  private given JsonValueCodec[Option[String]] = StrictRequest.optional(JsonCodecMaker.make[String])
  given JsonValueCodec[Monitoring_IN] = JsonCodecMaker.make(ApiJson.config.withSkipUnexpectedFields(false).withCheckFieldDuplication(true))

final case class MonitoringIntent_OUT(mode: String, channel: Option[String]) derives Schema
final case class ResolvedChannel_OUT(id: String, login: String, displayName: String) derives Schema
final case class FeatureEligibility_OUT(feature: String, eligible: Boolean, reason: Option[String]) derives Schema
final case class GrantCompatibility_OUT(status: String, features: List[FeatureEligibility_OUT], missingScopes: List[String]) derives Schema
final case class Monitoring_OUT(
    relayEpoch: String,
    revision: Long,
    settingsSource: String,
    desired: MonitoringIntent_OUT,
    effective: MonitoringIntent_OUT,
    transition: String,
    sourceGeneration: Long,
    resolvedChannel: Option[ResolvedChannel_OUT],
    grantCompatibility: GrantCompatibility_OUT,
    error: Option[Error_OUT]
) derives Schema
object Monitoring_OUT:
  given JsonValueCodec[Monitoring_OUT] = JsonCodecMaker.make(ApiJson.config.withTransientNone(false))

/** Shares durable settings with display writes. Provider preparation runs outside storage; local revalidation and commit share acceptance
  * order. The reliable hub fence precedes the nonblocking source request. Lookup pressure never queues provider work in the settings actor.
  */
private[relay] final class MonitoringController(
    deployment: TwitchConfig,
    settings: RuntimeSettingsWorker,
    hub: DeviceHub,
    source: RuntimeSource,
    oauth: TwitchOAuthOwner,
    resolve: String => Either[String, ResolvedChannel_OUT]
):
  private val resolved = AtomicReference[Option[ResolvedChannel_OUT]](None)
  private val resolutionFailure = AtomicReference[Option[String]](None)
  private val started = AtomicBoolean(false)
  private val lookup = Semaphore(1)

  private def prepare(desired: MonitoringSettings, deadlineNanos: Long): Either[String, Option[ResolvedChannel_OUT]] =
    if desired.mode != "live" then Right(None)
    else
      val cached = resolved.get().filter(value => desired.channel.value.contains(value.login))
      if cached.isDefined then Right(cached)
      else if Try(selectedConfig(desired).validateLive()).isFailure || oauth.auth.isEmpty then Left("configuration_missing")
      else resolveBounded(desired.channel.value.getOrElse(""), deadlineNanos).map(Some(_))

  private def resolveBounded(login: String, deadlineNanos: Long): Either[String, ResolvedChannel_OUT] =
    if !lookup.tryAcquire() then Left("channel_lookup_busy")
    else
      try
        val remaining = math.min(3.seconds.toNanos, deadlineNanos - System.nanoTime())
        if remaining <= 0 then Left("application_timeout")
        else supervised(timeoutOption(remaining.nanos)(resolve(login))).getOrElse(Left("channel_lookup_failed"))
      finally lookup.release()

  /** Startup preparation never runs in the storage actor and cannot overwrite a superseding monitoring preference. */
  def start()(using Ox): Unit =
    if started.compareAndSet(false, true) then
      forkDiscard:
        val desired = settings.snapshot.confirmed.monitoring
        if desired.mode == "live" && resolved.get().isEmpty && Try(selectedConfig(desired).validateLive()).isSuccess then
          val prepared = resolveBounded(desired.channel.value.getOrElse(""), RuntimeSettingsWorker.deadline())
          settings
            .read: snapshot =>
              if snapshot.confirmed.monitoring == desired then
                prepared match
                  case Right(value) => resolved.set(Some(value))
                  case Left(code)   => resolutionFailure.set(Some(code))
            .discard

  def read(): Monitoring_OUT = settings.read(capture)
  def readWithin(): Either[Fail, Monitoring_OUT] = captureWithin(identity)

  /** Join an authoritative consumer while the settings acceptance order is held. The callback must be local and bounded, never perform
    * provider or browser I/O. A source switch cannot interleave its hub fence between monitoring capture and this consumer's snapshot.
    */
  private[relay] def captureWithin[A](read: Monitoring_OUT => A): Either[Fail, A] =
    settings.readWithin(snapshot => read(capture(snapshot))).left.map(Fail.MonitoringUnavailable.apply)

  def patch(request: Monitoring_IN): Either[Fail, Monitoring_OUT] =
    val deadlineNanos = RuntimeSettingsWorker.deadline()
    val patch = SettingsPatch(mode = request.mode, channel = request.channel.map(_.toLowerCase(Locale.ROOT)))
    val target = patch.applyTo(settings.snapshot.confirmed).monitoring
    prepare(target, deadlineNanos)
      .flatMap: prepared =>
        settings.validatedPatch(patch, deadlineNanos)((before, next) =>
          if next.monitoring.mode == "live" && next.monitoring != target then Left("monitoring_superseded")
          else Right((next.monitoring != before.confirmed.monitoring, if next.monitoring.mode == "live" then prepared else None))
        )((committed, selected) =>
          resolved.set(selected._2)
          resolutionFailure.set(None)
          if selected._1 then source.request(selectedConfig(committed.confirmed.monitoring))
          capture(committed)
        )
      .left
      .map:
        case "configuration_missing" => Fail.ConfigurationMissing
        case code                    => Fail.MonitoringUnavailable(code)

  private def selectedConfig(value: MonitoringSettings): TwitchConfig =
    val mode = value.mode match
      case "disabled"  => TwitchMode.Disabled
      case "simulated" => TwitchMode.Simulated
      case "live"      => TwitchMode.Live
      case _           => throw IllegalArgumentException("invalid monitoring mode")
    deployment.copy(mode = mode, channel = value.channel.value.getOrElse(""))

  private def capture(snapshot: SettingsSnapshot): Monitoring_OUT =
    val desired = snapshot.confirmed.monitoring
    val fence = hub.sourceFence
    val applied = source.applied(fence)
    val configurationMissing = desired.mode == "live" && Try(selectedConfig(desired).validateLive()).isFailure
    val channel = resolved.get().filter(value => desired.channel.value.contains(value.login) && desired.mode == "live")
    val error =
      if snapshot.projectionFailed then Some("runtime_unavailable")
      else if snapshot.storageFailure.exists(_.uncertain) then Some("storage_uncertain")
      else if configurationMissing then Some("configuration_missing")
      else source.applicationFailure.orElse(resolutionFailure.get())
    val transition =
      if error.isDefined then "failed"
      else if applied.isDefined && (desired.mode != "live" || channel.isDefined) then "ready"
      else "applying"
    val effective = applied.fold(MonitoringIntent_OUT("disabled", None)): value =>
      MonitoringIntent_OUT(
        value.mode.toString.toLowerCase(Locale.ROOT),
        Option.when(value.mode != TwitchMode.Disabled)(if value.channel.isEmpty then "demo" else value.channel)
      )
    Monitoring_OUT(
      f"${fence.relayEpoch}%016x",
      fence.generation,
      snapshot.settingsSource,
      MonitoringIntent_OUT(desired.mode, desired.channel.value),
      effective,
      transition,
      fence.generation,
      channel,
      compatibility(desired.mode, channel),
      error.map(code => Error_OUT(code, Some(code)))
    )

  private def compatibility(mode: String, channel: Option[ResolvedChannel_OUT]): GrantCompatibility_OUT =
    def feature(name: String, eligible: Boolean, reason: String): FeatureEligibility_OUT =
      FeatureEligibility_OUT(name, eligible, Option.unless(eligible)(reason))
    if mode != "live" then GrantCompatibility_OUT("not_required", Nil, Nil)
    else
      val view = oauth.auth.map(_.view)
      val held = view.flatMap(_.held)
      val usable = view.flatMap(_.usable)
      val matching = usable.filter(token => channel.exists(_.id == token.userId))
      val scopes = matching.fold(List.empty[String])(_.scopes)
      val missing = deployment.oauth.scopes.diff(scopes)
      val status =
        if held.isEmpty then "missing"
        else if usable.isEmpty then "expired"
        else if channel.isEmpty then "partial"
        else if matching.isEmpty then "mismatched"
        else if missing.nonEmpty then "partial"
        else "usable"
      GrantCompatibility_OUT(
        status,
        List(
          feature("public_stream", channel.isDefined, "channel_unresolved"),
          feature("public_chat", channel.isDefined, "channel_unresolved"),
          feature("followers", matching.exists(_.scopes.contains(TwitchScopes.Followers)), status),
          feature("subscribers", matching.exists(_.scopes.contains(TwitchScopes.Subscriptions)), status),
          feature(
            "eventsub",
            channel.isDefined && (deployment.eventSub.transport == EventSubTransport.Webhook || matching.isDefined),
            status
          )
        ),
        missing
      )

final class MonitoringApi private[relay] (monitoring: MonitoringController) extends ServerEndpoints:
  override val endpoints: List[ServerEndpoint[Any, Identity]] = List(
    MonitoringApi.getEndpoint.handle(_ => monitoring.readWithin()),
    MonitoringApi.patchEndpoint.handle(monitoring.patch)
  )

object MonitoringApi:
  val getEndpoint: PublicEndpoint[Unit, Fail, Monitoring_OUT, Any] = Http.baseEndpoint.get
    .in("twitch" / "monitoring")
    .out(jsonBody[Monitoring_OUT])
    .summary("Read desired and effective runtime monitoring; provider health is separate")
    .tag("twitch")
  val patchEndpoint: PublicEndpoint[Monitoring_IN, Fail, Monitoring_OUT, Any] = Http.baseEndpoint.patch
    .in("twitch" / "monitoring")
    .in(jsonBody[Monitoring_IN])
    .out(jsonBody[Monitoring_OUT])
    .summary("Resolve and persist monitoring intent; apply asynchronously without changing display or OAuth")
    .tag("twitch")
