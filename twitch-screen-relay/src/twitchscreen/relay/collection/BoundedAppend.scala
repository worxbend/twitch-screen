package twitchscreen.relay.collection

extension [A](items: Vector[A])
  /** Appends `item` and keeps at most the newest `capacity` items, dropping the oldest first.
    *
    * Pure: callers keep their own `AtomicReference`/CAS or actor confinement. A non-positive capacity yields an empty vector, the same as
    * `takeRight`; callers validate capacity at config load (`ConfigLimits.buffer`).
    */
  private[relay] def appendBounded(item: A, capacity: Int): Vector[A] = (items :+ item).takeRight(capacity)
