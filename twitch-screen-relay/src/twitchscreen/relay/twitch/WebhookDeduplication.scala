package twitchscreen.relay.twitch

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import ox.discard
import twitchscreen.relay.http.Fail

private[twitch] enum WebhookClaim:
  case Fresh, Duplicate

/** Claims remain in flight until dispatch succeeds. Concurrent redelivery retries rather than acknowledging work that could still fail. */
private[twitch] final class WebhookDeduplication(capacity: Int = 4096, lifetime: Duration = Duration.ofMinutes(10)):
  private enum Delivery:
    case InFlight, Delivered
  private final case class Entry(expiresAt: Instant, delivery: Delivery)
  private final case class Entries(values: Map[String, Entry] = Map.empty, nextExpiry: Instant = Instant.MAX):
    def prune(now: Instant): Entries =
      if nextExpiry.isAfter(now) then this
      else
        val live = values.filter((_, entry) => entry.expiresAt.isAfter(now))
        Entries(live, live.valuesIterator.map(_.expiresAt).minOption.getOrElse(Instant.MAX))
  private val entries = AtomicReference(Entries())

  def claim(id: String, now: Instant): Either[Fail, WebhookClaim] = claim(id, now, now.plus(lifetime))

  @tailrec final def claim(id: String, now: Instant, expiresAt: Instant): Either[Fail, WebhookClaim] =
    val before = entries.get()
    val live = before.prune(now)
    live.values.get(id) match
      case Some(Entry(_, Delivery.Delivered))   => Right(WebhookClaim.Duplicate)
      case Some(_)                              => Left(Fail.Unavailable("Webhook dispatch is in progress; retry delivery"))
      case None if live.values.size >= capacity => Left(Fail.Unavailable("Webhook deduplication capacity reached; retry delivery"))
      case None =>
        val next = Entries(
          live.values.updated(id, Entry(expiresAt, Delivery.InFlight)),
          if expiresAt.isBefore(live.nextExpiry) then expiresAt else live.nextExpiry
        )
        if entries.compareAndSet(before, next) then Right(WebhookClaim.Fresh) else claim(id, now, expiresAt)

  def complete(id: String): Unit = entries
    .updateAndGet: before =>
      before.values
        .get(id)
        .fold(before)(entry => before.copy(values = before.values.updated(id, entry.copy(delivery = Delivery.Delivered))))
    .discard

  def release(id: String): Unit = entries.updateAndGet(before => before.copy(values = before.values - id)).discard
