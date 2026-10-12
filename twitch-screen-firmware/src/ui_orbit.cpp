#include "ui_orbit.h"

namespace {
constexpr uint32_t PaletteMs = 20000;
constexpr uint32_t FullTurn = 360000; // Millidegrees; preserve sub-degree motion.
const uint32_t Connected[] = {0x20ddf2, 0x23e88b, 0xa85bff};
const uint32_t Disconnected[] = {0x846139, 0xa77d44, 0x494c51};
const uint32_t ConnectedPeriod[] = {38000, 57000, 83000};
const uint32_t DisconnectedPeriod[] = {70000, 101000, 137000};
const uint16_t Radius[] = {61, 83, 106};
const int32_t Excursion[] = {2, 3, 2};
const int32_t Width[] = {8, 10, 6};

uint32_t dim(uint32_t color, unsigned divisor) {
  return (((color >> 16) / divisor) << 16) |
         ((((color >> 8) & 255) / divisor) << 8) | ((color & 255) / divisor);
}
lv_obj_t *arc(lv_obj_t *root, int width) {
  lv_obj_t *result = lv_arc_create(root);
  lv_obj_remove_style_all(result);
  lv_obj_set_style_arc_width(result, width, LV_PART_MAIN);
  lv_obj_set_style_arc_opa(result, LV_OPA_COVER, LV_PART_MAIN);
  lv_obj_set_style_arc_opa(result, LV_OPA_TRANSP, LV_PART_INDICATOR);
  lv_obj_set_style_arc_rounded(result, true, LV_PART_MAIN);
  lv_obj_set_clickable(result, false);
  lv_obj_set_scrollable(result, false);
  return result;
}
lv_obj_t *shape(lv_obj_t *root, int size, int radius, uint32_t color, int x, int y) {
  lv_obj_t *result = lv_obj_create(root);
  lv_obj_remove_style_all(result);
  lv_obj_set_size(result, size, size);
  lv_obj_set_style_radius(result, radius, 0);
  lv_obj_set_style_bg_color(result, lv_color_hex(color), 0);
  lv_obj_set_style_bg_opa(result, LV_OPA_COVER, 0);
  lv_obj_set_clickable(result, false);
  lv_obj_set_scrollable(result, false);
  lv_obj_align(result, LV_ALIGN_CENTER, x, y);
  return result;
}

uint32_t blend(uint32_t from, uint32_t to, uint32_t elapsed) {
  uint32_t result = 0;
  for (unsigned shift = 0; shift <= 16; shift += 8) {
    const int32_t start = (from >> shift) & 255;
    const int32_t end = (to >> shift) & 255;
    result |= static_cast<uint32_t>(start + (end - start) * static_cast<int32_t>(elapsed) /
                                    static_cast<int32_t>(PaletteMs)) << shift;
  }
  return result;
}
void advance(uint32_t &phase, uint32_t &remainder, uint32_t elapsed, uint32_t period) {
  const uint64_t numerator = static_cast<uint64_t>(elapsed) * FullTurn + remainder;
  phase = static_cast<uint32_t>((phase + numerator / period) % FullTurn);
  remainder = numerator % period;
}
}

