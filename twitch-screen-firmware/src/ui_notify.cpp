#include "ui_notify.h"

#include <lvgl.h>
#include <stdio.h>
#include <string.h>

#include "presentation.h"
#include "ui_common.h"
#include "ui_idle.h"

// Full-screen event card for the 240x240 round panel. Content stays inside
// the circle: nothing wider than ~170 px below the vertical center, and the
// accent ring hugs the edge.
//
// The kind is the WIRE CODE from EVENT payload byte +20
// (docs/PROTOCOL.md §6.4.1), not an ordinal. That is the whole point: 0x12 is
// FOLLOW here because 0x12 is FOLLOW on the wire, and a kind added to the
// protocol later cannot renumber the ones already flashed.

namespace {

constexpr uint32_t SLIDE_IN_MS = 350;
constexpr uint32_t SLIDE_OUT_MS = 280;
constexpr uint32_t ALERT_MS = 200;  // one flash pulse half-period

// §6.4 ttl_ds: 0 means "use the receiver's per-kind default". These are those
// defaults; anything non-zero on the wire wins, and the decoder has already
// clamped it to 6000 (10 min).
constexpr uint32_t HOLD_DEFAULT_MS = 3500;

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
Entrance cardEntrance = Entrance::Slide;  // decided once per card in uiNotifyShow
uint32_t holdMs = HOLD_DEFAULT_MS;   // how long the current card stays up

void animY(void *var, int32_t v) { lv_obj_set_y((lv_obj_t *)var, v); }
void animOpa(void *var, int32_t v) {
  lv_obj_set_style_opa((lv_obj_t *)var, (lv_opa_t)v, 0);
}

void animate(lv_obj_t *obj, lv_anim_exec_xcb_t cb, int32_t from, int32_t to,
             uint32_t duration, lv_anim_path_cb_t path, lv_anim_completed_cb_t ready) {
  lv_anim_t a;
  lv_anim_init(&a);
  lv_anim_set_var(&a, obj);
  lv_anim_set_values(&a, from, to);
  lv_anim_set_duration(&a, duration);
  lv_anim_set_exec_cb(&a, cb);
  lv_anim_set_path_cb(&a, path);
  if (ready) lv_anim_set_completed_cb(&a, ready);
  lv_anim_start(&a);
}

void hideReady(lv_anim_t *) {
  lv_obj_set_hidden(overlay, true);
  busy = false;
  uiIdleSetCovered(false);
}

void hideStart() {
  lv_anim_delete(ring, animOpa);  // stop the severe-card ring pulse, if any
  lv_obj_set_style_opa(ring, LV_OPA_COVER, 0);
  if (cardEntrance == Entrance::None) { hideReady(nullptr); return; }  // replay leaves at once
  uiIdleSetCovered(false);
  animate(overlay, animY, 0, PANEL, SLIDE_OUT_MS, lv_anim_path_ease_in, hideReady);
}

void holdTimerCb(lv_timer_t *t) {
  lv_timer_delete(t);
  hideStart();
}

void showReady(lv_anim_t *) { lv_timer_create(holdTimerCb, holdMs, nullptr); }

// Slide the card in. Only WARNING/ALERT keep the accent ring breathing while it
// is up (ringPulses, K-118); routine rings stay static at full opacity. Reached
// directly for routine cards or after the alert blink. Replay cards bypass this
// entirely.
void slideIn() {
  lv_obj_set_hidden(overlay, false);
  lv_obj_set_style_opa(overlay, LV_OPA_COVER, 0);
  animate(overlay, animY, PANEL, 0, SLIDE_IN_MS, lv_anim_path_ease_out, showReady);

  if (ringPulses(cardEntrance)) {
    lv_anim_t a;
    lv_anim_init(&a);
    lv_anim_set_var(&a, ring);
    lv_anim_set_values(&a, LV_OPA_COVER, 100);
    lv_anim_set_duration(&a, 600);
    lv_anim_set_exec_cb(&a, animOpa);
    lv_anim_set_path_cb(&a, lv_anim_path_ease_in_out);
    lv_anim_set_repeat_count(&a, LV_ANIM_REPEAT_INFINITE);
    lv_anim_set_reverse_duration(&a, 600);
    lv_anim_start(&a);
  }
}

void flashDone(lv_anim_t *) {
  lv_obj_set_hidden(flash, true);
  slideIn();
}

uint32_t holdMsFor(const Notification &n) {
  return n.ttl_ds ? static_cast<uint32_t>(n.ttl_ds) * 100u : kindPresentation(n.kind).holdMs;
}

void formatDuration(char *buf, size_t n, uint32_t sec) {
  if (sec >= 3600) {
    snprintf(buf, n, "%luh %02lum", (unsigned long)(sec / 3600),
             (unsigned long)(sec / 60 % 60));
  } else {
    snprintf(buf, n, "%lum", (unsigned long)(sec / 60));
  }
}

// The headline. §9.3: the relay substitutes "viewer" for a display name that
// folds away, so an empty actor here means the kind genuinely has no actor —
// or the gifter was anonymous (§6.4 eflags bit 3).
const char *headlineFor(const Notification &n) {
  if (n.actor[0] != '\0') return n.actor;
  if (n.anonymous) return "Anonymous";
  return kindLabel(n.kind);
}

// The body. §6.4.3 is the rule that matters here: a receiver that knows the
// kind and finds a NON-EMPTY text MUST render actor/text as given rather than
// composing its own sentence from the numeric fields. Otherwise a hand-written
// test card posted to /api/v1/notifications is silently replaced by "raided
// with 0 viewers". So: compose only into the gap the sender left.
void composeBody(char *buf, size_t cap, const Notification &n) {
  if (n.text[0] != '\0') {
    snprintf(buf, cap, "%s", n.text);
    return;
  }

  const char *tier = tierName(n.tier);
  const unsigned long v = (unsigned long)n.value;
  char dur[16];

  switch (n.kind) {
    case NotifyKind::StreamStart:
    case NotifyKind::Follow:
      // The caption + icon already say it; keep the card on the username.
      buf[0] = '\0';
      break;
    case NotifyKind::StreamEnd:
      formatDuration(dur, sizeof(dur), n.value);   // §6.4.1: duration in seconds
      snprintf(buf, cap, "streamed for %s", dur);
      break;
    case NotifyKind::Sub:
      if (tier && n.months > 1)      snprintf(buf, cap, "%s - %u months", tier, (unsigned)n.months);
      else if (tier)                 snprintf(buf, cap, "%s subscriber", tier);
      else if (n.months > 1)         snprintf(buf, cap, "%u months", (unsigned)n.months);
      else                           snprintf(buf, cap, "new subscriber");
      break;
    case NotifyKind::Gift:
      if (tier) snprintf(buf, cap, "gifted %lu x %s", v, tier);
      else      snprintf(buf, cap, "gifted %lu sub%s", v, v == 1 ? "" : "s");
      break;
    case NotifyKind::Raid:
      snprintf(buf, cap, "raiding with %lu viewer%s", v, v == 1 ? "" : "s");
      break;
    case NotifyKind::Bits:
      snprintf(buf, cap, "cheered %lu bit%s", v, v == 1 ? "" : "s");
      break;
    default:
      buf[0] = '\0';   // INFO / MESSAGE / WARNING / ALERT carry their own body
      break;
  }
}

// The widgets are shared by every kind, so each show re-applies a full layout.
// Chat inverts the priority: icon, caption and username shrink into a top
// strip and the message gets the big font and most of the circle — the text
// is the content, the rest is metadata.
void layoutDefault() {
  lv_obj_set_size(iconCircle, 64, 64);
  lv_obj_align(iconCircle, LV_ALIGN_CENTER, 0, -54);
  lv_obj_set_style_text_font(iconLabel, &lv_font_montserrat_28, 0);

  lv_obj_align(kindTxt, LV_ALIGN_CENTER, 0, -6);

  lv_obj_set_style_text_font(titleLabel, &lv_font_montserrat_28, 0);
  lv_obj_set_height(titleLabel, lv_font_get_line_height(&lv_font_montserrat_28));
  lv_obj_align(titleLabel, LV_ALIGN_CENTER, 0, 18);

  lv_obj_set_style_text_font(bodyLabel, &lv_font_montserrat_14, 0);
  lv_obj_set_style_text_color(bodyLabel, lv_color_hex(0x9E9E9E), 0);
  lv_obj_set_size(bodyLabel, 170, 36);  // two lines, ellipsis beyond
  lv_obj_align(bodyLabel, LV_ALIGN_CENTER, 0, 60);
}

void layoutChat() {
  lv_obj_set_size(iconCircle, 28, 28);
  lv_obj_align(iconCircle, LV_ALIGN_CENTER, 0, -80);
  lv_obj_set_style_text_font(iconLabel, &lv_font_montserrat_14, 0);

  lv_obj_align(kindTxt, LV_ALIGN_CENTER, 0, -58);

  lv_obj_set_style_text_font(titleLabel, &lv_font_montserrat_14, 0);
  lv_obj_set_height(titleLabel, lv_font_get_line_height(&lv_font_montserrat_14));
  lv_obj_align(titleLabel, LV_ALIGN_CENTER, 0, -36);

  lv_obj_set_style_text_font(bodyLabel, &lv_font_montserrat_20, 0);
  lv_obj_set_style_text_color(bodyLabel, lv_color_white(), 0);
  lv_obj_set_size(bodyLabel, 176, 108);  // ~5 wrapped lines at font 20
  lv_obj_align(bodyLabel, LV_ALIGN_CENTER, 0, 32);
}

}  // namespace

