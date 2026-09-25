package twitchscreen.relay.twitch

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import twitchscreen.relay.http.Fail

/** Retains every accepted ID for the freshness window. Saturation fails delivery so Twitch can retry; live entries are never evicted. */
private[twitch] final class WebhookDeduplication(capacity: Int = 4096, lifetime: Duration = Duration.ofMinutes(10)):
  private val entries = AtomicReference(Map.empty[String, Instant])

  @tailrec final def claim(id: String, now: Instant): Either[Fail, Boolean] =
    val before = entries.get()
    val live = before.filter((_, expires) => expires.isAfter(now))
    if live.contains(id) then Right(false)
    else if live.size >= capacity then Left(Fail.Unavailable("Webhook deduplication capacity reached; retry delivery"))
    else if entries.compareAndSet(before, live.updated(id, now.plus(lifetime))) then Right(true)
    else claim(id, now)
