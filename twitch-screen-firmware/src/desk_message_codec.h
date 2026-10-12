#pragma once

#include "desk_state_codec.h"

namespace desk {
namespace wire {

constexpr uint32_t Capability = 0x00000010u;
constexpr uint8_t TimeRequest = 0x05, DeskEvent = 0x2b;

struct Hello {
  tsb::TsbHello base{};
  uint64_t previousEpoch = 0, boot = 0, probe = 0;
  uint32_t processed = 0;
};
struct Welcome {
  tsb::TsbWelcome base{};
  uint64_t epoch = 0, connection = 0, sample = 0, echo = 0;
};
struct Event {
  tsb::TsbEvent base{};
  uint64_t epoch = 0, deadline = 0;
  uint32_t generation = 0, episode = 0;
  Origin origin = Origin::External;
};
struct Stats {
  tsb::TsbStats base{};
  uint64_t epoch = 0, connection = 0;
  uint32_t generation = 0, revision = 0;
  uint8_t availability = 0;
};
enum class Negotiation : uint8_t { Classic, Desk, Invalid };

// Encoder is exercised in host tests only until the complete production profile
// is ready. This helper does not change link_client's advertised capability set.
inline tsb::EncodeResult encodeHello(uint8_t *out, size_t capacity, const Hello &s) {
  if (!s.boot) return {0, tsb::EncodeError::Argument};
  std::array<uint8_t, 92> p{};
  write(p.data(), 4, s.base.last_seq); write(p.data() + 4, 4, s.base.caps | Capability);
  write(p.data() + 8, 2, s.base.rx_max);
  memcpy(p.data() + 12, s.base.device_id, 32); memcpy(p.data() + 44, s.base.fw_version, 16);
  write(p.data() + 60, 2, 1); write(p.data() + 64, 8, s.previousEpoch);
  write(p.data() + 72, 4, s.processed); write(p.data() + 76, 8, s.boot); write(p.data() + 84, 8, s.probe);
  return tsb::encodeFrame(out, capacity, tsb::T_HELLO, 0, p.data(), p.size());
}
inline bool helloSupportsDesk(const uint8_t *p, size_t size) {
  return p && size >= 92 && size <= tsb::MAX_PAYLOAD && (read(p + 4, 4) & Capability) &&
      read(p + 60, 2) == 1 && zero(p + 62, 2) && read(p + 76, 8);
}
inline Negotiation decodeWelcome(const uint8_t *p, size_t size, uint64_t expectedEcho, Welcome &out) {
  Welcome s;
  if (tsb::decodeWelcome(p, size, s.base) != tsb::DecodeResult::Ok) return Negotiation::Invalid;
  if (!(s.base.caps & Capability)) { out = s; return Negotiation::Classic; }
  if (size < 60 || size > tsb::MAX_PAYLOAD || read(p + 24, 2) != 1 || !zero(p + 26, 2)) return Negotiation::Invalid;
  s.epoch = read(p + 28, 8); s.connection = read(p + 36, 8);
  s.sample = read(p + 44, 8); s.echo = read(p + 52, 8);
  if (!s.epoch || !s.connection || s.echo != expectedEcho) return Negotiation::Invalid;
  out = s;
  return Negotiation::Desk;
}
inline tsb::EncodeResult encodeWelcome(uint8_t *out, size_t capacity, const Welcome &s) {
  if (!s.epoch || !s.connection) return {0, tsb::EncodeError::Argument};
  std::array<uint8_t, 60> p{};
  write(p.data(), 4, s.base.latest_seq); write(p.data() + 4, 4, s.base.server_time);
  write(p.data() + 8, 4, s.base.session_id); write(p.data() + 12, 2, s.base.max_frame);
  write(p.data() + 14, 2, s.base.ping_interval_s); write(p.data() + 16, 2, s.base.idle_timeout_s);
  write(p.data() + 18, 2, s.base.replay_window); write(p.data() + 20, 4, s.base.caps | Capability);
  write(p.data() + 24, 2, 1); write(p.data() + 28, 8, s.epoch); write(p.data() + 36, 8, s.connection);
  write(p.data() + 44, 8, s.sample); write(p.data() + 52, 8, s.echo);
  return tsb::encodeFrame(out, capacity, tsb::T_WELCOME, 0, p.data(), p.size());
}
inline tsb::EncodeResult encodeTimeRequest(uint8_t *out, size_t capacity, uint64_t probe) {
  std::array<uint8_t, 8> p{}; write(p.data(), 8, probe);
  return tsb::encodeFrame(out, capacity, TimeRequest, 0, p.data(), p.size());
}
inline bool validEvent(const Event &s) {
  return s.base.seq && s.epoch && s.deadline && static_cast<uint8_t>(s.origin) <= 2 &&
      (s.origin == Origin::Twitch ? s.generation && s.episode : !s.generation && !s.episode);
}
inline tsb::DecodeResult decodeEvent(const uint8_t *p, size_t size, Event &out) {
  if (!p || size < 196) return tsb::DecodeResult::ShortPayload;
  if (size > tsb::MAX_PAYLOAD) return tsb::DecodeResult::LengthOutOfRange;
  Event s;
  const tsb::DecodeResult base = tsb::decodeEvent(p, 168, s.base);
  if (base != tsb::DecodeResult::Ok) return base;
  s.epoch = read(p + 168, 8); s.generation = static_cast<uint32_t>(read(p + 176, 4));
  s.episode = static_cast<uint32_t>(read(p + 180, 4)); s.deadline = read(p + 184, 8);
  s.origin = static_cast<Origin>(p[192]);
  if (!zero(p + 193, 3) || !validEvent(s)) return tsb::DecodeResult::InvalidField;
  out = s;
  return tsb::DecodeResult::Ok;
}
inline tsb::EncodeResult encodeEvent(uint8_t *out, size_t capacity, uint8_t flags, const Event &s) {
  if (!validEvent(s)) return {0, tsb::EncodeError::Argument};
  std::array<uint8_t, 196> p{};
  write(p.data(), 4, s.base.seq); write(p.data() + 4, 4, s.base.ts); write(p.data() + 8, 4, s.base.value);
  memcpy(p.data() + 12, s.base.reserved1, 4); write(p.data() + 16, 2, s.base.months);
  write(p.data() + 18, 2, s.base.ttl_ds); p[20] = s.base.kind; p[21] = s.base.tier;
  p[22] = s.base.eflags; p[23] = s.base.reserved2;
  memcpy(p.data() + 24, s.base.actor, 48); memcpy(p.data() + 72, s.base.text, 96);
  write(p.data() + 168, 8, s.epoch); write(p.data() + 176, 4, s.generation); write(p.data() + 180, 4, s.episode);
  write(p.data() + 184, 8, s.deadline); p[192] = static_cast<uint8_t>(s.origin);
  return tsb::encodeFrame(out, capacity, DeskEvent, flags, p.data(), p.size());
}
inline tsb::DecodeResult decodeStats(const uint8_t *p, size_t size, Stats &out) {
  if (!p || size < 60) return tsb::DecodeResult::ShortPayload;
  if (size > tsb::MAX_PAYLOAD) return tsb::DecodeResult::LengthOutOfRange;
  Stats s;
  const tsb::DecodeResult base = tsb::decodeStats(p, 32, s.base);
  if (base != tsb::DecodeResult::Ok) return base;
  s.epoch = read(p + 32, 8); s.connection = read(p + 40, 8);
  s.generation = static_cast<uint32_t>(read(p + 48, 4)); s.revision = static_cast<uint32_t>(read(p + 52, 4));
  s.availability = p[56];
  if (!s.epoch || !s.connection || !s.generation || !s.revision || s.availability > 2 || !zero(p + 57, 3))
    return tsb::DecodeResult::InvalidField;
  out = s;
  return tsb::DecodeResult::Ok;
}
inline tsb::EncodeResult encodeStats(uint8_t *out, size_t capacity, const Stats &s) {
  if (!s.epoch || !s.connection || !s.generation || !s.revision || s.availability > 2) return {0, tsb::EncodeError::Argument};
  std::array<uint8_t, 60> p{};
  write(p.data(), 4, s.base.viewers); write(p.data() + 4, 4, s.base.msg_total);
  write(p.data() + 8, 4, s.base.uptime_s); write(p.data() + 12, 4, s.base.followers);
  write(p.data() + 16, 4, s.base.subs); write(p.data() + 20, 4, s.base.server_time);
  write(p.data() + 24, 4, s.base.stream_started_at); write(p.data() + 28, 2, s.base.chat_rate);
  p[30] = s.base.live; p[31] = s.base.sflags;
  write(p.data() + 32, 8, s.epoch); write(p.data() + 40, 8, s.connection);
  write(p.data() + 48, 4, s.generation); write(p.data() + 52, 4, s.revision); p[56] = s.availability;
  return tsb::encodeFrame(out, capacity, tsb::T_STATS, 0, p.data(), p.size());
}

}  // namespace wire
}  // namespace desk
