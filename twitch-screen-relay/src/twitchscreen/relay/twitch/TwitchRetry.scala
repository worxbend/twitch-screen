package twitchscreen.relay.twitch

import scala.concurrent.duration.{DurationLong, FiniteDuration}

/** Infinite supervised retries with bounded delay; interruption of pause propagates to the owning application scope. */
private[twitch] object TwitchRetry:
  def untilReady[A](attempt: () => Either[String, A], failed: String => Unit, pause: FiniteDuration => Unit): A =
    var retry = 0
    var result = attempt()
    while result.isLeft do
      result.left.foreach(failed)
      pause((1L << math.min(retry, 6)).min(60L).seconds)
      retry = math.min(retry + 1, 6)
      result = attempt()
    result match
      case Right(value) => value
      case Left(_)      => throw IllegalStateException("retry loop exited before success")
