package twitchscreen.relay.twitch

import java.security.SecureRandom
import java.time.{Clock, Duration, Instant}
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import ox.discard

/** Single-use, expiring CSRF states. A CAS transition bounds concurrent consent starts as well as sequential ones. */
private[twitch] final class PendingAuthorizations(clock: Clock, random: SecureRandom):
  private val states = AtomicReference(Map.empty[String, Instant])

  def issue(): String =
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    val state = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    val now = clock.instant()
    states
      .updateAndGet: before =>
        val live = before.filter((_, expires) => expires.isAfter(now)).toList.sortBy(_._2).takeRight(15).toMap
        live.updated(state, now.plus(Duration.ofMinutes(10)))
      .discard
    state

  def consume(state: String): Either[AuthorizationFailure, Unit] =
    val before = states.getAndUpdate(_ - state)
    before
      .get(state)
      .filter(_.isAfter(clock.instant()))
      .toRight(AuthorizationFailure.InvalidState)
      .map(_ => ())
