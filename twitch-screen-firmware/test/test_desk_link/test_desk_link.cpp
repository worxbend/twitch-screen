#include <stdio.h>
#include <deque>
#include <vector>
#include <algorithm>
#include <sys/socket.h>
#include <netinet/in.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include "link_client.h"
#include "desk_receiver.h"
#include "desk_application.h"

namespace {
struct Results { unsigned checks = 0, failures = 0; };
Results &results() { static Results r; return r; }
void check(bool ok, const char *message) {
  ++results().checks;
  if (!ok) { ++results().failures; printf("FAIL: %s\n", message); }
}
struct Transport : LinkTransport {
  uint32_t time = 1000;
  bool closed = false, blocked = false, wifi = true;
  size_t chunk = 512;
  std::deque<uint8_t> incoming;
  std::vector<uint8_t> outgoing;
  void begin() override {}
  void poll() override {}
  uint32_t now() const override { return time; }
  uint32_t jitter(uint32_t) override { return 0; }
  bool wifiConnected() const override { return wifi; }
  bool takeWifiDisconnect() override { return false; }
  bool readyForConnect() const override { return true; }
  bool startConnect() override { closed = false; return true; }
  Connect connectStatus() override { return Connect::Ready; }
  void close() override { closed = true; incoming.clear(); }
  int read(uint8_t *data, size_t size) override {
    size_t n = 0; while (n < size && !incoming.empty()) { data[n++] = incoming.front(); incoming.pop_front(); }
    return static_cast<int>(n);
  }
  int write(const uint8_t *data, size_t size) override {
    if (blocked) return 0;
    size = std::min(size, chunk); outgoing.insert(outgoing.end(), data, data + size);
    return static_cast<int>(size);
  }
  void log(const char *) override {}
  const char *deviceId() const override { return "desk-test"; }
  const char *firmwareVersion() const override { return "test"; }
  virtual void add(const uint8_t *data, size_t size) { incoming.insert(incoming.end(), data, data + size); }
};
struct Calls { unsigned welcomes = 0, stats = 0, notifications = 0; };
Calls &calls() { static Calls c; return c; }
desk::Controller *applicationController = nullptr;
void welcome(const LinkWelcome &w) {
  ++calls().welcomes;
  desk::applyApplicationGreeting(*applicationController, w);
}
bool notify(const Notification &) { ++calls().notifications; return true; }
bool capacity() { return false; } // DESK must never consult legacy capacity.
StreamStats lastStats;
void stats(const StreamStats &value) { ++calls().stats; lastStats = value; }
uint32_t seq() { return 0; }
const LinkHooks hooks = {welcome, notify, capacity, stats, seq};
void pump(Transport &t, unsigned count = 4) { while (count--) { linkLoop(); ++t.time; } }
void setup(Transport &t, desk::Receiver &receiver, const LinkHooks *binding = &hooks) {
  calls() = Calls(); applicationController = &receiver.controller();
  linkInit(binding, t, &receiver); pump(t);
}
void put(Transport &t, uint8_t type, const desk::wire::State &state) {
  std::array<uint8_t, 76> bytes{};
  const auto encoded = desk::wire::encode(bytes.data(), bytes.size(), type, 0, state);
  check(encoded.ok(), "encode test state"); t.add(bytes.data(), encoded.size);
}
desk::wire::State state() { desk::wire::State s; s.epoch = 7; s.connection = 9; return s; }
void greet(Transport &t, desk::Receiver &receiver, uint32_t latest = 10, uint64_t connection = 9) {
  desk::wire::Welcome w;
  w.epoch = 7; w.connection = connection; w.sample = 5000; w.echo = receiver.probe();
  w.base.latest_seq = latest; w.base.caps = tsb::CAP_ACK | tsb::CAP_GENERIC;
  w.base.max_frame = 256; w.base.idle_timeout_s = 90; w.base.ping_interval_s = 20;
  std::array<uint8_t, 68> bytes{};
  const auto n = desk::wire::encodeWelcome(bytes.data(), bytes.size(), w);
  check(n.ok(), "encode extended welcome"); t.add(bytes.data(), n.size); pump(t);
}
void synchronize(Transport &t, uint64_t connection = 9, bool weatherFresh = false) {
  auto s = state(); s.connection = connection;
  s.control.revision = 1; s.control.generation = 1; s.control.episode = 1;
  s.control.preferred = desk::Preferred::Weather;
  put(t, desk::wire::DisplayState, s);
  s.revision = 1;
  if (weatherFresh) { s.status = desk::Status::Fresh; s.deadline = 20000; strcpy(s.label, "Test"); }
  put(t, desk::wire::Weather, s);
  s.status = desk::Status::Missing; s.deadline = 0; memset(s.label, 0, 32);
  put(t, desk::wire::Cpu, s);
  desk::wire::Stats st; st.epoch = 7; st.connection = connection; st.generation = st.revision = 1;
  std::array<uint8_t, 68> bytes{}; auto encoded = desk::wire::encodeStats(bytes.data(), bytes.size(), st);
  check(encoded.ok(), "encode extended stats"); t.add(bytes.data(), encoded.size);
  s.sync.display = s.sync.weather = s.sync.cpu = s.sync.stats = s.sync.generation = s.sync.episode = 1;
  put(t, desk::wire::SyncEnd, s); pump(t, 8);
}
void event(Transport &t, uint32_t sequence, uint64_t deadline = 60000, desk::Origin origin = desk::Origin::External) {
  desk::wire::Event e; e.base.seq = sequence; e.base.kind = static_cast<uint8_t>(NotifyKind::Message);
  e.base.ttl_ds = 80; strcpy(e.base.actor, "Synthetic"); strcpy(e.base.text, "Body");
  e.epoch = 7; e.deadline = deadline; e.origin = origin;
  if (origin == desk::Origin::Twitch) e.generation = e.episode = 1;
  std::array<uint8_t, 204> frame{};
  const auto encoded = desk::wire::encodeEvent(frame.data(), frame.size(), 0, e);
  check(encoded.ok(), "encode desk event"); t.add(frame.data(), encoded.size);
}
struct Frame { uint8_t type = 0; std::vector<uint8_t> payload; };
std::vector<Frame> frames(const Transport &t) {
  std::vector<Frame> result; size_t offset = 0;
  while (offset + 8 <= t.outgoing.size()) {
    const size_t n = desk::wire::read(t.outgoing.data() + offset + 4, 2);
    if (offset + 8 + n > t.outgoing.size()) break;
    Frame f; f.type = t.outgoing[offset + 3];
    f.payload.assign(t.outgoing.begin() + offset + 8, t.outgoing.begin() + offset + 8 + n);
    result.push_back(f); offset += 8 + n;
  }
  return result;
}
std::vector<desk::wire::Report> reports(const Transport &t) {
  std::vector<desk::wire::Report> result;
  for (const auto &f : frames(t)) if (f.type == desk::wire::DeviceReport) {
    desk::wire::Report r;
    check(desk::wire::decodeReport(f.payload.data(), f.payload.size(), r) == tsb::DecodeResult::Ok, "decode actual TX report");
    result.push_back(r);
  }
  return result;
}
void testHandshake() {
  desk::Controller disabledController;
  desk::Receiver defaultReceiver(disabledController, 42);
  check(!defaultReceiver.enabled(), "boot identity alone never enables incomplete profile");
  desk::Receiver startup(disabledController, 0);
  check(!startup.initializeBootIdentity(0) && startup.boot() == 0,
        "zero boot identity rejects without mutation");
  check(startup.initializeBootIdentity(UINT64_C(0xfedcba9876543210)) &&
        startup.boot() == UINT64_C(0xfedcba9876543210) && !startup.enabled(),
        "full unsigned boot identity initializes without enabling incomplete profile");
  startup.classic(); startup.disconnected();
  check(!startup.initializeBootIdentity(77) && startup.boot() == UINT64_C(0xfedcba9876543210),
        "classic fallback and reconnect cannot replace boot identity");
  Transport t; desk::Controller controller; desk::Receiver receiver(controller, 42, true); setup(t, receiver);
  auto output = frames(t);
  check(output.size() == 1 && output[0].payload.size() == 92 &&
        desk::wire::helloSupportsDesk(output[0].payload.data(), output[0].payload.size()), "opt-in HELLO carries validated profile tail");
  greet(t, receiver);
  check(linkIsUp() && !linkDeskReady() && calls().stats == 0, "WELCOME is not application readiness");
  check(reports(t).empty(), "no initial credit report before coherent sync");
  synchronize(t, 9, true);
  check(linkDeskReady() && controller.selection(receiver.now()).base == desk::Base::Weather, "six-frame synchronization selects fresh weather");
  check(calls().stats == 1 && calls().notifications == 0, "staged STATS publishes only after sync");
  const auto r = reports(t);
  check(r.size() == 1 && r[0].grant == 8 && r[0].processed == 10 && r[0].revision == 1 && r[0].boot == 42,
        "actual initial report samples credit/cursors/boot");
  check(r.size() == 1 && r[0].observedDevice <= receiver.now() && r[0].observedRelayLower <= 5000 + receiver.now(),
        "report carries current conservative observation time");
}
void testCreditAndControl() {
  Transport t; desk::Controller c; desk::Receiver r(c, 42, true); setup(t, r); greet(t, r); synchronize(t);
  for (unsigned i = 11; i <= 18; ++i) event(t, i);
  pump(t, 20);
  check(c.pendingCount() == 8 && c.receivedTotal() == 8 && c.grantTotal() == 8 && linkIsUp(), "full queue consumes exactly granted events");
  auto s = state(); s.control.revision = 2; s.control.generation = 1; s.control.episode = 2;
  s.control.enabled = true; put(t, desk::wire::DisplayState, s);
  s.through = 20; put(t, desk::wire::ProgressBarrier, s); pump(t);
  check(c.appliedControl().revision == 2 && c.processedSeq() == 20 && c.enqueuedSeq() == 18 && linkIsUp(),
        "control and barrier progress with zero credit/full FIFO");
  t.time += 200; pump(t);
  auto observed = reports(t);
  check(observed.back().pending == 8 && observed.back().display == 2 && observed.back().grant == 8,
        "latest report reflects applied control without manufacturing credit");
  desk::Card card; check(c.start(r.now(), card), "pending transfers to active");
  t.time += 200; pump(t);
  observed = reports(t);
  check(observed.back().pending == 7 && observed.back().phase == desk::Phase::Entrance && observed.back().grant == 9,
        "one dequeue releases one credit and reports active entry");
  check(observed.back().entries[0].seq == 11 && observed.back().entries[1].seq == 12, "report active/FIFO order is truthful");
  event(t, 21, 5000); pump(t);
  check(c.processedSeq() == 21 && c.enqueuedSeq() == 18 && c.grantTotal() == 10 && c.expiredTotal() == 1,
        "expired frame consumes then replaces credit without enqueue ACK");
  event(t, 22, 60000, desk::Origin::Twitch); pump(t);
  check(c.suppressedTotal() == 1 && c.processedSeq() == 22 && c.grantTotal() == 11, "wrong-episode twitch is terminally suppressed");
  c.hold(r.now()); c.beginExit(r.now(), true); c.complete(r.now(), true);
  t.time += 200; pump(t); observed = reports(t);
  check(observed.back().phase == desk::Phase::None && observed.back().grant == 11, "completion adds no second credit");
}
void testRefusalAndReconnect() {
  Transport t; desk::Controller c; desk::Receiver r(c, 42, true); setup(t, r); greet(t, r); synchronize(t);
  for (unsigned i = 11; i <= 19; ++i) event(t, i);
  auto s = state(); s.through = 30; put(t, desk::wire::ProgressBarrier, s); pump(t, 20);
  check(t.closed && !linkDeskReady() && c.processedSeq() == 18 && c.refusedTotal() == 1, "ungranted event disconnects before barrier crosses hole");
  check(c.pendingCount() == 8, "generic accepted FIFO survives refusal/disconnect");
  t.time += 1000; pump(t); greet(t, r, 30, 10); synchronize(t, 10);
  auto observed = reports(t);
  check(linkDeskReady() && observed.back().connection == 10 && observed.back().grant == 0 && observed.back().pending == 8,
        "eight retained cards synchronize with zero initial credit");
  desk::Card card; c.start(r.now(), card); t.time += 200; pump(t); observed = reports(t);
  check(observed.back().grant == 1 && observed.back().entries[0].epoch == 7 && observed.back().entries[0].seq == 11,
        "retained identity unchanged and dequeue permits replay after reconnect");
}
void testRevisionFences() {
  Transport t; desk::Controller c; desk::Receiver r(c, 42, true); setup(t, r); greet(t, r); synchronize(t, 9, true);
  auto s = state(); s.revision = 1; s.status = desk::Status::Fresh; s.deadline = 20000; strcpy(s.label, "Test");
  std::array<uint8_t, 80> bytes{}; const auto n = desk::wire::encode(bytes.data(), bytes.size(), desk::wire::Weather, 0, s);
  bytes[n.size] = 0xe7; desk::wire::write(bytes.data() + 4, 2, 69); bytes[7] = tsb::headerCheck(bytes.data());
  t.add(bytes.data(), n.size + 1); pump(t);
  check(linkIsUp(), "equal known payload with unknown appended tail is accepted");
  bytes[8 + 67] = 'Z'; t.add(bytes.data(), n.size + 1); pump(t);
  check(t.closed, "equal-revision hidden receiver-NUL byte mutation fails closed");
  Transport stale; desk::Controller other; desk::Receiver receiver(other, 43, true); setup(stale, receiver); greet(stale, receiver); synchronize(stale);
  auto old = state(); old.connection = 8; old.control.revision = 999; old.control.generation = old.control.episode = 1;
  put(stale, desk::wire::DisplayState, old); pump(stale);
  check(linkIsUp() && other.appliedControl().revision == 1, "obsolete connection cannot change control");
  desk::wire::Stats statsValue; statsValue.epoch = 7; statsValue.connection = 9; statsValue.revision = 100; statsValue.generation = 2;
  std::array<uint8_t, 68> frame{}; const auto st = desk::wire::encodeStats(frame.data(), frame.size(), statsValue);
  stale.add(frame.data(), st.size); pump(stale);
  check(calls().stats == 1, "different-generation STATS cannot repaint current channel");
}
void sendStats(Transport &t, uint32_t revision, uint32_t generation, uint8_t availability = 1) {
  desk::wire::Stats value; value.epoch = 7; value.connection = 9;
  value.revision = revision; value.generation = generation; value.availability = availability;
  value.base.viewers = 321; value.base.followers = 123;
  std::array<uint8_t, 68> frame{};
  const auto encoded = desk::wire::encodeStats(frame.data(), frame.size(), value);
  check(encoded.ok(), "encode source-switch STATS"); t.add(frame.data(), encoded.size); pump(t);
}
void testStatsSourceFloor() {
  desk::Controller domain; domain.welcome(7, 9, 0, 0); domain.reconcile(0, true);
  desk::Control first; first.revision = first.generation = first.episode = 1;
  check(domain.control(7, 9, first) && domain.stats(7, 9, 100, 1), "domain accepts initial STATS floor");
  first.revision = first.generation = first.episode = 2;
  check(domain.control(7, 9, first) && domain.stats(7, 9, 1, 2) && !domain.stats(7, 9, 100, 2),
        "domain retains connection floor independent of generation");
  check(domain.stats(7, 9, 101, 2), "domain admits strictly newer source STATS");
  Transport t; desk::Controller c; desk::Receiver r(c, 42, true);
  setup(t, r); greet(t, r); synchronize(t);
  sendStats(t, 100, 1);
  auto s = state(); s.control.revision = 2; s.control.generation = 2; s.control.episode = 2; s.control.enabled = true;
  put(t, desk::wire::DisplayState, s); pump(t);
  check(lastStats.availability == 0 && lastStats.viewers == 0,
        "control alone synchronously invalidates old-source application telemetry");
  const unsigned before = calls().stats;
  sendStats(t, 1, 2);
  check(linkIsUp() && r.stats().revision == 100 && calls().stats == before,
        "lower STATS revision ignored across generation switch");
  check(c.stats(7, 9, 1, 2) && c.sync(7, 9, desk::Sync(), r.now()) == false,
        "controller ignores lower revision without disturbing ready session");
  sendStats(t, 100, 2);
  check(t.closed, "equal STATS revision with conflicting generation fails closed");
}
void testSyncAndClockBounds() {
  Transport missing; desk::Controller c; desk::Receiver r(c, 42, true); setup(missing, r); greet(missing, r);
  std::array<uint8_t, 12> heartbeat{}; tsb::encodePing(heartbeat.data(), heartbeat.size(), 1);
  heartbeat[3] = tsb::T_PING_RELAY; heartbeat[7] = tsb::headerCheck(heartbeat.data());
  for (unsigned i = 0; i < 5; ++i) { missing.time += 1000; missing.add(heartbeat.data(), heartbeat.size()); pump(missing, 1); }
  check(missing.closed && !linkIsUp(), "valid heartbeat traffic cannot postpone missing synchronization deadline");
  Transport slow; desk::Controller d; desk::Receiver rr(d, 42, true); setup(slow, rr); slow.time += 1001; greet(slow, rr);
  check(slow.closed, "slow initial WELCOME calibration is rejected");
  Transport t; desk::Controller q; desk::Receiver receiver(q, 42, true); setup(t, receiver); greet(t, receiver); synchronize(t, 9, true);
  event(t, 11); pump(t); const uint64_t accepted = q.pending(0)->deadline;
  t.time += 20000; pump(t);
  auto output = frames(t); uint64_t probe = 0;
  for (const auto &f : output) if (f.type == desk::wire::TimeRequest) probe = desk::wire::read(f.payload.data(), 8);
  check(probe > 0, "TIME_REQUEST refresh runs in actual session");
  auto s = state(); s.probe = probe; s.relaySample = 10000; put(t, desk::wire::TimeResponse, s); pump(t);
  check(q.pending(0)->deadline <= accepted, "recalibration never extends accepted queued expiry");
  t.time += 60001; pump(t, 1);
  check(t.closed && !linkIsUp(), "loop gap fences calibration before further admission");
}
void testReportShortWrites() {
  Transport t; desk::Controller c; desk::Receiver r(c, 42, true); setup(t, r); greet(t, r);
  t.chunk = 1; synchronize(t); pump(t, 260);
  auto observed = reports(t); check(observed.size() == 1, "252-byte initial report survives single-byte socket writes");
  t.blocked = true; event(t, 11); pump(t); t.time += 200; pump(t);
  auto s = state(); s.control.revision = 2; s.control.generation = 1; s.control.episode = 2; s.control.enabled = true;
  put(t, desk::wire::DisplayState, s); pump(t); t.time += 200; pump(t);
  desk::Card card; c.start(r.now(), card); t.time += 200; pump(t);
  t.blocked = false; t.chunk = 7; pump(t, 120); observed = reports(t);
  check(observed.back().display == 2 && observed.back().pending == 0 && observed.back().grant == 9,
        "blocked TX retains committed bytes and later coalesces latest lifecycle intent");
  const auto revision = observed.back().revision;
  t.time += 5000; pump(t, 100); observed = reports(t);
  check(observed.back().revision == revision + 1 && observed.back().observedDevice > observed.front().observedDevice,
        "idle refresh resamples observation rather than replaying old report bytes");
}
void testClassicAndViolations() {
  Transport t; desk::Controller c; desk::Receiver r(c, 42, true); setup(t, r);
  tsb::TsbWelcome w{}; w.caps = tsb::CAP_ACK; w.max_frame = 256;
  std::array<uint8_t, 24> welcomePayload{};
  desk::wire::write(welcomePayload.data(), 4, 37);
  desk::wire::write(welcomePayload.data() + 8, 4, 123);
  desk::wire::write(welcomePayload.data() + 12, 2, w.max_frame);
  desk::wire::write(welcomePayload.data() + 20, 4, w.caps);
  std::array<uint8_t, 32> frame{};
  auto n = tsb::encodeFrame(frame.data(), frame.size(), tsb::T_WELCOME, 0, welcomePayload.data(), welcomePayload.size());
  t.add(frame.data(), n.size); pump(t);
  check(linkIsUp() && !linkDeskReady() && c.selection(r.now()).reason == desk::Reason::Legacy, "opt-in device falls back with legacy relay");
  check(c.legacySeq() == 37 && c.legacySessionChanged() && reports(t).empty(),
        "shared application hook baselines classic cursor and emits no DESK report");
  check(desk::applyApplicationGreeting(c, LinkWelcome{40, 0, 123, tsb::CAP_ACK, 0}) == desk::ApplicationGreeting::Resumed &&
        c.legacySeq() == 37 && !c.legacySessionChanged(), "classic reconnect preserves resume cursor");
  check(desk::applyApplicationGreeting(c, LinkWelcome{2, 0, 124, tsb::CAP_ACK, 0}) == desk::ApplicationGreeting::Rebaselined &&
        c.legacySeq() == 2 && c.legacySessionChanged(), "classic relay restart rebaselines smaller sequence");
  Transport desk; desk::Controller d; desk::Receiver rr(d, 42, true); setup(desk, rr); greet(desk, rr); synchronize(desk);
  std::array<uint8_t, 168> eventPayload{}; desk::wire::write(eventPayload.data(), 4, 11);
  std::array<uint8_t, 176> plain{};
  n = tsb::encodeFrame(plain.data(), plain.size(), tsb::T_EVENT, 0, eventPayload.data(), eventPayload.size());
  desk.add(plain.data(), n.size); pump(desk);
  check(desk.closed && calls().notifications == 0, "plain EVENT in DESK session is fatal, not legacy-delivered");
  Transport order; desk::Controller o; desk::Receiver ro(o, 42, true); setup(order, ro); greet(order, ro);
  auto s = state(); s.revision = 1; put(order, desk::wire::Weather, s); pump(order);
  check(order.closed, "initial six-frame order is enforced");
}

// Match main's classic admission/capacity/cursor ownership, not counting-only hooks.
bool applicationNotify(const Notification &n) {
  ++calls().notifications;
  const auto result = applicationController->legacyAdmit(n);
  return result == desk::Admission::Enqueued || result == desk::Admission::Duplicate;
}
bool applicationCapacity() { return applicationController->pendingCount() < desk::Controller::Capacity; }
uint32_t applicationSeq() { return applicationController->legacySeq(); }
const LinkHooks applicationHooks = {welcome, applicationNotify, applicationCapacity, stats, applicationSeq};
void classicGreeting(Transport &t, uint32_t latest, uint32_t session = 123) {
  std::array<uint8_t, 24> payload{};
  desk::wire::write(payload.data(), 4, latest);
  desk::wire::write(payload.data() + 8, 4, session);
  desk::wire::write(payload.data() + 12, 2, 256);
  desk::wire::write(payload.data() + 18, 2, 64);
  desk::wire::write(payload.data() + 20, 4, tsb::CAP_ACK | tsb::CAP_GENERIC);
  std::array<uint8_t, 32> bytes{};
  const auto encoded = tsb::encodeFrame(bytes.data(), bytes.size(), tsb::T_WELCOME, 0, payload.data(), payload.size());
  check(encoded.ok(), "encode classic reconnect WELCOME"); t.add(bytes.data(), encoded.size); pump(t);
}
void classicEvent(Transport &t, uint32_t sequence, bool replay = false) {
  std::array<uint8_t, 168> payload{};
  desk::wire::write(payload.data(), 4, sequence);
  desk::wire::write(payload.data() + 18, 2, 80);
  payload[20] = static_cast<uint8_t>(NotifyKind::Message);
  std::array<uint8_t, 176> bytes{};
  const auto encoded = tsb::encodeFrame(bytes.data(), bytes.size(), tsb::T_EVENT, replay ? tsb::FLAG_REPLAY : 0,
                                       payload.data(), payload.size());
  check(encoded.ok(), "encode classic live/replayed EVENT"); t.add(bytes.data(), encoded.size); pump(t);
}
void reconnectHello(Transport &t, uint32_t classic, uint64_t epoch, uint32_t processed) {
  t.wifi = false; pump(t);
  t.outgoing.clear(); t.time += 10000; t.wifi = true; pump(t);
  const auto output = frames(t);
  check(output.size() == 1 && output[0].type == tsb::T_HELLO && output[0].payload.size() == 92,
        "actual reconnect emits complete extended HELLO");
  if (output.size() != 1 || output[0].payload.size() != 92) return;
  const auto &p = output[0].payload;
  check(desk::wire::read(p.data(), 4) == classic, "extended HELLO preserves application classic resume cursor");
  check(desk::wire::read(p.data() + 64, 8) == epoch && desk::wire::read(p.data() + 72, 4) == processed,
        "DESK epoch/processed resume tail remains independent of classic prefix");
}
void testClassicReplayResume() {
  Transport t; desk::Controller c; desk::Receiver r(c, 42, true);
  setup(t, r, &applicationHooks); classicGreeting(t, 5); classicEvent(t, 6);
  check(c.legacySeq() == 6 && r.enqueued() == 0, "classic EVENT advances only classic cursor");
  for (uint32_t next = 7; next <= 9; ++next) {
    reconnectHello(t, next - 1, 0, 0);
    classicGreeting(t, next);
    check(c.legacySeq() == next - 1 && !c.legacySessionChanged(), "actual classic WELCOME retains outgoing replay baseline");
    classicEvent(t, next, true);
    check(linkIsUp() && !linkDeskReady() && c.legacySeq() == next && c.pendingCount() == next - 5,
          "retained classic replay admitted across repeated extended HELLO fallback");
    check(c.pending(c.pendingCount() - 1)->notification.replay, "classic replay flag survives admission");
  }
  check(reports(t).empty(), "classic fallback never emits DEVICE_REPORT");
  // A former DESK sequence space must not overwrite a new classic relay cursor.
  Transport migration; desk::Controller other; desk::Receiver previous(other, 43, true);
  setup(migration, previous, &applicationHooks); greet(migration, previous); synchronize(migration);
  event(migration, 11); pump(migration);
  reconnectHello(migration, 0, 7, 11);
  classicGreeting(migration, 100); classicEvent(migration, 101);
  check(other.legacySeq() == 101 && previous.enqueued() == 11 && previous.processed() == 11,
        "DESK-to-classic migration keeps unrelated cursor spaces separate");
  reconnectHello(migration, 101, 7, 11);
  classicGreeting(migration, 102); classicEvent(migration, 102, true);
  check(linkIsUp() && other.legacySeq() == 102 && previous.enqueued() == 11 && previous.processed() == 11,
        "classic replay after DESK migration cannot mutate DESK progress");
  check(other.pending(0)->epoch == 7 && other.pending(0)->notification.seq == 11 &&
        other.pending(1)->epoch == 0 && other.pending(1)->notification.seq == 101 &&
        other.pending(2)->epoch == 0 && other.pending(2)->notification.seq == 102,
        "retained DESK and classic FIFO identities survive migration and replay");
  // Returning to the same capable epoch resumes its own tail, not classic 102.
  reconnectHello(migration, 102, 7, 11);
  greet(migration, previous, 12, 10); synchronize(migration, 10);
  check(linkDeskReady() && other.legacySeq() == 102 && other.processedSeq() == 11 && other.enqueuedSeq() == 11,
        "returning capable session resumes DESK tail without merging classic cursor");
  event(migration, 12); pump(migration);
  check(other.legacySeq() == 102 && other.processedSeq() == 12 && other.enqueuedSeq() == 12 && other.pendingCount() == 4,
        "capable replay after classic migration advances only current-epoch DESK progress");
}

// Real test-owned loopback TCP transport; no relay service or physical device.
struct TcpTransport : Transport {
  int listener = -1, client = -1, peer = -1;
  sockaddr_in address{};
  TcpTransport() {
    listener = socket(AF_INET, SOCK_STREAM, 0);
    address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK); address.sin_port = 0;
    check(listener >= 0 && bind(listener, reinterpret_cast<sockaddr *>(&address), sizeof(address)) == 0 && listen(listener, 1) == 0,
          "create isolated loopback listener");
    socklen_t length = sizeof(address); check(getsockname(listener, reinterpret_cast<sockaddr *>(&address), &length) == 0, "discover test-owned ephemeral port");
  }
  ~TcpTransport() override { close(); if (listener >= 0) ::close(listener); }
  bool startConnect() override {
    closed = false; client = socket(AF_INET, SOCK_STREAM, 0);
    if (client < 0 || connect(client, reinterpret_cast<sockaddr *>(&address), sizeof(address))) return false;
    peer = accept(listener, nullptr, nullptr);
    if (peer < 0) return false;
    fcntl(client, F_SETFL, O_NONBLOCK); fcntl(peer, F_SETFL, O_NONBLOCK);
    return true;
  }
  void close() override {
    closed = true; if (client >= 0) ::close(client); if (peer >= 0) ::close(peer); client = peer = -1;
  }
  int read(uint8_t *data, size_t size) override {
    const int n = recv(client, data, size, 0);
    return n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK) ? 0 : n == 0 ? -1 : n;
  }
  int write(const uint8_t *data, size_t size) override {
    const int n = send(client, data, std::min(size, chunk), MSG_NOSIGNAL);
    return n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK) ? 0 : n;
  }
  void poll() override {
    if (peer < 0) return;
    std::array<uint8_t, 512> bytes{};
    int n = 0; while ((n = recv(peer, bytes.data(), bytes.size(), 0)) > 0) outgoing.insert(outgoing.end(), bytes.data(), bytes.data() + n);
  }
  void add(const uint8_t *data, size_t size) override {
    check(send(peer, data, size, MSG_NOSIGNAL) == static_cast<int>(size), "synthetic peer sends real TCP bytes");
  }
};
void testRealTcp() {
  TcpTransport t; desk::Controller c; desk::Receiver r(c, 42, true); setup(t, r); greet(t, r); synchronize(t);
  // Allow the kernel to flush test-peer writes without changing product timers.
  for (unsigned i = 0; i < 100 && !linkDeskReady(); ++i) { usleep(1000); pump(t); }
  pump(t, 20);
  check(linkDeskReady() && !reports(t).empty(), "actual LinkSession exchanges six-frame sync and DEVICE_REPORT over TCP");
  for (unsigned i = 11; i <= 18; ++i) event(t, i);
  for (unsigned i = 0; i < 100 && c.pendingCount() != 8; ++i) { usleep(1000); pump(t); }
  auto s = state(); s.control.revision = 2; s.control.generation = 1; s.control.episode = 2; s.control.enabled = true;
  put(t, desk::wire::DisplayState, s);
  for (unsigned i = 0; i < 100 && c.appliedControl().revision != 2; ++i) { usleep(1000); pump(t); }
  t.time += 200; pump(t, 20);
  check(linkDeskReady() && c.pendingCount() == 8 && c.appliedControl().revision == 2,
        "real TCP full queue does not block independent control");
  t.close(); pump(t);
  check(!linkIsUp(), "real TCP loss invalidates session");
}
}
int main() {
  testHandshake();
  if (!results().failures) {
    testCreditAndControl(); testRefusalAndReconnect(); testRevisionFences();
    testStatsSourceFloor(); testSyncAndClockBounds(); testReportShortWrites(); testClassicAndViolations(); testClassicReplayResume(); testRealTcp();
  }
  printf("%u checks, %u failures\n", results().checks, results().failures);
  return results().failures != 0;
}