void uiNotifyInit() {
  overlay = lv_obj_create(lv_layer_top());
  lv_obj_set_size(overlay, PANEL, PANEL);
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
  lv_obj_set_style_radius(ring, LV_RADIUS_CIRCLE, 0);
  clearDecor(ring);
  lv_obj_set_style_border_width(ring, 3, 0);  // clearDecor zeroed it

  iconCircle = lv_obj_create(overlay);
  lv_obj_set_style_radius(iconCircle, LV_RADIUS_CIRCLE, 0);
  clearDecor(iconCircle);

  iconLabel = lv_label_create(iconCircle);
  lv_obj_set_style_text_color(iconLabel, lv_color_black(), 0);
  lv_obj_center(iconLabel);

  kindTxt = lv_label_create(overlay);
  lv_obj_set_style_text_font(kindTxt, &lv_font_montserrat_14, 0);
  lv_obj_set_style_text_letter_space(kindTxt, 2, 0);

  // A display name is up to 47 content bytes (§5), which overruns the circle
  // at font 28. Clip it with an ellipsis rather than letting it draw outside.
  titleLabel = lv_label_create(overlay);
  lv_obj_set_style_text_color(titleLabel, lv_color_white(), 0);
  lv_obj_set_width(titleLabel, 190);
  lv_label_set_long_mode(titleLabel, LV_LABEL_LONG_MODE_DOTS);
  lv_obj_set_style_text_align(titleLabel, LV_TEXT_ALIGN_CENTER, 0);

  bodyLabel = lv_label_create(overlay);
  lv_label_set_long_mode(bodyLabel, LV_LABEL_LONG_MODE_DOTS);
  lv_obj_set_style_text_align(bodyLabel, LV_TEXT_ALIGN_CENTER, 0);

  layoutDefault();

  seqLabel = lv_label_create(overlay);
  lv_obj_set_style_text_color(seqLabel, lv_color_hex(0x616161), 0);
  lv_obj_align(seqLabel, LV_ALIGN_BOTTOM_MID, 0, -18);

  lv_obj_set_hidden(overlay, true);

  // Full-screen alert flash (above the overlay): blinks in the event's
  // accent color before the card slides in, to catch peripheral vision.
  flash = lv_obj_create(lv_layer_top());
  lv_obj_set_size(flash, PANEL, PANEL);
  lv_obj_center(flash);
  lv_obj_set_style_bg_opa(flash, LV_OPA_COVER, 0);
  lv_obj_set_style_opa(flash, LV_OPA_TRANSP, 0);
  lv_obj_set_style_radius(flash, LV_RADIUS_CIRCLE, 0);
  clearDecor(flash);
  lv_obj_set_hidden(flash, true);
}

