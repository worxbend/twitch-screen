package twitchscreen.relay.protocol

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

private[relay] object EventRecords:
  /** The generic kinds and the `POST /api/v1/notifications` path (§6.4.3): the posted title and body travel as `actor` and `text` with the
    * numeric fields at 0, whatever kind was named, so that a hand-written card is never replaced by a composed sentence.
    */
  def card(seq: SeqNo, kind: NotificationKind, title: String, body: String, at: Instant, ttl: FiniteDuration): EventRecord =
    base(seq, kind, at, ttl).copy(actor = title, text = body)

  def streamStart(seq: SeqNo, channel: String, title: String, startedAt: Instant, at: Instant, ttl: FiniteDuration): EventRecord =
    base(seq, NotificationKind.StreamStart, at, ttl)
      .copy(value = EventValue.clamp(startedAt.getEpochSecond), actor = channel, text = title)

  def streamEnd(seq: SeqNo, channel: String, duration: FiniteDuration, at: Instant, ttl: FiniteDuration): EventRecord =
    base(seq, NotificationKind.StreamEnd, at, ttl).copy(value = EventValue.clamp(duration.toSeconds), actor = channel)

  /** `value` is fixed at 0: a follower total of 0 would be indistinguishable from "not reported", so totals live in `STATS`. */
  def follow(seq: SeqNo, follower: String, at: Instant, ttl: FiniteDuration): EventRecord =
    base(seq, NotificationKind.Follow, at, ttl).copy(actor = follower)

  def sub(
      seq: SeqNo,
      subscriber: String,
      tier: SubTier,
      months: SubMonths,
      message: String,
      at: Instant,
      ttl: FiniteDuration
  ): EventRecord =
    base(seq, NotificationKind.Sub, at, ttl).copy(months = months, tier = tier, actor = subscriber, text = message)

  def gift(seq: SeqNo, gifter: String, tier: SubTier, count: Int, anonymous: Boolean, at: Instant, ttl: FiniteDuration): EventRecord =
    val flags = if anonymous then EventFlags.Anonymous else EventFlags.Empty
    base(seq, NotificationKind.Gift, at, ttl).copy(value = EventValue.clamp(count.toLong), tier = tier, flags = flags, actor = gifter)

  def raid(seq: SeqNo, raider: String, viewers: Int, at: Instant, ttl: FiniteDuration): EventRecord =
    base(seq, NotificationKind.Raid, at, ttl).copy(value = EventValue.clamp(viewers.toLong), actor = raider)

  def chat(seq: SeqNo, chatter: String, message: String, colour: Option[ChatColour], at: Instant, ttl: FiniteDuration): EventRecord =
    val flags = colour.fold(EventFlags.Empty)(_ => EventFlags.ChatColourPresent)
    base(seq, NotificationKind.Chat, at, ttl)
      .copy(value = EventValue.clamp(colour.fold(0L)(_.rgb.toLong)), flags = flags, actor = chatter, text = message)

  /** Bits are a plain count. This protocol carries no monetary amount of any kind (§8). */
  def bits(seq: SeqNo, sender: String, amount: Int, message: String, at: Instant, ttl: FiniteDuration): EventRecord =
    base(seq, NotificationKind.Bits, at, ttl).copy(value = EventValue.clamp(amount.toLong), actor = sender, text = message)

  private def base(seq: SeqNo, kind: NotificationKind, at: Instant, ttl: FiniteDuration): EventRecord =
    EventRecord(
      seq = seq,
      at = Some(at),
      value = EventValue.Zero,
      months = SubMonths.Zero,
      ttl = DisplayTtl.fromDuration(ttl),
      kind = kind,
      tier = SubTier.NotApplicable,
      flags = EventFlags.Empty,
      actor = "",
      text = ""
    )
