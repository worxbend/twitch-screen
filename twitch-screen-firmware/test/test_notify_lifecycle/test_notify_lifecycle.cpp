#include <lvgl.h>
#include <stdio.h>
#include <stdlib.h>
#include <array>
#include "desk_controller.h"
#include "ui_notify.h"

namespace {
desk::Controller controller;
uint64_t now = 0;
unsigned checks = 0, failures = 0, holds = 0, exits = 0, completions = 0, reveals = 0;
void check(bool ok, const char *message) {
  ++checks;
  if (!ok) { ++failures; printf("FAIL: %s\n", message); }
}
void hold() {
  ++holds;
  // Replay already entered Hold at pending-to-active transfer.
  if (controller.phase() == desk::Phase::Entrance) check(controller.hold(now), "actual entrance callback starts hold");
  check(controller.phase() == desk::Phase::Hold, "hold callback sees active hold");
}
void beginExit() {
  ++exits;
  controller.beginExit(now, false);
  check(controller.phase() == desk::Phase::Exit, "exit callback precedes first uncovered base");
}
void complete() {
  ++completions;
  check(controller.complete(now, false), "actual animation callback completes controller");
}
const NotifyLifecycle lifecycle = {hold, beginExit, complete};
void flush(lv_display_t *display, const lv_area_t *, uint8_t *) { lv_display_flush_ready(display); }
void pump() {
  now += 5;
  controller.reconcile(now, false);
  lv_tick_inc(5);
  lv_timer_handler();
}
void runCard(NotifyKind kind, bool replay, uint32_t seq, const char *body = "", bool deskLayout = true) {
  Notification n; n.kind = kind; n.seq = seq; n.replay = replay; n.ttl_ds = 1;
  snprintf(n.actor, sizeof(n.actor), "A bounded synthetic headline");
  snprintf(n.text, sizeof(n.text), "%s", body);
  check(controller.legacyAdmit(n) == desk::Admission::Enqueued, "single owner admits classic card");
  desk::Card card;
  check(controller.start(now, card), "single owner transfers pending to active");
  const unsigned oldHold = holds, oldExit = exits, oldComplete = completions;
  uiNotifyShow(card.notification, deskLayout);
  check(uiNotifyBusy() && controller.active(), "UI and controller agree on active card");
  lv_obj_t *overlay = lv_obj_get_child(lv_layer_top(), 0);
  lv_obj_t *title = lv_obj_get_child(overlay, 3);
  lv_obj_t *text = lv_obj_get_child(overlay, 4);
  if (!deskLayout) {
    lv_obj_update_layout(overlay);
    check(lv_obj_get_style_text_font(title, LV_PART_MAIN) == &lv_font_montserrat_28 &&
          lv_obj_get_height(title) == lv_font_get_line_height(&lv_font_montserrat_28) && !lv_obj_is_hidden(text),
          "classic generic card keeps the legacy title/body layout after DESK cards");
  } else if (static_cast<uint8_t>(kind) <= static_cast<uint8_t>(NotifyKind::Alert)) {
    if (body[0]) {
      check(lv_obj_get_style_text_font(text, LV_PART_MAIN) == &lv_font_montserrat_20 &&
            lv_obj_get_style_text_font(title, LV_PART_MAIN) == &lv_font_montserrat_14,
            "generic body receives content-first fonts, not large metadata");
      lv_obj_update_layout(overlay);
      check(lv_obj_get_height(text) == 108 && !lv_obj_is_hidden(text) &&
            lv_label_get_long_mode(text) == LV_LABEL_LONG_MODE_DOTS,
            "generic body has bounded five-line wrapped ellipsis without scrolling");
    } else {
      lv_obj_update_layout(overlay);
      check(lv_obj_get_style_text_font(title, LV_PART_MAIN) == &lv_font_montserrat_28 &&
            lv_obj_get_height(title) == 90 && lv_obj_is_hidden(text),
            "bodyless generic headline receives multiline space and no empty body");
    }
  } else if (kind == NotifyKind::Follow) {
    check(lv_obj_get_style_text_font(title, LV_PART_MAIN) == &lv_font_montserrat_28 &&
          !lv_obj_is_hidden(text), "audience card resets generic layout and body visibility");
  }
  // Disconnect does not cancel entrance, hold or exit.
  controller.disconnect();
  for (unsigned i = 0; i < 1000 && uiNotifyBusy(); ++i) pump();
  check(!uiNotifyBusy() && !controller.active(), "real LVGL timers finish card offline");
  check(holds == oldHold + 1 && exits == oldExit + 1 && completions == oldComplete + 1,
        "each real lifecycle boundary fires exactly once");
  check(controller.grantTotal() == 0, "classic completion manufactures no DESK credits");
}
}

// Only the base-cover seam is replaced. The actual UI and LVGL animations run.
void uiBaseSetCovered(bool covered) {
  if (!covered) {
    ++reveals;
    check(controller.phase() == desk::Phase::Exit || controller.phase() == desk::Phase::None,
          "every base reveal follows reconciliation/lifecycle callback");
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
  uiNotifyInit(); uiNotifySetLifecycle(&lifecycle);
  controller.legacyGreeting(0, 1);
  runCard(NotifyKind::Message, false, 1, "Synthetic body with enough text to wrap into a readable content area.");
  runCard(NotifyKind::Alert, false, 2);
  runCard(NotifyKind::Message, true, 3, "Replay keeps the same content-first layout.");
  runCard(NotifyKind::Info, false, 4);
  runCard(NotifyKind::Warning, false, 5, "Severity still finishes its entire entrance, hold and exit offline.");
  runCard(NotifyKind::Follow, false, 6);
  runCard(NotifyKind::Message, false, 7, "Classic generic body keeps legacy geometry.", false);
  runCard(NotifyKind::Info, false, 8, "", false);
  check(reveals == 15, "slide cards reveal at exit and completion; replay reveals once");
  lv_display_delete(display);
  lv_deinit();
  printf("%u checks, %u failures\n", checks, failures);
  return failures != 0;
}
