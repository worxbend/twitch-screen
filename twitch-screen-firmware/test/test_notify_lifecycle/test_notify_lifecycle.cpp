#include <lvgl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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
  // Replay/chat already entered Hold at pending-to-active transfer.
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
void pump(unsigned count = 1) {
  for (unsigned i = 0; i < count; ++i) {
    now += 5;
    controller.reconcile(now, false);
    uiNotifySetBacklog(controller.pendingCount());
    lv_tick_inc(5);
    lv_timer_handler();
  }
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
void chatBurst() {
  controller = desk::Controller(); controller.legacyWelcome();
  Notification n; n.kind = NotifyKind::Chat; n.seq = 1;
  snprintf(n.actor, sizeof(n.actor), "Synthetic chatter");
  snprintf(n.text, sizeof(n.text), "Burst message 1");
  check(controller.legacyAdmit(n) == desk::Admission::Enqueued, "first burst chat admitted");
  desk::Card out;
  check(controller.start(now, out), "first burst chat starts");
  uiNotifySetBacklog(0); uiNotifyShow(out.notification);
  check(controller.phase() == desk::Phase::Hold, "real chat renderer enters hold immediately");
  for (uint32_t seq = 2; seq <= 9; ++seq) {
    n.seq = seq; snprintf(n.text, sizeof(n.text), "Burst message %lu", (unsigned long)seq);
    check(controller.legacyAdmit(n) == desk::Admission::Enqueued, "burst fills bounded FIFO without dropping");
  }
  const uint64_t firstStart = now;
  for (uint32_t seq = 1; seq <= 9; ++seq) {
    const uint64_t started = seq == 1 ? firstStart : now;
    const size_t pending = controller.pendingCount();
    lv_obj_t *overlay = lv_obj_get_child(lv_layer_top(), 0);
    lv_obj_t *body = lv_obj_get_child(overlay, 4);
    char expected[32]; snprintf(expected, sizeof(expected), "Burst message %lu", (unsigned long)seq);
    check(strcmp(lv_label_get_text(body), expected) == 0 && !lv_obj_is_hidden(overlay),
          "every FIFO message reaches the visible LVGL label unchanged");
    const uint64_t budget = pending >= 3 ? 250 : pending ? 500 : 2500;
    while (uiNotifyBusy() && now - started < budget + 100) pump();
    check(!uiNotifyBusy() && !controller.active(), "real LVGL callback completes each accelerated chat");
    check(now - started >= budget && now - started <= budget + 35, "real chat hold follows pressure budget without slide overhead");
    if (seq < 9) {
      check(controller.start(now, out) && out.notification.seq == seq + 1, "real burst consumption preserves FIFO");
      uiNotifySetBacklog(controller.pendingCount()); uiNotifyShow(out.notification);
    }
  }
  check(controller.pendingCount() == 0 && controller.refusedTotal() == 0, "all nine burst messages consumed without refusal");
}
void chatTimerEdges() {
  auto start = [](uint16_t ttl, bool replay, size_t waiting) {
    check(!uiNotifyBusy(), "edge scenario starts with no stale UI timer");
    controller = desk::Controller(); controller.legacyWelcome();
    Notification n; n.kind = NotifyKind::Chat; n.seq = 1; n.ttl_ds = ttl; n.replay = replay;
    snprintf(n.text, sizeof(n.text), "Timer edge chat");
    check(controller.legacyAdmit(n) == desk::Admission::Enqueued, "timer edge chat admitted");
    for (size_t i = 0; i < waiting; ++i) {
      n.seq = uint32_t(i + 2); n.kind = i % 2 ? NotifyKind::Alert : NotifyKind::Follow;
      check(controller.legacyAdmit(n) == desk::Admission::Enqueued, "mixed timer edge backlog admitted");
    }
    // Render loop propagates pressure both before and after transfer. Three
    // pending including active becomes two waiting: 500 ms, not 250 ms.
    uiNotifySetBacklog(controller.pendingCount());
    desk::Card out; check(controller.start(now, out), "timer edge transfers pending to active");
    uiNotifySetBacklog(controller.pendingCount()); uiNotifyShow(out.notification);
  };
  auto finish = [](uint64_t started, uint64_t budget) {
    const unsigned oldExit = exits, oldComplete = completions, oldReveal = reveals;
    while (uiNotifyBusy() && now - started < budget + 40) pump();
    check(!uiNotifyBusy() && !controller.active() && now - started >= budget && now - started <= budget + 35,
          "chat timer completes at its original-start budget without slide overhead");
    check(exits == oldExit + 1 && completions == oldComplete + 1 && reveals == oldReveal + 1,
          "instant chat exits/completes/reveals once in lifecycle order");
    for (unsigned i = 0; i < 10; ++i) pump();
    check(exits == oldExit + 1 && completions == oldComplete + 1,
          "deleted hold timer cannot fire again after completion");
  };
  for (bool replay : {false, true}) for (uint16_t ttl : {uint16_t(1), uint16_t(20)}) {
    start(ttl, replay, 3); const uint64_t started = now;
    pump(10); // 50 ms: changing pressure cannot cap an explicit wire hold.
    uiNotifySetBacklog(0); uiNotifySetBacklog(8);
    check(controller.remaining(now) == uint32_t(ttl) * 100u - 50u && uiNotifyBusy(),
          "explicit live/replay chat TTL survives maximum and drained pressure in real UI");
    finish(started, uint32_t(ttl) * 100u);
  }
  start(0, false, 2); const uint64_t thresholdStart = now;
  finish(thresholdStart, 500);

  start(0, false, 0); const uint64_t escalatedStart = now;
  pump(20); // 100 ms quiet.
  Notification n; n.kind = NotifyKind::Follow; n.seq = 2;
  check(controller.legacyAdmit(n) == desk::Admission::Enqueued, "late audience successor applies chat pressure");
  controller.reconcile(now, false); uiNotifySetBacklog(controller.pendingCount());
  check(controller.remaining(now) == 400 && uiNotifyBusy(), "late pressure uses original chat start");
  pump(20); // 200 ms from start, not from pressure arrival.
  n.seq = 3; controller.legacyAdmit(n); n.seq = 4; controller.legacyAdmit(n);
  controller.reconcile(now, false); uiNotifySetBacklog(controller.pendingCount());
  check(controller.remaining(now) == 50 && uiNotifyBusy(), "escalating pressure further shortens the existing real timer");
  finish(escalatedStart, 250);

  start(0, true, 0); const uint64_t lateStart = now;
  pump(120); // Pressure arrives after both busy-chat caps, before quiet expiry.
  n.seq = 2; check(controller.legacyAdmit(n) == desk::Admission::Enqueued, "very late successor admitted");
  controller.reconcile(now, false); uiNotifySetBacklog(controller.pendingCount());
  check(uiNotifyBusy() && controller.remaining(now) == 0 && controller.phase() == desk::Phase::Hold,
        "late cap readies LVGL timer without bypassing real callback lifecycle");
  finish(lateStart, 600); // Already elapsed: no new 500 ms grace period.

  // Keep controller's extended clock and LVGL's raw clock aligned through wrap.
  const uint32_t toWrap = (UINT32_MAX - 100u) - lv_tick_get();
  now += toWrap; lv_tick_inc(toWrap);
  start(0, false, 0); const uint64_t wrapStart = now;
  pump(40);
  n.seq = 2; controller.legacyAdmit(n);
  controller.reconcile(now, false); uiNotifySetBacklog(controller.pendingCount());
  check(lv_tick_get() < 1000 && controller.remaining(now) == 300,
        "real LVGL hold elapsed subtraction remains correct after raw tick wrap");
  finish(wrapStart, 500);
  start(0, false, 0); const uint64_t quietStart = now;
  finish(quietStart, 2500); // Prior card pressure and timer period must not leak.

  for (NotifyKind kind : {NotifyKind::Follow, NotifyKind::Warning, NotifyKind::Alert}) {
    controller = desk::Controller(); controller.legacyWelcome();
    Notification other; other.seq = 1; other.kind = kind;
    controller.legacyAdmit(other);
    for (uint32_t seq = 2; seq <= 4; ++seq) {
      Notification queued; queued.seq = seq; queued.kind = NotifyKind::Chat;
      check(controller.legacyAdmit(queued) == desk::Admission::Enqueued, "chat queues behind non-chat without preempting");
    }
    desk::Card out; check(controller.start(now, out), "mixed non-chat card starts");
    uiNotifySetBacklog(controller.pendingCount()); uiNotifyShow(out.notification);
    const uint64_t started = now;
    const uint64_t occupancy = kind == NotifyKind::Follow ? 4130 : 4930;
    check(controller.remaining(now) == occupancy, "controller occupancy matches real mixed-kind animation budget");
    const unsigned oldExit = exits, oldComplete = completions, oldReveal = reveals;
    pump(100);
    check(uiNotifyBusy() && controller.active() && controller.active()->notification.kind == kind && exits == oldExit,
          "chat queue pressure cannot cap audience/severity timers or bypass their entrance");
    while (uiNotifyBusy() && now - started < occupancy + 200) pump();
    if (now - started < occupancy || now - started > occupancy + 150)
      printf("mixed kind=%u elapsed=%llu expected=%llu..%llu\n", static_cast<unsigned>(kind),
             static_cast<unsigned long long>(now - started), static_cast<unsigned long long>(occupancy),
             static_cast<unsigned long long>(occupancy + 150));
    check(!uiNotifyBusy() && !controller.active() && now - started >= occupancy && now - started <= occupancy + 150,
          "mixed-kind real audience/severity keeps original full entrance hold and exit");
    check(exits == oldExit + 1 && completions == oldComplete + 1 && reveals == oldReveal + 2,
          "mixed-kind sliding lifecycle still reveals at exit and completion exactly once each");
  }
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
  chatBurst(); chatTimerEdges();
  lv_display_delete(display);
  lv_deinit();
  printf("%u checks, %u failures\n", checks, failures);
  return failures != 0;
}
