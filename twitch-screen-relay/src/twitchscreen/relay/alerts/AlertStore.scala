package twitchscreen.relay.alerts

import java.time.Instant
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import ox.discard

/** A bounded history of raised alerts, with at most one active alert per rule so a condition that persists for an hour produces one entry
  * rather than 240. Writes come from the single monitor fork; reads from HTTP threads.
  */
private[relay] final class AlertStore(capacity: Int):
  private val lastId = AtomicLong(0)
  private val alerts = AtomicReference(Vector.empty[Alert])

  /** Records a new firing unless this rule is already active. Returns the alert only when it was newly raised. */
  def raise(rule: AlertRule, message: String, at: Instant): Option[Alert] =
    if activeFor(rule.name).isDefined then None
    else
      val alert = Alert(lastId.incrementAndGet(), rule.name, rule.severity, message, at, AlertStatus.Active)
      alerts.updateAndGet(current => (current :+ alert).takeRight(capacity)).discard
      Some(alert)

  /** Closes the active alert for a rule whose condition no longer holds. */
  def resolve(ruleName: String, at: Instant): Option[Alert] =
    activeFor(ruleName).map: active =>
      val resolved = active.copy(status = AlertStatus.Resolved(at))
      replace(resolved)
      resolved

  def acknowledge(id: Long, at: Instant): Either[AcknowledgeFailure, Alert] =
    alerts.get().find(_.id == id) match
      case None                                  => Left(AcknowledgeFailure.NotFound(id))
      case Some(alert) if !alert.status.isActive => Left(AcknowledgeFailure.NotActive(id))
      case Some(alert) =>
        val acknowledged = alert.copy(status = AlertStatus.Acknowledged(at))
        replace(acknowledged)
        Right(acknowledged)

  /** Most recent first. */
  def recent(limit: Int, severity: Option[AlertSeverity], activeOnly: Boolean): List[Alert] =
    alerts
      .get()
      .reverseIterator
      .filter(alert => severity.forall(_ == alert.severity))
      .filter(alert => !activeOnly || alert.status.isActive)
      .take(limit)
      .toList

  def activeCount: Int = alerts.get().count(_.status.isActive)

  private def activeFor(ruleName: String): Option[Alert] =
    alerts.get().find(alert => alert.rule == ruleName && alert.status.isActive)

  private def replace(alert: Alert): Unit =
    alerts.updateAndGet(_.map(existing => if existing.id == alert.id then alert else existing)).discard
