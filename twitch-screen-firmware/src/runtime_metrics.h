// SPDX-License-Identifier: MIT
#pragma once
#include <stddef.h>
#include <stdint.h>

namespace desk {

// Fixed-memory cumulative timing observations. Bucket upper bounds are
// conservative: an upper bound above a target is not evidence of a violation.
class TimingHistogram {
 public:
  void observe(uint32_t microseconds) {
    if (samples_ == UINT32_MAX) { saturated_ = true; return; }
    ++samples_;
    if (microseconds > maximum_) maximum_ = microseconds;
    size_t bucket = 0;
    while (bucket < BoundCount && microseconds > bound(bucket)) ++bucket;
    ++counts_[bucket];
  }
  uint32_t samples() const { return samples_; }
  uint32_t maximum() const { return maximum_; }
  bool saturated() const { return saturated_; }
  uint32_t p95UpperUs() const {
    if (!samples_ || saturated_) return UINT32_MAX; // unavailable, never zero
    const uint64_t rank = (static_cast<uint64_t>(samples_) * 95 + 99) / 100;
    uint64_t count = 0;
    for (size_t index = 0; index <= BoundCount; ++index) {
      count += counts_[index];
      if (count >= rank) return index == BoundCount ? maximum_ : bound(index);
    }
    return UINT32_MAX;
  }
 private:
  static constexpr size_t BoundCount = 15;
  static uint32_t bound(size_t index) {
    static const uint32_t bounds[BoundCount] = {
      100, 250, 500, 1000, 2000, 3000, 4000, 5000,
      10000, 20000, 30000, 40000, 50000, 100000, 200000
    };
    return bounds[index];
  }
  uint32_t counts_[BoundCount + 1] = {};
  uint32_t samples_ = 0, maximum_ = 0;
  bool saturated_ = false;
};

class RuntimeMetrics {
 public:
  // Unsigned subtraction handles the normal micros() wrap. A duration spanning
  // more than one wrap cannot be recovered; watchdog/soak evidence stays separate.
  void loopStarted(uint32_t nowUs) {
    if (started_) loop_.observe(nowUs - previousLoopUs_);
    previousLoopUs_ = nowUs;
    started_ = true;
  }
  void uiFinished(uint32_t startedUs, uint32_t finishedUs) {
    ui_.observe(finishedUs - startedUs);
  }
  void sampleResources(uint32_t freeHeap, uint32_t largestBlock, uint32_t stackBytes) {
    resourcesObserved_ = true;
    if (freeHeap < freeHeapMinimum_) freeHeapMinimum_ = freeHeap;
    if (largestBlock < largestBlockMinimum_) largestBlockMinimum_ = largestBlock;
    if (stackBytes < stackMinimum_) stackMinimum_ = stackBytes;
  }
  const TimingHistogram &loop() const { return loop_; }
  const TimingHistogram &ui() const { return ui_; }
  bool resourcesObserved() const { return resourcesObserved_; }
  uint32_t freeHeapMinimum() const { return freeHeapMinimum_; }
  uint32_t largestBlockMinimum() const { return largestBlockMinimum_; }
  uint32_t stackMinimum() const { return stackMinimum_; }
 private:
  TimingHistogram loop_, ui_;
  uint32_t previousLoopUs_ = 0;
  uint32_t freeHeapMinimum_ = UINT32_MAX, largestBlockMinimum_ = UINT32_MAX, stackMinimum_ = UINT32_MAX;
  bool started_ = false, resourcesObserved_ = false;
};
} // namespace desk
