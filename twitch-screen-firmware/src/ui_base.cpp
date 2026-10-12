#include "ui_base.h"
#include "ui_idle.h"
#include "ui_common.h"

namespace {
BaseView base;
// Original weather/core pictograms, drawn in a fixed 48px object using LVGL primitives.
void pictogram(lv_event_t *event) {
  auto &view = *static_cast<BaseView::TelemetryView *>(lv_event_get_user_data(event));
  lv_area_t area; lv_obj_get_coords(view.icon, &area);
  auto *layer = lv_event_get_layer(event);
  auto line = [&](int x1, int y1, int x2, int y2, uint32_t color, int width = 3) {
    lv_draw_line_dsc_t d; lv_draw_line_dsc_init(&d);
    d.p1 = {area.x1 + x1, area.y1 + y1}; d.p2 = {area.x1 + x2, area.y1 + y2};
    d.color = lv_color_hex(color); d.width = width; d.round_start = d.round_end = 1;
    lv_draw_line(layer, &d);
  };
  auto disc = [&](int x, int y, int radius, uint32_t color) {
    lv_draw_rect_dsc_t d; lv_draw_rect_dsc_init(&d);
    d.radius = LV_RADIUS_CIRCLE; d.bg_color = lv_color_hex(color); d.bg_opa = LV_OPA_COVER;
    lv_area_t a = {area.x1 + x - radius, area.y1 + y - radius,
                   area.x1 + x + radius, area.y1 + y + radius};
    lv_draw_rect(layer, &d, &a);
  };
  if (view.cpuIcon) {
    line(15, 14, 33, 14, 0x23E88B); line(33, 14, 33, 32, 0x23E88B);
    line(33, 32, 15, 32, 0x23E88B); line(15, 32, 15, 14, 0x23E88B);
    for (int p = 19; p <= 29; p += 5) {
      line(p, 8, p, 11, 0x20DDF2, 2); line(p, 35, p, 38, 0x20DDF2, 2);
      line(9, p - 1, 12, p - 1, 0x20DDF2, 2); line(36, p - 1, 39, p - 1, 0x20DDF2, 2);
    }
    line(21, 20, 27, 20, 0xF5F7FA, 2); line(21, 25, 27, 25, 0xF5F7FA, 2);
    return;
  }
  const unsigned c = view.condition;
  if (c == 0 || c > 8) {
    line(24, 8, 39, 23, 0xA85BFF); line(39, 23, 24, 38, 0xA85BFF);
    line(24, 38, 9, 23, 0xA85BFF); line(9, 23, 24, 8, 0xA85BFF);
    line(24, 17, 24, 24, 0xF5F7FA); disc(24, 30, 1, 0xF5F7FA); return;
  }
  if (c == 1 || c == 2) {
    const int x = c == 1 ? 24 : 16, y = c == 1 ? 23 : 16;
    disc(x, y, 7, 0xFFBC35);
    for (int a = 0; a < 360; a += 45) {
      const int sx = lv_trigo_cos(a), sy = lv_trigo_sin(a);
      line(x + sx * 11 / 32768, y + sy * 11 / 32768,
           x + sx * 15 / 32768, y + sy * 15 / 32768, 0xFFBC35, 2);
    }
    if (c == 1) return;
  }
  if (c != 7) {
    disc(16, 25, 7, 0xD7E5EF); disc(24, 21, 9, 0xD7E5EF); disc(33, 26, 7, 0xD7E5EF);
    line(15, 31, 34, 31, 0xD7E5EF, 3);
  }
  if (c == 4) {
    line(11, 37, 37, 37, 0x20DDF2, 2); line(16, 42, 32, 42, 0x20DDF2, 2);
  } else if (c == 5 || c == 6) {
    for (int x = 16; x <= 34; x += 9) line(x, 36, x - 2, c == 5 ? 38 : 43, 0x20DDF2, 2);
  } else if (c == 7) {
    for (int a = 0; a < 180; a += 60) {
      const int x = lv_trigo_cos(a) * 17 / 32768, y = lv_trigo_sin(a) * 17 / 32768;
      line(24 - x, 24 - y, 24 + x, 24 + y, 0x20DDF2, 2);
      for (int sign : {-1, 1}) {
        const int ex = 24 + sign * x * 2 / 3, ey = 24 + sign * y * 2 / 3;
        line(ex, ey, ex - sign * y / 4 - sign * x / 4,
             ey + sign * x / 4 - sign * y / 4, 0x20DDF2, 2);
        line(ex, ey, ex + sign * y / 4 - sign * x / 4,
             ey - sign * x / 4 - sign * y / 4, 0x20DDF2, 2);
      }
    }
  } else if (c == 8) {
    line(26, 33, 20, 39, 0xFFBC35); line(20, 39, 27, 39, 0xFFBC35);
    line(27, 39, 22, 46, 0xFFBC35);
  }
}
void readingFont(BaseView::TelemetryView &view, const char *number) {
  const bool compact = strlen(number) > 5;
  if (view.compact == compact) return;
  view.compact = compact;
  const lv_font_t *font = compact ? &lv_font_montserrat_28 : &lv_font_montserrat_48;
  lv_obj_set_style_text_font(view.value, font, 0);
  lv_obj_set_height(view.value, lv_font_get_line_height(font));
}
lv_obj_t *label(lv_obj_t *root, const lv_font_t *font, int width, int y) {
  lv_obj_t *result = lv_label_create(root);
  lv_obj_set_style_text_font(result, font, 0);
  lv_obj_set_style_text_color(result, lv_color_hex(0xEDF5F7), 0);
  lv_obj_set_style_text_align(result, LV_TEXT_ALIGN_CENTER, 0);
  lv_obj_set_size(result, width, lv_font_get_line_height(font));
  lv_label_set_long_mode(result, LV_LABEL_LONG_MODE_DOTS);
  lv_obj_align(result, LV_ALIGN_CENTER, 0, y);
  lv_label_set_text_static(result, "");
  return result;
}
void centi(char *out, size_t size, int32_t value) {
  const uint32_t magnitude = static_cast<uint32_t>(value < 0 ? -value : value);
  snprintf(out, size, "%s%lu.%02lu", value < 0 ? "-" : "",
           (unsigned long)(magnitude / 100), (unsigned long)(magnitude % 100));
}
// Wire labels are bounded, but unsupported bytes must never reach the ASCII font.
void asciiLabel(char (&out)[32], const char (&input)[32]) {
  size_t i = 0;
  for (; i < 31 && input[i]; ++i) {
    const uint8_t byte = static_cast<uint8_t>(input[i]);
    out[i] = byte >= 32 && byte <= 126 ? input[i] : '_';
  }
  out[i] = 0;
}
}
BaseView &uiBaseView() { return base; }
void uiBaseSetCovered(bool covered) { base.setCovered(covered); }

