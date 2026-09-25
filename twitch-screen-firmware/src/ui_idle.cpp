#include "ui_idle.h"

#include <Arduino.h>
#include <lvgl.h>

#include "assets/twitch_glitch.h"
#include "presentation.h"

// Layout for the 240x240 round panel (radius 120, center 120,120).
// Usable half-width at offset y from center: sqrt(120^2 - y^2) minus ~8 px
// of bezel/gauge margin. Everything below is placed to respect that curve:
//   y=62 -> ~94 px, y=80 -> ~81 px, y=90 -> ~71 px.
// The edge gauge is an arc of outer radius 117 and width 3, so nothing may
// reach past r = 114; the bottom chip row is placed at r_max = 109.

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
lv_obj_t *chipFoll = nullptr;
lv_obj_t *chipSubs = nullptr;
// §6.5's "the last value stays on screen until the next stream begins": the same
// three totals, on the offline screen, because that is where they have to survive.
lv_obj_t *offChipChat = nullptr;
lv_obj_t *offChipFoll = nullptr;
lv_obj_t *offChipSubs = nullptr;
lv_obj_t *connectGroup = nullptr;
lv_obj_t *connLabel = nullptr;

bool linkUp = false;

lv_image_dsc_t glitch48, glitch84;

bool curLive = false;
int64_t shownViewers = -1;

// §6.5 local uptime ticking. uptime_s is a snapshot taken at server_time and
// STATS only arrives every 5 s, so the label would step in 5 s jumps. Anchor
// the value against millis() on receipt and re-anchor on every STATS, which
// makes it tick once a second without ever drifting away from the relay.
uint32_t uptimeBase = 0;
uint32_t uptimeAnchorMs = 0;
bool uptimeValid = false;

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

lv_obj_t *makeLabelAt(lv_obj_t *parent, const lv_font_t *font, lv_color_t color,
                      lv_align_t align, int x, int y) {
  lv_obj_t *l = lv_label_create(parent);
  lv_obj_set_style_text_font(l, font, 0);
  lv_obj_set_style_text_color(l, color, 0);
  lv_obj_align(l, align, x, y);
  return l;
}

lv_obj_t *makeLabel(lv_obj_t *parent, const lv_font_t *font, lv_color_t color,
                    lv_align_t align, int y) {
  return makeLabelAt(parent, font, color, align, 0, y);
}

void clearDecor(lv_obj_t *o) {
  lv_obj_set_style_border_width(o, 0, 0);
  lv_obj_remove_style(o, nullptr, LV_PART_SCROLLBAR);
  lv_obj_set_scrollable(o, false);
  lv_obj_set_clickable(o, false);
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
  char text[8];
  formatCount(text, sizeof(text), v < 0 ? 0u : (uint32_t)v);
  lv_label_set_text((lv_obj_t *)var, text);
}

void setViewers(uint32_t v) {
  if ((int64_t)v == shownViewers) return;
  lv_anim_delete(viewersValue, viewersAnim);
  if (v >= 10000) {
    char text[8];
    formatCount(text, sizeof(text), v);
    lv_label_set_text(viewersValue, text);
    shownViewers = v;
    return;
  }
  lv_anim_t a;
  lv_anim_init(&a);
  lv_anim_set_var(&a, viewersValue);
  lv_anim_set_values(&a, shownViewers < 0 || shownViewers >= 10000 ? (int32_t)v : (int32_t)shownViewers, (int32_t)v);
  lv_anim_set_duration(&a, COUNT_ANIM_MS);
  lv_anim_set_exec_cb(&a, viewersAnim);
  lv_anim_set_path_cb(&a, lv_anim_path_ease_out);
  lv_anim_start(&a);
  shownViewers = v;
}

void formatUptime(char *buf, size_t n, uint32_t sec) {
  snprintf(buf, n, "%lu:%02lu:%02lu", (unsigned long)(sec / 3600),
           (unsigned long)(sec / 60 % 60), (unsigned long)(sec % 60));
}

void renderUptime() {
  if (!uptimeValid) return;
  const uint32_t sec = uptimeBase + (millis() - uptimeAnchorMs) / 1000;
  char buf[16];
  formatUptime(buf, sizeof(buf), sec);
  lv_label_set_text(uptimeLabel, buf);
}

void uptimeTickCb(lv_timer_t *) {
  if (!curLive) return;
  renderUptime();
}

