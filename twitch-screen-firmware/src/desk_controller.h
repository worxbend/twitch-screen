#pragma once

#include <array>
#include <stdint.h>
#include "notification.h"
#include "presentation.h"

namespace desk {

enum class Base : uint8_t { ConnectedOrbit, DisconnectedOrbit, Twitch, Weather, Cpu };
enum class Reason : uint8_t {
  Selected, RelayDown, Synchronizing, GenericSelected, DataMissing, DataStale,
  TwitchOverride, Legacy, ClockUnavailable
};
enum class Preferred : uint8_t { Generic, Weather, Cpu };
enum class Status : uint8_t { Missing, Fresh, Stale };
enum class Origin : uint8_t { External, Twitch, Relay, Unknown = 255 };
enum class Phase : uint8_t { None, Entrance, Hold, Exit };
enum class Admission : uint8_t { Enqueued, Duplicate, Expired, Suppressed, Refused, Invalid };

struct Selection {
  Base base = Base::DisconnectedOrbit;
  Reason reason = Reason::RelayDown;
};
struct Control {
  uint32_t revision = 0, generation = 0, episode = 0;
  bool enabled = false;
  Preferred preferred = Preferred::Generic;
};
// Domain projection only. The wire adapter must also validate complete payload
// equality for equal revisions before publishing this readiness projection.
struct Freshness {
  uint32_t revision = 0;
  Status status = Status::Missing;
  uint64_t relayDeadline = 0, localDeadline = 0;
};
struct Sync {
  uint32_t display = 0, weather = 0, cpu = 0, stats = 0, generation = 0, episode = 0;
};
struct Card {
  Notification notification;
  uint64_t epoch = 0, deadline = 0, relayDeadline = 0;
  uint32_t generation = 0, episode = 0;
  Origin origin = Origin::External;
};

inline void incrementSaturated(uint32_t &value) {
  if (value != UINT32_MAX) ++value;
}
inline bool sameControl(const Control &a, const Control &b) {
  return a.revision == b.revision && a.generation == b.generation &&
      a.episode == b.episode && a.enabled == b.enabled && a.preferred == b.preferred;
}

// The only DESK screen/card policy owner. No clock, transport or LVGL calls.
// Callbacks own real presentation completion; estimates never complete a card.
class Controller {
 public:
  static constexpr size_t Capacity = 8;

