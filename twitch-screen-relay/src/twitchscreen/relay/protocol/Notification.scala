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

/** A notification that has been accepted by the relay and given its place in the stream, as the management API sees it. Only
  * [[twitchscreen.relay.device.DeviceHub]] constructs these, because only it can assign a sequence number.
  *
  * This is the HTTP projection of an [[EventRecord]], not the wire record: `title` is the record's `actor` and `body` is its `text`. The
  * structured numbers of §6.4 — `value`, `months`, `tier`, `eflags` — are on the wire and deliberately not in this JSON, which has always
  * been a title-and-body vocabulary and stays one.
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

object Notification:
  private[relay] def from(record: EventRecord): Notification =
    Notification(
      seq = record.seq,
      id = NotificationId.forSeq(record.seq),
      kind = record.kind,
      title = record.actor,
      body = record.text,
      at = record.at.getOrElse(Instant.EPOCH),
      ttl = record.ttl.duration
    )

/** A notification that has not been sequenced yet — what the HTTP API hands to the hub.
  *
  * §6.4.3 keeps `POST /api/v1/notifications` working unchanged: the posted title lands in `actor`, the posted body in `text`, and every
  * numeric field stays 0, **whatever kind was named**. A hand-written `{"type":"raid","title":"…"}` test card is therefore rendered as
  * written rather than silently replaced by "raided with 0 viewers".
  */
final case class NotificationRequest(kind: NotificationKind, title: String, body: String, ttl: FiniteDuration)

/** Everything needed to build one `EVENT` payload (§6.4) except its sequence number, which only the hub may assign.
  *
  * This is what a Twitch event becomes on its way to the wire. It exists because §6.4's numeric fields — the raider's viewer count, the
  * bits amount, the sub tier and its month count, the gifted-sub count, the chat colour — have to survive the trip as numbers. Flattening
  * them into an English sentence, as the v2 router did, put them somewhere the device cannot read and left the structured fields at zero.
  */
private[relay] final case class EventRequest(
    kind: NotificationKind,
    actor: String,
    text: String,
    ttl: FiniteDuration,
    value: EventValue = EventValue.Zero,
    months: SubMonths = SubMonths.Zero,
    tier: SubTier = SubTier.NotApplicable,
    flags: EventFlags = EventFlags.Empty
):
  /** `actor` and `text` are the values *before* truncation: [[Tsb3Encoder]] cuts them to the field widths per §9.2 and sets the matching
    * `eflags` bit, so a record whose flags disagree with its bytes cannot be constructed.
    */
  def record(seq: SeqNo, at: Instant): EventRecord =
    EventRecord(
      seq = seq,
      at = Some(at),
      value = value,
      months = months,
      ttl = DisplayTtl.fromDuration(ttl),
      kind = kind,
      tier = tier,
      flags = flags,
      actor = actor,
      text = text
    )

private[relay] object EventRequest:
  /** A Twitch-sourced event, which takes §6.4.1's per-kind display time rather than naming one of its own.
    *
    * The ttl is derived from the kind here, in one place, because the kind is the only thing that determines it and a caller that could
    * pass its own would be a caller that could disagree with the vectors of §18.
    */
  def of(
      kind: NotificationKind,
      actor: String,
      text: String,
      value: EventValue = EventValue.Zero,
      months: SubMonths = SubMonths.Zero,
      tier: SubTier = SubTier.NotApplicable,
      flags: EventFlags = EventFlags.Empty
  ): EventRequest =
    EventRequest(kind, actor, text, DisplayTtl.defaultFor(kind), value, months, tier, flags)

  /** §6.4.3's generic path. Every numeric field stays 0 whatever kind the caller named, and the posted `ttl` is honoured as posted. */
  def card(request: NotificationRequest): EventRequest =
    EventRequest(kind = request.kind, actor = request.title, text = request.body, ttl = request.ttl)