bool uiNotifyBusy() { return busy; }

namespace {
void applyKindPresentation(const Notification &n) {
  if (n.kind == NotifyKind::Chat) layoutChat();
  else layoutDefault();
  const KindPresentation &presentation = kindPresentation(n.kind);
  const lv_color_t accent = lv_color_hex(presentation.color);
  lv_obj_set_style_border_color(ring, accent, 0);
  lv_obj_set_style_bg_color(iconCircle, accent, 0);
  lv_obj_set_style_text_color(kindTxt, accent, 0);
  lv_label_set_text_static(iconLabel, presentation.icon);
  lv_label_set_text_static(kindTxt, presentation.label);
}

void applyTexts(const Notification &n) {
  lv_label_set_text(titleLabel, headlineFor(n));

  // §6.4.1: CHAT.value is the chatter's name colour, and only when
  // eflags.CHAT_COLOUR_PRESENT is set — black is a legal colour, so the flag,
  // not a zero test, is what decides.
  if (n.kind == NotifyKind::Chat && n.chatColorPresent) {
    lv_obj_set_style_text_color(titleLabel, lv_color_hex(readableChatColor(n.value)), 0);
  } else {
    lv_obj_set_style_text_color(titleLabel, lv_color_white(), 0);
  }

  char body[96];
  composeBody(body, sizeof(body), n);
  lv_label_set_text(bodyLabel, body);

  lv_label_set_text_fmt(seqLabel, "#%lu", (unsigned long)n.seq);

}

void playEntrance(const Notification &n) {
  switch (cardEntrance) {
    case Entrance::None:
      // Replay displays immediately, with no slide, flash, or perpetual ring pulse.
      lv_obj_set_y(overlay, 0);
      lv_obj_set_style_opa(overlay, LV_OPA_COVER, 0);
      lv_obj_set_hidden(overlay, false);
      showReady(nullptr);
      return;
    case Entrance::Slide:
      // Routine cards only slide, with a static ring. Full-screen attention is
      // reserved for severity.
      slideIn();
      return;
    case Entrance::FlashThenSlide:  // Warning and Alert blink, then flashDone slides in
      break;
  }
  lv_obj_set_style_bg_color(flash, lv_color_hex(kindPresentation(n.kind).color), 0);
  lv_obj_set_hidden(flash, false);
  lv_anim_t a;
  lv_anim_init(&a);
  lv_anim_set_var(&a, flash);
  lv_anim_set_values(&a, LV_OPA_TRANSP, 230);
  lv_anim_set_duration(&a, ALERT_MS);
  lv_anim_set_exec_cb(&a, animOpa);
  lv_anim_set_path_cb(&a, lv_anim_path_ease_in_out);
  lv_anim_set_reverse_duration(&a, ALERT_MS);
  lv_anim_set_repeat_count(&a, 2);  // 3 pulses total
  lv_anim_set_completed_cb(&a, flashDone);
  lv_anim_start(&a);
}

}  // namespace

void uiNotifyShow(const Notification &n) {
  if (busy || !overlay) return;
  busy = true;
  cardEntrance = entranceFor(n.replay, n.kind);
  uiIdleSetCovered(true);
  holdMs = holdMsFor(n);
  applyKindPresentation(n);
  applyTexts(n);
  playEntrance(n);
}
