package twitchscreen.relay.twitch

import java.util.concurrent.ThreadLocalRandom
import scala.annotation.tailrec
import scala.concurrent.duration.{DurationLong, FiniteDuration}

/** Bounded exponential retries with jitter; interruption of pause propagates to the owning application scope. */
private[twitch] object TwitchRetry:
  def untilReady[A](
      attempt: () => Either[String, A],
      failed: String => Unit,
      pause: FiniteDuration => Unit,
      jitter: Long => Long = upper => ThreadLocalRandom.current().nextLong(upper + 1)
  ): A =
    @tailrec def loop(retry: Int): A = attempt() match
      case Right(value) => value
      case Left(reason) =>
        failed(reason)
        val ceiling = (1L << math.min(retry, 6)).min(60L) * 1000L
        val half = ceiling / 2
        pause((half + math.max(0L, math.min(half, jitter(half)))).millis)
        loop(math.min(retry + 1, 6))
    loop(0)
