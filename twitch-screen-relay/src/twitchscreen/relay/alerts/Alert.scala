package twitchscreen.relay.alerts

import java.time.Instant
import sttp.tapir.Schema

/** Where an alert stands. A closed ADT rather than nullable timestamps, so "resolved but never raised" cannot exist. */
enum AlertStatus:
  case Active
  case Acknowledged(at: Instant)
  case Resolved(at: Instant)

object AlertStatus:
  given Schema[AlertStatus] = Schema.derived

  extension (status: AlertStatus)
    def isActive: Boolean = status match
      case Active => true
      case _      => false

/** One firing of one rule. */
final case class Alert(
    id: Long,
    rule: String,
    severity: AlertSeverity,
    message: String,
    raisedAt: Instant,
    status: AlertStatus
) derives Schema

/** Why an acknowledgement could not be applied. */
enum AcknowledgeFailure:
  case NotFound(id: Long)
  case NotActive(id: Long)
