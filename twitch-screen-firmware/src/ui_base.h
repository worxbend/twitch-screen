#pragma once

#include "desk_state_codec.h"
#include "ui_orbit.h"
#include "ui_generic.h"
#include "ui_gauge.h"

// Rendering adapter: applies the sole Controller's selection. Never selects a
// dashboard from a snapshot or link flag, and owns no notification queue.
class BaseView {
 public:
  void build(uint32_t seed);
  void destroy();
  void apply(desk::Selection selection, const desk::wire::State &weather,
             const desk::wire::State &cpu);
  void setCovered(bool covered);
  desk::Base selected() const { return selected_; }
  bool covered() const { return covered_; }
  // Set only after Twitch, both telemetry widgets and orbit construction.
  bool built() const { return built_; }
  struct TelemetryView {
    lv_obj_t *root = nullptr, *value = nullptr, *label = nullptr, *unit = nullptr,
             *detail = nullptr, *secondary = nullptr;
    uiGauge::Dial primary, memory;
    lv_obj_t *icon = nullptr;
    uint8_t condition = 255;
    bool cpuIcon = false, compact = false;
    char valueText[16] = {}, labelText[32] = {}, unitText[16] = {},
         detailText[32] = {}, secondaryText[32] = {};
  };
  const TelemetryView &weatherView() const { return weather_; }
  const TelemetryView &cpuView() const { return cpu_; }
  OrbitView &orbit() { return orbit_; }
  GenericView &generic() { return generic_; }
 private:
  static void buildTelemetry(TelemetryView &view);
  static void renderWeather(TelemetryView &view, const desk::wire::State &state);
  static void renderCpu(TelemetryView &view, const desk::wire::State &state);
  void visibility();
  OrbitView orbit_;
  GenericView generic_;
  TelemetryView weather_, cpu_;
  desk::Base selected_ = desk::Base::DisconnectedOrbit;
  bool built_ = false, covered_ = false;
};

BaseView &uiBaseView();
void uiBaseSetCovered(bool covered);
