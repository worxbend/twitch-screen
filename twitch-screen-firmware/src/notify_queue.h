#pragma once

#include <stddef.h>
#include <stdint.h>

#include "notification.h"

// The device's notification queue and its sequence accounting — docs/PROTOCOL.md
// §10.2 and §10.5. This is in a header rather than inside main.cpp because it is
// the part of the firmware that replay actually depends on, and a rule that
// cannot be tested is a rule that quietly stops holding.
//
// The two §10.5 requirements, stated as this class implements them:
//
//   1. `lastSeq` advances only AFTER an event has been successfully enqueued for
//      display, never at the moment of decode.
//   2. On overflow the NEWEST event is refused. The oldest is NOT evicted:
//      evicting it would advance the high-water mark past a card that was never
//      drawn, and the reconnect replay could then never bring it back.
//
// A third rule follows from the second and is easy to miss. Once an event has
// been refused, `lastSeq` must not advance PAST it either, or the next HELLO
// asks the relay to replay from a point beyond the hole. So a refusal opens a
// gap: later events are still queued and still shown — refusing them too would
// lose more, not less — but the reported high-water mark stays frozen until the
// next greet, whose replay burst begins exactly at the refused event. The price
// is a few duplicate cards after a reconnect. The alternative is an event that
// nobody ever sees and that nothing can recover.
template <size_t CAP>
class NotifyQueue {
 public:
  enum class Greet : uint8_t { Rebaselined, Resumed };
  enum class Offer : uint8_t { Enqueued, Duplicate, Refused };

  NotifyQueue()
      : head_(0), count_(0), lastSeq_(0), sessionId_(0),
        sessionKnown_(false), sessionChanged_(false), gapOpen_(false),
        shown_(0), refused_(0) {}

  // §6.1 / §6.6: the highest seq successfully ENQUEUED FOR DISPLAY. This exact
  // value goes out in HELLO.last_seq and in ACK.seq.
  uint32_t lastSeq() const { return lastSeq_; }
  size_t   size()    const { return count_; }
  bool     gapOpen() const { return gapOpen_; }
  uint32_t shown()   const { return shown_; }
  uint32_t refused() const { return refused_; }

  // §10.2. Exactly two conditions, both computed from last_seq and latest_seq,
  // because §10.3's replay precondition on the relay is the same predicate over
  // the same two numbers: the relay replays on precisely the greets where this
  // returns Resumed, and sends nothing on precisely the greets where it returns
  // Rebaselined.
  //
  // session_id is deliberately NOT one of them. An earlier draft of the spec made
  // a differing session_id a third trigger, but HELLO carries no session_id echo,
  // so the relay has no field to compare against and could not implement the
  // matching half. The two sides then tested different things: after a relay
  // restart the relay pushed up to 80 replayed EVENTs while this queue, having
  // just re-baselined to latest_seq, dropped every one of them as a duplicate —
  // 14 kB on the wire, the outbound queue spent, and nothing drawn. The value is
  // still remembered and still logged, because "the relay restarted under me" is
  // the first thing an operator wants from a serial log.
  Greet greet(uint32_t latestSeq, uint32_t sessionId) {
    const bool freshBoot   = (lastSeq_ == 0);
    const bool relayBehind = (latestSeq < lastSeq_);
    sessionChanged_ = (!sessionKnown_ || sessionId != sessionId_);

    sessionId_ = sessionId;
    sessionKnown_ = true;
    // The replay burst starts at lastSeq_ + 1, which is the very event the gap
    // was opened on, so the freeze has served its purpose either way.
    gapOpen_ = false;

    if (freshBoot || relayBehind) {
      lastSeq_ = latestSeq;   // whatever is already queued stays queued
      return Greet::Rebaselined;
    }
    return Greet::Resumed;
  }

  // True when the last greet came from a relay process this device had not seen
  // before. Nothing in §10.2 depends on it; main logs the transition.
  bool sessionChanged() const { return sessionChanged_; }

  Offer offer(const Notification &n) {
    if (n.seq <= lastSeq_) return Offer::Duplicate;

    if (count_ == CAP) {
      ++refused_;
      gapOpen_ = true;
      return Offer::Refused;          // refuse the NEWEST; evict nothing
    }

    buf_[(head_ + count_) % CAP] = n;
    ++count_;
    ++shown_;
    if (!gapOpen_) lastSeq_ = n.seq;  // advance only on a successful enqueue
    return Offer::Enqueued;
  }

  bool take(Notification &out) {
    if (count_ == 0) return false;
    out = buf_[head_];
    head_ = (head_ + 1) % CAP;
    --count_;
    return true;
  }

 private:
  Notification buf_[CAP];
  size_t   head_;
  size_t   count_;
  uint32_t lastSeq_;
  uint32_t sessionId_;
  bool     sessionKnown_;
  bool     sessionChanged_;
  bool     gapOpen_;
  uint32_t shown_;
  uint32_t refused_;
};
