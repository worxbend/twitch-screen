package twitchscreen.relay.device

import twitchscreen.relay.protocol.SeqNo

/** Actor-confined u32 allocator: exhaustion never wraps or changes the high-water mark. */
private[device] final class SequenceAllocator(initial: SeqNo):
  private var current = initial
  def latest: SeqNo = current
  def allocate(): Option[SeqNo] = current.next.map: next =>
    current = next
    next