uint32_t OrbitView::random() {
  seed_ ^= seed_ << 13; seed_ ^= seed_ >> 17; seed_ ^= seed_ << 5;
  return seed_;
}
void OrbitView::build(lv_obj_t *parent, uint32_t seed) {
  destroy();
  frame_ = Frame(); seed_ = seed ? seed : 1;
  breath_ = random() % FullTurn; breathRemainder_ = 0; blendMs_ = 0;
  visible_ = disconnected_ = false;
  root_ = lv_obj_create(parent);
  lv_obj_remove_style_all(root_);
  lv_obj_set_size(root_, 240, 240); lv_obj_center(root_);
  lv_obj_set_style_bg_color(root_, lv_color_hex(0x000000), 0);
  lv_obj_set_style_bg_opa(root_, LV_OPA_COVER, 0);
  lv_obj_set_scrollable(root_, false);
  // Fixed draw order: recessed tracks, nine rounded color segments, then core.
  for (size_t i = 0; i < tracks_.size(); ++i) tracks_[i] = arc(root_, Width[i]);
  for (size_t i = 0; i < arcs_.size(); ++i) {
    angle_[i] = random() % FullTurn; remainder_[i] = 0;
    from_[i] = target_[i] = frame_.color[i] = Connected[i];
    arcs_[i] = arc(root_, Width[i]);
  }
  for (size_t i = 0; i < segments_.size(); ++i) segments_[i] = arc(root_, Width[i / 2]);
  core_ = shape(root_, 66, LV_RADIUS_CIRCLE, 0x08141e, 0, 0);
  lv_obj_set_style_border_width(core_, 1, 0);
  lv_obj_set_style_border_color(core_, lv_color_hex(0x203742), 0);
  // Concentric inset and four offset facets suggest depth without raster assets,
  // a fictitious reading, or an expensive full-screen shadow.
  shape(core_, 48, LV_RADIUS_CIRCLE, 0x03080d, 0, 0);
  const int x[] = {0, 12, 0, -12}, y[] = {-12, 0, 12, 0};
  for (size_t i = 0; i < facets_.size(); ++i) {
    facets_[i] = shape(core_, 10, 3, Connected[i % 3], x[i], y[i]);
    lv_obj_set_style_border_width(facets_[i], 1, 0);
    lv_obj_set_style_border_color(facets_[i], lv_color_hex(0xc5f5f6), 0);
    lv_obj_set_style_border_side(facets_[i], LV_BORDER_SIDE_TOP, 0);
  }
  shape(core_, 5, LV_RADIUS_CIRCLE, 0xf5f7fa, 0, 0);
  loss_ = lv_label_create(root_);
  lv_label_set_text_static(loss_, "Offline"); // Persistent status, never a reading.
  lv_obj_set_style_text_font(loss_, &lv_font_montserrat_14, 0);
  lv_obj_set_style_text_color(loss_, lv_color_hex(0xffbc35), 0);
  lv_obj_set_style_bg_color(loss_, lv_color_hex(0x201708), 0);
  lv_obj_set_style_bg_opa(loss_, LV_OPA_COVER, 0);
  lv_obj_set_style_radius(loss_, 9, 0);
  lv_obj_set_style_border_width(loss_, 1, 0);
  lv_obj_set_style_border_color(loss_, lv_color_hex(0x846139), 0);
  lv_obj_set_style_pad_hor(loss_, 9, 0);
  lv_obj_set_style_pad_ver(loss_, 4, 0);
  lv_obj_align(loss_, LV_ALIGN_CENTER, 0, 78);
  lastTick_ = lv_tick_get();
  timer_ = lv_timer_create(timer, 50, this);
  lv_timer_pause(timer_);
  render(); lv_obj_set_hidden(root_, true);
}
void OrbitView::destroy() {
  if (timer_) lv_timer_delete(timer_);
  if (root_) lv_obj_delete(root_);
  timer_ = nullptr; root_ = loss_ = core_ = nullptr;
  arcs_.fill(nullptr); tracks_.fill(nullptr); segments_.fill(nullptr); facets_.fill(nullptr);
  visible_ = false;
}
void OrbitView::setVisible(bool visible) {
  if (!root_ || visible_ == visible) return;
  visible_ = visible; lastTick_ = lv_tick_get();
  lv_obj_set_hidden(root_, !visible);
  if (visible) { lv_timer_reset(timer_); lv_timer_resume(timer_); }
  else lv_timer_pause(timer_);
}
void OrbitView::retarget() {
  from_ = frame_.color;
  const uint32_t *palette = disconnected_ ? Disconnected : Connected;
  // A seeded target changes only after a complete 20-second interpolation.
  const unsigned offset = random() % 3;
  for (size_t i = 0; i < target_.size(); ++i) target_[i] = palette[(i + offset) % 3];
  blendMs_ = 0;
}
void OrbitView::setDisconnected(bool disconnected) {
  if (!root_ || disconnected_ == disconnected) return;
  disconnected_ = disconnected;
  // Preserve angular/breathing phase and current colors across composition edits.
  remainder_.fill(0); breathRemainder_ = 0; retarget(); render();
}
void OrbitView::timer(lv_timer_t *timer) {
  static_cast<OrbitView *>(lv_timer_get_user_data(timer))->update();
}
void OrbitView::update() {
  if (!visible_) return;
  const uint32_t now = lv_tick_get();
  uint32_t elapsed = now - lastTick_; lastTick_ = now;
  // Never jump a composition across a delayed loop. UI time is not expiry time.
  if (elapsed > 1000) elapsed = 50;
  frame_.visibleMs += elapsed;
  const uint32_t *period = disconnected_ ? DisconnectedPeriod : ConnectedPeriod;
  for (size_t i = 0; i < arcs_.size(); ++i) advance(angle_[i], remainder_[i], elapsed, period[i]);
  advance(breath_, breathRemainder_, elapsed, disconnected_ ? 13000 : 8000);
  if (elapsed >= PaletteMs - blendMs_) {
    for (size_t i = 0; i < target_.size(); ++i) frame_.color[i] = target_[i];
    retarget();
  } else blendMs_ += elapsed;
  for (size_t i = 0; i < target_.size(); ++i) frame_.color[i] = blend(from_[i], target_[i], blendMs_);
  render();
}
void OrbitView::render() {
  for (size_t i = 0; i < arcs_.size(); ++i) {
    frame_.rotation[i] = angle_[i] / 1000;
    const int16_t phase = static_cast<int16_t>((breath_ / 1000 + i * 120) % 360);
    frame_.radius[i] = Radius[i] + Excursion[i] * lv_trigo_sin(phase) / (1 << LV_TRIGO_SHIFT);
    // Shared phase/radius keeps the three rounded segments intentionally spaced.
    // The final end at 318 leaves a 42-degree outer break even offline.
    lv_obj_t *parts[] = {tracks_[i], arcs_[i], segments_[i * 2], segments_[i * 2 + 1]};
    const int start[] = {0, 0, 148, 266};
    const int end[] = {318, 132, 250, 318};
    for (unsigned part = 0; part < 4; ++part) {
      lv_obj_set_size(parts[part], frame_.radius[i] * 2, frame_.radius[i] * 2);
      lv_obj_center(parts[part]);
      lv_arc_set_rotation(parts[part], frame_.rotation[i]);
      lv_arc_set_bg_angles(parts[part], start[part], end[part]);
      const uint32_t color = part == 0 ? dim(frame_.color[i], 9) :
          part == 2 ? frame_.color[(i + 1) % 3] :
          part == 3 ? frame_.color[(i + 2) % 3] : frame_.color[i];
      lv_obj_set_style_arc_color(parts[part], lv_color_hex(color), LV_PART_MAIN);
    }
  }
  for (size_t i = 0; i < facets_.size(); ++i)
    lv_obj_set_style_bg_color(facets_[i], lv_color_hex(dim(frame_.color[i % 3], i == 2 ? 2 : 1)), 0);
  lv_obj_set_hidden(loss_, !disconnected_);
}
