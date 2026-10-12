#include <algorithm>
#include <stdio.h>
#include <vector>
#include "runtime_metrics.h"

namespace {
struct Checker {
  int checks = 0, failures = 0;
  void operator()(bool ok, const char *what) {
    ++checks;
    if (!ok) { ++failures; printf("FAIL: %s\n", what); }
  }
};
}
int main() {
  Checker check;
  desk::TimingHistogram h;
  check(h.p95UpperUs() == UINT32_MAX && h.samples() == 0, "missing samples unavailable");
  for (unsigned i = 0; i < 95; ++i) h.observe(5000);
  for (unsigned i = 0; i < 5; ++i) h.observe(200001);
  check(h.samples() == 100 && h.p95UpperUs() == 5000, "nearest-rank p95 exact target");
  h.observe(200001);
  check(h.p95UpperUs() == 200001 && h.maximum() == 200001, "overflow bucket uses actual maximum");
  desk::RuntimeMetrics metrics;
  check(!metrics.resourcesObserved() && metrics.freeHeapMinimum() == UINT32_MAX, "unobserved resources not zero");
  metrics.loopStarted(UINT32_MAX - 999);
  check(metrics.loop().samples() == 0, "boot does not fabricate loop interval");
  metrics.loopStarted(4000);
  check(metrics.loop().maximum() == 5000, "normal micros wrap");
  metrics.uiFinished(UINT32_MAX - 99, 150);
  check(metrics.ui().maximum() == 250, "UI timing wrap");
  metrics.sampleResources(50000, 20000, 3000);
  metrics.sampleResources(45000, 22000, 2500);
  metrics.sampleResources(48000, 16000, 2800);
  check(metrics.resourcesObserved() && metrics.freeHeapMinimum() == 45000 &&
        metrics.largestBlockMinimum() == 16000 && metrics.stackMinimum() == 2500, "independent sampled resource minima");
  metrics.sampleResources(0, 0, 0);
  check(metrics.freeHeapMinimum() == 0 && metrics.stackMinimum() == 0, "zero observations remain real");
  check(sizeof(desk::RuntimeMetrics) <= 192, "bounded static memory");
  desk::TimingHistogram random;
  std::vector<uint32_t> samples;
  uint32_t seed = 1;
  for (unsigned i = 0; i < 2000; ++i) {
    seed = seed * 1664525U + 1013904223U;
    const uint32_t value = seed % 250001;
    samples.push_back(value); random.observe(value);
    std::vector<uint32_t> sorted = samples;
    std::sort(sorted.begin(), sorted.end());
    const size_t rank = (sorted.size() * 95 + 99) / 100;
    check(random.p95UpperUs() >= sorted[rank - 1] && random.maximum() == sorted.back(), "histogram never understates exact p95");
  }
  printf("%d checks, %d failures\n", check.checks, check.failures);
  return check.failures ? 1 : 0;
}
