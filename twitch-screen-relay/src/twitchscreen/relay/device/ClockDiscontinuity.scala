package twitchscreen.relay.device

/** Compares successive wall/monotonic deltas without converting wall time into any publication deadline. The application coordinator must
  * sample at most one second apart and perform a reliable reset, including source cancellation/recreation and alert invalidation.
  */
private[relay] final class ClockDiscontinuity(wallMillis: () => Long, nanoTime: () => Long = () => System.nanoTime()):
  private var previousWall = wallMillis()
  private var previousNanos = nanoTime()

  def sample(): Boolean = synchronized:
    val wall = wallMillis()
    val nanos = nanoTime()
    // Subtraction intentionally preserves System.nanoTime's signed-wrap interval convention.
    val elapsedNanos = nanos - previousNanos
    val wallDelta = BigInt(wall) - BigInt(previousWall)
    val monotonicDelta = BigInt(elapsedNanos) / 1000000
    previousWall = wall
    previousNanos = nanos
    elapsedNanos < 0 || (wallDelta - monotonicDelta).abs > 2000