// Bottom stat row: the three numbers that matter while live, plus the two the
// relay has always sent and the v2 screen threw away (STATS +12 followers,
// +16 subs). Values at y=62, captions at y=80, x = -50 / 0 / +50 — the far
// corner of the outermost caption sits at r = 109, inside the edge gauge.
lv_obj_t *makeChip(lv_obj_t *parent, int x, const char *caption) {
  lv_obj_t *value = makeLabelAt(parent, &lv_font_montserrat_14, COL_TEXT,
                                LV_ALIGN_CENTER, x, 62);
  lv_label_set_text(value, "0");
  lv_obj_t *cap = makeLabelAt(parent, &lv_font_montserrat_14, COL_DIM,
                              LV_ALIGN_CENTER, x, 80);
  lv_obj_set_style_text_letter_space(cap, 1, 0);
  lv_label_set_text(cap, caption);
  return value;
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
  lv_obj_set_clickable(edgeArc, false);

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

  // Time since stream start — one of the three numbers the screen exists for.
  uptimeLabel = makeLabel(liveGroup, &lv_font_montserrat_14, COL_DIM,
                          LV_ALIGN_CENTER, -70);
  lv_label_set_text(uptimeLabel, "0:00:00");
  lv_timer_create(uptimeTickCb, 1000, nullptr);

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

  chipFoll = makeChip(liveGroup, -50, "FLW");
  chipChat = makeChip(liveGroup, 0, "MSG");
  chipSubs = makeChip(liveGroup, 50, "SUB");
}

void buildOffline(lv_obj_t *scr) {
  offlineGroup = lv_obj_create(scr);
  lv_obj_set_size(offlineGroup, 240, 240);
  lv_obj_center(offlineGroup);
  lv_obj_set_style_bg_opa(offlineGroup, LV_OPA_TRANSP, 0);
  lv_obj_set_style_pad_all(offlineGroup, 0, 0);
  clearDecor(offlineGroup);

  // Everything moves up to make room for the stat row at the same y as the live
  // screen's (values 62, captions 80), so the two screens do not shuffle the
  // numbers around when a stream ends. The 84 px logo centred at -52 spans
  // -94..-10, clear of OFFLINE at 8 and of the round bezel at r = 114.
  lv_obj_t *logo = lv_image_create(offlineGroup);
  lv_image_set_src(logo, &glitch84);
  lv_obj_set_style_opa(logo, 70, 0);
  lv_obj_align(logo, LV_ALIGN_CENTER, 0, -52);

  lv_obj_t *off = makeLabel(offlineGroup, &lv_font_montserrat_28, COL_DIM,
                            LV_ALIGN_CENTER, 8);
  lv_obj_set_style_text_letter_space(off, 2, 0);
  lv_label_set_text(off, "OFFLINE");

  lv_obj_t *sub = makeLabel(offlineGroup, &lv_font_montserrat_14, COL_DIM,
                            LV_ALIGN_CENTER, 34);
  lv_label_set_text(sub, "waiting to go live");

  offChipFoll = makeChip(offlineGroup, -50, "FLW");
  offChipChat = makeChip(offlineGroup, 0, "MSG");
  offChipSubs = makeChip(offlineGroup, 50, "SUB");

  lv_obj_set_hidden(offlineGroup, true);
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
  lv_obj_set_clickable(spinner, false);

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
    lv_obj_set_hidden(connectGroup, false);
    lv_obj_set_hidden(liveGroup, true);
    lv_obj_set_hidden(offlineGroup, true);
    return;
  }
  lv_obj_set_hidden(connectGroup, true);
  if (curLive) {
    lv_obj_set_hidden(liveGroup, false);
    lv_obj_set_hidden(offlineGroup, true);
  } else {
    lv_obj_set_hidden(liveGroup, true);
    lv_obj_set_hidden(offlineGroup, false);
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
  const bool live = s.live != 0;   // §6.5: any non-zero value means live
  if (live != curLive) {
    curLive = live;
    applyVisibility();
  }

  // §6.5: msg_total resets when a stream STARTS and deliberately not when one
  // ends — "the last value stays on screen until the next stream begins" — and the
  // relay keeps sending all three totals in every offline STATS (vector V5 pins
  // msg_total = 2135 with live = 0). Applying them only while live threw the
  // final message count of the stream that just ended straight on the floor, and
  // the viewer never learned it. Both screens carry the row; only the live one
  // carries viewers, uptime and the chat-rate ring, which are the figures that
  // genuinely have no meaning offline.
  char buf[16];
  formatCount(buf, sizeof(buf), s.msgTotal);
  lv_label_set_text(chipChat, buf);
  lv_label_set_text(offChipChat, buf);
  formatCount(buf, sizeof(buf), s.followers);
  lv_label_set_text(chipFoll, buf);
  lv_label_set_text(offChipFoll, buf);
  formatCount(buf, sizeof(buf), s.subs);
  lv_label_set_text(chipSubs, buf);
  lv_label_set_text(offChipSubs, buf);

  if (!live) {
    uptimeValid = false;
    return;
  }

  // §6.5: stream_started_at is carried alongside uptime_s precisely so a device
  // can prefer the absolute anchor. Use it when the relay knows both clocks,
  // and fall back to the relative snapshot when either is unknown.
  uptimeBase = (s.streamStartedAt != 0 && s.serverTime >= s.streamStartedAt)
                   ? (s.serverTime - s.streamStartedAt)
                   : s.uptimeSec;
  uptimeAnchorMs = millis();
  uptimeValid = true;
  renderUptime();

  setViewers(s.viewers);
  lv_arc_set_value(edgeArc, (int32_t)(s.chatRate > 100 ? 100 : s.chatRate));
}

void uiIdleSetOnline(bool online) {
  if (!connectGroup || online == linkUp) return;
  linkUp = online;
  applyVisibility();
}
