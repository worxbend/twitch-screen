#pragma once

#include "desk_state_codec.h"

namespace desk {
namespace wire {

constexpr uint8_t DeviceReport = 0x06;
constexpr uint16_t ReportPayloadBytes = 244;
static_assert(ReportPayloadBytes + tsb::HEADER_SIZE == 252, "report frame budget");
struct Entry {
  uint64_t epoch = 0;
  uint32_t seq = 0;
  uint8_t kind = 0;
  Origin origin = Origin::External;
};
struct Report {
  uint64_t epoch = 0, connection = 0, boot = 0;
  uint32_t revision = 0;
  uint64_t observedDevice = 0, observedRelayLower = 0, grant = 0;
  uint32_t processed = 0, enqueued = 0;
  uint32_t display = 0, generation = 0, episode = 0, weather = 0, cpu = 0;
  Base base = Base::ConnectedOrbit;
  Reason reason = Reason::Selected;
  Phase phase = Phase::None;
  uint8_t pending = 0;
  uint32_t remaining = 0, expired = 0, suppressed = 0, refused = 0;
  std::array<Entry, 9> entries{};
};
inline bool empty(const Entry &s) {
  return !s.epoch && !s.seq && !s.kind && s.origin == Origin::External;
}
inline bool valid(const Entry &s) {
  return s.seq && (s.origin == Origin::Unknown ? !s.epoch :
      s.epoch && static_cast<uint8_t>(s.origin) <= 2);
}
inline bool valid(const Report &s) {
  if (!s.epoch || !s.connection || !s.boot || !s.revision || !s.display || !s.generation ||
      !s.episode || !s.weather || !s.cpu || s.enqueued > s.processed || s.pending > 8 ||
      static_cast<uint8_t>(s.base) > 4 || static_cast<uint8_t>(s.reason) > 8 ||
      static_cast<uint8_t>(s.phase) > 3) return false;
  if (s.phase == Phase::None ? s.remaining || !empty(s.entries[0]) : !valid(s.entries[0])) return false;
  for (size_t i = 1; i < s.entries.size(); ++i)
    if (i <= s.pending ? !valid(s.entries[i]) : !empty(s.entries[i])) return false;
  // Retained entries intentionally do not compare with current epoch/cursors.
  return true;
}
inline tsb::EncodeResult encodeReport(uint8_t *out, size_t capacity, const Report &s) {
  if (!valid(s)) return {0, tsb::EncodeError::Argument};
  std::array<uint8_t, ReportPayloadBytes> p{};
  write(p.data(), 8, s.epoch); write(p.data() + 8, 8, s.connection); write(p.data() + 16, 8, s.boot);
  write(p.data() + 24, 4, s.revision); write(p.data() + 28, 8, s.observedDevice);
  write(p.data() + 36, 8, s.observedRelayLower); write(p.data() + 44, 8, s.grant);
  write(p.data() + 52, 4, s.processed); write(p.data() + 56, 4, s.enqueued);
  write(p.data() + 60, 4, s.display); write(p.data() + 64, 4, s.generation);
  write(p.data() + 68, 4, s.episode); write(p.data() + 72, 4, s.weather); write(p.data() + 76, 4, s.cpu);
  p[80] = static_cast<uint8_t>(s.base); p[81] = static_cast<uint8_t>(s.reason);
  p[82] = static_cast<uint8_t>(s.phase); p[83] = s.pending;
  write(p.data() + 84, 4, s.remaining); write(p.data() + 88, 4, s.expired);
  write(p.data() + 92, 4, s.suppressed); write(p.data() + 96, 4, s.refused);
  for (size_t i = 0; i < s.entries.size(); ++i) {
    uint8_t *entry = p.data() + 100 + i * 16;
    write(entry, 8, s.entries[i].epoch); write(entry + 8, 4, s.entries[i].seq);
    entry[12] = s.entries[i].kind; entry[13] = static_cast<uint8_t>(s.entries[i].origin);
  }
  return tsb::encodeFrame(out, capacity, DeviceReport, 0, p.data(), p.size());
}
inline tsb::DecodeResult decodeReport(const uint8_t *p, size_t size, Report &out) {
  if (!p || size < ReportPayloadBytes) return tsb::DecodeResult::ShortPayload;
  if (size > tsb::MAX_PAYLOAD) return tsb::DecodeResult::LengthOutOfRange;
  Report s;
  s.epoch = read(p, 8); s.connection = read(p + 8, 8); s.boot = read(p + 16, 8);
  s.revision = static_cast<uint32_t>(read(p + 24, 4)); s.observedDevice = read(p + 28, 8);
  s.observedRelayLower = read(p + 36, 8); s.grant = read(p + 44, 8);
  s.processed = static_cast<uint32_t>(read(p + 52, 4)); s.enqueued = static_cast<uint32_t>(read(p + 56, 4));
  s.display = static_cast<uint32_t>(read(p + 60, 4)); s.generation = static_cast<uint32_t>(read(p + 64, 4));
  s.episode = static_cast<uint32_t>(read(p + 68, 4)); s.weather = static_cast<uint32_t>(read(p + 72, 4));
  s.cpu = static_cast<uint32_t>(read(p + 76, 4)); s.base = static_cast<Base>(p[80]); s.reason = static_cast<Reason>(p[81]);
  s.phase = static_cast<Phase>(p[82]); s.pending = p[83]; s.remaining = static_cast<uint32_t>(read(p + 84, 4));
  s.expired = static_cast<uint32_t>(read(p + 88, 4)); s.suppressed = static_cast<uint32_t>(read(p + 92, 4));
  s.refused = static_cast<uint32_t>(read(p + 96, 4));
  for (size_t i = 0; i < s.entries.size(); ++i) {
    const uint8_t *entry = p + 100 + i * 16;
    if (!zero(entry + 14, 2)) return tsb::DecodeResult::InvalidField;
    s.entries[i].epoch = read(entry, 8); s.entries[i].seq = static_cast<uint32_t>(read(entry + 8, 4));
    s.entries[i].kind = entry[12]; s.entries[i].origin = static_cast<Origin>(entry[13]);
  }
  if (!valid(s)) return tsb::DecodeResult::InvalidField;
  out = s;
  return tsb::DecodeResult::Ok;
}

}  // namespace wire
}  // namespace desk
