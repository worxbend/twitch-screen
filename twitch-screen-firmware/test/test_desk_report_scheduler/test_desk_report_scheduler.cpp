#include <stdio.h>
#include <string.h>
#include "desk_report_scheduler.h"

namespace {
struct Checker {
  int checks = 0, failures = 0;
  void operator()(bool ok, const char *message) {
    ++checks;
    if (!ok) { ++failures; printf("FAIL: %s\n", message); }
  }
};
struct Sink {
  std::array<uint8_t, 512> bytes{};
  size_t size = 0;
  bool reject = false;
  size_t available() const { return bytes.size() - size; }
  bool enqueue(const uint8_t *frame, size_t count) {
    if (reject || count > available()) return false;
    memcpy(bytes.data() + size, frame, count); size += count;
    return true;
  }
  void drain(size_t count) {
    if (count > size) count = size;
    memmove(bytes.data(), bytes.data() + count, size - count); size -= count;
  }
};
desk::wire::Report last(const Sink &sink, Checker &check) {
  desk::wire::Report report;
  check(sink.size >= 252, "whole report frame admitted");
  if (sink.size >= 252)
    check(desk::wire::decodeReport(sink.bytes.data() + sink.size - 244, 244, report) ==
          tsb::DecodeResult::Ok, "admitted report decodes");
  return report;
}
void ready(desk::Controller &controller, uint64_t connection, uint64_t now, Checker &check) {
  controller.welcome(1, connection, 0, now);
  controller.reconcile(now, true);
  desk::Control control;
  control.revision = control.generation = control.episode = 1;
  check(controller.control(1, connection, control), "control staged");
  desk::Freshness missing; missing.revision = 1;
  check(controller.weather(1, connection, missing) && controller.cpu(1, connection, missing),
        "missing sources staged");
  check(controller.stats(1, connection, 1, 1), "stats staged");
  desk::Sync sync; sync.display = sync.weather = sync.cpu = sync.stats = sync.generation = sync.episode = 1;
  check(controller.sync(1, connection, sync, now), "sync completed");
}
desk::Card card(uint32_t seq) {
  desk::Card result; result.epoch = 1; result.deadline = 50000;
  result.notification.seq = seq; result.notification.ttl_ds = 80;
  return result;
}
}