  void welcome(uint64_t epoch, uint64_t connection, uint32_t latestSeq, uint64_t now) {
    disconnect();
    if (!epoch || !connection) return;
    if (epoch != epoch_ || !processed_ || processed_ > latestSeq) {
      processed_ = latestSeq; enqueued_ = latestSeq;
    }
    epoch_ = epoch; connection_ = connection; linked_ = true; legacy_ = false;
    welcomedAt_ = now; recovery_ = false; grant_ = 0; received_ = 0;
    control_ = Control(); weather_ = Freshness(); cpu_ = Freshness();
    statsRevision_ = 0; statsGeneration_ = 0;
    haveControl_ = haveWeather_ = haveCpu_ = haveStats_ = false;
  }
  enum class LegacyGreet : uint8_t { Rebaselined, Resumed };
  LegacyGreet legacyGreeting(uint32_t latestSeq, uint32_t sessionId) {
    legacySessionChanged_ = !legacySessionKnown_ || legacySessionId_ != sessionId;
    legacySessionId_ = sessionId; legacySessionKnown_ = true; legacyGap_ = false;
    const bool baseline = !legacySeq_ || latestSeq < legacySeq_;
    if (baseline) legacySeq_ = latestSeq;
    legacyWelcome();
    return baseline ? LegacyGreet::Rebaselined : LegacyGreet::Resumed;
  }
  Admission legacyAdmit(const Notification &notification) {
    if (notification.seq <= legacySeq_) return Admission::Duplicate;
    if (pendingCount_ == Capacity) {
      incrementSaturated(refused_); legacyGap_ = true; return Admission::Refused;
    }
    Card card; card.notification = notification; card.epoch = 0;
    card.origin = Origin::Unknown; card.deadline = UINT64_MAX;
    pending_[pendingCount_++] = card;
    incrementSaturated(legacyAccepted_);
    if (!legacyGap_) legacySeq_ = notification.seq;
    return Admission::Enqueued;
  }
  uint32_t legacySeq() const { return legacySeq_; }
  uint32_t legacyAccepted() const { return legacyAccepted_; }
  bool legacyGap() const { return legacyGap_; }
  bool legacySessionChanged() const { return legacySessionChanged_; }
  void legacyWelcome() {
    disconnect(); linked_ = true; legacy_ = true; recovery_ = false;
  }
  void disconnect() {
    linked_ = ready_ = clockValid_ = false;
    removeIneligible(0, true);
  }
  bool control(uint64_t epoch, uint64_t connection, const Control &value) {
    if (!session(epoch, connection) || !value.revision || !value.generation || !value.episode ||
        static_cast<uint8_t>(value.preferred) > 2) return false;
    if (haveControl_ && value.revision < control_.revision) return true;
    if (haveControl_ && value.revision == control_.revision) return sameControl(value, control_);
    if (haveControl_ && (value.generation < control_.generation || value.episode < control_.episode)) return false;
    control_ = value; haveControl_ = true;
    removeIneligible(0, false);
    return true;
  }
  bool weather(uint64_t epoch, uint64_t connection, const Freshness &value) {
    return snapshot(epoch, connection, value, weather_, haveWeather_);
  }
  bool cpu(uint64_t epoch, uint64_t connection, const Freshness &value) {
    return snapshot(epoch, connection, value, cpu_, haveCpu_);
  }
  bool stats(uint64_t epoch, uint64_t connection, uint32_t revision, uint32_t generation) {
    if (!session(epoch, connection) || !haveControl_ || !revision || generation != control_.generation) return false;
    if (revision < statsRevision_) return true;
    if (revision == statsRevision_ && generation != statsGeneration_) return false;
    statsRevision_ = revision; statsGeneration_ = generation; haveStats_ = true;
    return true;
  }
  bool sync(uint64_t epoch, uint64_t connection, const Sync &value, uint64_t now) {
    if (!session(epoch, connection) || ready_ || !clockValid_ || now < welcomedAt_ ||
        now - welcomedAt_ >= 5000 || !haveControl_ || !haveWeather_ || !haveCpu_ || !haveStats_ ||
        value.display != control_.revision || value.weather != weather_.revision || value.cpu != cpu_.revision ||
        value.stats != statsRevision_ || value.generation != control_.generation ||
        value.generation != statsGeneration_ || value.episode != control_.episode) return false;
    ready_ = true;
    grant_ = Capacity - pendingCount_;
    reconcile(now, true);
    return true;
  }
  // Required before every render/reveal, including during slide-out.
  Selection reconcile(uint64_t now, bool calibrated) {
    clockValid_ = calibrated;
    if (linked_ && !legacy_ && (!calibrated || now < welcomedAt_ ||
        (!ready_ && now - welcomedAt_ >= 5000))) {
      recovery_ = true;
      disconnect();
    }
    removeIneligible(now, !linked_);
    return selection(now);
  }
  Selection selection(uint64_t now) const {
    Selection result;
    if (!linked_) return result;
    result.base = Base::ConnectedOrbit; result.reason = Reason::Synchronizing;
    if (legacy_) { result.base = Base::Twitch; result.reason = Reason::Legacy; return result; }
    if (!ready_) return result;
    if (control_.enabled) { result.base = Base::Twitch; result.reason = Reason::TwitchOverride; return result; }
    if (control_.preferred == Preferred::Generic) { result.reason = Reason::GenericSelected; return result; }
    const Freshness &data = control_.preferred == Preferred::Weather ? weather_ : cpu_;
    if (!clockValid_) { result.reason = Reason::ClockUnavailable; return result; }
    if (data.status == Status::Missing) { result.reason = Reason::DataMissing; return result; }
    if (data.status != Status::Fresh || data.localDeadline <= now) { result.reason = Reason::DataStale; return result; }
    result.base = control_.preferred == Preferred::Weather ? Base::Weather : Base::Cpu;
    result.reason = Reason::Selected;
    return result;
  }
  Admission admit(const Card &value, uint64_t now) {
    if (!ready_ || !linked_ || legacy_ || !clockValid_ || value.epoch != epoch_ ||
        !value.notification.seq || static_cast<uint8_t>(value.origin) > 2 ||
        (value.origin != Origin::Twitch && (value.generation || value.episode))) return Admission::Invalid;
    if (received_ >= grant_) return refuse();
    ++received_;
    if (value.notification.seq <= processed_) { release(); return Admission::Duplicate; }
    if (value.deadline <= now) {
      incrementSaturated(expired_); processed_ = value.notification.seq; release(); return Admission::Expired;
    }
    if (!eligible(value)) {
      incrementSaturated(suppressed_); processed_ = value.notification.seq; release(); return Admission::Suppressed;
    }
    if (pendingCount_ == Capacity) return refuse();
    pending_[pendingCount_++] = value;
    processed_ = enqueued_ = value.notification.seq;
    return Admission::Enqueued;
  }
  bool barrier(uint64_t epoch, uint64_t connection, uint32_t through) {
    if (!session(epoch, connection) || !ready_ || through < processed_) return false;
    processed_ = through;
    return true;
  }
  bool start(uint64_t now, Card &out) {
    removeIneligible(now, !linked_);
    if (phase_ != Phase::None || !pendingCount_) return false;
    active_ = pending_[0]; erase(0);
    phase_ = entranceFor(active_.notification.replay, active_.notification.kind) == Entrance::None
        ? Phase::Hold : Phase::Entrance;
    activeEnd_ = addBounded(now, occupancy(active_.notification));
    out = active_;
    return true;
  }
  bool hold(uint64_t now) {
    if (phase_ != Phase::Entrance) return false;
    phase_ = Phase::Hold;
    activeEnd_ = addBounded(now, holdDuration(active_.notification) + exitDuration(active_.notification));
    return true;
  }
  Selection beginExit(uint64_t now, bool calibrated) {
    const Selection result = reconcile(now, calibrated);
    if (phase_ == Phase::Hold) {
      phase_ = Phase::Exit;
      activeEnd_ = addBounded(now, exitDuration(active_.notification));
    }
    return result;
  }
  bool complete(uint64_t now, bool calibrated) {
    reconcile(now, calibrated);
    if (phase_ != Phase::Exit) return false;
    phase_ = Phase::None; active_ = Card(); activeEnd_ = 0;
    return true;  // Active completion does NOT release a second credit.
  }
  uint32_t remaining(uint64_t now) const {
    return phase_ != Phase::None && activeEnd_ > now ? static_cast<uint32_t>(activeEnd_ - now) : 0;
  }
  // Recalibration can only shorten accepted current-epoch lifetimes. Cards
  // retained from another epoch keep their already conservative local deadline.
  template <class Clock>
  void recalibrate(const Clock &clock, uint64_t now) {
    for (size_t i = 0; i < pendingCount_; ++i) {
      Card &card = pending_[i];
      if (card.epoch == epoch_ && card.relayDeadline) {
        const uint64_t converted = clock.deadline(card.relayDeadline, now);
        if (converted < card.deadline) card.deadline = converted;
      }
    }
    shortenSnapshot(weather_, clock, now);
    shortenSnapshot(cpu_, clock, now);
    reconcile(now, clock.usable(now));
  }
  size_t pendingCount() const { return pendingCount_; }
  const Card *pending(size_t index) const { return index < pendingCount_ ? &pending_[index] : nullptr; }
  const Card *active() const { return phase_ == Phase::None ? nullptr : &active_; }
  Phase phase() const { return phase_; }
  bool ready() const { return ready_; }
  bool recoveryRequired() const { return recovery_; }
  uint64_t grantTotal() const { return grant_; }
  uint64_t receivedTotal() const { return received_; }
  uint32_t processedSeq() const { return processed_; }
  uint32_t enqueuedSeq() const { return enqueued_; }
  uint32_t expiredTotal() const { return expired_; }
  uint32_t suppressedTotal() const { return suppressed_; }
  uint32_t refusedTotal() const { return refused_; }
  const Control &appliedControl() const { return control_; }
  uint64_t relayEpoch() const { return epoch_; }
  uint64_t connectionId() const { return connection_; }
  uint32_t weatherRevision() const { return weather_.revision; }
  uint32_t cpuRevision() const { return cpu_.revision; }

