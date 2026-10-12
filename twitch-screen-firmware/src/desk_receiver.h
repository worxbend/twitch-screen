#pragma once

#include "desk_message_codec.h"
#include "desk_report_scheduler.h"
#include "notification_wire.h"
#include "stats_projection.h"

namespace desk {

enum class Received : uint8_t { Ignored, Applied, Enqueued, Stats, Invalid, Fatal };

// Loop-owned wire/session adapter. Controller remains the sole policy/queue owner.
// Constructing this adapter does not enable the capability in the application.
class Receiver {
 public:
  Receiver(Controller &controller, uint64_t boot, bool profileReady = false)
      : controller_(controller), boot_(boot), profileReady_(profileReady) {}
  bool enabled() const { return profileReady_ && boot_ != 0; }
  uint64_t now() const { return now_; }
  uint64_t probe() const { return probe_; }
  uint64_t boot() const { return boot_; }
  // Set once at boot before linkInit. Reconnects and classic fallback must not
  // replace the identity used by HELLO and every DEVICE_REPORT.
  bool initializeBootIdentity(uint64_t boot) {
    if (!boot || boot_ || negotiated_) return false;
    boot_ = boot;
    return true;
  }
  uint64_t previousEpoch() const { return controller_.relayEpoch(); }
  uint32_t processed() const { return controller_.processedSeq(); }
  uint32_t enqueued() const { return controller_.enqueuedSeq(); }
  bool negotiated() const { return negotiated_; }
  bool ready() const { return negotiated_ && controller_.ready(); }
  bool calibrated() const { return clock_.usable(now_); }
  const wire::State &weather() const { return weather_.value; }
  const wire::State &cpu() const { return cpu_.value; }
  const wire::Stats &stats() const { return stats_; }
  StreamStats applicationStats() const {
    if (!ready() || stats_.generation != controller_.appliedControl().generation)
      return projectStats(tsb::TsbStats(), 0);
    return projectStats(stats_.base, stats_.availability);
  }
  Controller &controller() { return controller_; }

  // Called before input dispatch, including on disconnected loops.
  bool tick(uint32_t raw) {
    now_ = elapsed_.update(raw);
    if (elapsed_.discontinuity()) clock_.invalidate();
    if (!negotiated_) return true;
    controller_.reconcile(now_, clock_.usable(now_));
    observe();
    return !controller_.recoveryRequired();
  }
  void hello() { probe_ = now_; }
  bool welcome(const wire::Welcome &w) {
    if (!enabled()) return false;
    negotiated_ = true; nextGreeting_ = 0;
    display_ = Slot(); weather_ = Slot(); cpu_ = Slot();
    stats_ = wire::Stats(); statsBytes_.fill(0);
    controller_.welcome(w.epoch, w.connection, w.base.latest_seq, now_);
    clock_.invalidate(); clock_.request(w.epoch, w.connection, probe_);
    if (!clock_.accept(w.epoch, w.connection, w.echo, w.sample, now_)) return false;
    controller_.reconcile(now_, true);
    reports_.begin(w.epoch, w.connection, boot_);
    lastProbeAt_ = now_; observed_.fill(UINT64_MAX);
    return true;
  }
  void disconnected() {
    negotiated_ = false; clock_.invalidate(); controller_.disconnect();
  }
  void classic() { disconnected(); controller_.legacyWelcome(); }

  Received receive(uint8_t type, uint8_t flags, const uint8_t *p, size_t size) {
    if (!negotiated_) return Received::Ignored;
    if (type == tsb::T_EVENT) return Received::Fatal;
    if (type == wire::DeskEvent) return event(flags, p, size);
    if (type == tsb::T_STATS) return statistics(p, size);
    wire::State s;
    if (wire::decode(type, p, size, s) != tsb::DecodeResult::Ok) return Received::Invalid;
    if (s.epoch != controller_.relayEpoch() || s.connection != controller_.connectionId()) return Received::Ignored;
    if (type == wire::TimeResponse) {
      if (!clock_.accept(s.epoch, s.connection, s.probe, s.relaySample, now_)) return Received::Ignored;
      controller_.recalibrate(clock_, now_);
      observe();
      return Received::Applied;
    }
    if (!controller_.ready()) {
      static const uint8_t order[] = {wire::DisplayState, wire::Weather, wire::Cpu, tsb::T_STATS, wire::SyncEnd};
      if (nextGreeting_ >= sizeof(order) || type != order[nextGreeting_]) return Received::Fatal;
    }
    if (type == wire::ProgressBarrier) {
      if (!controller_.barrier(s.epoch, s.connection, s.through)) return Received::Fatal;
    } else if (type == wire::SyncEnd) {
      if (!controller_.sync(s.epoch, s.connection, s.sync, now_)) return Received::Fatal;
    } else {
      Slot &slot = type == wire::DisplayState ? display_ : type == wire::Weather ? weather_ : cpu_;
      const uint32_t revision = type == wire::DisplayState ? s.control.revision : s.revision;
      const uint32_t old = type == wire::DisplayState ? slot.value.control.revision : slot.value.revision;
      if (old && revision < old) return Received::Ignored;
      if (old && revision == old && memcmp(slot.bytes.data(), p, wire::length(type))) return Received::Fatal;
      bool accepted = false;
      if (type == wire::DisplayState) accepted = controller_.control(s.epoch, s.connection, s.control);
      else {
        Freshness f; f.revision = s.revision; f.status = s.status; f.relayDeadline = s.deadline;
        f.localDeadline = s.status == Status::Fresh ? clock_.deadline(s.deadline, now_) : 0;
        accepted = type == wire::Weather ? controller_.weather(s.epoch, s.connection, f) : controller_.cpu(s.epoch, s.connection, f);
      }
      if (!accepted) return Received::Fatal;
      slot.value = s; memcpy(slot.bytes.data(), p, wire::length(type));
    }
    if (nextGreeting_ < 5) ++nextGreeting_;
    observe();
    return Received::Applied;
  }

