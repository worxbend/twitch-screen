package twitchscreen.relay.alerts

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import java.time.{Clock, Instant}
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.http.{ApiJson, CustomMethod, Fail, Http, ServerEndpoints}

/** Where an alert stands, flattened for the wire: the domain's [[AlertStatus]] carries its timestamp inside the case. */
enum AlertState:
  case Active, Acknowledged, Resolved

object AlertState:
  given Schema[AlertState] = Schema.derivedEnumeration[AlertState].defaultStringBased

final case class Alert_OUT(
    id: Long,
    rule: String,
    severity: AlertSeverity,
    message: String,
    raisedAt: Instant,
    state: AlertState,
    stateChangedAt: Option[Instant]
) derives Schema

object Alert_OUT:
  given JsonValueCodec[Alert_OUT] = JsonCodecMaker.make(ApiJson.config)

  def from(alert: Alert): Alert_OUT = alert.status match
    case AlertStatus.Active           => make(alert, AlertState.Active, None)
    case AlertStatus.Acknowledged(at) => make(alert, AlertState.Acknowledged, Some(at))
    case AlertStatus.Resolved(at)     => make(alert, AlertState.Resolved, Some(at))

  private def make(alert: Alert, state: AlertState, changedAt: Option[Instant]): Alert_OUT =
    Alert_OUT(alert.id, alert.rule, alert.severity, alert.message, alert.raisedAt, state, changedAt)

final case class Alerts_OUT(alerts: List[Alert_OUT]) derives Schema

object Alerts_OUT:
  given JsonValueCodec[Alerts_OUT] = JsonCodecMaker.make(ApiJson.config)

final case class AlertRule_OUT(name: String, severity: AlertSeverity, description: String) derives Schema

final case class AlertRules_OUT(rules: List[AlertRule_OUT]) derives Schema

object AlertRules_OUT:
  given JsonValueCodec[AlertRules_OUT] = JsonCodecMaker.make(ApiJson.config)

  def from(rules: List[AlertRule]): AlertRules_OUT =
    AlertRules_OUT(rules.map(rule => AlertRule_OUT(rule.name, rule.severity, rule.description)))

/** Reading the alerts and acknowledging them.
  *
  * Rules are read-only here on purpose: they come from the config file, so they survive a restart. An endpoint that could add a rule at
  * runtime would quietly lose it on the next reboot of the Pi.
  */
final class AlertsApi(store: AlertStore, rules: List[AlertRule], clock: Clock) extends ServerEndpoints:
  import AlertsApi.*

  override val endpoints: List[ServerEndpoint[Any, Identity]] = List(
    listEndpoint.handleSuccess((pageSize, severity, activeOnly) =>
      Alerts_OUT(store.recent(pageSize, severity, activeOnly).map(Alert_OUT.from))
    ),
    rulesEndpoint.handleSuccess(_ => AlertRules_OUT.from(rules)),
    acknowledgeEndpoint.handle(id => store.acknowledge(id, clock.instant()).map(Alert_OUT.from).left.map(explain))
  )

  private def explain(failure: AcknowledgeFailure): Fail = failure match
    case AcknowledgeFailure.NotFound(id)  => Fail.NotFound(s"No alert with id $id")
    case AcknowledgeFailure.NotActive(id) => Fail.Conflict(s"Alert $id is no longer active")

object AlertsApi:
  private val DefaultPageSize = 50

  /** The `{id}:acknowledge` path segment. Passed to `path` explicitly rather than left implicit: the ambient `Codec.long` would otherwise
    * win and reject the whole segment, because it cannot see past the verb.
    */
  private val acknowledgeTarget: Codec[String, Long, CodecFormat.TextPlain] =
    CustomMethod.on("acknowledge", raw => raw.toLongOption.toRight(s"Not an alert id: $raw"), _.toString)

  val listEndpoint: PublicEndpoint[(Int, Option[AlertSeverity], Boolean), Fail, Alerts_OUT, Any] =
    Http.baseEndpoint.get
      .in("alerts")
      .in(query[Int]("pageSize").default(DefaultPageSize))
      .in(query[Option[AlertSeverity]]("severity"))
      .in(query[Boolean]("activeOnly").default(false))
      .out(jsonBody[Alerts_OUT])
      .summary("List raised alerts, most recent first")
      .tag("alerts")

  val rulesEndpoint: PublicEndpoint[Unit, Fail, AlertRules_OUT, Any] =
    Http.baseEndpoint.get
      .in("alertRules")
      .out(jsonBody[AlertRules_OUT])
      .summary("List the alert rules this relay was configured with")
      .tag("alerts")

  val acknowledgeEndpoint: PublicEndpoint[Long, Fail, Alert_OUT, Any] =
    Http.baseEndpoint.post
      .in("alerts" / path[Long]("id:acknowledge")(using acknowledgeTarget))
      .out(jsonBody[Alert_OUT])
      .summary("Acknowledge an active alert")
      .description("Stops it counting as active without pretending its cause is fixed; the rule still watches.")
      .tag("alerts")
