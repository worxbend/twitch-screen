#pragma once
#include "ui_common.h"
#include <array>

// Four fixed rounded segments: no objects or styles allocated by updates.
namespace uiGauge {
constexpr uint32_t Colors[] = {0x20DDF2, 0x23E88B, 0xFFBC35, 0xFF526A};
struct Dial {
  std::array<lv_obj_t *, 4> arcs{};
  lv_obj_t *marker = nullptr;
  int value = -1;
  int radius = 0;
  bool available = true;
};
inline void build(Dial &dial, lv_obj_t *root, int diameter, int width, bool marker) {
  dial.radius = (diameter - width) / 2;
  for (unsigned i = 0; i < dial.arcs.size(); ++i) {
    auto *arc = dial.arcs[i] = lv_arc_create(root);
    lv_obj_remove_style_all(arc);
    lv_obj_set_size(arc, diameter, diameter); lv_obj_center(arc);
    lv_obj_set_clickable(arc, false); lv_obj_set_scrollable(arc, false);
    lv_arc_set_rotation(arc, 135 + i * 69);
    lv_arc_set_range(arc, 0, 10000);
    lv_arc_set_bg_angles(arc, 0, 63);
    for (auto part : {LV_PART_MAIN, LV_PART_INDICATOR}) {
      lv_obj_set_style_arc_width(arc, width, part);
      lv_obj_set_style_arc_rounded(arc, true, part);
      lv_obj_set_style_arc_opa(arc, LV_OPA_COVER, part);
    }
    lv_obj_set_style_arc_color(arc, lv_color_hex(0x20252D), LV_PART_MAIN);
    lv_obj_set_style_arc_color(arc, lv_color_hex(Colors[i]), LV_PART_INDICATOR);
    lv_arc_set_value(arc, 0);
  }
  if (marker) {
    dial.marker = lv_obj_create(root); lv_obj_remove_style_all(dial.marker);
    lv_obj_set_size(dial.marker, 12, 12);
    lv_obj_set_style_radius(dial.marker, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_color(dial.marker, lv_color_hex(0xF5F7FA), 0);
    lv_obj_set_style_bg_opa(dial.marker, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(dial.marker, 3, 0);
    lv_obj_set_style_border_color(dial.marker, lv_color_black(), 0);
  }
}
inline void update(Dial &dial, int basisPoints, bool available = true, bool scale = false) {
  const int value = basisPoints < 0 ? 0 : basisPoints > 10000 ? 10000 : basisPoints;
  if (dial.value == value && dial.available == available) return;
  dial.value = value; dial.available = available;
  const int sweep = value * 270 / 10000;
  for (unsigned i = 0; i < dial.arcs.size(); ++i) {
    int degrees = sweep - static_cast<int>(i) * 69;
    degrees = degrees < 0 ? 0 : degrees > 63 ? 63 : degrees;
    lv_arc_set_value(dial.arcs[i], available ? (scale ? 10000 : degrees * 10000 / 63) : 0);
  }
  if (dial.marker) {
    const int angle = 135 + sweep;
    const int x = dial.radius * lv_trigo_cos(angle) / (1 << LV_TRIGO_SHIFT);
    const int y = dial.radius * lv_trigo_sin(angle) / (1 << LV_TRIGO_SHIFT);
    lv_obj_align(dial.marker, LV_ALIGN_CENTER, x, y);
  }
}
// Equivalent physical scale in either unit; saturation does not change the reading.
inline int temperature(int centi, bool fahrenheit) {
  const int celsius = fahrenheit ? (centi - 3200) * 5 / 9 : centi;
  return (celsius + 3000) * 10000 / 8000;
}
}
