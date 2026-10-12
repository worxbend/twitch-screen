#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include "desk_state_codec.h"
#include "desk_message_codec.h"
#include "desk_report_codec.h"

namespace {
using namespace desk;
struct Checker {
  int checks = 0, failures = 0;
  void operator()(bool ok, const char *what) {
    ++checks; if (!ok) { ++failures; printf("FAIL: %s\n", what); }
  }
};
std::vector<std::string> split(const std::string &input, char separator) {
  std::vector<std::string> fields;
  size_t begin = 0;
  for (;;) {
    const size_t end = input.find(separator, begin);
    fields.push_back(input.substr(begin, end == std::string::npos ? end : end - begin));
    if (end == std::string::npos) break;
    begin = end + 1;
  }
  return fields;
}
std::vector<uint8_t> hex(const std::string &value) {
  std::vector<uint8_t> bytes;
  for (size_t i = 0; i + 1 < value.size(); i += 2)
    bytes.push_back(static_cast<uint8_t>(strtoul(value.substr(i, 2).c_str(), nullptr, 16)));
  return bytes;
}
uint64_t number(const std::vector<std::string> &fields, size_t index) {
  return strtoull(fields.at(index).c_str(), nullptr, 10);
}
int16_t signedNumber(const std::vector<std::string> &fields, size_t index) {
  return static_cast<int16_t>(strtol(fields.at(index).c_str(), nullptr, 10));
}
// Independent field-to-model binding from canonical TSV, not production decode.
wire::State model(const std::string &name, const std::vector<std::string> &f) {
  wire::State s;
  s.epoch = number(f, 0); s.connection = number(f, 1);
  if (name == "TIME_RESPONSE") { s.probe = number(f, 2); s.relaySample = number(f, 3); }
  else if (name == "DISPLAY_STATE") {
    s.control.revision = number(f, 2); s.control.generation = number(f, 3); s.control.episode = number(f, 4);
    s.control.enabled = number(f, 5) != 0; s.control.preferred = static_cast<Preferred>(number(f, 6));
  } else if (name == "WEATHER" || name == "CPU") {
    s.revision = number(f, 2); s.deadline = number(f, 3); s.status = static_cast<Status>(number(f, 4));
    size_t labelIndex;
    if (name == "WEATHER") {
      s.unit = number(f, 5); s.condition = number(f, 6); s.presence = number(f, 7);
      s.temperature = signedNumber(f, 8); s.humidity = number(f, 9); labelIndex = 11;
    } else {
      s.presence = number(f, 5); s.cpu = number(f, 6); s.memory = number(f, 7);
      s.temperature = signedNumber(f, 8); labelIndex = 9;
    }
    const std::vector<uint8_t> label = hex(f.at(labelIndex));
    if (!label.empty() && label.size() < sizeof(s.label)) memcpy(s.label, label.data(), label.size());
  } else if (name == "SYNC_END") {
    s.sync.display = number(f, 2); s.sync.weather = number(f, 3); s.sync.cpu = number(f, 4);
    s.sync.stats = number(f, 5); s.sync.generation = number(f, 6); s.sync.episode = number(f, 7);
  } else s.through = number(f, 2);
  return s;
}
void textField(char *out, size_t width, const std::string &value) {
  const std::vector<uint8_t> bytes = hex(value);
  if (!bytes.empty() && bytes.size() < width) memcpy(out, bytes.data(), bytes.size());
}
void sameBytes(Checker &check, const tsb::EncodeResult &result, const std::array<uint8_t, 256> &encoded,
               const std::vector<uint8_t> &expected) {
  check(result.ok() && result.size == expected.size() && memcmp(encoded.data(), expected.data(), expected.size()) == 0,
        "actual profile codec equals independent canonical bytes");
}
void additionalVector(Checker &check, const std::vector<std::string> &columns, const std::vector<uint8_t> &bytes) {
  const std::vector<std::string> f = split(columns[4], ',');
  std::array<uint8_t, 256> encoded{}, decodedBytes{};
  std::array<uint8_t, 257> unaligned{};
  memcpy(unaligned.data() + 1, bytes.data(), bytes.size());
  const uint8_t *payload = unaligned.data() + 9;
  const size_t size = bytes.size() - 8;
  if (columns[1] == "HELLO") {
    wire::Hello s;
    s.base.last_seq = number(f, 0); s.base.caps = number(f, 1); s.base.rx_max = number(f, 2);
    textField(s.base.device_id, sizeof(s.base.device_id), f.at(4));
    textField(s.base.fw_version, sizeof(s.base.fw_version), f.at(5));
    s.previousEpoch = number(f, 8); s.processed = number(f, 9); s.boot = number(f, 10); s.probe = number(f, 11);
    sameBytes(check, wire::encodeHello(encoded.data(), encoded.size(), s), encoded, bytes);
    check(wire::helloSupportsDesk(payload, size), "valid complete HELLO supports DESK in test peer");
    for (size_t truncated = 0; truncated < size; ++truncated)
      check(!wire::helloSupportsDesk(payload, truncated), "incomplete HELLO falls back, never advertises support");
    check(!wire::encodeHello(encoded.data(), bytes.size() - 1, s).ok(), "HELLO capacity bound");
    unaligned[9 + 62] = 1;
    check(!wire::helloSupportsDesk(payload, size), "invalid HELLO reserved tail falls back");
  } else if (columns[1] == "WELCOME") {
    wire::Welcome s;
    s.base.latest_seq = number(f, 0); s.base.server_time = number(f, 1); s.base.session_id = number(f, 2);
    s.base.max_frame = number(f, 3); s.base.ping_interval_s = number(f, 4); s.base.idle_timeout_s = number(f, 5);
    s.base.replay_window = number(f, 6); s.base.caps = number(f, 7);
    s.epoch = number(f, 10); s.connection = number(f, 11); s.sample = number(f, 12); s.echo = number(f, 13);
    sameBytes(check, wire::encodeWelcome(encoded.data(), encoded.size(), s), encoded, bytes);
    wire::Welcome decoded;
    check(wire::decodeWelcome(payload, size, s.echo, decoded) == wire::Negotiation::Desk, "negotiated WELCOME decoded unaligned");
    sameBytes(check, wire::encodeWelcome(decodedBytes.data(), decodedBytes.size(), decoded), decodedBytes, bytes);
    for (size_t truncated = 0; truncated < size; ++truncated)
      check(wire::decodeWelcome(payload, truncated, s.echo, decoded) == wire::Negotiation::Invalid, "negotiated WELCOME truncation resynchronizes");
    check(wire::decodeWelcome(payload, size, s.echo + 1, decoded) == wire::Negotiation::Invalid, "WELCOME probe mismatch rejected");
    unaligned[9 + 20] &= static_cast<uint8_t>(~wire::Capability);
    unaligned[9 + 24] = 99;
    check(wire::decodeWelcome(payload, size, s.echo + 1, decoded) == wire::Negotiation::Classic, "classic WELCOME ignores all extra tail bytes");
  } else if (columns[1] == "TIME_REQUEST") {
    sameBytes(check, wire::encodeTimeRequest(encoded.data(), encoded.size(), number(f, 0)), encoded, bytes);
    check(!wire::encodeTimeRequest(encoded.data(), bytes.size() - 1, number(f, 0)).ok(), "time request capacity bound");
  } else if (columns[1] == "STATS") {
    wire::Stats s;
    s.base.viewers = number(f, 0); s.base.msg_total = number(f, 1); s.base.uptime_s = number(f, 2);
    s.base.followers = number(f, 3); s.base.subs = number(f, 4); s.base.server_time = number(f, 5);
    s.base.stream_started_at = number(f, 6); s.base.chat_rate = number(f, 7); s.base.live = number(f, 8); s.base.sflags = number(f, 9);
    s.epoch = number(f, 10); s.connection = number(f, 11); s.generation = number(f, 12); s.revision = number(f, 13); s.availability = number(f, 14);
    sameBytes(check, wire::encodeStats(encoded.data(), encoded.size(), s), encoded, bytes);
    wire::Stats decoded;
    check(wire::decodeStats(payload, size, decoded) == tsb::DecodeResult::Ok, "extended STATS decode");
    sameBytes(check, wire::encodeStats(decodedBytes.data(), decodedBytes.size(), decoded), decodedBytes, bytes);
    for (size_t truncated = 0; truncated < size; ++truncated)
      check(wire::decodeStats(payload, truncated, decoded) == tsb::DecodeResult::ShortPayload, "STATS requires complete negotiated tail");
    unaligned[9 + 56] = 3;
    check(wire::decodeStats(payload, size, decoded) == tsb::DecodeResult::InvalidField, "unknown STATS availability invalid");
  } else if (columns[1] == "DESK_EVENT") {
    wire::Event s;
    s.base.seq = number(f, 0); s.base.ts = number(f, 1); s.base.value = number(f, 2);
    s.base.months = number(f, 4); s.base.ttl_ds = number(f, 5); s.base.kind = number(f, 6);
    s.base.tier = number(f, 7); s.base.eflags = number(f, 8); s.base.reserved2 = number(f, 9);
    textField(s.base.actor, sizeof(s.base.actor), f.at(10)); textField(s.base.text, sizeof(s.base.text), f.at(11));
    s.epoch = number(f, 12); s.generation = number(f, 13); s.episode = number(f, 14);
    s.deadline = number(f, 15); s.origin = static_cast<Origin>(number(f, 16));
    const uint8_t flags = static_cast<uint8_t>(strtoul(columns[2].c_str(), nullptr, 10));
    sameBytes(check, wire::encodeEvent(encoded.data(), encoded.size(), flags, s), encoded, bytes);
    wire::Event decoded;
    check(wire::decodeEvent(payload, size, decoded) == tsb::DecodeResult::Ok, "DESK_EVENT decode");
    sameBytes(check, wire::encodeEvent(decodedBytes.data(), decodedBytes.size(), flags, decoded), decodedBytes, bytes);
    for (size_t truncated = 0; truncated < size; ++truncated)
      check(wire::decodeEvent(payload, truncated, decoded) == tsb::DecodeResult::ShortPayload, "event tail truncation rejected");
    unaligned[9 + 192] = 255;
    check(wire::decodeEvent(payload, size, decoded) == tsb::DecodeResult::InvalidField, "unknown origin cannot arrive as DESK_EVENT");
    unaligned[9 + 192] = static_cast<uint8_t>(s.origin); unaligned[9 + 193] = 1;
    check(wire::decodeEvent(payload, size, decoded) == tsb::DecodeResult::InvalidField, "DESK reserved strict");
    unaligned[9 + 193] = 0; unaligned[9 + 12] = 99; unaligned[9 + 20] = 255;
    check(wire::decodeEvent(payload, size, decoded) == tsb::DecodeResult::Ok && decoded.base.kind == 255 && decoded.base.reserved1[0] == 99,
          "classic event reserved bytes and unknown visual-kind fallback remain unchanged");
  } else if (columns[1] == "DEVICE_REPORT") {
    wire::Report s;
    s.epoch = number(f, 0); s.connection = number(f, 1); s.boot = number(f, 2); s.revision = number(f, 3);
    s.observedDevice = number(f, 4); s.observedRelayLower = number(f, 5); s.grant = number(f, 6);
    s.processed = number(f, 7); s.enqueued = number(f, 8); s.display = number(f, 9); s.generation = number(f, 10);
    s.episode = number(f, 11); s.weather = number(f, 12); s.cpu = number(f, 13);
    s.base = static_cast<Base>(number(f, 14)); s.reason = static_cast<Reason>(number(f, 15)); s.phase = static_cast<Phase>(number(f, 16));
    s.pending = number(f, 17); s.remaining = number(f, 18); s.expired = number(f, 19); s.suppressed = number(f, 20); s.refused = number(f, 21);
    for (size_t i = 0; i < 9; ++i) {
      s.entries[i].epoch = number(f, 22 + i * 5); s.entries[i].seq = number(f, 23 + i * 5);
      s.entries[i].kind = number(f, 24 + i * 5); s.entries[i].origin = static_cast<Origin>(number(f, 25 + i * 5));
    }
    sameBytes(check, wire::encodeReport(encoded.data(), encoded.size(), s), encoded, bytes);
    wire::Report decoded;
    check(wire::decodeReport(payload, size, decoded) == tsb::DecodeResult::Ok, "report decode permits original retained/legacy identities");
    sameBytes(check, wire::encodeReport(decodedBytes.data(), decodedBytes.size(), decoded), decodedBytes, bytes);
    for (size_t truncated = 0; truncated < size; ++truncated)
      check(wire::decodeReport(payload, truncated, decoded) == tsb::DecodeResult::ShortPayload, "report truncation rejected");
    check(!wire::encodeReport(encoded.data(), 251, s).ok(), "252-byte report does not fit 251 bytes");
    unaligned[9 + 83] = 9;
    check(wire::decodeReport(payload, size, decoded) == tsb::DecodeResult::InvalidField, "report count over capacity invalid");
    unaligned[9 + 83] = s.pending; unaligned[9 + 114] = 1;
    check(wire::decodeReport(payload, size, decoded) == tsb::DecodeResult::InvalidField, "every report entry reserved field strict");
  } else check(false, "all canonical profile record families supported");
}
void negativeCases(Checker &check) {
  wire::State s; s.epoch = 1; s.connection = 2; s.revision = 1;
  s.status = Status::Fresh; s.deadline = 1000; snprintf(s.label, sizeof(s.label), "host");
  std::array<uint8_t, 256> frame{};
  check(wire::encode(frame.data(), frame.size(), wire::Cpu, 0, s).ok(), "zero CPU with absent memory is valid");
  s.presence = 1;
  check(wire::encode(frame.data(), frame.size(), wire::Cpu, 0, s).ok(), "present zero memory is distinct and valid");
  wire::State decoded;
  check(wire::decode(wire::Cpu, frame.data() + 8, 68, decoded) == tsb::DecodeResult::Ok && decoded.presence == 1 && decoded.memory == 0,
        "zero measurement retains presence bit");
  std::array<uint8_t, 256> changed = frame;
  changed[8 + 67] = 'x';
  check(wire::decode(wire::Cpu, changed.data() + 8, 68, decoded) == tsb::DecodeResult::Ok && decoded.label[31] == 0,
        "fresh label receiver forces final NUL without modifying wire bytes");
  check(!wire::sameKnownPayload(wire::Cpu, frame.data() + 8, 68, changed.data() + 8, 68),
        "equal-revision raw comparison detects receiver-normalized byte change");
  changed = frame; changed[8 + 68] = 99;
  check(wire::sameKnownPayload(wire::Cpu, frame.data() + 8, 68, changed.data() + 8, 69),
        "equal-revision comparison ignores only appended unknown tail");
  s.cpu = 10001;
  check(!wire::encode(frame.data(), frame.size(), wire::Cpu, 0, s).ok(), "CPU overflow rejected");
  s.cpu = 0; s.presence = 4;
  check(!wire::encode(frame.data(), frame.size(), wire::Cpu, 0, s).ok(), "unknown presence rejected");
  s.presence = 0; s.memory = 1;
  check(!wire::encode(frame.data(), frame.size(), wire::Cpu, 0, s).ok(), "absent optional memory must zero fill");
  s.memory = 0; s.status = Status::Stale;
  check(!wire::encode(frame.data(), frame.size(), wire::Cpu, 0, s).ok(), "stale cannot carry deadline/data");
  s = wire::State(); s.epoch = 1; s.connection = 2; s.revision = 1;
  check(wire::encode(frame.data(), frame.size(), wire::Weather, 0, s).ok(), "explicit missing weather");
  frame[8 + 36] = 'x';
  check(wire::decode(wire::Weather, frame.data() + 8, 68, decoded) == tsb::DecodeResult::InvalidField, "all missing text bytes must zero fill");
  s.status = Status::Fresh; s.deadline = 1000; snprintf(s.label, sizeof(s.label), "desk");
  s.temperature = -10000;
  check(wire::encode(frame.data(), frame.size(), wire::Weather, 0, s).ok(), "negative Celsius boundary");
  s.temperature = -10001;
  check(!wire::encode(frame.data(), frame.size(), wire::Weather, 0, s).ok(), "negative Celsius outside boundary");
  s.unit = 1; s.temperature = -14800;
  check(wire::encode(frame.data(), frame.size(), wire::Weather, 0, s).ok(), "Fahrenheit negative boundary");
  frame[8 + 35] = 1;
  check(wire::decode(wire::Weather, frame.data() + 8, 68, decoded) == tsb::DecodeResult::InvalidField, "known reserved byte strict");
  wire::State same = s;
  check(wire::sameKnownState(wire::Weather, s, same), "equal full known content");
  same.label[0] = 'X';
  check(!wire::sameKnownState(wire::Weather, s, same), "equal revision changed label must be rejected by session");
  same = s; ++same.deadline;
  check(!wire::sameKnownState(wire::Weather, s, same), "equal revision changed deadline differs");
  s = wire::State(); s.epoch = 1; s.connection = 2;
  s.control.revision = s.control.generation = s.control.episode = 1;
  check(wire::encode(frame.data(), frame.size(), wire::DisplayState, 0, s).ok(), "display state");
  frame[8 + 28] = 2;
  check(wire::decode(wire::DisplayState, frame.data() + 8, 32, decoded) == tsb::DecodeResult::InvalidField, "enabled is strictly binary");
  frame[8 + 28] = 0; frame[8 + 29] = 3;
  check(wire::decode(wire::DisplayState, frame.data() + 8, 32, decoded) == tsb::DecodeResult::InvalidField, "unknown required preferred enum");
  check(wire::decode(0xff, nullptr, 0, decoded) == tsb::DecodeResult::UnknownType, "unknown record safe");
  check(wire::decode(wire::Cpu, nullptr, 68, decoded) == tsb::DecodeResult::ShortPayload, "null safe");
}
}
int main() {
  Checker check;
  std::ifstream input("../protocol/desk-v1-vectors.tsv");
  if (!input) input.open("protocol/desk-v1-vectors.tsv");
  check(bool(input), "canonical vector file available (no copied fixture)");
  unsigned vectors = 0, additional = 0;
  std::string line;
  while (std::getline(input, line)) {
    const std::vector<std::string> columns = split(line, '\t');
    if (columns.size() != 5) { check(false, "canonical TSV shape"); continue; }
    const std::vector<uint8_t> bytes = hex(columns[3]);
    if (bytes.size() < 8) { check(false, "complete frame required"); continue; }
    if (!wire::length(bytes[3])) {
      ++additional; additionalVector(check, columns, bytes); continue;
    }
    ++vectors;
    const wire::State expected = model(columns[1], split(columns[4], ','));
    std::array<uint8_t, 256> encoded{};
    const tsb::EncodeResult result = wire::encode(encoded.data(), encoded.size(), bytes[3],
        static_cast<uint8_t>(strtoul(columns[2].c_str(), nullptr, 10)), expected);
    check(result.ok() && result.size == bytes.size() && memcmp(encoded.data(), bytes.data(), bytes.size()) == 0,
          "runtime encoder equals independent canonical bytes");
    // Unaligned payload tests the real decoder's byte access, not struct overlays.
    std::array<uint8_t, 257> unaligned{};
    memcpy(unaligned.data() + 1, bytes.data(), bytes.size());
    wire::State decoded;
    check(wire::decode(bytes[3], unaligned.data() + 9, bytes.size() - 8, decoded) == tsb::DecodeResult::Ok &&
        wire::sameKnownState(bytes[3], decoded, expected), "runtime decoder equals independently bound fields");
    for (size_t truncated = 0; truncated < bytes.size() - 8; ++truncated)
      check(wire::decode(bytes[3], bytes.data() + 8, truncated, decoded) == tsb::DecodeResult::ShortPayload, "every truncation rejected before reads");
    check(wire::decode(bytes[3], unaligned.data() + 9, bytes.size() - 7, decoded) == tsb::DecodeResult::Ok, "unknown appended tail ignored");
    check(!wire::encode(encoded.data(), bytes.size() - 1, bytes[3], 0, expected).ok(), "encoder capacity bound");
  }
  check(vectors == 12 && additional == 11, "all 23 canonical profile fixtures exercised");
  negativeCases(check);
  printf("DESK vectors exercised: %u\n", vectors + additional);
  printf("%d checks, %d failures\n", check.checks, check.failures);
  return check.failures != 0;
}
