#include <stdio.h>
#include <string.h>
#include <deque>
#include <vector>
#include "link_client.h"
#include "notify_queue.h"
#include "proto_codec.h"
#include "vectors.h"
#include "dns_lookup.h"

namespace {
constexpr uint32_t SECOND_MS = 1000;
constexpr uint32_t CONNECT_DEADLINE_MS = 3 * SECOND_MS;
constexpr uint32_t WELCOME_DEADLINE_MS = 5 * SECOND_MS;
constexpr uint32_t ACK_COALESCE_MS = 200;
constexpr uint32_t PING_INTERVAL_MS = 15 * SECOND_MS;
constexpr uint32_t SILENCE_DEADLINE_MS = 45 * SECOND_MS;
constexpr uint32_t STABLE_INTERVAL_MS = 60 * SECOND_MS;
constexpr uint32_t DEFAULT_PAUSE_LIMIT_MS = 180 * SECOND_MS;
int checks = 0, failures = 0;
void check(bool pass, const char *what) {
  ++checks;
  if (!pass) { ++failures; printf("FAIL: %s\n", what); }
}
struct FakeTransport : LinkTransport {
  uint32_t time = 0;
  bool wifi = true, edge = false, closed = false, writeBlocked = false, available = true;
  Connect connecting = Connect::Ready;
  size_t writeChunk = 256;
  int attempts = 0;
  std::deque<uint8_t> inbound;
  std::vector<uint8_t> outbound;
  void begin() override {}
  void poll() override {}
  uint32_t now() const override { return time; }
  uint32_t jitter(uint32_t) override { return 0; }
  bool wifiConnected() const override { return wifi; }
  bool takeWifiDisconnect() override { bool e = edge; edge = false; return e; }
  bool readyForConnect() const override { return available; }
  bool startConnect() override { ++attempts; closed = false; return true; }
  Connect connectStatus() override { return connecting; }
  void close() override { closed = true; inbound.clear(); }
  int read(uint8_t *data, size_t size) override {
    size_t got = 0;
    while (got < size && !inbound.empty()) { data[got++] = inbound.front(); inbound.pop_front(); }
    return (int)got;
  }
  int write(const uint8_t *data, size_t size) override {
    if (writeBlocked) return 0;
    const size_t n = size < writeChunk ? size : writeChunk;
    outbound.insert(outbound.end(), data, data + n);
    return (int)n;
  }
  void log(const char *) override {}
  const char *deviceId() const override { return "test-device"; }
  const char *firmwareVersion() const override { return "test"; }
  void add(const uint8_t *data, size_t n) { inbound.insert(inbound.end(), data, data + n); }
};
NotifyQueue<8> queue;
int welcomes = 0, stats = 0;
uint32_t welcomedCaps = 0;
bool refuse = false;
void welcome(const LinkWelcome &w) { ++welcomes; welcomedCaps = w.caps; queue.greet(w.latestSeq, w.sessionId); }
bool notify(const Notification &n) { return !refuse && queue.offer(n) != NotifyQueue<8>::Offer::Refused; }
bool capacity() { return queue.size() < 8; }
void statistic(const StreamStats &) { ++stats; }
uint32_t lastSeq() { return queue.lastSeq(); }
const LinkHooks hooks = {welcome, notify, capacity, statistic, lastSeq};
void reset(FakeTransport &t) {
  queue = NotifyQueue<8>(); welcomes = stats = 0; refuse = false;
  linkInit(&hooks, t);
}
void pump(FakeTransport &t, unsigned count = 4) {
  while (count--) { linkLoop(); ++t.time; }
}
void greet(FakeTransport &t) {
  pump(t);
  // V3 is WELCOME latest_seq=127 and all capabilities.
  t.add(gv::WELCOME, sizeof(gv::WELCOME)); pump(t);
  check(linkIsUp(), "valid WELCOME enters streaming");
}
void event(FakeTransport &t, uint32_t seq) {
  std::vector<uint8_t> frame(gv::EVENT_STREAM_START, gv::EVENT_STREAM_START + sizeof(gv::EVENT_STREAM_START));
  frame[8] = seq & 255; frame[9] = (seq >> 8) & 255;
  frame[10] = (seq >> 16) & 255; frame[11] = seq >> 24;
  t.add(frame.data(), frame.size());
}
void testDnsGeneration() {
  DnsLookup lookup;
  const uint32_t lost = lookup.begin();
  check(lookup.state() == DnsLookup::State::Pending, "DNS request starts pending");
  lookup.cancel();
  lookup.complete(lost, true, 0x01020304);
  check(lookup.state() == DnsLookup::State::Idle, "late callback cannot revive cancelled DNS");
  const uint32_t retry = lookup.begin();
  lookup.complete(lost, false);
  check(lookup.state() == DnsLookup::State::Pending, "old DNS failure cannot fail a retry");
  lookup.complete(retry, true, 0x05060708);
  lookup.complete(lost, true, 0x01020304);
  check(lookup.state() == DnsLookup::State::Ready && lookup.address() == 0x05060708,
        "replacement DNS result survives stale success callback");
  const uint32_t failed = lookup.begin();
  lookup.complete(failed, false);
  check(lookup.state() == DnsLookup::State::Failed, "current DNS failure propagates");
}

void testHandshakeAndTimeouts() {
  FakeTransport t; reset(t);
  t.connecting = LinkTransport::Connect::Pending;
  pump(t);
  check(!linkIsUp() && t.outbound.empty(), "pending connect emits no HELLO");
  t.time = CONNECT_DEADLINE_MS + 1; pump(t);
  check(t.closed, "connect deadline closes pending transport");
  t.time += 1000; t.connecting = LinkTransport::Connect::Ready; pump(t);
  check(!t.outbound.empty() && t.outbound[3] == tsb::T_HELLO, "HELLO follows completed connect");
  t.time += WELCOME_DEADLINE_MS + 1; pump(t); check(t.closed, "missing WELCOME times out");
}
void testWifiEdgeAndBackoff() {
  FakeTransport t; reset(t); greet(t);
  t.edge = true; pump(t, 1);
  check(!linkIsUp() && t.closed, "brief WiFi disconnect tears down even after reassociation");
  unsigned attempts = t.attempts;
  t.time += 998; pump(t, 1); check(t.attempts == (int)attempts, "backoff waits for deadline");
  t.time += 2; greet(t); t.edge = true; pump(t, 1);
  attempts = t.attempts; t.time += 1001; pump(t, 1);
  check(t.attempts == (int)attempts, "short streaming session preserves exponential ramp");
}
void testProlongedOutage() {
  FakeTransport t; reset(t); greet(t);
  t.wifi = false; pump(t, 1);
  check(!linkIsUp() && t.closed, "WiFi loss without latched edge tears down streaming");
  const int attempts = t.attempts; t.outbound.clear();
  bool stayedDown = true, noAttempts = true;
  // 120 s outage in 1 s steps; pump() returning at all proves linkLoop keeps returning.
  for (int s = 0; s < 120; ++s) {
    t.time += 1000; pump(t, 3);
    if (linkIsUp()) stayedDown = false;
    if (t.attempts != attempts) noAttempts = false;
  }
  check(stayedDown, "link stays down for a prolonged WiFi outage");
  check(noAttempts, "no connect attempts while WiFi is down, even past backoff deadlines");
  check(t.outbound.empty(), "no bytes written during WiFi outage");
  // Stale edge latched during the outage must not tear down the recovered session.
  t.edge = true; t.wifi = true; t.connecting = LinkTransport::Connect::Ready;
  pump(t, 1);
  check(t.attempts == attempts && !t.edge, "stale outage edge is consumed without an attempt");
  pump(t, 1);
  check(t.attempts == attempts + 1, "exactly one new attempt after WiFi returns");
  pump(t, 3);
  check(t.attempts == attempts + 1, "no duplicate attempt while connecting");
  check(t.outbound.size() >= tsb::HEADER_SIZE && t.outbound[3] == tsb::T_HELLO, "HELLO re-sent after outage");
  const int welcomesBefore = welcomes; greet(t);
  check(linkIsUp() && welcomes == welcomesBefore + 1 && t.attempts == attempts + 1,
        "recovered session reaches streaming on the same attempt");
}
void testWritesAndAck() {
  FakeTransport t; reset(t); t.writeChunk = 3; greet(t); pump(t, 30);
  uint8_t expected[tsb::HEADER_SIZE + tsb::LEN_HELLO];
  tsb::TsbHello hello;
  tsb::buildHello(hello, 0, tsb::CAP_ACK | tsb::CAP_CHAT | tsb::CAP_GENERIC,
                  t.deviceId(), t.firmwareVersion());
  tsb::encodeHello(expected, sizeof(expected), hello);
  check(t.outbound.size() == sizeof(expected) &&
        memcmp(t.outbound.data(), expected, sizeof(expected)) == 0,
        "partial writes retain every byte of exactly one complete HELLO");
  const size_t before = t.outbound.size();
  event(t, queue.lastSeq() + 1); pump(t, 20);
  check(t.outbound.size() == before, "ACK held until coalescing interval");
  t.time += ACK_COALESCE_MS; pump(t, 10);
  check(t.outbound.size() == before + 12, "one ACK acknowledges accepted EVENT");
  t.writeBlocked = true; t.time += PING_INTERVAL_MS + SECOND_MS; pump(t);
  t.time += CONNECT_DEADLINE_MS + 1; pump(t);
  check(t.closed && !linkIsUp(), "stalled output closes at write deadline");
}
void testBurst() {
  FakeTransport t; reset(t); greet(t);
  const uint32_t baseline = queue.lastSeq();
  for (uint32_t i = 1; i <= 30; ++i) event(t, baseline + i);
  pump(t, 100);
  check(queue.size() == 8 && queue.refused() == 0, "burst pauses at eight queued notifications");
  Notification n; unsigned shown = 0;
  while (shown < 30 && queue.take(n)) {
    ++shown; check(n.seq == baseline + shown, "burst preserves event ordering"); pump(t, 10);
  }
  check(shown == 30 && queue.lastSeq() == baseline + 30, "entire burst drains without reconnect or high-water gap");
  check(linkIsUp(), "burst leaves session connected");
}
void testPausedQueueTimersAndWrongVersion() {
  FakeTransport t; reset(t); greet(t);
  const uint32_t baseline = queue.lastSeq();
  for (uint32_t seq = baseline + 1; seq <= baseline + 9; ++seq) event(t, seq);
  pump(t, 40);
  size_t before = t.outbound.size();
  for (int i = 0; i < 4; ++i) { t.time += PING_INTERVAL_MS + SECOND_MS; pump(t); }
  check(linkIsUp() && queue.size() == 8 && queue.lastSeq() == baseline + 8,
        "full queue pauses >heartbeat timeout without changing high-water mark");
  check(t.outbound.size() >= before + 48, "periodic outbound heartbeats continue while input paused");
  Notification n; queue.take(n); pump(t);
  check(queue.lastSeq() == baseline + 9, "paused EVENT resumes immediately after display capacity returns");

  FakeTransport wrong; reset(wrong); greet(wrong);
  for (int i = 0; i < 8; ++i) event(wrong, baseline + i + 1);
  pump(wrong, 40);
  std::vector<uint8_t> frame(gv::EVENT_STREAM_START,
      gv::EVENT_STREAM_START + sizeof(gv::EVENT_STREAM_START));
  frame[2] = 4; frame[7] = tsb::headerCheck(frame.data());
  wrong.add(frame.data(), frame.size()); pump(wrong);
  check(wrong.closed && !linkIsUp(), "queue capacity never delays wrong-version teardown");
}
void testSessionBoundaries() {
  FakeTransport t; reset(t); greet(t);
  check(welcomedCaps == (tsb::CAP_ACK | tsb::CAP_CHAT | tsb::CAP_GENERIC),
        "WELCOME cannot enable unadvertised capabilities");
  event(t, queue.lastSeq() + 1); pump(t);
  const uint32_t baseline = queue.lastSeq();
  t.add(gv::WELCOME, sizeof(gv::WELCOME)); pump(t);
  check(!linkIsUp() && welcomes == 1 && queue.lastSeq() == baseline,
        "duplicate WELCOME closes without rewinding application baseline");

  FakeTransport paused; reset(paused); greet(paused);
  for (unsigned i = 1; i <= 9; ++i) event(paused, queue.lastSeq() + i);
  pump(paused, 40);
  paused.time += DEFAULT_PAUSE_LIMIT_MS; pump(paused, 1);
  check(paused.closed && !linkIsUp(), "full-queue pause expires after twice relay idle timeout");

  FakeTransport busy; reset(busy); busy.available = false;
  busy.time = STABLE_INTERVAL_MS; pump(busy, 100);
  check(busy.attempts == 0, "busy close worker defers without burning retry attempts");
  busy.available = true; greet(busy); busy.edge = true; pump(busy, 1);
  busy.time += 1000; pump(busy, 1);
  check(busy.attempts == 2, "first real failure still retries at initial backoff");
}

void testHeartbeatCapsAndBye() {
  FakeTransport t; reset(t); greet(t);
  const size_t before = t.outbound.size();
  uint8_t ping[12]; tsb::encodePing(ping, sizeof(ping), 0x12345678);
  ping[3] = tsb::T_PING_RELAY; ping[6] = tsb::FLAG_REPLAY;
  ping[7] = tsb::headerCheck(ping);
  t.add(ping, sizeof(ping)); pump(t);
  check(t.outbound.size() == before + 12 &&
        t.outbound[before + 3] == tsb::T_PONG_DEVICE &&
        t.outbound[before + 8] == 0x78,
        "PING with irrelevant REPLAY receives immediate matching PONG");
  t.time += SILENCE_DEADLINE_MS + 1; pump(t);
  check(t.closed && !linkIsUp(), "silent peer reaches heartbeat deadline");

  FakeTransport noAck; reset(noAck); pump(noAck);
  std::vector<uint8_t> welcomeBytes(gv::WELCOME, gv::WELCOME + sizeof(gv::WELCOME));
  welcomeBytes[28] &= ~tsb::CAP_ACK;
  noAck.add(welcomeBytes.data(), welcomeBytes.size()); pump(noAck);
  const size_t sent = noAck.outbound.size();
  event(noAck, queue.lastSeq() + 1); pump(noAck); noAck.time += ACK_COALESCE_MS + 1; pump(noAck);
  check(noAck.outbound.size() == sent, "no ACK is emitted without negotiated CAP_ACK");

  FakeTransport bye; reset(bye); greet(bye);
  std::vector<uint8_t> goodbye(gv::BYE_VERSION_MISMATCH,
      gv::BYE_VERSION_MISMATCH + sizeof(gv::BYE_VERSION_MISMATCH));
  goodbye[8] = 0xe7; goodbye[9] = 0x03; // unknown code 999
  goodbye[12] = 60; goodbye[13] = 0; // fixed minimum 60 seconds
  goodbye[2] = 99; goodbye[7] = tsb::headerCheck(goodbye.data());
  bye.add(goodbye.data(), goodbye.size()); pump(bye);
  check(bye.closed && !linkIsUp(), "unknown cross-version BYE always tears down");
  int attempts = bye.attempts; bye.time += 59990; pump(bye);
  check(bye.attempts == attempts, "BYE retry floor may exceed ordinary ramp cap");
  bye.time += 20; pump(bye); check(bye.attempts == attempts + 1, "BYE floor eventually retries");
}
void byeCode(FakeTransport &t, uint8_t code) {
  std::vector<uint8_t> f(gv::BYE_VERSION_MISMATCH,
      gv::BYE_VERSION_MISMATCH + sizeof(gv::BYE_VERSION_MISMATCH));
  f[8] = code; f[9] = 0;     // BYE code
  f[12] = 0; f[13] = 0;      // retry_after_s = 0: no relay-supplied floor
  f[7] = tsb::headerCheck(f.data());
  t.add(f.data(), f.size());
}
// §12.1 — BYE(9 REPLACED) and BYE(1 UNSUPPORTED_VERSION) skip the 1 s ramp and
// wait the full 30 s cap even when retry_after_s is 0 (jitter is 0 here).
void byeJumpsToCap(uint8_t code, const char *tears, const char *waits,
                   const char *once, const char *noDup) {
  FakeTransport r; reset(r); greet(r);
  byeCode(r, code); const uint32_t t0 = r.time; pump(r, 1);
  check(r.closed && !linkIsUp(), tears);
  const int a = r.attempts;
  r.time = t0 + 1000;  pump(r, 1);
  r.time = t0 + 2000;  pump(r, 1);
  r.time = t0 + 29999; pump(r, 1);
  check(r.attempts == a, waits);
  r.time = t0 + 30000; pump(r, 1);
  check(r.attempts == a + 1, once);
  pump(r, 5);
  check(r.attempts == a + 1, noDup);
}
void testReplacedAndVersionByeJumpToCap() {
  byeJumpsToCap(tsb::BYE_REPLACED, "REPLACED BYE tears down",
                "REPLACED waits for 30 s cap, not the 1 s ramp",
                "REPLACED: exactly one attempt at 30 s cap",
                "REPLACED: no duplicate attempt after cap");
  byeJumpsToCap(tsb::BYE_UNSUPPORTED_VERSION, "UNSUPPORTED_VERSION BYE tears down",
                "UNSUPPORTED_VERSION with retry_after 0 still jumps to 30 s cap",
                "UNSUPPORTED_VERSION: exactly one attempt at 30 s cap",
                "UNSUPPORTED_VERSION: no duplicate attempt after cap");
}
void testStableRecoveryAndWrap() {
  FakeTransport t; reset(t); greet(t);
  t.edge = true; pump(t); t.time += 1000; greet(t);
  // Keep transport active with complete frames for a full stable interval.
  t.time += 30000; t.add(gv::STATS_LIVE, sizeof(gv::STATS_LIVE)); pump(t);
  t.time += 30001; t.add(gv::STATS_LIVE, sizeof(gv::STATS_LIVE)); pump(t);
  t.edge = true; pump(t);
  int attempts = t.attempts; t.time += 1000; pump(t);
  check(t.attempts == attempts + 1, "stable streaming interval resets retry ramp");
  FakeTransport wrap; reset(wrap); greet(wrap);
  wrap.time = 0xfffffff0u;
  // Supplying a frame refreshes silence immediately before millis rollover.
  wrap.add(gv::STATS_LIVE, sizeof(gv::STATS_LIVE)); pump(wrap);
  wrap.edge = true; pump(wrap); attempts = wrap.attempts;
  wrap.time += 999; pump(wrap);
  check(wrap.attempts == attempts + 1, "retry deadline is wrap-safe");
}
void testRefusalAndInvalidHandshake() {
  FakeTransport t; reset(t); greet(t); refuse = true;
  uint32_t seq = queue.lastSeq(); event(t, seq + 1); event(t, seq + 2); pump(t);
  check(t.closed && queue.lastSeq() == seq, "actual refusal closes before later EVENT can advance mark");
  FakeTransport bad; reset(bad); pump(bad); bad.add(gv::EVENT_STREAM_START, sizeof(gv::EVENT_STREAM_START)); pump(bad);
  check(bad.closed && welcomes == 0, "EVENT before WELCOME is fatal");
}
}
int main() {
  testDnsGeneration(); testHandshakeAndTimeouts(); testWifiEdgeAndBackoff(); testProlongedOutage(); testWritesAndAck();
  testBurst(); testRefusalAndInvalidHandshake();
  testHeartbeatCapsAndBye(); testReplacedAndVersionByeJumpToCap(); testStableRecoveryAndWrap();
  testPausedQueueTimersAndWrongVersion(); testSessionBoundaries();
  printf("%d checks, %d failures\n", checks, failures);
  return failures != 0;
}
