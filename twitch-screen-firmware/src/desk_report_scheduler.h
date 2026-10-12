#pragma once

#include "desk_clock.h"
#include "desk_report_codec.h"

namespace desk {

enum class ReportAttempt : uint8_t { NotDue, Sent, Backpressure, Invalid, Recovery };

// The caller reconciles controller/time before flushing on the Arduino loop.
// Loop-owned, replaceable intent only: never retain an aged encoded report.
// The sink must atomically enqueue a whole frame or return false without writes.
// Once admitted, FIFO bytes belong to the sink, including partial socket writes.
class ReportScheduler {
 public:
  void begin(uint64_t epoch, uint64_t connection, uint64_t boot) {
    epoch_ = epoch; connection_ = connection; boot_ = boot;
    revision_ = 0; lastSent_ = 0; dirty_ = true;
  }
  void changed() { dirty_ = true; }
  uint32_t revision() const { return revision_; }

  template <class Sink>
  ReportAttempt flush(const Controller &controller, const Calibration &clock,
                      uint64_t now, Sink &sink) {
    if (!epoch_ || !connection_ || !boot_ || !controller.ready() ||
        controller.relayEpoch() != epoch_ || controller.connectionId() != connection_)
      return ReportAttempt::Invalid;
    if (!clock.matches(epoch_, connection_) || !clock.usable(now) ||
        (revision_ && now < lastSent_)) return ReportAttempt::Recovery;
    if (revision_) {
      const uint64_t elapsed = now - lastSent_;
      if (elapsed < 200 || (!dirty_ && elapsed < 5000)) return ReportAttempt::NotDue;
    }
    if (revision_ == UINT32_MAX) return ReportAttempt::Recovery;
    constexpr size_t FrameBytes = wire::ReportPayloadBytes + tsb::HEADER_SIZE;
    if (sink.available() < FrameBytes) return ReportAttempt::Backpressure;

    wire::Report report;
    report.epoch = epoch_; report.connection = connection_; report.boot = boot_;
    report.revision = revision_ + 1;
    report.observedDevice = now; report.observedRelayLower = clock.observationLower(now);
    report.grant = controller.grantTotal(); report.processed = controller.processedSeq();
    report.enqueued = controller.enqueuedSeq();
    const Control &control = controller.appliedControl();
    report.display = control.revision; report.generation = control.generation; report.episode = control.episode;
    report.weather = controller.weatherRevision(); report.cpu = controller.cpuRevision();
    const Selection selection = controller.selection(now);
    report.base = selection.base; report.reason = selection.reason;
    report.phase = controller.phase(); report.remaining = controller.remaining(now);
    report.pending = static_cast<uint8_t>(controller.pendingCount());
    report.expired = controller.expiredTotal(); report.suppressed = controller.suppressedTotal();
    report.refused = controller.refusedTotal();
    const Card *active = controller.active();
    if (active) report.entries[0] = entry(*active);
    for (size_t i = 0; i < controller.pendingCount(); ++i)
      report.entries[i + 1] = entry(*controller.pending(i));
    std::array<uint8_t, FrameBytes> frame{};
    const tsb::EncodeResult encoded = wire::encodeReport(frame.data(), frame.size(), report);
    if (!encoded.ok()) return ReportAttempt::Invalid;
    if (!sink.enqueue(frame.data(), encoded.size)) return ReportAttempt::Backpressure;
    // No revision/time/dirty progress on a rejected admission.
    revision_ = report.revision; lastSent_ = now; dirty_ = false;
    return ReportAttempt::Sent;
  }

 private:
  static wire::Entry entry(const Card &card) {
    wire::Entry result;
    result.epoch = card.epoch; result.seq = card.notification.seq;
    result.kind = static_cast<uint8_t>(card.notification.kind); result.origin = card.origin;
    return result;
  }
  uint64_t epoch_ = 0, connection_ = 0, boot_ = 0, lastSent_ = 0;
  uint32_t revision_ = 0;
  bool dirty_ = false;
};

}  // namespace desk
