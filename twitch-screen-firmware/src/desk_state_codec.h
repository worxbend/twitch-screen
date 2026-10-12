#pragma once

#include <string.h>
#include "proto_codec.h"
#include "desk_controller.h"

namespace desk {
namespace wire {

// This codec is an implementation precursor, not capability negotiation.
// link_client still advertises only its classic capabilities.
constexpr uint8_t TimeResponse = 0x26, DisplayState = 0x27, Weather = 0x28,
    Cpu = 0x29, SyncEnd = 0x2a, ProgressBarrier = 0x2c;

struct State {
  uint64_t epoch = 0, connection = 0;
  Control control;
  Sync sync;
  uint32_t revision = 0, through = 0;
  uint64_t deadline = 0, probe = 0, relaySample = 0;
  Status status = Status::Missing;
  uint8_t unit = 0, condition = 0, presence = 0, humidity = 0;
  int16_t temperature = 0;
  uint16_t cpu = 0, memory = 0;
  char label[32] = {};
};

inline uint16_t length(uint8_t type) {
  switch (type) {
    case TimeResponse: case DisplayState: return 32;
    case Weather: case Cpu: return 68;
    case SyncEnd: return 40;
    case ProgressBarrier: return 20;
    default: return 0;
  }
}
inline uint64_t read(const uint8_t *p, size_t width) {
  uint64_t value = 0;
  for (size_t i = 0; i < width; ++i) value |= uint64_t(p[i]) << (8u * i);
  return value;
}
inline void write(uint8_t *p, size_t width, uint64_t value) {
  for (size_t i = 0; i < width; ++i) p[i] = static_cast<uint8_t>(value >> (8u * i));
}
inline int16_t signed16(const uint8_t *p) {
  const uint16_t bits = static_cast<uint16_t>(read(p, 2));
  const int32_t value = bits & 0x8000u ? int32_t(bits) - 65536 : int32_t(bits);
  return static_cast<int16_t>(value);
}
inline bool zero(const uint8_t *p, size_t count) {
  for (size_t i = 0; i < count; ++i) if (p[i]) return false;
  return true;
}
inline bool valid(const State &s, uint8_t type) {
  if (!s.epoch || !s.connection) return false;
  if (type == TimeResponse || type == ProgressBarrier) return true;
  if (type == DisplayState)
    return s.control.revision && s.control.generation && s.control.episode &&
        static_cast<uint8_t>(s.control.preferred) <= 2;
  if (type == SyncEnd)
    return s.sync.display && s.sync.weather && s.sync.cpu && s.sync.stats && s.sync.generation && s.sync.episode;
  if (!s.revision || static_cast<uint8_t>(s.status) > 2) return false;
  if (s.status != Status::Fresh)
    return !s.deadline && !s.unit && !s.condition && !s.presence && !s.temperature &&
        !s.humidity && !s.cpu && !s.memory && zero(reinterpret_cast<const uint8_t *>(s.label), sizeof(s.label));
  if (!s.deadline || s.label[31] || !s.label[0]) return false;
  if (type == Weather)
    return s.unit <= 1 && s.condition <= 8 && s.presence <= 1 && s.humidity <= 100 &&
        (s.presence || !s.humidity) && s.temperature >= (s.unit ? -14800 : -10000) &&
        s.temperature <= (s.unit ? 21200 : 10000);
  return type == Cpu && s.presence <= 3 && s.cpu <= 10000 && s.memory <= 10000 &&
      ((s.presence & 1) || !s.memory) && ((s.presence & 2) ?
        s.temperature >= -2000 && s.temperature <= 15000 : !s.temperature);
}

// Payload bounds are established before any field access. Appended unknown
// tails are ignored, as required by section 20.1. Known reserved fields are strict.
inline tsb::DecodeResult decode(uint8_t type, const uint8_t *p, size_t size, State &out) {
  const uint16_t expected = length(type);
  if (!expected) return tsb::DecodeResult::UnknownType;
  if (!p || size < expected) return tsb::DecodeResult::ShortPayload;
  if (size > tsb::MAX_PAYLOAD) return tsb::DecodeResult::LengthOutOfRange;
  State s;
  s.epoch = read(p, 8); s.connection = read(p + 8, 8);
  switch (type) {
    case TimeResponse:
      s.probe = read(p + 16, 8); s.relaySample = read(p + 24, 8); break;
    case DisplayState:
      s.control.revision = static_cast<uint32_t>(read(p + 16, 4));
      s.control.generation = static_cast<uint32_t>(read(p + 20, 4));
      s.control.episode = static_cast<uint32_t>(read(p + 24, 4));
      if (p[28] > 1 || !zero(p + 30, 2)) return tsb::DecodeResult::InvalidField;
      s.control.enabled = p[28] != 0; s.control.preferred = static_cast<Preferred>(p[29]); break;
    case Weather: case Cpu:
      s.revision = static_cast<uint32_t>(read(p + 16, 4)); s.deadline = read(p + 20, 8);
      s.status = static_cast<Status>(p[28]);
      if (type == Weather) {
        s.unit = p[29]; s.condition = p[30]; s.presence = p[31];
        s.temperature = signed16(p + 32); s.humidity = p[34];
        if (p[35]) return tsb::DecodeResult::InvalidField;
      } else {
        s.presence = p[29]; s.cpu = static_cast<uint16_t>(read(p + 30, 2));
        s.memory = static_cast<uint16_t>(read(p + 32, 2)); s.temperature = signed16(p + 34);
      }
      if (s.status == Status::Fresh) tsb::copyWireString(s.label, p + 36, sizeof(s.label));
      else memcpy(s.label, p + 36, sizeof(s.label));
      break;
    case SyncEnd:
      s.sync.display = static_cast<uint32_t>(read(p + 16, 4));
      s.sync.weather = static_cast<uint32_t>(read(p + 20, 4));
      s.sync.cpu = static_cast<uint32_t>(read(p + 24, 4));
      s.sync.stats = static_cast<uint32_t>(read(p + 28, 4));
      s.sync.generation = static_cast<uint32_t>(read(p + 32, 4));
      s.sync.episode = static_cast<uint32_t>(read(p + 36, 4)); break;
    case ProgressBarrier: s.through = static_cast<uint32_t>(read(p + 16, 4)); break;
    default: return tsb::DecodeResult::UnknownType;
  }
  if (!valid(s, type)) return tsb::DecodeResult::InvalidField;
  out = s;
  return tsb::DecodeResult::Ok;
}
inline tsb::EncodeResult encode(uint8_t *out, size_t capacity, uint8_t type, uint8_t flags, const State &s) {
  const uint16_t size = length(type);
  if (!size || !valid(s, type)) return {0, tsb::EncodeError::Argument};
  std::array<uint8_t, 68> p{};
  write(p.data(), 8, s.epoch); write(p.data() + 8, 8, s.connection);
  switch (type) {
    case TimeResponse: write(p.data() + 16, 8, s.probe); write(p.data() + 24, 8, s.relaySample); break;
    case DisplayState:
      write(p.data() + 16, 4, s.control.revision); write(p.data() + 20, 4, s.control.generation);
      write(p.data() + 24, 4, s.control.episode); p[28] = s.control.enabled; p[29] = static_cast<uint8_t>(s.control.preferred); break;
    case Weather: case Cpu:
      write(p.data() + 16, 4, s.revision); write(p.data() + 20, 8, s.deadline); p[28] = static_cast<uint8_t>(s.status);
      if (type == Weather) {
        p[29] = s.unit; p[30] = s.condition; p[31] = s.presence;
        write(p.data() + 32, 2, static_cast<uint16_t>(s.temperature)); p[34] = s.humidity;
      } else {
        p[29] = s.presence; write(p.data() + 30, 2, s.cpu); write(p.data() + 32, 2, s.memory);
        write(p.data() + 34, 2, static_cast<uint16_t>(s.temperature));
      }
      memcpy(p.data() + 36, s.label, 32); break;
    case SyncEnd:
      write(p.data() + 16, 4, s.sync.display); write(p.data() + 20, 4, s.sync.weather);
      write(p.data() + 24, 4, s.sync.cpu); write(p.data() + 28, 4, s.sync.stats);
      write(p.data() + 32, 4, s.sync.generation); write(p.data() + 36, 4, s.sync.episode); break;
    case ProgressBarrier: write(p.data() + 16, 4, s.through); break;
    default: return {0, tsb::EncodeError::Argument};
  }
  return tsb::encodeFrame(out, capacity, type, flags, p.data(), size);
}

// Compare retained validated known wire bytes before projection/normalization.
// Equal revisions cannot hide a changed receiver-normalized string byte.
inline bool sameKnownPayload(uint8_t type, const uint8_t *a, size_t aSize,
                             const uint8_t *b, size_t bSize) {
  const uint16_t known = length(type);
  State left, right;
  return known && decode(type, a, aSize, left) == tsb::DecodeResult::Ok &&
      decode(type, b, bSize, right) == tsb::DecodeResult::Ok && memcmp(a, b, known) == 0;
}

// Compare canonical known domain bytes, not padded native structs or a lossy
// hash. Use sameKnownPayload for equal-revision wire acceptance before receiver
// normalization. Local converted deadlines remain in Controller and only shorten.
inline bool sameKnownState(uint8_t type, const State &a, const State &b) {
  std::array<uint8_t, 76> left{}, right{};
  const tsb::EncodeResult x = encode(left.data(), left.size(), type, 0, a);
  const tsb::EncodeResult y = encode(right.data(), right.size(), type, 0, b);
  return x.ok() && y.ok() && x.size == y.size && memcmp(left.data(), right.data(), x.size) == 0;
}

}  // namespace wire
}  // namespace desk
