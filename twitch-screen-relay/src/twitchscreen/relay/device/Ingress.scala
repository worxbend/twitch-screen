package twitchscreen.relay.device

/** The canonical calibrated producer domain. JSON uses fixed-width hexadecimal epochs and safely representable numeric milliseconds. */
private[relay] final case class RelayClock(relayEpoch: Long, monotonicMs: Long):
  require(relayEpoch != 0 && monotonicMs >= 0 && monotonicMs <= 9007199254740991L)
  def epochHex: String = f"$relayEpoch%016x"

private[device] enum IngressFailure:
  case EpochChanged, Expired, RateLimited, SequenceSpent, RevisionSpent

/** Actor-confined global 10/s token bucket with burst 20. Invalid and expired writes never debit admission. */
private[device] final class IngressRateLimit(ratePerSecond: Long = 10L, burst: Long = 20L):
  require(ratePerSecond >= 1 && ratePerSecond <= 1000 && burst >= 1 && burst <= 1000)
  private val capacity = burst * 1000L
  private val fullRefillMs = (capacity + ratePerSecond - 1L) / ratePerSecond
  private var tokens = capacity
  private var sampled = 0L
  def admit(nowMs: Long): Boolean =
    require(nowMs >= sampled, "ingress clock must not regress")
    tokens = math.min(capacity, tokens + math.min(fullRefillMs, nowMs - sampled) * ratePerSecond)
    sampled = nowMs
    if tokens < 1000L then false
    else
      tokens -= 1000L
      true
