package twitchscreen.relay.alerts

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

/** Bounded alert history. Acknowledgement silences an alert; only resolution closes its condition. Every mutation commits one immutable
  * snapshot so concurrent HTTP acknowledgements cannot undo monitor transitions.
  */
private[relay] final class AlertStore(capacity: Int):
  private final case class State(lastId: Long, alerts: Vector[Alert])
  private val state = AtomicReference(State(0L, Vector.empty))

  def raise(rule: AlertRule, message: String, at: Instant): Option[Alert] =
    transition: current =>
      if current.alerts.exists(alert => alert.rule == rule.name && alert.status.isOpen) then (current, None)
      else
        val alert = Alert(current.lastId + 1, rule.name, rule.severity, message, at, AlertStatus.Active)
        (State(alert.id, (current.alerts :+ alert).takeRight(capacity)), Some(alert))

  def resolve(ruleName: String, at: Instant): Option[Alert] =
    transition: current =>
      current.alerts.find(alert => alert.rule == ruleName && alert.status.isOpen) match
        case None => (current, None)
        case Some(alert) =>
          val resolved = alert.copy(status = AlertStatus.Resolved(at))
          (replace(current, resolved), Some(resolved))

  def acknowledge(id: Long, at: Instant): Either[AcknowledgeFailure, Alert] =
    transition: current =>
      current.alerts.find(_.id == id) match
        case None                                  => (current, Left(AcknowledgeFailure.NotFound(id)))
        case Some(alert) if !alert.status.isActive => (current, Left(AcknowledgeFailure.NotActive(id)))
        case Some(alert) =>
          val acknowledged = alert.copy(status = AlertStatus.Acknowledged(at))
          (replace(current, acknowledged), Right(acknowledged))

  /** Most recent first; active means unacknowledged, preserving the management API's existing filter. */
  def recent(limit: Int, severity: Option[AlertSeverity], activeOnly: Boolean): List[Alert] =
    state
      .get()
      .alerts
      .reverseIterator
      .filter(alert => severity.forall(_ == alert.severity))
      .filter(alert => !activeOnly || alert.status.isActive)
      .take(limit)
      .toList

  def activeCount: Int = state.get().alerts.count(_.status.isActive)

  private def replace(current: State, alert: Alert): State =
    current.copy(alerts = current.alerts.map(existing => if existing.id == alert.id then alert else existing))

  /** Pure transitions can retry after contention; their result belongs to the snapshot actually committed. */
  @tailrec
  private def transition[A](update: State => (State, A)): A =
    val before = state.get()
    val (after, result) = update(before)
    if state.compareAndSet(before, after) then result else transition(update)
