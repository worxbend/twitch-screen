package twitchscreen.relay.protocol

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import sttp.tapir.Schema

/** Stable identifier of a notification, derived from its sequence number so it survives a restart of the reader. */
opaque type NotificationId = String

object NotificationId:
  def forSeq(seq: SeqNo): NotificationId = f"ntf-${seq.value}%04d"

  extension (id: NotificationId) def value: String = id

  given Schema[NotificationId] = Schema.schemaForString.as[NotificationId]

/** A notification that has been accepted by the relay and given its place in the stream. Only [[twitchscreen.relay.device.DeviceHub]]
  * constructs these, because only it can assign a sequence number.
  */
final case class Notification(
    seq: SeqNo,
    id: NotificationId,
    kind: NotificationKind,
    title: String,
    body: String,
    at: Instant,
    ttl: FiniteDuration
)

/** A notification that has not been sequenced yet — what an event source or the HTTP API hands to the hub. */
final case class NotificationRequest(kind: NotificationKind, title: String, body: String, ttl: FiniteDuration)