void BaseView::build(uint32_t seed) {
  if (built_) return;
  uiIdleBuild();
  orbit_.build(lv_screen_active(), seed);
  // Keep legacy orbit container/report semantics; Generic owns its visible content.
  generic_.build(orbit_.root());
  buildTelemetry(weather_);
  cpu_.cpuIcon = true;
  buildTelemetry(cpu_);
  built_ = true;
  visibility();
}
void BaseView::buildTelemetry(TelemetryView &view) {
  view.root = lv_obj_create(lv_screen_active());
  lv_obj_remove_style_all(view.root);
  lv_obj_set_size(view.root, PANEL, PANEL);
  lv_obj_center(view.root);
  clearDecor(view.root);
  lv_obj_set_style_bg_color(view.root, lv_color_black(), 0);
  lv_obj_set_style_bg_opa(view.root, LV_OPA_COVER, 0);
  uiGauge::build(view.primary, view.root, 222, 11, !view.cpuIcon);
  if (view.cpuIcon) uiGauge::build(view.memory, view.root, 190, 4, false);
  // Keep the host rectangle inside the inner memory ring, including long
  // ellipsized names. Masking the ring with a label backdrop is not clearance.
  view.label = view.cpuIcon
      ? label(view.root, &lv_font_montserrat_14, 92, -65)
      : label(view.root, &lv_font_montserrat_20, 136, -77);
  view.icon = lv_obj_create(view.root); lv_obj_remove_style_all(view.icon);
  lv_obj_set_size(view.icon, 48, 48); lv_obj_align(view.icon, LV_ALIGN_CENTER, 0, -40);
  clearDecor(view.icon);
  lv_obj_add_event_cb(view.icon, pictogram, LV_EVENT_DRAW_MAIN, &view);
  view.value = label(view.root, &lv_font_montserrat_48, 174, 9);
  view.unit = label(view.root, &lv_font_montserrat_14, 124, 43);
  lv_obj_set_style_text_color(view.unit, lv_color_hex(0x20DDF2), 0);
  view.detail = label(view.root, &lv_font_montserrat_14, 160, 65);
  view.secondary = label(view.root, &lv_font_montserrat_14, 136, 87);
  lv_obj_set_style_text_color(view.secondary, lv_color_hex(0xA6B2C0), 0);
}
void BaseView::renderWeather(TelemetryView &view, const desk::wire::State &state) {
  static const char *const conditions[] = {"Unknown", "Clear", "Partly cloudy", "Cloudy",
      "Fog", "Drizzle", "Rain", "Snow", "Thunderstorm"};
  char text[32];
  asciiLabel(text, state.label);
  setStaticLabel(view.label, view.labelText, text);
  char number[16];
  centi(number, sizeof(number), state.temperature);
  readingFont(view, number);
  uiGauge::update(view.primary, uiGauge::temperature(state.temperature, state.unit), true, true);
  if (view.condition != state.condition) {
    view.condition = state.condition;
    lv_obj_invalidate(view.icon);
  }
  setStaticLabel(view.value, view.valueText, number);
  setStaticLabel(view.unit, view.unitText, state.unit ? "deg F" : "deg C");
  setStaticLabel(view.detail, view.detailText, conditions[state.condition <= 8 ? state.condition : 0]);
  text[0] = 0;
  if (state.presence & 1) snprintf(text, sizeof(text), "Humidity %u%%", state.humidity);
  setStaticLabel(view.secondary, view.secondaryText, text);
}
void BaseView::renderCpu(TelemetryView &view, const desk::wire::State &state) {
  char text[32];
  asciiLabel(text, state.label);
  setStaticLabel(view.label, view.labelText, text);
  char number[16];
  const unsigned load = state.cpu > 10000 ? 10000 : state.cpu;
  const unsigned memory = state.memory > 10000 ? 10000 : state.memory;
  centi(number, sizeof(number), load);
  readingFont(view, number);
  uiGauge::update(view.primary, load);
  uiGauge::update(view.memory, memory, (state.presence & 1) != 0);
  setStaticLabel(view.value, view.valueText, number);
  setStaticLabel(view.unit, view.unitText, "% CPU");
  snprintf(text, sizeof(text), "Memory unavailable");
  if (state.presence & 1) {
    char number[16]; centi(number, sizeof(number), memory);
    snprintf(text, sizeof(text), "Memory %s%%", number);
  }
  setStaticLabel(view.detail, view.detailText, text);
  // CPU temperature is optional telemetry, not the device's secondary metric.
  setStaticLabel(view.secondary, view.secondaryText, "");
}
void BaseView::destroy() {
  generic_.destroy();
  orbit_.destroy();
  if (weather_.root) lv_obj_delete(weather_.root);
  if (cpu_.root) lv_obj_delete(cpu_.root);
  weather_ = {}; cpu_ = {};
  built_ = false;
}
void BaseView::apply(desk::Selection selection, const desk::wire::State &weather,
                     const desk::wire::State &cpu) {
  selected_ = selection.base;
  if (built_ && selected_ == desk::Base::Weather) renderWeather(weather_, weather);
  if (built_ && selected_ == desk::Base::Cpu) renderCpu(cpu_, cpu);
  visibility();
}
void BaseView::setCovered(bool covered) {
  covered_ = covered;
  visibility();
}
void BaseView::visibility() {
  if (!built_) return;
  const bool visible = !covered_;
  uiIdleSetVisible(visible && selected_ == desk::Base::Twitch);
  lv_obj_set_hidden(weather_.root, !visible || selected_ != desk::Base::Weather);
  lv_obj_set_hidden(cpu_.root, !visible || selected_ != desk::Base::Cpu);
  orbit_.setDisconnected(selected_ == desk::Base::DisconnectedOrbit);
  orbit_.setVisible(visible && (selected_ == desk::Base::ConnectedOrbit ||
                               selected_ == desk::Base::DisconnectedOrbit));
  generic_.setVisible(visible && (selected_ == desk::Base::ConnectedOrbit ||
                                 selected_ == desk::Base::DisconnectedOrbit));
}
