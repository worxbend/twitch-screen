#include "ui_idle.h"

#include <Arduino.h>
#include <lvgl.h>

#include "assets/twitch_glitch.h"

// Layout for the 240x240 round panel (radius 120, center 120,120).
// Usable half-width at offset y from center: sqrt(120^2 - y^2) minus ~8 px
// of bezel/gauge margin. Everything below is placed to respect that curve:
//   y=70 -> ~89 px, y=86 -> ~75 px, y=90 -> ~71 px.

namespace {

constexpr uint32_t COUNT_ANIM_MS = 800;

const lv_color_t COL_BG = lv_color_hex(0x0E0E10);
const lv_color_t COL_PURPLE = lv_color_hex(0x9146FF);
const lv_color_t COL_TRACK = lv_color_hex(0x1F1F23);
const lv_color_t COL_TEXT = lv_color_hex(0xEFEFF1);
const lv_color_t COL_DIM = lv_color_hex(0x7A7A88);
const lv_color_t COL_LIVE = lv_color_hex(0xEB0400);

lv_obj_t *liveGroup = nullptr;
lv_obj_t *offlineGroup = nullptr;

lv_obj_t *edgeArc = nullptr;
lv_obj_t *liveDot = nullptr;
lv_obj_t *uptimeLabel = nullptr;
lv_obj_t *viewersValue = nullptr;
lv_obj_t *chipChat = nullptr;
lv_obj_t *connectGroup = nullptr;
lv_obj_t *connLabel = nullptr;

bool linkUp = false;

lv_image_dsc_t glitch48, glitch84;

bool curLive = false;
int32_t shownViewers = -1;

void initDsc(lv_image_dsc_t *d, const uint16_t *data, int size) {
  memset(d, 0, sizeof(*d));
#ifdef LV_IMAGE_HEADER_MAGIC
  d->header.magic = LV_IMAGE_HEADER_MAGIC;
#endif
  d->header.cf = LV_COLOR_FORMAT_RGB565;
  d->header.w = size;
  d->header.h = size;
  d->header.stride = size * 2;
  d->data_size = size * size * 2;
  d->data = (const uint8_t *)data;
}

lv_obj_t *makeLabel(lv_obj_t *parent, const lv_font_t *font, lv_color_t color,
                    lv_align_t align, int y) {
  lv_obj_t *l = lv_label_create(parent);
  lv_obj_set_style_text_font(l, font, 0);
  lv_obj_set_style_text_color(l, color, 0);
  lv_obj_align(l, align, 0, y);
  return l;
}

void clearDecor(lv_obj_t *o) {
  lv_obj_set_style_border_width(o, 0, 0);
  lv_obj_remove_style(o, nullptr, LV_PART_SCROLLBAR);
  lv_obj_clear_flag(o, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_clear_flag(o, LV_OBJ_FLAG_CLICKABLE);
}

void pulseAnim(void *var, int32_t v) {
  lv_obj_set_style_opa((lv_obj_t *)var, (lv_opa_t)v, 0);
}

void startPulse(lv_obj_t *obj, lv_opa_t lo, lv_opa_t hi, uint32_t ms) {
  lv_anim_t a;
  lv_anim_init(&a);
  lv_anim_set_var(&a, obj);
  lv_anim_set_values(&a, lo, hi);
  lv_anim_set_duration(&a, ms);
  lv_anim_set_exec_cb(&a, pulseAnim);
  lv_anim_set_path_cb(&a, lv_anim_path_ease_in_out);
  lv_anim_set_repeat_count(&a, LV_ANIM_REPEAT_INFINITE);
  lv_anim_set_playback_duration(&a, ms);
  lv_anim_start(&a);
}

void viewersAnim(void *var, int32_t v) {
  lv_label_set_text_fmt((lv_obj_t *)var, "%ld", (long)v);
}

void setViewers(uint32_t v) {
  if ((int32_t)v == shownViewers) return;
  lv_anim_delete(viewersValue, viewersAnim);
  lv_anim_t a;
  lv_anim_init(&a);
  lv_anim_set_var(&a, viewersValue);
  lv_anim_set_values(&a, shownViewers < 0 ? (int32_t)v : shownViewers, (int32_t)v);
  lv_anim_set_duration(&a, COUNT_ANIM_MS);
  lv_anim_set_exec_cb(&a, viewersAnim);
  lv_anim_set_path_cb(&a, lv_anim_path_ease_out);
  lv_anim_start(&a);
  shownViewers = (int32_t)v;
}

void formatK(char *buf, size_t n, uint32_t v) {
  if (v >= 1000) snprintf(buf, n, "%lu.%luK", (unsigned long)(v / 1000),
                          (unsigned long)(v % 1000 / 100));
  else snprintf(buf, n, "%lu", (unsigned long)v);
}

void formatUptime(char *buf, size_t n, uint32_t sec) {
  snprintf(buf, n, "%lu:%02lu:%02lu", (unsigned long)(sec / 3600),
           (unsigned long)(sec / 60 % 60), (unsigned long)(sec % 60));
}

void buildLive(lv_obj_t *scr) {
  liveGroup = lv_obj_create(scr);
  lv_obj_set_size(liveGroup, 240, 240);
  lv_obj_center(liveGroup);
  lv_obj_set_style_bg_opa(liveGroup, LV_OPA_TRANSP, 0);
  lv_obj_set_style_pad_all(liveGroup, 0, 0);
  clearDecor(liveGroup);

  // Full-ring chat-activity gauge hugging the round edge.
  edgeArc = lv_arc_create(liveGroup);
  lv_obj_set_size(edgeArc, 234, 234);
  lv_obj_center(edgeArc);
  lv_arc_set_rotation(edgeArc, 270);
  lv_arc_set_bg_angles(edgeArc, 0, 360);
  lv_arc_set_range(edgeArc, 0, 100);
  lv_arc_set_value(edgeArc, 0);
  lv_obj_set_style_arc_width(edgeArc, 3, LV_PART_MAIN);
  lv_obj_set_style_arc_width(edgeArc, 3, LV_PART_INDICATOR);
  lv_obj_set_style_arc_color(edgeArc, COL_TRACK, LV_PART_MAIN);
  lv_obj_set_style_arc_color(edgeArc, COL_PURPLE, LV_PART_INDICATOR);
  lv_obj_set_style_arc_rounded(edgeArc, true, LV_PART_INDICATOR);
  lv_obj_remove_style(edgeArc, nullptr, LV_PART_KNOB);
  lv_obj_clear_flag(edgeArc, LV_OBJ_FLAG_CLICKABLE);

  // LIVE pill.
  lv_obj_t *pill = lv_obj_create(liveGroup);
  lv_obj_set_size(pill, 62, 20);
  lv_obj_align(pill, LV_ALIGN_CENTER, 0, -90);
  lv_obj_set_style_bg_color(pill, COL_LIVE, 0);
  lv_obj_set_style_radius(pill, 10, 0);
  lv_obj_set_style_pad_all(pill, 0, 0);
  clearDecor(pill);

  liveDot = lv_obj_create(pill);
  lv_obj_set_size(liveDot, 7, 7);
  lv_obj_align(liveDot, LV_ALIGN_LEFT_MID, 10, 0);
  lv_obj_set_style_bg_color(liveDot, lv_color_white(), 0);
  lv_obj_set_style_radius(liveDot, LV_RADIUS_CIRCLE, 0);
  clearDecor(liveDot);
  startPulse(liveDot, 255, 60, 700);

  lv_obj_t *liveTxt = makeLabel(pill, &lv_font_montserrat_14,
                                lv_color_white(), LV_ALIGN_LEFT_MID, 0);
  lv_obj_set_x(liveTxt, 22);
  lv_label_set_text(liveTxt, "LIVE");

  uptimeLabel = makeLabel(liveGroup, &lv_font_montserrat_14, COL_DIM,
                          LV_ALIGN_CENTER, -70);
  lv_label_set_text(uptimeLabel, "0:00:00");

  lv_obj_t *logo = lv_image_create(liveGroup);
  lv_image_set_src(logo, &glitch48);
  lv_obj_align(logo, LV_ALIGN_CENTER, 0, -32);

  viewersValue = makeLabel(liveGroup, &lv_font_montserrat_48, COL_TEXT,
                           LV_ALIGN_CENTER, 8);
  lv_label_set_text(viewersValue, "0");

  lv_obj_t *caption = makeLabel(liveGroup, &lv_font_montserrat_14, COL_DIM,
                                LV_ALIGN_CENTER, 44);
  lv_obj_set_style_text_letter_space(caption, 2, 0);
  lv_label_set_text(caption, "VIEWERS");

  // Sole bottom stat: total chat messages, centered on the vertical axis.
  chipChat = makeLabel(liveGroup, &lv_font_montserrat_20, COL_TEXT,
                       LV_ALIGN_CENTER, 70);
  lv_obj_t *chatCap = makeLabel(liveGroup, &lv_font_montserrat_14, COL_DIM,
                                LV_ALIGN_CENTER, 89);
  lv_obj_set_style_text_letter_space(chatCap, 1, 0);
  lv_label_set_text(chatCap, "MSG");
}

void buildOffline(lv_obj_t *scr) {
  offlineGroup = lv_obj_create(scr);
  lv_obj_set_size(offlineGroup, 240, 240);
  lv_obj_center(offlineGroup);
  lv_obj_set_style_bg_opa(offlineGroup, LV_OPA_TRANSP, 0);
  lv_obj_set_style_pad_all(offlineGroup, 0, 0);
  clearDecor(offlineGroup);

  lv_obj_t *logo = lv_image_create(offlineGroup);
  lv_image_set_src(logo, &glitch84);
  lv_obj_set_style_opa(logo, 70, 0);
  lv_obj_align(logo, LV_ALIGN_CENTER, 0, -20);

  lv_obj_t *off = makeLabel(offlineGroup, &lv_font_montserrat_28, COL_DIM,
                            LV_ALIGN_CENTER, 40);
  lv_obj_set_style_text_letter_space(off, 2, 0);
  lv_label_set_text(off, "OFFLINE");

  lv_obj_t *sub = makeLabel(offlineGroup, &lv_font_montserrat_14, COL_DIM,
                            LV_ALIGN_CENTER, 66);
  lv_label_set_text(sub, "waiting to go live");

  lv_obj_add_flag(offlineGroup, LV_OBJ_FLAG_HIDDEN);
}

lv_obj_t *makeGroup(lv_obj_t *scr) {
  lv_obj_t *g = lv_obj_create(scr);
  lv_obj_set_size(g, 240, 240);
  lv_obj_center(g);
  lv_obj_set_style_bg_opa(g, LV_OPA_TRANSP, 0);
  lv_obj_set_style_pad_all(g, 0, 0);
  clearDecor(g);
  return g;
}

void spinAnim(void *var, int32_t v) {
  lv_arc_set_rotation((lv_obj_t *)var, v);
}

void dotsCb(lv_timer_t *) {
  static uint8_t n = 0;
  n = (n + 1) % 4;
  lv_label_set_text(connLabel, n == 0 ? "CONNECTING" : n == 1 ? "CONNECTING."
                                   : n == 2 ? "CONNECTING.." : "CONNECTING...");
}

void buildConnect(lv_obj_t *scr) {
  connectGroup = makeGroup(scr);

  // Purple spinner arc orbiting the logo.
  lv_obj_t *spinner = lv_arc_create(connectGroup);
  lv_obj_set_size(spinner, 140, 140);
  lv_obj_align(spinner, LV_ALIGN_CENTER, 0, -14);
  lv_arc_set_bg_angles(spinner, 0, 80);
  lv_arc_set_range(spinner, 0, 100);
  lv_arc_set_value(spinner, 100);
  lv_obj_set_style_arc_width(spinner, 5, LV_PART_MAIN);
  lv_obj_set_style_arc_width(spinner, 5, LV_PART_INDICATOR);
  lv_obj_set_style_arc_color(spinner, COL_TRACK, LV_PART_MAIN);
  lv_obj_set_style_arc_color(spinner, COL_PURPLE, LV_PART_INDICATOR);
  lv_obj_set_style_arc_rounded(spinner, true, LV_PART_INDICATOR);
  lv_obj_remove_style(spinner, nullptr, LV_PART_KNOB);
  lv_obj_clear_flag(spinner, LV_OBJ_FLAG_CLICKABLE);

  lv_anim_t a;
  lv_anim_init(&a);
  lv_anim_set_var(&a, spinner);
  lv_anim_set_values(&a, 0, 360);
  lv_anim_set_duration(&a, 1400);
  lv_anim_set_exec_cb(&a, spinAnim);
  lv_anim_set_repeat_count(&a, LV_ANIM_REPEAT_INFINITE);
  lv_anim_start(&a);

  lv_obj_t *logo = lv_image_create(connectGroup);
  lv_image_set_src(logo, &glitch84);
  lv_obj_align(logo, LV_ALIGN_CENTER, 0, -14);
  startPulse(logo, 110, 255, 1200);  // breathing glow

  connLabel = makeLabel(connectGroup, &lv_font_montserrat_20, COL_DIM,
                        LV_ALIGN_CENTER, 72);
  lv_label_set_text(connLabel, "CONNECTING");
  lv_timer_create(dotsCb, 450, nullptr);
}

void applyVisibility() {
  if (!linkUp) {
    lv_obj_remove_flag(connectGroup, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(liveGroup, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(offlineGroup, LV_OBJ_FLAG_HIDDEN);
    return;
  }
  lv_obj_add_flag(connectGroup, LV_OBJ_FLAG_HIDDEN);
  if (curLive) {
    lv_obj_remove_flag(liveGroup, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(offlineGroup, LV_OBJ_FLAG_HIDDEN);
  } else {
    lv_obj_add_flag(liveGroup, LV_OBJ_FLAG_HIDDEN);
    lv_obj_remove_flag(offlineGroup, LV_OBJ_FLAG_HIDDEN);
  }
}

}  // namespace

void uiIdleBuild() {
  initDsc(&glitch48, twitch_glitch_48, 48);
  initDsc(&glitch84, twitch_glitch_84, 84);

  lv_obj_t *scr = lv_screen_active();
  lv_obj_set_style_bg_color(scr, COL_BG, 0);

  buildLive(scr);
  buildOffline(scr);
  buildConnect(scr);
  applyVisibility();  // starts in CONNECTING state until the link is up
}

void uiIdleSetStats(const StreamStats &s) {
  if (s.live != curLive) {
    curLive = s.live;
    applyVisibility();
  }
  if (!s.live) return;

  char buf[16];
  formatUptime(buf, sizeof(buf), s.uptimeSec);
  lv_label_set_text(uptimeLabel, buf);

  setViewers(s.viewers);
  lv_arc_set_value(edgeArc, (int32_t)(s.chatRate > 100 ? 100 : s.chatRate));

  formatK(buf, sizeof(buf), s.msgTotal);
  lv_label_set_text(chipChat, buf);
}

void uiIdleSetOnline(bool online) {
  if (!connectGroup || online == linkUp) return;
  linkUp = online;
  applyVisibility();
}
