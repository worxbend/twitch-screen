package twitchscreen.relay.device

import java.util.concurrent.ThreadLocalRandom

/** One process-local clock domain. Wall-clock corrections cannot extend retained publication lifetimes. */
private[device] final class RelayTimeline(
    val epoch: Long = RelayTimeline.randomEpoch(),
    nanoTime: () => Long = () => System.nanoTime()
):
  require(epoch != 0, "relay epoch must be nonzero")
  private val started = nanoTime()

  /** Starts a new clock domain without deriving lifetime from the prior domain. */
  def renewed(epoch: Long): RelayTimeline =
    require(epoch != this.epoch, "replacement epoch must differ")
    RelayTimeline(epoch, nanoTime)

  def nowMs: Long = 1L + (nanoTime() - started) / 1000000L

private[device] object RelayTimeline:
  def randomEpoch(): Long =
    var value = ThreadLocalRandom.current().nextLong()
    while value == 0 do value = ThreadLocalRandom.current().nextLong()
    value
