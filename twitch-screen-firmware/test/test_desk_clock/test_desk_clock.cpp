#include <stdio.h>
#include "desk_clock.h"

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
  desk::MillisClock clock;
  check(clock.update(0xfffffff0u) == 0xfffffff0u, "first raw sample");
  check(clock.update(20) == UINT64_C(4294967316) && !clock.discontinuity(), "normal u32 wrap");
  check(clock.update(60020) == UINT64_C(4295027316) && !clock.discontinuity(), "60 second boundary");
  clock.update(120021);
  check(clock.discontinuity(), "long loop gap detected");
  clock.update(120022);
  check(!clock.discontinuity(), "gap is one observation, not permanent wrap failure");

  desk::Calibration c;
  check(!c.usable(0) && c.deadline(2000, 0) == 0, "no uncalibrated fallback");
  c.request(1, 2, 100);
  check(!c.accept(2, 2, 100, 1000, 120), "old epoch rejected");
  check(!c.accept(1, 3, 100, 1000, 120), "old connection rejected");
  check(!c.accept(1, 2, 99, 1000, 120), "wrong probe rejected");
  check(!c.accept(1, 2, 100, 1000, 99), "backward clock rejected");
  check(!c.accept(1, 2, 100, 1000, 1101), "slow calibration rejected");
  check(c.accept(1, 2, 100, 1000, 120), "matching calibration accepted");
  check(!c.accept(1, 2, 100, 1000, 121), "duplicate response rejected");
  check(c.rtt() == 20 && c.usable(60120) && !c.usable(60121), "RTT and age boundary");
  check(c.deadline(2000, 120) == 1048, "asymmetric-safe deadline with rounded guard");
  check(c.deadline(2000, 1048) == 0, "deadline equality expired");
  check(c.deadline(0, 120) == 0 && c.deadline(UINT64_MAX, 120) == 0, "past and unbounded deadlines fail closed");
  check(c.observationLower(120) == 949, "lower observation bound includes guard");
  check(c.observationLower(60121) == 0, "aged observation unknown");
  c.request(1, 2, 200);
  check(!c.accept(1, 2, 100, 1000, 220), "obsolete probe cannot replace calibration");
  check(c.accept(1, 2, 200, 1100, 220), "fresh probe accepted");
  check(desk::shortenDeadline(1048, c.deadline(2000, 220)) == 1048, "recalibration never renews deadline");
  c.request(2, 3, 230);
  check(!c.usable(230), "new session cannot reuse old calibration while probe is pending");
  check(c.accept(2, 3, 230, 1200, 250), "new session calibration accepted");
  c.invalidate();
  check(!c.usable(250), "loop/session invalidation");

  // R1: freshness starts at acceptance, not at the older calibration sample.
  desk::Calibration weatherClock;
  weatherClock.request(1, 2, 1000);
  check(weatherClock.accept(1, 2, 1000, 5000, 1100), "weather calibration accepted");
  check(weatherClock.deadline(1806000, 2100) == 1800148,
        "maximum weather freshness accepted after calibration");
  const uint64_t ages[] = {0, 1, 1000, 19999, 20000, 59999, 60000};
  for (uint64_t age : ages) {
    const uint64_t now = 1100 + age;
    const uint64_t relayDeadline = 5000 + age + 1800000;
    const uint64_t guard = 50 + (1800000 + age + 100 + 999) / 1000;
    const uint64_t expected = 1000 + 1800000 + age - guard;
    check(weatherClock.deadline(relayDeadline, now) == expected,
          "maximum freshness survives every usable calibration age");
    check(expected > now && expected - now < 1800000,
          "maximum freshness remains conservatively guarded");
  }
  check(weatherClock.deadline(1865000, 61101) == 0,
        "maximum freshness cannot bypass expired calibration");
  check(weatherClock.deadline(5000 + 1900000, 61100) == 0,
        "elapsed calibration age does not permit unbounded future lifetime");
  const uint64_t acceptedWeather = weatherClock.deadline(1806000, 2100);
  weatherClock.request(1, 2, 2100);
  check(weatherClock.accept(1, 2, 2100, 6000, 2200), "weather refresh accepted");
  check(desk::shortenDeadline(acceptedWeather, weatherClock.deadline(1806000, 2200)) == acceptedWeather,
        "maximum weather freshness is never renewed by calibration refresh");

  uint64_t out = 0;
  check(!desk::translate(UINT64_MAX, 2, 1, out), "positive translation overflow");
  check(!desk::translate(0, 0, 1, out), "negative translation underflow");
  check(desk::translate(UINT64_MAX, 1, 1, out) && out == UINT64_MAX, "full-width exact value");
  c.request(3, 4, UINT64_MAX - 100);
  check(c.accept(3, 4, UINT64_MAX - 100, UINT64_MAX - 50, UINT64_MAX - 90), "full-width calibration");
  check(c.deadline(UINT64_MAX, UINT64_MAX - 90) == 0, "guard prevents overflow at full width");

  // Exercise both signs of the offset and every allowed RTT. The deadline may
  // be early, but must never be later than the earliest causal conversion.
  for (uint64_t rtt = 0; rtt <= 1000; ++rtt) {
    desk::Calibration probe;
    probe.request(1, 1, 5000);
    check(probe.accept(1, 1, 5000, 100, 5000 + rtt), "bounded RTT accepted with negative offset");
    const uint64_t deadline = probe.deadline(2100, 5000 + rtt);
    check(deadline > 5000 + rtt && deadline < 7000, "all RTTs conservatively age future deadline");
    check(probe.observationLower(5000 + rtt) <= 100, "lower bound never assumes symmetric delay");
  }
  printf("%d checks, %d failures\n", check.checks, check.failures);
  return check.failures != 0;
}
