#pragma once

#include <atomic>
#include <stdint.h>

// One loop-thread request, completed by serialized lwIP callbacks. Cancellation
// invalidates the token even when a callback is lost or arrives after a retry.
class DnsLookup {
 public:
  enum class State : uint8_t { Idle, Pending, Ready, Failed };

  uint32_t begin() {
    const uint32_t token = (request_.load(std::memory_order_acquire) & ~STATE_MASK) +
                          GENERATION_STEP + static_cast<uint32_t>(State::Pending);
    request_.store(token, std::memory_order_release);
    return token;
  }
  void cancel() { request_.fetch_and(~STATE_MASK, std::memory_order_acq_rel); }
  bool matches(uint32_t token) const {
    return request_.load(std::memory_order_acquire) == token;
  }
  void complete(uint32_t token, bool found, uint32_t address = 0) {
    if (!matches(token)) return;
    if (found) address_.store(address, std::memory_order_relaxed);
    const uint32_t result = (token & ~STATE_MASK) |
        static_cast<uint32_t>(found ? State::Ready : State::Failed);
    request_.compare_exchange_strong(token, result, std::memory_order_release);
  }
  State state() const {
    return static_cast<State>(request_.load(std::memory_order_acquire) & STATE_MASK);
  }
  uint32_t address() const { return address_.load(std::memory_order_relaxed); }

 private:
  static constexpr uint32_t STATE_MASK = 3;
  static constexpr uint32_t GENERATION_STEP = STATE_MASK + 1;
  std::atomic<uint32_t> request_{0};
  std::atomic<uint32_t> address_{0};
};
