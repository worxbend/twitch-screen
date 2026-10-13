#include <stdio.h>
#include "desk_controller.h"
#include "desk_clock.h"
#include "desk_view_boundary.h"
#include "notify_queue.h"

namespace {
using namespace desk;
struct Checker {
  int checks = 0, failures = 0;
  void operator()(bool ok, const char *what) {
    ++checks; if (!ok) { ++failures; printf("FAIL: %s\n", what); }
  }
};
Control control(uint32_t revision = 1, bool enabled = false, Preferred preferred = Preferred::Generic,
                uint32_t generation = 1, uint32_t episode = 1) {
  Control value;
  value.revision = revision; value.enabled = enabled; value.preferred = preferred;
  value.generation = generation; value.episode = episode;
  return value;
}
Freshness fresh(uint64_t deadline, uint32_t revision = 1) {
  Freshness value;
  value.revision = revision; value.status = Status::Fresh;
  value.relayDeadline = deadline + 500; value.localDeadline = deadline;
  return value;
}
Freshness missing(uint32_t revision = 1) { Freshness value; value.revision = revision; return value; }
Sync boundary(uint32_t display = 1, uint32_t weather = 1, uint32_t cpu = 1,
              uint32_t stats = 1, uint32_t generation = 1, uint32_t episode = 1) {
  Sync value;
  value.display = display; value.weather = weather; value.cpu = cpu;
  value.stats = stats; value.generation = generation; value.episode = episode;
  return value;
}
Card card(uint32_t seq, Origin origin = Origin::External, uint64_t deadline = 100000, uint64_t epoch = 10) {
  Card value;
  value.notification.seq = seq; value.notification.ttl_ds = 300;
  value.epoch = epoch; value.deadline = deadline; value.origin = origin;
  if (origin == Origin::Twitch) value.generation = value.episode = 1;
  return value;
}
void connect(Controller &c, Checker &check, const Control &intent = control(), uint64_t now = 100,
             uint64_t connection = 20, uint64_t epoch = 10, uint32_t latest = 0) {
  c.welcome(epoch, connection, latest, now);
  check(c.reconcile(now, true).reason == Reason::Synchronizing, "WELCOME awaits complete sync");
  check(c.control(epoch, connection, intent), "stage control");
  check(c.weather(epoch, connection, missing()), "explicit missing weather");
  check(c.cpu(epoch, connection, missing()), "explicit missing CPU");
  check(c.stats(epoch, connection, 1, intent.generation), "stage matching stats");
  check(c.sync(epoch, connection, boundary(intent.revision, 1, 1, 1, intent.generation, intent.episode), now), "complete coherent sync");
}
void selectionMatrix(Checker &check) {
  for (unsigned enabled = 0; enabled < 2; ++enabled) {
    for (unsigned preferred = 0; preferred < 3; ++preferred) {
      for (unsigned state = 0; state < 3; ++state) {
        Controller c;
        connect(c, check, control(1, enabled != 0, static_cast<Preferred>(preferred)));
        Freshness data = state == 0 ? missing(2) : fresh(state == 1 ? 500 : 100, 2);
        check(c.weather(10, 20, data) && c.cpu(10, 20, data), "cache both sources while hidden");
        const Selection selected = c.reconcile(200, true);
        const Base expected = enabled ? Base::Twitch : preferred && state == 1
            ? (preferred == 1 ? Base::Weather : Base::Cpu) : Base::ConnectedOrbit;
        check(selected.base == expected, "override/preference/freshness matrix");
        c.disconnect();
        check(c.selection(200).base == Base::DisconnectedOrbit, "link loss beats cached telemetry");
      }
    }
  }
}
void synchronization(Checker &check) {
  Controller c;
  c.welcome(10, 20, 50, 100);
  check(c.processedSeq() == 50 && c.enqueuedSeq() == 50, "fresh boot baseline does not replay history");
  c.reconcile(100, true);
  check(!c.control(10, 19, control()) && !c.control(11, 20, control()), "obsolete connection/epoch rejected");
  Control bad = control(); bad.preferred = static_cast<Preferred>(3);
  check(!c.control(10, 20, bad), "unknown preferred enum rejected");
  check(c.control(10, 20, control()), "control staged");
  check(c.weather(10, 20, fresh(1000)), "fresh weather staged");
  check(c.cpu(10, 20, missing()), "missing CPU staged");
  check(c.selection(200).base == Base::ConnectedOrbit, "staged data never visible before SYNC_END");
  check(!c.sync(10, 20, boundary(), 200), "missing stats prevents sync");
  check(!c.stats(10, 20, 1, 2) && c.stats(10, 20, 1, 1), "stats generation fenced");
  check(!c.sync(10, 20, boundary(2), 200), "revision mismatch prevents sync");
  check(c.sync(10, 20, boundary(), 200) && c.ready(), "all referenced records synchronize");
  check(!c.sync(10, 20, boundary(), 201), "duplicate sync cannot reset credits");
  check(c.control(10, 20, control()) && !c.control(10, 20, control(1, true)), "equal control revision requires equal policy");
  check(c.control(10, 20, control(2, false, Preferred::Weather)), "preference applied immediately");
  check(c.selection(999).base == Base::Weather && c.reconcile(1000, true).reason == Reason::DataStale, "expiry boundary never reveals stale weather");
  check(!c.weather(10, 20, fresh(2000)), "equal source revision cannot change absolute deadline");
  Freshness shortened = fresh(1000); shortened.localDeadline = 900;
  check(c.weather(10, 20, shortened), "equal deadline calibration may shorten");
  check(c.weather(10, 20, fresh(1000)) && c.selection(950).reason == Reason::DataStale, "repeated frame cannot extend local deadline");
  Freshness invalid = missing(3); invalid.localDeadline = 10;
  check(!c.cpu(10, 20, invalid), "missing record cannot have a deadline");
  c.welcome(10, 21, 50, 2000);
  check(!c.ready() && c.selection(2000).reason == Reason::Synchronizing, "reconnect invalidates all dashboard availability");
  check(c.reconcile(6999, true).reason == Reason::Synchronizing, "sync timeout not early");
  check(c.reconcile(7000, true).base == Base::DisconnectedOrbit && c.recoveryRequired(), "bounded sync recovery");
  connect(c, check, control(), 8000, 22, 11, 3);
  check(c.processedSeq() == 3, "epoch change rebaselines current cursors");
  check(c.reconcile(8001, false).base == Base::DisconnectedOrbit && c.recoveryRequired(), "clock loss disconnects before reveal");
  c.legacyWelcome();
  check(c.reconcile(8002, false).reason == Reason::Legacy, "legacy connected fallback is Twitch, no applied DESK claim");
  c.disconnect();
  check(c.selection(8003).base == Base::DisconnectedOrbit, "legacy loss fallback policy");
}
void lifecycle(Checker &check) {
  Controller c;
  connect(c, check, control(1, true));
  check(c.admit(card(1, Origin::Twitch, 150), 100) == Admission::Enqueued, "Twitch card eligible");
  Card out;
  check(c.start(110, out) && c.phase() == Phase::Entrance && c.grantTotal() == 9, "pending-to-active transfer releases exactly one slot");
  check(c.remaining(110) == 30630, "remaining includes entrance/hold/exit");
  check(c.admit(card(2, Origin::External), 110) == Admission::Enqueued, "generic pending successor");
  check(c.admit(card(3, Origin::Twitch), 110) == Admission::Enqueued, "Twitch pending successor");
  check(c.control(10, 20, control(2, false, Preferred::Cpu, 1, 2)), "disable applies beneath active card");
  check(c.active()->notification.seq == 1 && c.pendingCount() == 1 && c.pending(0)->notification.seq == 2,
        "active Twitch finishes, pending Twitch purged, generic retained");
  check(c.suppressedTotal() == 1 && c.grantTotal() == 10, "terminal pending suppression releases once");
  check(c.hold(460) && c.phase() == Phase::Hold, "actual entrance callback starts hold");
  check(c.reconcile(20000, true).base == Base::ConnectedOrbit && c.active(), "active waiting expiry never cancels hold");
  check(!c.complete(20000, true), "estimated time or control cannot complete active card");
  check(c.cpu(10, 20, fresh(30500, 2)), "fresh CPU behind active card");
  check(c.beginExit(30460, true).base == Base::Cpu && c.phase() == Phase::Exit, "first reveal recomputes current base");
  check(c.reconcile(30500, true).reason == Reason::DataStale && c.active(), "freshness updates during slide-out");
  const uint64_t grant = c.grantTotal();
  check(c.complete(30740, true) && c.grantTotal() == grant, "completion does not manufacture credit");
  check(c.start(30741, out) && out.notification.seq == 2, "generic FIFO survives mixed suppression");
  c.disconnect();
  check(c.hold(31091), "active generic hold continues offline");
  check(c.beginExit(61091, false).base == Base::DisconnectedOrbit, "offline exit reveals local base");
  check(c.complete(61371, false), "offline report/clock cannot block completion");
}
void chatPacing(Checker &check) {
  Controller c;
  c.legacyWelcome();
  Notification n; n.kind = NotifyKind::Chat; n.seq = 1;
  check(c.legacyAdmit(n) == Admission::Enqueued, "quiet chat admitted");
  Card out;
  check(c.start(100, out) && c.phase() == Phase::Hold, "chat appears immediately without entrance animation");
  check(c.remaining(100) == 2500, "quiet chat keeps readable default hold without exit animation");
  n.seq = 2;
  check(c.legacyAdmit(n) == Admission::Enqueued, "new chat queues behind active card");
  c.reconcile(200, false);
  check(c.remaining(200) == 400, "arrival shortens existing chat to 500 ms from original hold start");
  n.seq = 3; c.legacyAdmit(n);
  n.seq = 4; c.legacyAdmit(n);
  c.reconcile(250, false);
  check(c.remaining(250) == 100, "three waiting cards shorten active chat to 250 ms total");
  check(c.beginExit(350, false).base == Base::Twitch && c.complete(350, false), "chat exits immediately through real lifecycle");
  check(c.start(350, out) && out.notification.seq == 2, "accelerated chat keeps FIFO order");
  check(c.remaining(350) == 500, "two waiting cards use intermediate pacing");
  c.disconnect(); c.reconcile(400, false);
  check(c.pendingCount() == 2 && c.remaining(400) == 450,
        "classic disconnect retains pending FIFO and shortened hold");
  c.beginExit(850, false); c.complete(850, false);

  Controller explicitHold;
  explicitHold.legacyWelcome();
  n.seq = 1; n.ttl_ds = 20;
  explicitHold.legacyAdmit(n); explicitHold.start(100, out);
  n.seq = 2; explicitHold.legacyAdmit(n);
  explicitHold.reconcile(200, false);
  check(explicitHold.remaining(200) == 1900, "explicit wire hold duration remains authoritative");
  check(entranceFor(false, NotifyKind::Follow) == Entrance::Slide &&
        entranceFor(false, NotifyKind::Alert) == Entrance::FlashThenSlide,
        "audience and severity animations unchanged");
}
void chatEdges(Checker &check) {
  // Explicit durations (including shorter-than-cap holds) win for live and
  // replay chat at every bounded FIFO pressure, not just one example TTL.
  for (bool replay : {false, true}) for (uint16_t ttl : {uint16_t(0), uint16_t(1), uint16_t(20), uint16_t(6000)})
    for (size_t waiting = 0; waiting <= Controller::Capacity; ++waiting) {
      Controller c; c.legacyWelcome();
      Notification n; n.kind = NotifyKind::Chat; n.seq = 1; n.replay = replay; n.ttl_ds = ttl;
      check(c.legacyAdmit(n) == Admission::Enqueued, "matrix chat admitted");
      Card out; check(c.start(100, out), "matrix chat starts before burst");
      for (size_t i = 0; i < waiting; ++i) {
        n.seq = uint32_t(i + 2); n.kind = i % 2 ? NotifyKind::Alert : NotifyKind::Follow;
        check(c.legacyAdmit(n) == Admission::Enqueued, "mixed-kind queue contributes pressure without priority reordering");
      }
      c.reconcile(110, false);
      const uint32_t budget = ttl ? uint32_t(ttl) * 100u : waiting >= 3 ? 250u : waiting ? 500u : 2500u;
      check(c.remaining(110) == budget - 10 && c.phase() == Phase::Hold,
            "explicit TTL/replay/mixed-kind pressure matrix matches original-start hold");
      check(!c.hold(120) && c.remaining(120) == budget - 20,
            "duplicate hold callback cannot restart an immediate chat");
    }

  Controller c; connect(c, check, control(1, true));
  Card value = card(1, Origin::Twitch); value.notification.kind = NotifyKind::Chat; value.notification.ttl_ds = 0;
  check(c.admit(value, 100) == Admission::Enqueued, "DESK default chat admitted");
  Card out; check(c.start(100, out), "DESK chat starts quiet");
  for (uint32_t seq = 2; seq <= 4; ++seq) {
    value = card(seq, Origin::Twitch, seq == 2 ? 220 : 100000);
    value.notification.kind = seq == 2 ? NotifyKind::Follow : NotifyKind::Chat;
    check(c.admit(value, 120) == Admission::Enqueued, "DESK pressure cards admitted");
  }
  c.reconcile(150, true);
  check(c.remaining(150) == 200, "DESK three waiting cards shorten chat to 250 ms");
  c.reconcile(220, true);
  check(c.pendingCount() == 2 && c.expiredTotal() == 1 && c.remaining(220) == 130,
        "expiry reducing pressure never lengthens an already shortened active chat");
  check(c.control(10, 20, control(2, false, Preferred::Weather, 1, 2)), "disable drains remaining Twitch pressure");
  c.reconcile(230, true);
  check(c.pendingCount() == 0 && c.suppressedTotal() == 2 && c.remaining(230) == 120 && c.active(),
        "actual queue removal keeps shortened active card alive until its callback");
  const uint64_t grant = c.grantTotal();
  check(grant - c.receivedTotal() == Controller::Capacity, "expiry/suppression/start preserve all DESK credits");
  check(c.beginExit(350, true).reason == Reason::DataMissing && c.complete(350, true) && c.grantTotal() == grant,
        "instant chat reveal applies new base without releasing active credit twice");

  Controller late; late.legacyWelcome();
  Notification n; n.seq = 1; n.kind = NotifyKind::Chat;
  late.legacyAdmit(n); late.start(100, out);
  n.seq = 2; n.kind = NotifyKind::Warning; late.legacyAdmit(n);
  late.reconcile(1000, false);
  check(late.remaining(1000) == 0 && late.phase() == Phase::Hold && !late.complete(1000, false),
        "pressure arriving after the cap makes hold due, never completes it without UI callback");
  late.beginExit(1000, false); late.complete(1000, false);
  check(late.start(1000, out) && out.notification.kind == NotifyKind::Warning && late.remaining(1000) == 4930,
        "severe successor retains flash/slide/default hold/exit under mixed chat pressure");

  // Feed the same wrapping raw clock used by Receiver, not a wrapping u64.
  MillisClock clock; Controller wrap; wrap.legacyWelcome();
  n.seq = 1; n.kind = NotifyKind::Chat;
  const uint32_t rawStart = UINT32_MAX - 100;
  const uint64_t started = clock.update(rawStart);
  wrap.legacyAdmit(n); wrap.start(started, out);
  n.seq = 2; wrap.legacyAdmit(n);
  const uint64_t wrapped = clock.update(uint32_t(rawStart + 200u));
  wrap.reconcile(wrapped, false);
  check(!clock.discontinuity() && wrap.remaining(wrapped) == 300, "chat cap uses extended millis across raw clock wrap");
  wrap.reconcile(clock.update(uint32_t(rawStart + 500u)), false);
  check(wrap.remaining(started + 500) == 0 && wrap.active(), "wrap deadline becomes due without estimated completion");
}
void pressure(Checker &check) {
  Controller c;
  connect(c, check);
  for (uint32_t seq = 1; seq <= 8; ++seq)
    check(c.admit(card(seq), 100) == Admission::Enqueued, "bounded queue fills");
  check(c.pendingCount() == 8 && c.grantTotal() == 8, "eight pending separate from active");
  c.disconnect();
  connect(c, check, control(), 200, 21, 10, 8);
  check(c.grantTotal() == 0 && c.pendingCount() == 8, "full retained FIFO synchronizes with zero grant");
  check(c.processedSeq() == 8 && c.pending(0)->epoch == 10, "resume preserves current cursor and retained identity");
  Card out;
  check(c.start(200, out) && c.grantTotal() == 1, "one pending start grants one admission");
  check(c.admit(card(9, Origin::External, 200), 200) == Admission::Expired, "expired record classifies before capacity");
  check(c.processedSeq() == 9 && c.enqueuedSeq() == 8 && c.grantTotal() == 2, "terminal expiry progress separate from ACK");
  check(c.admit(card(9), 200) == Admission::Duplicate && c.grantTotal() == 3, "duplicate returns one consumed credit");
  check(c.admit(card(10, Origin::Twitch), 200) == Admission::Suppressed && c.grantTotal() == 4, "ineligible Twitch returns consumed credit");
  check(c.admit(card(11), 200) == Admission::Enqueued && c.pendingCount() == 8, "released credit admits successor");
  check(c.admit(card(12), 200) == Admission::Refused && c.recoveryRequired(), "unexpected no-credit event closes session");
  check(c.processedSeq() == 11 && c.enqueuedSeq() == 11 && !c.barrier(10, 21, 12), "refusal never crosses cursor hole");
  connect(c, check, control(), 300, 22, 11, 1);
  check(c.processedSeq() == 1 && c.pending(0)->epoch == 10 && c.pending(0)->notification.seq == 2,
        "new epoch baselines without relabeling retained generic");
  check(!c.barrier(10, 22, 20) && c.barrier(11, 22, 20) && c.enqueuedSeq() == 1, "barrier scoped and enqueue-only ACK unchanged");
  c.reconcile(100000, true);
  check(c.pendingCount() == 0 && c.active() && c.expiredTotal() == 9, "expired pending compacts while active continues");
}
void episodeAndFifo(Checker &check) {
  Controller c;
  connect(c, check, control(1, true));
  check(c.admit(card(1), 100) == Admission::Enqueued, "external first");
  check(c.admit(card(2, Origin::Twitch), 100) == Admission::Enqueued, "Twitch interleaved");
  check(c.admit(card(3, Origin::Relay), 100) == Admission::Enqueued, "relay diagnostic independent");
  check(c.control(10, 20, control(3, true, Preferred::Generic, 1, 3)), "coalesced disable/re-enable preserves episode fence");
  check(c.pendingCount() == 2 && c.pending(0)->notification.seq == 1 && c.pending(1)->notification.seq == 3,
        "terminal suppression preserves generic FIFO");
  check(c.admit(card(4, Origin::Twitch), 100) == Admission::Suppressed, "old episode cannot resurrect");
  Card switched = card(5, Origin::Twitch); switched.episode = 3;
  check(c.admit(switched, 100) == Admission::Enqueued, "current episode accepted");
  check(c.control(10, 20, control(4, true, Preferred::Generic, 2, 4)) && c.pendingCount() == 2, "source switch purges pending old generation");
  check(!c.stats(10, 20, 2, 1), "old generation stats never applied");
  c.disconnect();
  check(c.pendingCount() == 2, "all independent cards retained offline");
  Card out;
  check(c.start(200, out) && out.notification.seq == 1, "offline external FIFO starts");
  check(c.hold(550) && c.beginExit(30550, false).base == Base::DisconnectedOrbit && c.complete(30830, false), "complete entire card offline");
  check(c.start(30831, out) && out.notification.seq == 3, "offline relay card follows");
}
void replayAndSeverity(Checker &check) {
  for (unsigned replay = 0; replay < 2; ++replay) {
    Controller c; connect(c, check);
    Card value = card(1); value.notification.kind = NotifyKind::Alert; value.notification.replay = replay != 0;
    check(c.admit(value, 100) == Admission::Enqueued, "external severity never inferred as Twitch");
    Card out; check(c.start(100, out), "severity card starts");
    check(c.remaining(100) == (replay ? 30000u : 31430u), "severity flash and replay occupancy estimates");
    if (!replay) check(c.hold(1250), "severity actual flash/slide completion");
    check(c.beginExit(40000, true).base == Base::ConnectedOrbit, "replay exit also reconciles");
    check(c.complete(40000, true), "callback rather than estimate completes replay");
  }
}
struct FakeInputs {
  Controller &controller;
  unsigned order = 0;
  uint64_t time = 0;
  bool clock = true, loseLink = false;
  explicit FakeInputs(Controller &value) : controller(value) {}
  void dispatch() { order = 1; if (loseLink) controller.disconnect(); }
  uint64_t now() { return time; }
  bool calibrated(uint64_t) { return clock; }
};
struct FakeView {
  FakeInputs &inputs;
  Checker &check;
  Selection selected;
  Base expected = Base::ConnectedOrbit;
  FakeView(FakeInputs &source, Checker &checker) : inputs(source), check(checker) {}
  void apply(Selection value) {
    check(inputs.order == 1, "input dispatch precedes selection apply");
    selected = value; inputs.order = 2;
  }
  void pump() {
    check(inputs.order == 2 && selected.base == expected, "fresh selection applied before render pump");
    inputs.order = 3;
  }
  void reveal() {
    check(inputs.order == 2 && selected.base == expected, "fresh selection applied before every reveal");
    inputs.order = 3;
  }
};
void viewBoundary(Checker &check) {
  Controller c; connect(c, check, control(1, false, Preferred::Weather));
  c.weather(10, 20, fresh(1000, 2));
  Card value = card(1); c.admit(value, 100);
  Card out; c.start(100, out); c.hold(450);
  FakeInputs inputs(c); FakeView view(inputs, check);
  ViewBoundary<FakeInputs, FakeView> boundary(c, inputs, view);
  inputs.time = 999; view.expected = Base::Weather;
  boundary.pump();
  inputs.time = 1000; view.expected = Base::ConnectedOrbit;
  boundary.beginExit();
  check(c.phase() == Phase::Exit && c.active(), "exit starts without completing active card");
  inputs.time = 1001; inputs.loseLink = true; view.expected = Base::DisconnectedOrbit;
  boundary.pump();
  inputs.time = 1280; inputs.clock = false;
  check(boundary.complete() && !c.active(), "completion reconciles offline and reveals current base");
}
void legacyParity(Checker &check) {
  Controller c; NotifyQueue<8> reference;
  uint64_t now = 0;
  for (unsigned step = 0; step < 2000; ++step) {
    ++now;
    if (step % 29 == 0) {
      const uint32_t latest = step % 58 == 0 ? step / 3 : step + 1;
      const auto expected = reference.greet(latest, step / 29);
      const auto actual = c.legacyGreeting(latest, step / 29);
      check((expected == NotifyQueue<8>::Greet::Rebaselined) ==
            (actual == Controller::LegacyGreet::Rebaselined), "classic baseline parity");
      check(c.legacySessionChanged() == reference.sessionChanged(), "classic session log parity");
    }
    if (step % 7 == 0) {
      Notification expected; Card actual;
      const bool taken = reference.take(expected), started = c.start(now, actual);
      check(taken == started && (!taken || expected.seq == actual.notification.seq), "classic FIFO parity");
      if (started) { c.hold(now); c.beginExit(now, false); c.complete(now, false); }
    }
    Notification n; n.seq = step % 11 == 0 ? step / 2 : step + 1;
    const auto expected = reference.offer(n); const auto actual = c.legacyAdmit(n);
    check(static_cast<unsigned>(expected) == (actual == Admission::Enqueued ? 0u :
          actual == Admission::Duplicate ? 1u : 2u), "classic admission parity");
    check(c.pendingCount() == reference.size() && c.legacySeq() == reference.lastSeq() &&
          c.legacyGap() == reference.gapOpen() && c.legacyAccepted() == reference.shown() &&
          c.refusedTotal() == reference.refused(), "classic accounting parity after every operation");
    if (step % 13 == 0) c.disconnect();
  }
}
void deterministicPressure(Checker &check) {
  Controller c; connect(c, check, control(1, true));
  uint32_t seq = 0;
  uint64_t now = 100;
  uint32_t revision = 1, episode = 1;
  bool enabled = true;
  for (unsigned step = 0; step < 4000; ++step) {
    now += 10;
    c.reconcile(now, true);
    if (step % 17 == 0) {
      enabled = !enabled; ++revision; ++episode;
      check(c.control(10, 20, control(revision, enabled, Preferred::Generic, 1, episode)), "repeated control preserves fences");
    }
    if (c.receivedTotal() < c.grantTotal()) {
      Card value = card(++seq, step % 3 == 0 ? Origin::Twitch : Origin::External, now + 80);
      if (value.origin == Origin::Twitch) value.episode = episode;
      const Admission result = c.admit(value, now);
      check(result == Admission::Enqueued || result == Admission::Suppressed, "credit-safe sustained traffic never refuses");
    }
    if (c.phase() == Phase::None) {
      Card out; c.start(now, out);
    } else if (c.phase() == Phase::Entrance) c.hold(now);
    else if (c.phase() == Phase::Hold && step % 13 == 0) c.beginExit(now, true);
    else if (c.phase() == Phase::Exit) c.complete(now, true);
    check(c.grantTotal() - c.receivedTotal() == Controller::Capacity - c.pendingCount(), "credit conservation after every mutation");
    check(c.pendingCount() <= 8 && c.enqueuedSeq() <= c.processedSeq(), "bounded queue and independent monotonic cursors");
    for (size_t i = 0; i < c.pendingCount(); ++i) {
      const Card &value = *c.pending(i);
      check(value.deadline > now, "no expired pending after reconcile/admit");
      check(value.origin != Origin::Twitch || (enabled && value.episode == episode), "no obsolete Twitch pending after control");
      if (i) check(c.pending(i - 1)->notification.seq < value.notification.seq, "mixed compaction preserves FIFO");
    }
  }
}
}
int main() {
  Checker check;
  selectionMatrix(check); synchronization(check); lifecycle(check);
  chatPacing(check); chatEdges(check); pressure(check); episodeAndFifo(check); replayAndSeverity(check);
  viewBoundary(check); legacyParity(check); deterministicPressure(check);
  printf("%d checks, %d failures\n", check.checks, check.failures);
  return check.failures != 0;
}