 private:
  template <class Clock>
  static void shortenSnapshot(Freshness &value, const Clock &clock, uint64_t now) {
    if (value.status != Status::Fresh) return;
    const uint64_t converted = clock.deadline(value.relayDeadline, now);
    if (converted < value.localDeadline) value.localDeadline = converted;
  }
  static uint64_t addBounded(uint64_t now, uint32_t duration) {
    return duration > UINT64_MAX - now ? UINT64_MAX : now + duration;
  }
  static uint32_t holdDuration(const Notification &n) {
    return n.ttl_ds ? uint32_t(n.ttl_ds) * 100u : kindPresentation(n.kind).holdMs;
  }
  static uint32_t exitDuration(const Notification &n) { return n.replay ? 0u : 280u; }
  static uint32_t occupancy(const Notification &n) {
    const Entrance entrance = entranceFor(n.replay, n.kind);
    return holdDuration(n) + exitDuration(n) + (entrance == Entrance::None ? 0u :
        entrance == Entrance::FlashThenSlide ? 1550u : 350u);
  }
  bool session(uint64_t epoch, uint64_t connection) const {
    return linked_ && !legacy_ && epoch == epoch_ && connection == connection_;
  }
  bool snapshot(uint64_t epoch, uint64_t connection, const Freshness &value, Freshness &slot, bool &known) {
    if (!session(epoch, connection) || !value.revision || static_cast<uint8_t>(value.status) > 2 ||
        (value.status != Status::Fresh && (value.relayDeadline || value.localDeadline))) return false;
    if (known && value.revision < slot.revision) return true;
    if (known && value.revision == slot.revision) {
      if (value.status != slot.status || value.relayDeadline != slot.relayDeadline) return false;
      if (value.localDeadline < slot.localDeadline) slot.localDeadline = value.localDeadline;
      return true;
    }
    slot = value; known = true;
    return true;
  }
  bool eligible(const Card &card) const {
    return card.origin != Origin::Twitch || (linked_ && ready_ && control_.enabled &&
        card.generation == control_.generation && card.episode == control_.episode);
  }
  void release() {
    if (!ready_) return;
    if (grant_ == UINT64_MAX) { recovery_ = true; disconnect(); return; }
    ++grant_;
  }
  void erase(size_t index) {
    for (size_t i = index + 1; i < pendingCount_; ++i) pending_[i - 1] = pending_[i];
    --pendingCount_;
    pending_[pendingCount_] = Card();
    release();
  }
  void removeIneligible(uint64_t now, bool disconnected) {
    for (size_t i = 0; i < pendingCount_;) {
      const Card &card = pending_[i];
      if (card.deadline <= now) { incrementSaturated(expired_); erase(i); }
      else if (card.origin == Origin::Twitch && (disconnected || !eligible(card))) {
        incrementSaturated(suppressed_); erase(i);
      } else ++i;
    }
  }
  Admission refuse() {
    incrementSaturated(refused_); recovery_ = true; disconnect();
    return Admission::Refused;
  }
  std::array<Card, Capacity> pending_{};
  size_t pendingCount_ = 0;
  Card active_;
  Phase phase_ = Phase::None;
  uint64_t activeEnd_ = 0, epoch_ = 0, connection_ = 0, welcomedAt_ = 0;
  uint64_t grant_ = 0, received_ = 0;
  uint32_t processed_ = 0, enqueued_ = 0, expired_ = 0, suppressed_ = 0, refused_ = 0;
  Control control_;
  Freshness weather_, cpu_;
  uint32_t statsRevision_ = 0, statsGeneration_ = 0;
  bool linked_ = false, legacy_ = false, ready_ = false, clockValid_ = false, recovery_ = false;
  uint32_t legacySeq_ = 0, legacySessionId_ = 0, legacyAccepted_ = 0;
  bool legacySessionKnown_ = false, legacySessionChanged_ = false, legacyGap_ = false;
  bool haveControl_ = false, haveWeather_ = false, haveCpu_ = false, haveStats_ = false;
};

}  // namespace desk