int main() {
  Checker check;
  desk::Controller controller;
  desk::Calibration clock;
  clock.request(1, 2, 1000);
  check(clock.accept(1, 2, 1000, 5000, 1100), "clock accepted");
  desk::ReportScheduler scheduler;
  Sink sink;
  scheduler.begin(1, 2, 3);
  check(scheduler.flush(controller, clock, 1100, sink) == desk::ReportAttempt::Invalid && !sink.size,
        "no report before coherent synchronization");
  ready(controller, 2, 1100, check);
  check(scheduler.flush(controller, clock, 1100, sink) == desk::ReportAttempt::Sent,
        "initial report immediately after sync");
  auto report = last(sink, check);
  check(report.revision == 1 && report.grant == 8 && !report.pending && report.boot == 3 &&
        report.observedDevice == 1100 && report.observedRelayLower == clock.observationLower(1100),
        "initial credit and observation derive from current state");
  sink.drain(sink.size);
  check(controller.admit(card(1), 1110) == desk::Admission::Enqueued, "first card admitted");
  scheduler.changed();
  check(scheduler.flush(controller, clock, 1299, sink) == desk::ReportAttempt::NotDue && !sink.size,
        "change coalescing respects 200 ms minimum");
  check(controller.admit(card(2), 1200) == desk::Admission::Enqueued, "second card admitted");
  check(scheduler.flush(controller, clock, 1300, sink) == desk::ReportAttempt::Sent,
        "latest state replaces unsent intermediate intent");
  report = last(sink, check);
  check(report.pending == 2 && report.entries[1].seq == 1 && report.entries[2].seq == 2 &&
        report.observedDevice == 1300 && report.processed == 2 && report.enqueued == 2,
        "report sampled at admission, not change time");

  // Leave report bytes partially transmitted, then block the next whole admission.
  sink.drain(10);
  const auto partial = sink.bytes;
  sink.size = 300;
  scheduler.changed();
  check(scheduler.flush(controller, clock, 1500, sink) == desk::ReportAttempt::Backpressure &&
        scheduler.revision() == 2 && sink.bytes == partial,
        "insufficient TX capacity leaves partial FIFO bytes and revision untouched");
  desk::Card active;
  check(controller.start(1510, active), "pending-to-active transition while report blocked");
  sink.drain(sink.size);
  sink.reject = true;
  check(scheduler.flush(controller, clock, 1600, sink) == desk::ReportAttempt::Backpressure &&
        scheduler.revision() == 2 && !sink.size, "atomic sink rejection retains latest intent");
  sink.reject = false;
  check(scheduler.flush(controller, clock, 1700, sink) == desk::ReportAttempt::Sent,
        "blocked report samples newest state when admission succeeds");
  report = last(sink, check);
  check(report.revision == 3 && report.pending == 1 && report.entries[0].seq == 1 &&
        report.entries[1].seq == 2 && report.grant == 9 && report.phase == desk::Phase::Entrance &&
        report.remaining == controller.remaining(1700) && report.observedDevice == 1700,
        "active identity, credit release and total occupancy are truthful");
  sink.drain(sink.size);
  check(scheduler.flush(controller, clock, 6699, sink) == desk::ReportAttempt::NotDue,
        "unchanged state has no per-frame report");
  check(scheduler.flush(controller, clock, 6700, sink) == desk::ReportAttempt::Sent,
        "idle report refresh at five seconds");
  report = last(sink, check);
  check(report.observedDevice == 6700 && report.remaining == controller.remaining(6700),
        "idle refresh does not reuse an aged report");
  sink.drain(sink.size);
  check(scheduler.flush(controller, clock, 6699, sink) == desk::ReportAttempt::Recovery,
        "backward clock cannot renew report lifetime");
  check(scheduler.flush(controller, clock, 61101, sink) == desk::ReportAttempt::Recovery,
        "expired calibration prevents report");

  controller.disconnect();
  check(controller.hold(7000), "offline hold continues without report transport");
  controller.beginExit(15000, false);
  check(controller.complete(15280, false), "offline active completion independent of report");
  check(scheduler.flush(controller, clock, 15280, sink) == desk::ReportAttempt::Invalid,
        "offline report cannot pretend current connection");
  ready(controller, 4, 16000, check);
  check(scheduler.flush(controller, clock, 16000, sink) == desk::ReportAttempt::Invalid,
        "old connection intent rejected after reconnect");
  scheduler.begin(1, 4, 3);
  check(scheduler.flush(controller, clock, 16000, sink) == desk::ReportAttempt::Recovery,
        "usable old-connection calibration cannot age new report");
  clock.request(1, 4, 16000);
  check(clock.accept(1, 4, 16000, 20000, 16100), "reconnect clock accepted");
  check(scheduler.flush(controller, clock, 16100, sink) == desk::ReportAttempt::Sent,
        "new connection starts report revision and credit afresh");
  report = last(sink, check);
  check(report.revision == 1 && report.connection == 4 && report.pending == 1 && report.grant == 7 &&
        report.entries[1].seq == 2 && report.entries[1].epoch == 1,
        "retained queue identities and observation-time credit survive reconnect");
  sink.drain(sink.size);

  // Many changes remain constant-memory intent; only latest coherent state is encoded.
  for (uint32_t i = 0; i < 10000; ++i) scheduler.changed();
  check(scheduler.flush(controller, clock, 16300, sink) == desk::ReportAttempt::Sent &&
        scheduler.revision() == 2 && sink.size == 252, "burst emits one bounded frame");
  sink.drain(sink.size);
  controller.reconcile(50000, true);
  scheduler.changed();
  check(scheduler.flush(controller, clock, 50000, sink) == desk::ReportAttempt::Sent,
        "terminal expiry emits final empty state");
  report = last(sink, check);
  check(!report.pending && report.phase == desk::Phase::None && report.expired == 1 &&
        report.grant == 8 && desk::wire::empty(report.entries[0]) && desk::wire::empty(report.entries[1]),
        "empty state replaces intermediate queue without inventing completed pixels");
  sink.drain(sink.size);

  desk::Controller full;
  ready(full, 5, 17000, check);
  for (uint32_t seq = 1; seq <= 8; ++seq)
    check(full.admit(card(seq), 17000) == desk::Admission::Enqueued, "fill pending FIFO");
  full.disconnect();
  ready(full, 6, 17100, check);
  clock.request(1, 6, 17100);
  check(clock.accept(1, 6, 17100, 21000, 17200), "full reconnect calibration");
  scheduler.begin(1, 6, 3);
  check(scheduler.flush(full, clock, 17200, sink) == desk::ReportAttempt::Sent,
        "full retained FIFO does not block initial report");
  report = last(sink, check);
  check(report.pending == 8 && report.grant == 0 && report.entries[8].seq == 8,
        "initial zero credit is observation-time authoritative");
  sink.drain(sink.size);
  check(full.start(17210, active), "dequeue full retained FIFO");
  scheduler.changed();
  check(scheduler.flush(full, clock, 17400, sink) == desk::ReportAttempt::Sent,
        "pending departure reports replacement credit");
  report = last(sink, check);
  check(report.pending == 7 && report.grant == 1 && report.entries[0].seq == 1 &&
        report.entries[1].seq == 2, "active transfer releases exactly one credit");
  printf("%d checks, %d failures\n", check.checks, check.failures);
  return check.failures != 0;
}
