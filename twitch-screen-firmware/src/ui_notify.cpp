#include "ui_notify.h"

#include <lvgl.h>

// Full-screen event card for the 240x240 round panel. Content stays inside
// the circle: nothing wider than ~170 px below the vertical center, and the
// accent ring hugs the edge.

namespace {

constexpr uint32_t SLIDE_IN_MS = 350;
constexpr uint32_t SLIDE_OUT_MS = 280;
constexpr uint32_t HOLD_MS = 3500;
constexpr uint32_t ALERT_MS = 200;  // one flash pulse half-period

lv_obj_t *overlay = nullptr;
lv_obj_t *flash = nullptr;
lv_obj_t *ring = nullptr;
lv_obj_t *iconCircle = nullptr;
lv_obj_t *iconLabel = nullptr;
lv_obj_t *kindTxt = nullptr;
lv_obj_t *titleLabel = nullptr;
lv_obj_t *bodyLabel = nullptr;
lv_obj_t *seqLabel = nullptr;

bool busy = false;

void animY(void *var, int32_t v) { lv_obj_set_y((lv_obj_t *)var, v); }
void animOpa(void *var, int32_t v) {
  lv_obj_set_style_opa((lv_obj_t *)var, (lv_opa_t)v, 0);
}

void animate(lv_obj_t *obj, lv_anim_exec_xcb_t cb, int32_t from, int32_t to,
             uint32_t duration, lv_anim_path_cb_t path, lv_anim_ready_cb_t ready) {
  lv_anim_t a;
  lv_anim_init(&a);
  lv_anim_set_var(&a, obj);
  lv_anim_set_values(&a, from, to);
  lv_anim_set_duration(&a, duration);
  lv_anim_set_exec_cb(&a, cb);
  lv_anim_set_path_cb(&a, path);
  if (ready) lv_anim_set_ready_cb(&a, ready);
  lv_anim_start(&a);
}

void hideReady(lv_anim_t *) {
  lv_obj_add_flag(overlay, LV_OBJ_FLAG_HIDDEN);
  busy = false;
}

void hideStart() {
  lv_anim_delete(ring, animOpa);  // stop the hold-time ring pulse
  lv_obj_set_style_opa(ring, LV_OPA_COVER, 0);
  animate(overlay, animY, 0, 240, SLIDE_OUT_MS, lv_anim_path_ease_in, hideReady);
  animate(overlay, animOpa, LV_OPA_COVER, LV_OPA_TRANSP, SLIDE_OUT_MS,
          lv_anim_path_ease_in, nullptr);
}

void holdTimerCb(lv_timer_t *t) {
  lv_timer_delete(t);
  hideStart();
}

void showReady(lv_anim_t *) { lv_timer_create(holdTimerCb, HOLD_MS, nullptr); }

// Alert blink finished: drop the flash, slide the card in, and keep the
// accent ring breathing while the card is up.
void flashDone(lv_anim_t *) {
  lv_obj_add_flag(flash, LV_OBJ_FLAG_HIDDEN);
  lv_obj_remove_flag(overlay, LV_OBJ_FLAG_HIDDEN);
  animate(overlay, animY, 240, 0, SLIDE_IN_MS, lv_anim_path_ease_out, showReady);
  animate(overlay, animOpa, LV_OPA_TRANSP, LV_OPA_COVER, SLIDE_IN_MS,
          lv_anim_path_ease_out, nullptr);

  lv_anim_t a;
  lv_anim_init(&a);
  lv_anim_set_var(&a, ring);
  lv_anim_set_values(&a, LV_OPA_COVER, 100);
  lv_anim_set_duration(&a, 600);
  lv_anim_set_exec_cb(&a, animOpa);
  lv_anim_set_path_cb(&a, lv_anim_path_ease_in_out);
  lv_anim_set_repeat_count(&a, LV_ANIM_REPEAT_INFINITE);
  lv_anim_set_playback_duration(&a, 600);
  lv_anim_start(&a);
}

// Bold event letter inside the colored disc (Twitch semantic colors).
const char *kindIcon(NotifyKind k) {
  switch (k) {
    case NotifyKind::Follow:  return "F";
    case NotifyKind::Sub:     return "S";
    case NotifyKind::Gift:    return "G";
    case NotifyKind::Raid:    return "R";
    case NotifyKind::Chat:    return "C";
    case NotifyKind::Bits:    return "B";
    case NotifyKind::Message: return "M";
    case NotifyKind::Warning: return "!";
    case NotifyKind::Alert:   return "!";
    default:                  return "i";
  }
}

lv_color_t kindLvColor(NotifyKind k) {
  switch (k) {
    case NotifyKind::Follow:  return lv_color_hex(0x9146FF);  // twitch purple
    case NotifyKind::Sub:     return lv_color_hex(0xFFB300);  // gold
    case NotifyKind::Gift:    return lv_color_hex(0xFF75E6);  // pink
    case NotifyKind::Raid:    return lv_color_hex(0xEB0400);  // live red
    case NotifyKind::Chat:    return lv_color_hex(0x26C6DA);  // cyan
    case NotifyKind::Bits:    return lv_color_hex(0xBF94FF);  // light purple
    case NotifyKind::Message: return lv_color_hex(0x66BB6A);
    case NotifyKind::Warning: return lv_color_hex(0xFFA726);
    case NotifyKind::Alert:   return lv_color_hex(0xEF5350);
    default:                  return lv_color_hex(0x26C6DA);
  }
}

void clearDecor(lv_obj_t *o) {
  lv_obj_set_style_border_width(o, 0, 0);
  lv_obj_remove_style(o, nullptr, LV_PART_SCROLLBAR);
  lv_obj_clear_flag(o, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_clear_flag(o, LV_OBJ_FLAG_CLICKABLE);
}

}  // namespace

void uiNotifyInit() {
  overlay = lv_obj_create(lv_layer_top());
  lv_obj_set_size(overlay, 240, 240);
  lv_obj_center(overlay);
  lv_obj_set_style_bg_color(overlay, lv_color_black(), 0);
  lv_obj_set_style_bg_opa(overlay, LV_OPA_COVER, 0);
  lv_obj_set_style_pad_all(overlay, 0, 0);
  lv_obj_set_style_radius(overlay, LV_RADIUS_CIRCLE, 0);
  clearDecor(overlay);

  // Circular accent ring hugging the round panel edge.
  ring = lv_obj_create(overlay);
  lv_obj_set_size(ring, 232, 232);
  lv_obj_center(ring);
  lv_obj_set_style_bg_opa(ring, LV_OPA_TRANSP, 0);
  lv_obj_set_style_border_width(ring, 3, 0);
  lv_obj_set_style_radius(ring, LV_RADIUS_CIRCLE, 0);
  clearDecor(ring);
  lv_obj_set_style_border_width(ring, 3, 0);  // clearDecor zeroed it

  iconCircle = lv_obj_create(overlay);
  lv_obj_set_size(iconCircle, 64, 64);
  lv_obj_align(iconCircle, LV_ALIGN_CENTER, 0, -54);
  lv_obj_set_style_radius(iconCircle, LV_RADIUS_CIRCLE, 0);
  clearDecor(iconCircle);

  iconLabel = lv_label_create(iconCircle);
  lv_obj_set_style_text_color(iconLabel, lv_color_black(), 0);
  lv_obj_set_style_text_font(iconLabel, &lv_font_montserrat_28, 0);
  lv_obj_center(iconLabel);

  kindTxt = lv_label_create(overlay);
  lv_obj_set_style_text_font(kindTxt, &lv_font_montserrat_14, 0);
  lv_obj_set_style_text_letter_space(kindTxt, 2, 0);
  lv_obj_align(kindTxt, LV_ALIGN_CENTER, 0, -6);

  titleLabel = lv_label_create(overlay);
  lv_obj_set_style_text_font(titleLabel, &lv_font_montserrat_20, 0);
  lv_obj_set_style_text_color(titleLabel, lv_color_white(), 0);
  lv_obj_align(titleLabel, LV_ALIGN_CENTER, 0, 22);

  bodyLabel = lv_label_create(overlay);
  lv_obj_set_size(bodyLabel, 170, 36);  // ~2 wrapped lines, clipped beyond
  lv_label_set_long_mode(bodyLabel, LV_LABEL_LONG_WRAP);
  lv_obj_set_style_text_align(bodyLabel, LV_TEXT_ALIGN_CENTER, 0);
  lv_obj_set_style_text_color(bodyLabel, lv_color_hex(0x9E9E9E), 0);
  lv_obj_align(bodyLabel, LV_ALIGN_CENTER, 0, 52);

  seqLabel = lv_label_create(overlay);
  lv_obj_set_style_text_color(seqLabel, lv_color_hex(0x616161), 0);
  lv_obj_align(seqLabel, LV_ALIGN_BOTTOM_MID, 0, -18);

  lv_obj_add_flag(overlay, LV_OBJ_FLAG_HIDDEN);

  // Full-screen alert flash (above the overlay): blinks in the event's
  // accent color before the card slides in, to catch peripheral vision.
  flash = lv_obj_create(lv_layer_top());
  lv_obj_set_size(flash, 240, 240);
  lv_obj_center(flash);
  lv_obj_set_style_bg_opa(flash, LV_OPA_COVER, 0);
  lv_obj_set_style_opa(flash, LV_OPA_TRANSP, 0);
  lv_obj_set_style_radius(flash, LV_RADIUS_CIRCLE, 0);
  clearDecor(flash);
  lv_obj_add_flag(flash, LV_OBJ_FLAG_HIDDEN);
}

bool uiNotifyBusy() { return busy; }

void uiNotifyShow(const Notification &n) {
  if (busy || !overlay) return;
  busy = true;

  lv_color_t accent = kindLvColor(n.kind);
  lv_obj_set_style_border_color(ring, accent, 0);
  lv_obj_set_style_bg_color(iconCircle, accent, 0);
  lv_obj_set_style_text_color(kindTxt, accent, 0);

  lv_label_set_text(iconLabel, kindIcon(n.kind));
  lv_label_set_text(kindTxt, kindLabel(n.kind));
  lv_label_set_text(titleLabel, n.title);
  lv_label_set_text(bodyLabel, n.body);
  lv_label_set_text_fmt(seqLabel, "#%lu", (unsigned long)n.seq);

  // Attention grab: blink the whole panel in the accent color (3 pulses,
  // ~840 ms total), then flashDone slides the card in.
  lv_obj_set_style_bg_color(flash, accent, 0);
  lv_obj_remove_flag(flash, LV_OBJ_FLAG_HIDDEN);
  lv_anim_t a;
  lv_anim_init(&a);
  lv_anim_set_var(&a, flash);
  lv_anim_set_values(&a, LV_OPA_TRANSP, 230);
  lv_anim_set_duration(&a, ALERT_MS);
  lv_anim_set_exec_cb(&a, animOpa);
  lv_anim_set_path_cb(&a, lv_anim_path_ease_in_out);
  lv_anim_set_playback_duration(&a, ALERT_MS);
  lv_anim_set_repeat_count(&a, 2);  // 3 pulses total
  lv_anim_set_ready_cb(&a, flashDone);
  lv_anim_start(&a);
}
