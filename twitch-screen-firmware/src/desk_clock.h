#pragma once

#include <stdint.h>
#include <limits.h>

namespace desk {

// Extend Arduino millis without mistaking its normal wrap for a suspend.
// Call on every loop; multiple unobserved wraps cannot be reconstructed.
class MillisClock {
 public:
  uint64_t update(uint32_t raw) {
    gap_ = false;
    if (!initialized_) {
      initialized_ = true;
      value_ = raw;
    } else {
      const uint32_t elapsed = raw - previous_;
      gap_ = elapsed > 60000u;
      value_ += elapsed;
    }
    previous_ = raw;
    return value_;
  }
  bool discontinuity() const { return gap_; }
 private:
  bool initialized_ = false;
  bool gap_ = false;
  uint32_t previous_ = 0;
  uint64_t value_ = 0;
};

// Unsigned checked translation avoids narrowing untrusted u64 wire values to
// signed offsets. The equivalent signed expression is base + (value-origin).
inline bool translate(uint64_t base, uint64_t value, uint64_t origin, uint64_t &out) {
  if (value >= origin) {
    const uint64_t delta = value - origin;
    if (delta > UINT64_MAX - base) return false;
    out = base + delta;
  } else {
    const uint64_t delta = origin - value;
    if (delta > base) return false;
    out = base - delta;
  }
  return true;
}

class Calibration {
 public:
  void invalidate() { valid_ = false; pending_ = false; }
  // Only one outstanding probe. The owner supplies current session identity.
  void request(uint64_t epoch, uint64_t connection, uint64_t deviceMs) {
    if (epoch != epoch_ || connection != connection_) valid_ = false;
    epoch_ = epoch; connection_ = connection; probe_ = deviceMs; pending_ = true;
  }
  bool accept(uint64_t epoch, uint64_t connection, uint64_t echo,
              uint64_t relayMs, uint64_t receiptMs) {
    if (!pending_ || epoch != epoch_ || connection != connection_ || echo != probe_ ||
        receiptMs < probe_ || receiptMs - probe_ > 1000u) return false;
    d0_ = probe_; d1_ = receiptMs; r_ = relayMs;
    pending_ = false; valid_ = true;
    return true;
  }
  bool usable(uint64_t now) const {
    return valid_ && now >= d1_ && now - d1_ <= 60000u;
  }
  // Lifetime is bounded from acceptance, which may follow the calibration
  // sample. Allow the elapsed calibration age/RTT and its 1000 ppm rate bound;
  // usable() limits this arithmetic to 61000 ms. Past/invalid values fail closed.
  uint64_t deadline(uint64_t relayDeadline, uint64_t now) const {
    if (!usable(now)) return 0;
    const uint64_t distance = relayDeadline >= r_ ? relayDeadline - r_ : r_ - relayDeadline;
    const uint64_t elapsed = now - d0_;
    const uint64_t horizon = 1800000u + elapsed + (elapsed + 999u) / 1000u;
    if (distance > horizon) return 0;
    const uint64_t guard = 50u + (distance + d1_ - d0_ + 999u) / 1000u;
    uint64_t local = 0;
    if (!translate(d0_, relayDeadline, r_, local) || local <= guard) return 0;
    local -= guard;
    return local > now ? local : 0;
  }
  // Lower offset r-d1, aged with the same relative-rate/scheduling allowance.
  uint64_t observationLower(uint64_t now) const {
    if (!usable(now)) return 0;
    uint64_t lower = 0;
    const uint64_t guard = 50u + (now - d0_ + 999u) / 1000u;
    if (!translate(r_, now, d1_, lower) || lower <= guard) return 0;
    return lower - guard;
  }
  bool matches(uint64_t epoch, uint64_t connection) const {
    return valid_ && epoch == epoch_ && connection == connection_;
  }
  uint64_t rtt() const { return d1_ - d0_; }
 private:
  bool valid_ = false;
  bool pending_ = false;
  uint64_t epoch_ = 0, connection_ = 0, probe_ = 0;
  uint64_t d0_ = 0, d1_ = 0, r_ = 0;
};

inline uint64_t shortenDeadline(uint64_t accepted, uint64_t recalibrated) {
  return recalibrated < accepted ? recalibrated : accepted;
}

}  // namespace desk
