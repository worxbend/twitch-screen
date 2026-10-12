#include <lvgl.h>
#include <stdio.h>
#include <stdlib.h>
#include <array>
#include "desk_ui_application.h"
#include "ui_idle.h"
#include "ui_notify.h"

namespace {
desk::Controller controller;
desk::Receiver receiver(controller, 0, true);
unsigned checks = 0, failures = 0, exits = 0, completions = 0;
uint32_t raw = 0;
void check(bool ok, const char *message) {
  ++checks;
  if (!ok) { ++failures; printf("FAIL: %s\n", message); }
}
void apply() { desk::applyApplicationView(receiver, raw); }
void hold() { receiver.tick(raw); controller.hold(receiver.now()); }
void exitCard() {
  apply(); controller.beginExit(receiver.now(), receiver.calibrated()); ++exits;
}
void complete() {
  apply(); check(controller.complete(receiver.now(), receiver.calibrated()), "real completion applies current controller"); ++completions;
}
desk::Received send(uint8_t type, const desk::wire::State &state) {
  std::array<uint8_t, 256> frame{};
  const auto encoded = desk::wire::encode(frame.data(), frame.size(), type, 0, state);
  check(encoded.ok(), "test update encodes with actual codec");
  return receiver.receive(type, 0, frame.data() + 8, encoded.size - 8);
}
desk::wire::State data(uint32_t revision, uint64_t deadline) {
  desk::wire::State s;
  s.epoch = 10; s.connection = 20; s.revision = revision;
  s.deadline = deadline; s.status = desk::Status::Fresh;
  snprintf(s.label, sizeof(s.label), "Test host");
  return s;
}
void negotiate(uint64_t weatherDeadline = 0) {
  receiver.tick(raw); receiver.hello();
  desk::wire::Welcome w;
  w.epoch = 10; w.connection = 20; w.echo = receiver.probe(); w.sample = receiver.now();
  check(receiver.welcome(w), "opted-in synthetic WELCOME calibrates receiver");
  desk::wire::State s;
  s.epoch = 10; s.connection = 20;
  s.control.revision = s.control.generation = s.control.episode = 1;
  s.control.preferred = desk::Preferred::Weather;
  check(send(desk::wire::DisplayState, s) == desk::Received::Applied, "stage weather preference");
  s = data(1, weatherDeadline ? weatherDeadline : receiver.now() + 5000);
  s.temperature = -123; s.condition = 7; s.presence = 1;
  check(send(desk::wire::Weather, s) == desk::Received::Applied, "stage real wire weather");
  s = data(1, receiver.now() + 10000); s.cpu = 1234; s.presence = 1;
  check(send(desk::wire::Cpu, s) == desk::Received::Applied, "stage real wire CPU");
  desk::wire::Stats stats;
  stats.epoch = 10; stats.connection = 20; stats.generation = stats.revision = 1;
  std::array<uint8_t, 256> frame{};
  const auto encoded = desk::wire::encodeStats(frame.data(), frame.size(), stats);
  check(encoded.ok() && receiver.receive(tsb::T_STATS, 0, frame.data() + 8, encoded.size - 8) == desk::Received::Applied,
        "stage actual extended STATS");
  apply();
  check(lv_obj_is_hidden(uiBaseView().weatherView().root), "staged fresh weather never shows before SYNC_END");
  s = {}; s.epoch = 10; s.connection = 20;
  s.sync.display = s.sync.weather = s.sync.cpu = s.sync.stats = s.sync.generation = s.sync.episode = 1;
  check(send(desk::wire::SyncEnd, s) == desk::Received::Applied && receiver.ready(), "six-frame synthetic greeting completes");
  apply();
}
void preference(uint32_t revision, desk::Preferred preferred, bool enabled = false) {
  desk::wire::State s; s.epoch = 10; s.connection = 20;
  s.control.revision = revision; s.control.generation = s.control.episode = 1;
  s.control.enabled = enabled; s.control.preferred = preferred;
  check(send(desk::wire::DisplayState, s) == desk::Received::Applied, "actual control update accepted");
  apply();
}
void deskCard(uint32_t seq) {
  desk::wire::Event event;
  event.epoch = 10; event.deadline = receiver.now() + 10000;
  event.origin = desk::Origin::External;
  event.base.seq = seq; event.base.ttl_ds = 1;
  std::array<uint8_t, 256> frame{};
  const auto encoded = desk::wire::encodeEvent(frame.data(), frame.size(), 0, event);
  check(encoded.ok() && receiver.receive(desk::wire::DeskEvent, 0, frame.data() + 8, encoded.size - 8) == desk::Received::Enqueued,
        "actual DESK_EVENT enters sole controller");
  desk::Card card;
  check(controller.start(receiver.now(), card), "DESK event starts");
  uiNotifyShow(card.notification);
}
const NotifyLifecycle lifecycle = {hold, exitCard, complete};
std::array<uint16_t, 240 * 240> framebuffer{};
void flush(lv_display_t *display, const lv_area_t *area, uint8_t *pixels) {
  const int width = lv_area_get_width(area);
  const uint32_t stride = lv_draw_buf_width_to_stride(width, LV_COLOR_FORMAT_RGB565);
  for (int y = area->y1; y <= area->y2; ++y)
    for (int x = area->x1; x <= area->x2; ++x) {
      uint16_t pixel;
      memcpy(&pixel, pixels + (y - area->y1) * stride + (x - area->x1) * 2, 2);
      if (x >= 0 && x < 240 && y >= 0 && y < 240) framebuffer[y * 240 + x] = pixel;
    }
  lv_display_flush_ready(display);
}
void capture(const char *name) {
  const char *directory = getenv("TS_UI_CAPTURE_DIR");
  if (!directory || !*directory) return;
  lv_obj_invalidate(lv_screen_active()); lv_refr_now(nullptr);
  char path[1024]; snprintf(path, sizeof(path), "%s/%s.ppm", directory, name);
  FILE *out = fopen(path, "wb"); check(out != nullptr, "capture destination is writable");
  if (!out) return;
  fprintf(out, "P6\n# Synthetic codec test telemetry; actual LVGL RGB565 flush\n240 240\n255\n");
  for (uint16_t pixel : framebuffer) {
    const uint8_t r = (pixel >> 11) & 31, g = (pixel >> 5) & 63, b = pixel & 31;
    const uint8_t rgb[] = {static_cast<uint8_t>((r << 3) | (r >> 2)),
      static_cast<uint8_t>((g << 2) | (g >> 4)), static_cast<uint8_t>((b << 3) | (b >> 2))};
    if (fwrite(rgb, 1, 3, out) != 3) { check(false, "capture RGB565 pixel writes"); break; }
  }
  check(fclose(out) == 0, "capture closes successfully");
}
void dialCheck(const uiGauge::Dial &dial, int value, bool available, bool scale = false) {
  check(dial.value == value && dial.available == available, "cached dial preserves normalized reading and availability");
  for (unsigned i = 0; i < dial.arcs.size(); ++i) {
    int degrees = value * 270 / 10000 - static_cast<int>(i) * 69;
    degrees = degrees < 0 ? 0 : degrees > 63 ? 63 : degrees;
    check(lv_arc_get_min_value(dial.arcs[i]) == 0 && lv_arc_get_max_value(dial.arcs[i]) == 10000,
          "actual segment bounds are 0..10000");
    check(lv_arc_get_value(dial.arcs[i]) == (available ? (scale ? 10000 : degrees * 10000 / 63) : 0),
          "actual arcs encode zero/full/partial rather than decorative fake fill");
    check(lv_obj_get_style_arc_rounded(dial.arcs[i], LV_PART_INDICATOR), "segments have rounded ends");
  }
}
void safeLabels(const BaseView::TelemetryView &view) {
  lv_obj_update_layout(view.root);
  lv_point_t textSize;
  lv_text_get_size(&textSize, view.valueText, lv_obj_get_style_text_font(view.value, LV_PART_MAIN),
                   lv_obj_get_style_text_letter_space(view.value, LV_PART_MAIN), 0, 1000, LV_TEXT_FLAG_NONE);
  check(textSize.x <= lv_obj_get_content_width(view.value), "exact numeric reading fits without dots or clipping");
  for (auto *label : {view.label, view.value, view.unit, view.detail, view.secondary}) {
    lv_area_t a; lv_obj_get_coords(label, &a);
    for (int x : {a.x1, a.x2}) for (int y : {a.y1, a.y2})
      check((x - 120) * (x - 120) + (y - 120) * (y - 120) <= 120 * 120,
            "label rectangle stays within circular panel");
  }
}
void titleClearance(const BaseView::TelemetryView &view) {
  // Clear only the glyphs, not their layout, to inspect the actual dial backdrop.
  lv_label_set_text_static(view.label, "");
  lv_obj_invalidate(view.root); lv_refr_now(nullptr);
  lv_area_t area; lv_obj_get_coords(view.label, &area);
  const int innerRadius = view.memory.radius - 2 - 4;
  for (int x : {area.x1, area.x2}) for (int y : {area.y1, area.y2})
    check((x - 120) * (x - 120) + (y - 120) * (y - 120) <= innerRadius * innerRadius,
          "CPU host label stays four pixels inside memory ring, without masking it");
  bool clear = true;
  for (int y = area.y1; y <= area.y2; ++y)
    for (int x = area.x1; x <= area.x2; ++x)
      clear = clear && framebuffer[y * 240 + x] == 0;
  check(clear, "CPU title zone contains no ring pixels at partial or full memory load");
  lv_label_set_text_static(view.label, view.labelText);
  lv_obj_invalidate(view.root); lv_refr_now(nullptr);
}
void pump(unsigned count = 1) {
  for (unsigned i = 0; i < count; ++i) {
    raw += 5; apply(); lv_tick_inc(5); lv_timer_handler();
  }
}
void classicCard(uint32_t seq, NotifyKind kind = NotifyKind::Message) {
  Notification n; n.kind = kind; n.seq = seq; n.ttl_ds = 1;
  check(controller.legacyAdmit(n) == desk::Admission::Enqueued, "classic admission uses sole queue");
  desk::Card card; check(controller.start(receiver.now(), card), "pending transfers to active");
  uiNotifyShow(card.notification);
  check(uiBaseView().covered() && !uiIdleVisible() && lv_obj_is_hidden(uiBaseView().orbit().root()), "card cover hides all base rendering");
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
  check(!receiver.enabled(), "production startup without boot identity cannot advertise DESK");
  check(!desk::initializeApplicationBoot(receiver, 7) && receiver.boot() == 0,
        "startup before base construction refuses identity and DESK");
  uiBaseView().build(123);
  check(!desk::initializeApplicationBoot(receiver, 7) && receiver.boot() == 0,
        "startup without notification widgets refuses DESK");
  uiNotifyInit();
  check(!desk::initializeApplicationBoot(receiver, 7) && receiver.boot() == 0,
        "startup without full lifecycle binding refuses DESK");
  uiNotifySetLifecycle(&lifecycle);
  check(!desk::initializeApplicationBoot(receiver, 0) && !receiver.enabled(), "zero boot rejects ready UI");
  check(desk::initializeApplicationBoot(receiver, 7) && receiver.enabled() && !receiver.ready(),
        "actual constructed UI and lifecycle enable profile but not session readiness");
  check(!desk::initializeApplicationBoot(receiver, 8) && receiver.boot() == 7, "boot identity is immutable");
  apply(); pump(20);
  check(uiBaseView().selected() == desk::Base::DisconnectedOrbit && !lv_obj_is_hidden(uiBaseView().orbit().root()) && !uiIdleVisible(), "first boot selects local disconnected orbit");
  capture("generic-disconnected");
  const uint64_t phase = uiBaseView().orbit().frame().visibleMs;
  controller.legacyGreeting(0, 1); apply(); pump(20);
  check(uiIdleVisible() && lv_obj_is_hidden(uiBaseView().orbit().root()), "classic relay selects existing Twitch dashboard only");
  check(uiBaseView().orbit().frame().visibleMs == phase, "hidden orbit pauses its visible clock");
  classicCard(1);
  controller.disconnect();
  for (unsigned i = 0; i < 1000 && uiNotifyBusy(); ++i) pump();
  check(!uiNotifyBusy() && !controller.active() && exits == 1 && completions == 1, "actual LVGL card finishes completely offline");
  check(uiBaseView().selected() == desk::Base::DisconnectedOrbit && !uiBaseView().covered() && !lv_obj_is_hidden(uiBaseView().orbit().root()), "actual uncover reveals disconnected orbit");
  pump(20);
  check(uiBaseView().orbit().frame().visibleMs > phase, "orbit resumes rather than restarts after card");
  controller.legacyGreeting(1, 1); apply();
  classicCard(2, NotifyKind::Alert); controller.disconnect();
  for (unsigned i = 0; i < 1000 && uiNotifyBusy(); ++i) pump();
  check(!controller.active() && exits == 2 && completions == 2, "severity entrance hold exit completes offline");
  negotiate();
  const auto &weather = uiBaseView().weatherView();
  const auto &cpu = uiBaseView().cpuView();
  check(!lv_obj_is_hidden(weather.root) && strcmp(lv_label_get_text(weather.value), "-1.23") == 0 &&
        strcmp(lv_label_get_text(weather.detail), "Snow") == 0 && strcmp(lv_label_get_text(weather.secondary), "Humidity 0%") == 0,
        "fresh wire weather renders exact negative centi and measured zero humidity");
  static const char *const conditions[] = {"Unknown", "Clear", "Partly cloudy", "Cloudy",
      "Fog", "Drizzle", "Rain", "Snow", "Thunderstorm"};
  std::array<uint64_t, 9> conditionPixels{};
  for (uint8_t condition = 0; condition <= 8; ++condition) {
    auto sample = data(2 + condition, receiver.now() + 5000);
    sample.temperature = condition == 8 ? -14800 : 21200;
    sample.unit = 1; sample.condition = condition;
    sample.presence = condition % 2; sample.humidity = sample.presence ? 100 : 0;
    snprintf(sample.label, sizeof(sample.label), "Helsinki");
    check(send(desk::wire::Weather, sample) == desk::Received::Applied, "every provider condition receives via production codec");
    apply(); pump(2);
    check(weather.condition == condition, "fixed pictogram follows all nine provider conditions");
    dialCheck(weather.primary, condition == 8 ? 0 : 10000, true, true);
    safeLabels(weather);
    lv_obj_invalidate(weather.icon); lv_refr_now(nullptr);
    lv_area_t iconArea; lv_obj_get_coords(weather.icon, &iconArea);
    uint64_t signature = 1469598103934665603ULL;
    unsigned lit = 0;
    for (int y = iconArea.y1; y <= iconArea.y2; ++y)
      for (int x = iconArea.x1; x <= iconArea.x2; ++x) {
        const auto pixel = framebuffer[y * 240 + x];
        if (pixel) ++lit;
        signature = (signature ^ pixel) * 1099511628211ULL;
      }
    check(lit > 30, "condition pictogram produces visible real RGB565 pixels");
    for (unsigned prior = 0; prior < condition; ++prior)
      check(conditionPixels[prior] != signature, "each condition has a distinct drawn pictogram");
    conditionPixels[condition] = signature;
    char captureName[32]; snprintf(captureName, sizeof(captureName), "weather-condition-%u", condition);
    capture(captureName);
    check(strcmp(lv_label_get_text(weather.detail), conditions[condition]) == 0 &&
          strcmp(lv_label_get_text(weather.label), "Helsinki") == 0 &&
          strcmp(lv_label_get_text(weather.unit), "deg F") == 0 &&
          strcmp(lv_label_get_text(weather.value), condition == 8 ? "-148.00" : "212.00") == 0 &&
          strcmp(lv_label_get_text(weather.secondary), sample.presence ? "Humidity 100%" : "") == 0,
          "real Weather labels show title, condition, Fahrenheit extremes and honest humidity");
  }
  preference(2, desk::Preferred::Cpu);
  check(!lv_obj_is_hidden(cpu.root) && strcmp(lv_label_get_text(cpu.value), "12.34") == 0 &&
        strcmp(lv_label_get_text(cpu.detail), "Memory 0.00%") == 0, "CPU basis points and present zero memory render directly");
  auto update = data(2, receiver.now() + 10000); update.cpu = 9999;
  update.presence = 2; update.temperature = 4500;
  snprintf(update.label, sizeof(update.label), "Host\x01\xff");
  check(send(desk::wire::Cpu, update) == desk::Received::Applied, "CPU optional absence update"); apply();
  check(strcmp(lv_label_get_text(cpu.value), "99.99") == 0 && strcmp(lv_label_get_text(cpu.detail), "Memory unavailable") == 0 &&
        !lv_label_get_text(cpu.secondary)[0] && strcmp(lv_label_get_text(cpu.label), "Host__") == 0,
        "missing memory is not zero; temperature not extra secondary; labels font-safe");
  preference(3, desk::Preferred::Weather, true);
  check(uiIdleVisible() && lv_obj_is_hidden(weather.root) && lv_obj_is_hidden(cpu.root), "override hides telemetry");
  preference(4, desk::Preferred::Weather);
  update = data(11, receiver.now() + 300); update.temperature = 123; update.unit = 1;
  check(send(desk::wire::Weather, update) == desk::Received::Applied, "short freshness update");
  deskCard(1);
  for (unsigned i = 0; i < 1000 && exits == 2; ++i) pump();
  check(exits == 3 && uiNotifyBusy() && !uiBaseView().covered(), "actual first exit step uncovers base");
  check(lv_obj_is_hidden(weather.root) && uiBaseView().selected() == desk::Base::ConnectedOrbit,
        "expired during hold never flashes weather at first slide-out");
  preference(5, desk::Preferred::Cpu);
  check(!lv_obj_is_hidden(cpu.root) && uiNotifyBusy(), "preference during actual exit applies immediately");
  update = data(3, receiver.now() + 40); update.cpu = 5000;
  check(send(desk::wire::Cpu, update) == desk::Received::Applied, "CPU deadline during exit");
  pump(10);
  check(uiNotifyBusy() && lv_obj_is_hidden(cpu.root) && uiBaseView().selected() == desk::Base::ConnectedOrbit,
        "expiry during visible exit hides stale CPU before rendering");
  receiver.disconnected(); apply();
  check(uiBaseView().selected() == desk::Base::DisconnectedOrbit && !lv_obj_is_hidden(uiBaseView().orbit().root()),
        "link loss during slide-out reveals disconnected orbit");
  for (unsigned i = 0; i < 1000 && uiNotifyBusy(); ++i) pump();
  check(!controller.active() && completions == 3, "DESK card finishes through real callbacks offline");
  const uint64_t expiredDeadline = receiver.weather().deadline;
  negotiate(expiredDeadline);
  check(receiver.ready() && receiver.enabled() && receiver.boot() == 7 &&
        uiBaseView().selected() == desk::Base::ConnectedOrbit && lv_obj_is_hidden(weather.root) &&
        controller.selection(receiver.now()).reason == desk::Reason::DataStale,
        "reconnect completes without renewing original expired telemetry or boot identity");
  update = data(2, receiver.now() + 5000); update.temperature = 2150; update.condition = 2;
  check(send(desk::wire::Weather, update) == desk::Received::Applied, "fresh post-reconnect replacement received"); apply();
  check(!lv_obj_is_hidden(weather.root) && strcmp(lv_label_get_text(weather.value), "21.50") == 0,
        "new fresh replacement restores Weather via production UI path");
  update = {}; update.epoch = 10; update.connection = 20; update.revision = 3;
  check(send(desk::wire::Weather, update) == desk::Received::Applied, "explicit missing Weather received"); apply();
  check(lv_obj_is_hidden(weather.root) && uiBaseView().selected() == desk::Base::ConnectedOrbit &&
        controller.selection(receiver.now()).reason == desk::Reason::DataMissing,
        "missing snapshot hides Weather and reports honest missing status");
  receiver.classic(); controller.legacyGreeting(0, 2); apply();
  check(uiIdleVisible() && lv_obj_is_hidden(weather.root) && lv_obj_is_hidden(cpu.root) && !receiver.ready(),
        "Desk to classic fallback never exposes cached Weather or applied Desk readiness");
  // Adapter-only geometry vectors after the controller/lifecycle contract sequence.
  const uint32_t weatherObjects = lv_obj_get_child_count(weather.root);
  const uint32_t cpuObjects = lv_obj_get_child_count(cpu.root);
  desk::Selection geometry; geometry.base = desk::Base::Weather;
  desk::wire::State ws, cs; snprintf(ws.label, sizeof(ws.label), "Helsinki");
  ws.temperature = 2150; ws.condition = 2; ws.presence = 1; ws.humidity = 63;
  uiBaseView().apply(geometry, ws, cs); dialCheck(weather.primary, 6437, true, true);
  capture("weather-normal-celsius");
  const auto celsiusValue = weather.primary.value;
  lv_obj_update_layout(weather.root);
  lv_area_t markerC; lv_obj_get_coords(weather.primary.marker, &markerC);
  ws.unit = 1; ws.temperature = 7070; uiBaseView().apply(geometry, ws, cs);
  lv_obj_update_layout(weather.root);
  lv_area_t markerF; lv_obj_get_coords(weather.primary.marker, &markerF);
  check(weather.primary.value == celsiusValue && memcmp(&markerC, &markerF, sizeof(markerC)) == 0,
        "equivalent Celsius/Fahrenheit render identical marker and physical scale");
  capture("weather-equivalent-fahrenheit");
  ws.unit = 0; ws.temperature = -10000; uiBaseView().apply(geometry, ws, cs);
  dialCheck(weather.primary, 0, true, true); safeLabels(weather); capture("weather-min-celsius");
  check(strcmp(lv_label_get_text(weather.value), "-100.00") == 0 && weather.compact,
        "extreme negative temperature stays exact with fitting shipped font");
  ws.temperature = 10000; uiBaseView().apply(geometry, ws, cs);
  dialCheck(weather.primary, 10000, true, true); capture("weather-max-celsius");
  geometry.base = desk::Base::Cpu; snprintf(cs.label, sizeof(cs.label), "Workstation");
  cs.cpu = 6789; cs.memory = 4321; cs.presence = 1;
  uiBaseView().apply(geometry, ws, cs); dialCheck(cpu.primary, 6789, true);
  dialCheck(cpu.memory, 4321, true); safeLabels(cpu); titleClearance(cpu); capture("cpu-normal");
  snprintf(cs.label, sizeof(cs.label), "Very wide workstation label");
  cs.memory = 10000; uiBaseView().apply(geometry, ws, cs);
  titleClearance(cpu); safeLabels(cpu); capture("cpu-wide-host-full-memory");
  cs.memory = 4321; uiBaseView().apply(geometry, ws, cs);
  titleClearance(cpu); capture("cpu-wide-host-partial-memory");
  snprintf(cs.label, sizeof(cs.label), "Workstation");
  uiBaseView().apply(geometry, ws, cs);
  cs.presence = 0; cs.memory = 0; uiBaseView().apply(geometry, ws, cs);
  dialCheck(cpu.memory, 0, false); capture("cpu-missing-memory");
  cs.presence = 1; cs.cpu = 0; uiBaseView().apply(geometry, ws, cs);
  dialCheck(cpu.primary, 0, true); dialCheck(cpu.memory, 0, true); capture("cpu-zero");
  check(strcmp(lv_label_get_text(cpu.detail), "Memory 0.00%") == 0, "measured zero remains distinct from missing");
  cs.presence = 0; uiBaseView().apply(geometry, ws, cs); dialCheck(cpu.memory, 0, false);
  check(strcmp(lv_label_get_text(cpu.detail), "Memory unavailable") == 0, "missing to zero to missing clears text and arcs honestly");
  cs.presence = 1; cs.cpu = cs.memory = 10000; uiBaseView().apply(geometry, ws, cs);
  dialCheck(cpu.primary, 10000, true); dialCheck(cpu.memory, 10000, true);
  check(strcmp(lv_label_get_text(cpu.value), "100.00") == 0 && cpu.compact, "full CPU stays exact and fits");
  capture("cpu-full");
  cs.cpu = cs.memory = 65535; uiBaseView().apply(geometry, ws, cs);
  dialCheck(cpu.primary, 10000, true); dialCheck(cpu.memory, 10000, true);
  check(strcmp(lv_label_get_text(cpu.value), "100.00") == 0, "adapter defensively clamps percentages to 100");
  for (unsigned i = 0; i < 500; ++i) {
    cs.cpu = i * 20; ws.condition = i % 9;
    geometry.base = desk::Base::Cpu; uiBaseView().apply(geometry, ws, cs);
    geometry.base = desk::Base::Weather; uiBaseView().apply(geometry, ws, cs);
  }
  check(lv_obj_get_child_count(weather.root) == weatherObjects && lv_obj_get_child_count(cpu.root) == cpuObjects,
        "hundreds of readings and condition changes create no LVGL objects");
  geometry.base = desk::Base::ConnectedOrbit; uiBaseView().apply(geometry, ws, cs);
  lv_tick_inc(500); lv_timer_handler(); capture("generic-connected");
  uiBaseView().destroy(); lv_display_delete(display); lv_deinit();
  printf("%u checks, %u failures\n", checks, failures);
  return failures != 0;
}
