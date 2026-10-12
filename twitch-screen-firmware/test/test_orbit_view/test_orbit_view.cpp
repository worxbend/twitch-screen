#include "ui_orbit.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <array>

namespace {
unsigned checks = 0, failures = 0, flushes = 0;
void check(bool ok, const char *message) {
  ++checks;
  if (!ok) { ++failures; printf("FAIL: %s\n", message); }
}
void flush(lv_display_t *display, const lv_area_t *, uint8_t *) {
  ++flushes; lv_display_flush_ready(display);
}
void pump(unsigned count = 1) {
  while (count--) { lv_tick_inc(50); lv_timer_handler(); }
}
unsigned timers() {
  unsigned count = 0;
  for (lv_timer_t *t = lv_timer_get_next(nullptr); t; t = lv_timer_get_next(t)) ++count;
  return count;
}
void bounds(const OrbitView &view, bool disconnected) {
  const auto &f = view.frame();
  const int base[] = {61, 83, 106}, excursion[] = {2, 3, 2};
  for (unsigned i = 0; i < 3; ++i) {
    check(f.rotation[i] < 360 && f.radius[i] >= base[i] - excursion[i] &&
          f.radius[i] <= base[i] + excursion[i], "angles and breathing stay inside round-panel bounds");
  }
  check(lv_obj_get_child_count(view.root()) == 14,
        "fixed three tracks, nine color segments, core and offline badge; no growing objects");
  lv_obj_t *outer = lv_obj_get_child(view.root(), 2);
  check(lv_arc_get_bg_angle_end(outer) - lv_arc_get_bg_angle_start(outer) < 330 &&
        lv_arc_get_bg_angle_end(lv_obj_get_child(view.root(), 11)) == 318,
        "outer orbit retains more than a 30-degree gap, including rounded caps");
  lv_obj_t *loss = lv_obj_get_child(view.root(), 13);
  check(lv_obj_is_hidden(loss) == !disconnected,
        "connection-loss badge is steady and exclusive to disconnected composition");
  check(strcmp(lv_label_get_text(loss), "Offline") == 0 &&
        lv_color_to_u32(lv_obj_get_style_text_color(loss, LV_PART_MAIN)) ==
            lv_color_to_u32(lv_color_hex(0xffbc35)) &&
        lv_obj_get_style_bg_opa(loss, LV_PART_MAIN) == LV_OPA_COVER,
        "offline is immediate legible amber text on an opaque backing, not palette-dependent");
  for (unsigned i = 0; i < 3; ++i) {
    lv_obj_t *primary = lv_obj_get_child(view.root(), 3 + i);
    check(lv_obj_get_style_arc_rounded(primary, LV_PART_MAIN) &&
          lv_arc_get_bg_angle_end(primary) == 132 &&
          lv_arc_get_bg_angle_start(lv_obj_get_child(view.root(), 6 + i * 2)) == 148,
          "rounded segmented composition keeps deliberate internal gaps");
    check(f.radius[i] + lv_obj_get_style_arc_width(primary, LV_PART_MAIN) / 2 < 118,
          "breathing ring and round caps remain inside the circular panel");
  }
  lv_obj_t *core = lv_obj_get_child(view.root(), 12);
  check(lv_obj_get_child_count(core) == 6 && lv_obj_get_width(core) == 66 &&
        lv_obj_get_width(lv_obj_get_child(core, 0)) == 48 &&
        lv_color_to_u32(lv_obj_get_style_bg_color(core, LV_PART_MAIN)) !=
            lv_color_to_u32(lv_obj_get_style_bg_color(lv_obj_get_child(core, 0), LV_PART_MAIN)),
        "fixed original four-facet core has concentric recessed depth");
  lv_area_t area;
  lv_obj_get_coords(loss, &area);
  for (int x : {area.x1, area.x2}) for (int y : {area.y1, area.y2})
    check((x - 120) * (x - 120) + (y - 120) * (y - 120) < 118 * 118,
          "offline badge corners stay inside circular safe area");
}
}
// ui_notify.cpp is linked by this shared environment, but is not invoked here.
void uiBaseSetCovered(bool) {}
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
  const unsigned baselineTimers = timers();
  lv_obj_t *screen = lv_screen_active();
  const unsigned baselineChildren = lv_obj_get_child_count(screen);
  OrbitView view;
  view.build(screen, 123);
  const auto initial = view.frame();
  check(initial.color == std::array<uint32_t, 3>{{0x20ddf2, 0x23e88b, 0xa85bff}},
        "connected face starts with rich distinct cyan, mint and violet");
  check(lv_color_to_u32(lv_obj_get_style_bg_color(view.root(), LV_PART_MAIN)) ==
            lv_color_to_u32(lv_color_hex(0)), "instrument face is true black");
  check(timers() == baselineTimers + 1, "one 50 ms orbit timer");
  check(lv_obj_is_hidden(view.root()), "new view starts hidden");
  pump(20);
  check(view.frame().visibleMs == 0, "hidden view performs no animation work");
  view.setVisible(true); pump(20);
  check(view.frame().visibleMs == 1000 && flushes > 0, "actual LVGL timer and memory-display rendering run");
  bounds(view, false);
  const auto covered = view.frame();
  view.setVisible(false); pump(200);
  check(view.frame().visibleMs == covered.visibleMs && view.frame().rotation == covered.rotation &&
        view.frame().radius == covered.radius && view.frame().color == covered.color,
        "cover pauses composition without resetting phase or palette");
  view.setVisible(true);
  check(view.frame().rotation == covered.rotation, "uncover preserves composition before next frame");
  pump();
  check(view.frame().visibleMs == covered.visibleMs + 50, "uncover excludes hidden residence time");
  auto previous = view.frame();
  view.setDisconnected(true);
  check(view.frame().rotation == previous.rotation && view.frame().radius == previous.radius &&
        view.frame().color == previous.color, "connection change preserves motion and interpolates colors");
  // Ten minutes of synthetic visible time. Not a hardware resource/soak pass.
  for (unsigned i = 0; i < 12000; ++i) {
    if (i % 600 == 0) view.setDisconnected((i / 600) % 2 == 0);
    previous = view.frame(); pump();
    bounds(view, (i / 600) % 2 == 0);
    for (unsigned arc = 0; arc < 3; ++arc) {
      check((view.frame().rotation[arc] + 360 - previous.rotation[arc]) % 360 <= 1,
            "angular motion remains smooth through a complete turn and composition switches");
      for (unsigned shift = 0; shift <= 16; shift += 8) {
        const int before = (previous.color[arc] >> shift) & 255;
        const int after = (view.frame().color[arc] >> shift) & 255;
        check(abs(after - before) <= 1, "palette drift has no frame-to-frame jumps");
      }
    }
  }
  check(timers() == baselineTimers + 1, "mixed composition switches keep one timer");
  view.setDisconnected(true); pump(400);
  for (uint32_t color : view.frame().color)
    check(color == 0x846139 || color == 0xa77d44 || color == 0x494c51,
          "disconnected palette settles to subdued amber and charcoal after smooth interpolation");
  bounds(view, true);
  view.setVisible(false);
  lv_tick_inc(UINT32_MAX - lv_tick_get() - 10);
  view.setVisible(true);
  const uint64_t beforeWrap = view.frame().visibleMs;
  pump();
  check(view.frame().visibleMs == beforeWrap + 50, "normal LVGL tick wrap preserves visible elapsed time");
  view.setVisible(false);
  const unsigned beforeRebuild = timers();
  view.build(screen, 123);
  check(timers() == beforeRebuild && lv_obj_get_child_count(screen) == baselineChildren + 1,
        "rebuilding replaces owned shapes/timer without leaks");
  view.destroy();
  check(timers() == baselineTimers && lv_obj_get_child_count(screen) == baselineChildren,
        "explicit teardown removes owned timer and shapes");
  view.build(screen, 123);
  check(view.frame().rotation == initial.rotation && view.frame().radius == initial.radius,
        "same seed gives stable initial composition");
  view.destroy(); view.destroy();
  lv_display_delete(display); lv_deinit();
  printf("%u checks, %u failures\n", checks, failures);
  return failures != 0;
}