  template <class Sink>
  bool output(Sink &sink) {
    if (!negotiated_) return true;
    if (!tickCurrent()) return false;
    if (now_ - lastProbeAt_ >= 20000) {
      std::array<uint8_t, 16> frame{};
      const tsb::EncodeResult encoded = wire::encodeTimeRequest(frame.data(), frame.size(), now_);
      if (encoded.ok() && sink.available() >= encoded.size && sink.enqueue(frame.data(), encoded.size)) {
        clock_.request(controller_.relayEpoch(), controller_.connectionId(), now_);
        lastProbeAt_ = now_;
      }
    }
    if (!controller_.ready()) return true;
    const ReportAttempt attempt = reports_.flush(controller_, clock_, now_, sink);
    return attempt != ReportAttempt::Invalid && attempt != ReportAttempt::Recovery;
  }

 private:
  struct Slot { wire::State value; std::array<uint8_t, 68> bytes{}; };
  bool tickCurrent() {
    controller_.reconcile(now_, clock_.usable(now_)); observe();
    return !controller_.recoveryRequired();
  }
  Received event(uint8_t flags, const uint8_t *p, size_t size) {
    wire::Event e;
    if (wire::decodeEvent(p, size, e) != tsb::DecodeResult::Ok) return Received::Fatal;
    if (!controller_.ready() || !clock_.usable(now_) || e.epoch != controller_.relayEpoch()) return Received::Fatal;
    Card card; notificationFromEvent(e.base, flags, card.notification);
    card.epoch = e.epoch; card.relayDeadline = e.deadline; card.deadline = clock_.deadline(e.deadline, now_);
    card.generation = e.generation; card.episode = e.episode; card.origin = e.origin;
    const Admission result = controller_.admit(card, now_);
    observe();
    if (result == Admission::Refused || result == Admission::Invalid) return Received::Fatal;
    return result == Admission::Enqueued ? Received::Enqueued : Received::Applied;
  }
  Received statistics(const uint8_t *p, size_t size) {
    wire::Stats s;
    if (wire::decodeStats(p, size, s) != tsb::DecodeResult::Ok) return Received::Invalid;
    if (s.epoch != controller_.relayEpoch() || s.connection != controller_.connectionId() ||
        s.generation != controller_.appliedControl().generation) return Received::Ignored;
    if (!controller_.ready() && nextGreeting_ != 3) return Received::Fatal;
    if (stats_.revision) {
      if (s.revision < stats_.revision) return Received::Ignored;
      if (s.revision == stats_.revision && memcmp(statsBytes_.data(), p, statsBytes_.size())) return Received::Fatal;
    }
    if (!controller_.stats(s.epoch, s.connection, s.revision, s.generation)) return Received::Fatal;
    stats_ = s; memcpy(statsBytes_.data(), p, statsBytes_.size());
    if (!controller_.ready()) ++nextGreeting_;
    observe();
    return controller_.ready() ? Received::Stats : Received::Applied;
  }
  // Exact meaningful state, without countdown/clock fields that would dirty every
  // loop. Identity changes, dequeue, phase, expiry and base transitions all report.
  void observe() {
    std::array<uint64_t, 32> key{};
    key[0] = controller_.grantTotal(); key[1] = controller_.processedSeq(); key[2] = controller_.enqueuedSeq();
    key[3] = controller_.appliedControl().revision; key[4] = controller_.weatherRevision(); key[5] = controller_.cpuRevision();
    key[6] = static_cast<uint8_t>(controller_.phase()); key[7] = controller_.pendingCount();
    key[8] = controller_.expiredTotal(); key[9] = controller_.suppressedTotal(); key[10] = controller_.refusedTotal();
    const Selection selected = controller_.selection(now_);
    key[11] = static_cast<uint8_t>(selected.base); key[12] = static_cast<uint8_t>(selected.reason);
    for (size_t i = 0; i < 9; ++i) {
      const Card *card = i ? controller_.pending(i - 1) : controller_.active();
      if (card) { key[13 + 2 * i] = card->epoch; key[14 + 2 * i] = card->notification.seq; }
    }
    if (key != observed_) { reports_.changed(); observed_ = key; }
  }
  Controller &controller_;
  uint64_t boot_ = 0, now_ = 0, probe_ = 0, lastProbeAt_ = 0;
  MillisClock elapsed_;
  Calibration clock_;
  ReportScheduler reports_;
  Slot display_, weather_, cpu_;
  wire::Stats stats_;
  std::array<uint8_t, 60> statsBytes_{};
  std::array<uint64_t, 32> observed_{};
  uint8_t nextGreeting_ = 0;
  bool negotiated_ = false, profileReady_ = false;
};

}  // namespace desk
