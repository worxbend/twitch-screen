package twitchscreen.relay.device

import java.time.Instant
import twitchscreen.relay.protocol.DeviceId

/** Actor-confined, per-identity rolling window. Expired identities are removed on admission. */
private[device] final class ReclaimPolicy:
  private var recent = Map.empty[DeviceId, Vector[Instant]]

  def allow(device: DeviceId, now: Instant): Boolean =
    val cutoff = now.minusSeconds(60)
    recent = recent.flatMap: (id, times) =>
      val active = times.dropWhile(!_.isAfter(cutoff))
      Option.when(active.nonEmpty)(id -> active)
    val times = recent.getOrElse(device, Vector.empty)
    if times.size >= 16 then false
    else
      recent = recent.updated(device, times :+ now)
      true
