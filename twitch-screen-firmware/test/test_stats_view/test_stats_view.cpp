#include <lvgl.h>
#include <array>
#include <deque>
#include <vector>
#include <stdio.h>
#include <stdlib.h>
#include "desk_application.h"
#include "desk_ui_application.h"
#include "link_client.h"
#include "ui_notify.h"

namespace {
unsigned checks = 0, failures = 0, exits = 0;
void check(bool value, const char *message) {
  ++checks;
  if (!value) { ++failures; printf("FAIL: %s\n", message); }
}
desk::Controller controller;
desk::Receiver receiver(controller, 0, true);
struct Transport : LinkTransport {
  uint32_t time = 1000;
  std::deque<uint8_t> input;
  std::vector<uint8_t> output;
  void begin() override {}
  void poll() override {}
  uint32_t now() const override { return time; }
  uint32_t jitter(uint32_t) override { return 0; }
  bool wifiConnected() const override { return true; }
  bool takeWifiDisconnect() override { return false; }
  bool readyForConnect() const override { return true; }
  bool startConnect() override { return true; }
  Connect connectStatus() override { return Connect::Ready; }
  void close() override { input.clear(); }
  int read(uint8_t *p, size_t size) override {
    size_t n = 0;
    while (n < size && !input.empty()) { p[n++] = input.front(); input.pop_front(); }
    return static_cast<int>(n);
  }
  int write(const uint8_t *p, size_t size) override {
    output.insert(output.end(), p, p + size);
    return static_cast<int>(size);
  }
  void log(const char *) override {}
  const char *deviceId() const override { return "stats-view-test"; }
  const char *firmwareVersion() const override { return "test"; }
  void add(const uint8_t *p, size_t size) { input.insert(input.end(), p, p + size); }
} transport;
void apply() { desk::applyApplicationView(receiver, transport.time); }
void pump(unsigned count = 4) {
  while (count--) { linkLoop(); apply(); transport.time += 5; lv_tick_inc(5); lv_timer_handler(); }
}
void welcome(const LinkWelcome &w) { desk::applyApplicationGreeting(controller, w); }
bool notify(const Notification &) { return true; }
bool capacity() { return true; }
uint32_t sequence() { return controller.legacySeq(); }
const LinkHooks hooks = {welcome, notify, capacity, uiIdleSetStats, sequence};
void hold() { receiver.tick(transport.time); controller.hold(receiver.now()); }
void exitCard() { apply(); controller.beginExit(receiver.now(), receiver.calibrated()); ++exits; }
void complete() { apply(); controller.complete(receiver.now(), receiver.calibrated()); }
const NotifyLifecycle lifecycle = {hold, exitCard, complete};
void flush(lv_display_t *display, const lv_area_t *, uint8_t *) { lv_display_flush_ready(display); }
unsigned visibleText(lv_obj_t *obj, const char *text) {
  if (lv_obj_is_hidden(obj)) return 0;
  unsigned count = lv_obj_check_type(obj, &lv_label_class) && strcmp(lv_label_get_text(obj), text) == 0 ? 1 : 0;
  for (uint32_t i = 0; i < lv_obj_get_child_count(obj); ++i) count += visibleText(lv_obj_get_child(obj, i), text);
  return count;
}
unsigned text(const char *value) { return visibleText(lv_screen_active(), value); }
void state(uint8_t type, const desk::wire::State &s) {
  std::array<uint8_t, 256> bytes{};
  const auto encoded = desk::wire::encode(bytes.data(), bytes.size(), type, 0, s);
  check(encoded.ok(), "encode actual state frame"); transport.add(bytes.data(), encoded.size); pump();
}
void control(uint32_t revision, uint32_t generation) {
  desk::wire::State s; s.epoch = 10; s.connection = 20;
  s.control.revision = revision; s.control.generation = generation; s.control.episode = revision;
  s.control.enabled = true;
  state(desk::wire::DisplayState, s);
}
void statistics(uint32_t revision, uint32_t generation, uint8_t availability, bool live = false, uint32_t viewers = 0) {
  desk::wire::Stats s; s.epoch = 10; s.connection = 20;
  s.revision = revision; s.generation = generation; s.availability = availability;
  s.base.live = live; s.base.viewers = viewers; s.base.uptime_s = 10;
  std::array<uint8_t, 68> bytes{};
  const auto encoded = desk::wire::encodeStats(bytes.data(), bytes.size(), s);
  check(encoded.ok(), "encode actual STATS frame"); transport.add(bytes.data(), encoded.size); pump();
}
void synchronize() {
  linkInit(&hooks, transport, &receiver); pump();
  check(!transport.output.empty() && desk::wire::helloSupportsDesk(
        transport.output.data() + 8, transport.output.size() - 8),
        "actual production-ready HELLO advertises complete DESK tail");
  desk::wire::Welcome w; w.epoch = 10; w.connection = 20;
  w.echo = receiver.probe(); w.sample = receiver.now(); w.base.max_frame = 256;
  w.base.caps = tsb::CAP_ACK | tsb::CAP_GENERIC;
  std::array<uint8_t, 68> bytes{};
  const auto encoded = desk::wire::encodeWelcome(bytes.data(), bytes.size(), w);
  check(encoded.ok(), "encode actual WELCOME"); transport.add(bytes.data(), encoded.size); pump();
  control(1, 1);
  desk::wire::State s; s.epoch = 10; s.connection = 20; s.revision = 1;
  state(desk::wire::Weather, s); state(desk::wire::Cpu, s); statistics(1, 1, 1);
  s.sync.display = s.sync.weather = s.sync.cpu = s.sync.stats = s.sync.generation = s.sync.episode = 1;
  state(desk::wire::SyncEnd, s);
  check(linkDeskReady() && uiIdleVisible() && text("0") == 3, "actual LinkSession greeting renders observed zero offline chips");
}
void checkLifecycleReports() {
  std::array<bool, 4> seen{};
  uint32_t revision = 0;
  uint64_t observed = 0;
  size_t offset = 0;
  while (offset + tsb::HEADER_SIZE <= transport.output.size()) {
    const uint8_t *frame = transport.output.data() + offset;
    const size_t size = desk::wire::read(frame + 4, 2);
    if (offset + tsb::HEADER_SIZE + size > transport.output.size()) break;
    if (frame[3] == desk::wire::DeviceReport) {
      desk::wire::Report report;
      check(desk::wire::decodeReport(frame + tsb::HEADER_SIZE, size, report) == tsb::DecodeResult::Ok,
            "actual outbound lifecycle report decodes");
      check(report.revision > revision && report.observedDevice >= observed && report.boot == 7,
            "lifecycle report revisions/time increase with stable boot identity");
      revision = report.revision; observed = report.observedDevice;
      if (report.phase != desk::Phase::None) {
        seen[static_cast<uint8_t>(report.phase)] = true;
        check(report.pending == 0 && report.grant == 9 && report.processed == 1 && report.enqueued == 1 &&
              report.entries[0].seq == 1 && report.entries[0].epoch == 10 &&
              report.entries[0].origin == desk::Origin::External,
              "actual LVGL phase report retains active identity and releases only dequeue credit");
      } else if (report.processed == 1 && report.pending == 0) {
        seen[0] = true;
        check(report.grant == 9 && desk::wire::empty(report.entries[0]) && report.remaining == 0,
              "completion report clears active entry without second credit");
      }
    }
    offset += tsb::HEADER_SIZE + size;
  }
  check(seen[0] && seen[1] && seen[2] && seen[3],
        "actual LinkSession reports entrance hold exit and completion from real LVGL callbacks");
}
void card() {
  desk::wire::Event e; e.epoch = 10; e.base.seq = 1; e.base.ttl_ds = 10;
  e.deadline = receiver.now() + 10000; e.origin = desk::Origin::External;
  std::array<uint8_t, 204> bytes{};
  const auto encoded = desk::wire::encodeEvent(bytes.data(), bytes.size(), 0, e);
  check(encoded.ok(), "encode generic card"); transport.add(bytes.data(), encoded.size); pump();
  desk::Card active; check(controller.start(receiver.now(), active), "transport card starts without preemption");
  uiNotifyShow(active.notification, active.origin != desk::Origin::Unknown);
}
}
extern "C" void uiFaultHalt(const char *file, int line) {
  fprintf(stderr, "LVGL assertion: %s:%d\n", file, line); abort();
}
int main() {
  lv_init();
  lv_display_t *display = lv_display_create(240, 240);
  std::array<uint8_t, 240 * 8 * 2> pixels{};
  lv_display_set_color_format(display, LV_COLOR_FORMAT_RGB565);
  lv_display_set_buffers(display, pixels.data(), nullptr, pixels.size(), LV_DISPLAY_RENDER_MODE_PARTIAL);
  lv_display_set_flush_cb(display, flush);
  uiBaseView().build(1); uiNotifyInit(); uiNotifySetLifecycle(&lifecycle);
  check(desk::initializeApplicationBoot(receiver, 7) && receiver.enabled(),
        "production boot initializer enables real UI before actual LinkSession");
  synchronize();
  statistics(2, 1, 0);
  check(text("--") == 3 && text("0") == 0 && uiIdleVisible(), "unknown STATS stay Twitch and never measure zero");
  statistics(3, 1, 2, true);
  check(text("--") == 5 && text("0") == 0, "unavailable live STATS hide chips viewers and uptime");
  pump(250);
  check(text("--") == 5, "unknown uptime timer never manufactures elapsed measurement");
  statistics(4, 1, 1, true);
  pump(400);
  check(text("0") == 4 && text("--") == 0, "observed live zero remains a measurement");
  check(text("0:00:11") + text("0:00:12") == 1, "pre-render projection does not reanchor uptime each loop");
  statistics(100, 1, 1, true, 321); pump(200);
  check(text("321") == 1, "observed prior-source viewers reach actual label");
  control(2, 2);
  check(text("321") == 0 && text("--") == 3 && uiIdleVisible(), "control alone invalidates prior source before render");
  statistics(1, 2, 1, true, 999);
  check(receiver.stats().revision == 100 && text("999") == 0 && text("--") == 3, "lower matching-generation revision cannot revive telemetry");
  statistics(101, 2, 1, true, 123); pump(200);
  check(text("123") == 1, "higher matching-generation observed STATS restores values");
  card(); statistics(102, 2, 2, true);
  check(uiBaseView().covered() && text("--") == 0, "hidden unavailable update does not draw under card");
  for (unsigned i = 0; i < 1000 && exits == 0; ++i) pump(1);
  check(exits == 1 && uiNotifyBusy() && text("--") == 5, "first actual exit reveal applies hidden unavailable values");
  statistics(103, 2, 1, true, 555);
  control(3, 3);
  check(uiNotifyBusy() && controller.active() && text("555") == 0 && text("--") == 3,
        "source switch during exit invalidates old view without interrupting active card");
  statistics(104, 3, 1);
  check(text("0") == 3 && text("--") == 0, "new-source observed zero replaces unknown during exit");
  for (unsigned i = 0; i < 1000 && uiNotifyBusy(); ++i) pump(1);
  check(!controller.active() && !uiNotifyBusy(), "active card finishes through actual callbacks");
  pump(50); // transmit coalesced completion before the intentional session failure
  checkLifecycleReports();
  desk::wire::State weather; weather.epoch = 10; weather.connection = 20;
  weather.revision = 2; weather.status = desk::Status::Fresh;
  weather.deadline = receiver.now() + 5000; weather.temperature = 1875;
  weather.condition = 6; weather.presence = 1; weather.humidity = 81;
  snprintf(weather.label, sizeof(weather.label), "Helsinki");
  state(desk::wire::Weather, weather);
  check(uiIdleVisible() && text("18.75") == 0, "socket Weather updates cache beneath Twitch override");
  desk::wire::State displayState; displayState.epoch = 10; displayState.connection = 20;
  displayState.control.revision = 4; displayState.control.generation = 3; displayState.control.episode = 4;
  displayState.control.preferred = desk::Preferred::Weather;
  state(desk::wire::DisplayState, displayState);
  check(!uiIdleVisible() && text("18.75") == 1 && text("Rain") == 1 && text("Helsinki") == 1 &&
        text("Humidity 81%") == 1 && text("deg C") == 1,
        "relay pushed Display Weather renders exact labels via actual LinkSession and production application");
  desk::wire::State cpu; cpu.epoch = 10; cpu.connection = 20; cpu.revision = 2;
  cpu.status = desk::Status::Fresh; cpu.deadline = receiver.now() + 5000;
  cpu.cpu = 4375; cpu.memory = 6200; cpu.presence = 1;
  snprintf(cpu.label, sizeof(cpu.label), "Desktop");
  state(desk::wire::Cpu, cpu);
  displayState.control.revision = 5; displayState.control.preferred = desk::Preferred::Cpu;
  state(desk::wire::DisplayState, displayState);
  check(text("43.75") == 1 && text("Memory 62.00%") == 1 && text("18.75") == 0,
        "socket Display CPU switches the same production base without leaking Weather");
  displayState.control.revision = 6; displayState.control.preferred = desk::Preferred::Weather;
  state(desk::wire::DisplayState, displayState);
  pump(1000);
  check(linkDeskReady() && uiBaseView().selected() == desk::Base::ConnectedOrbit && text("18.75") == 0 &&
        controller.selection(receiver.now()).reason == desk::Reason::DataStale,
        "socket snapshot ages to original conservative expiry before actual render");
  statistics(104, 3, 2);
  check(!linkIsUp(), "equal conflicting availability fails closed through LinkSession");
  uiBaseView().destroy(); lv_display_delete(display); lv_deinit();
  printf("%u checks, %u failures\n", checks, failures);
  return failures != 0;
}
