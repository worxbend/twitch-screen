#pragma once
#include "generic_service.h"
#include <lvgl.h>
// Main/LVGL thread only. Native text remains 240px; photographs are 96px enlarged.
class GenericView {
 public:
  void build(lv_obj_t *parent);
  void destroy();
  void setVisible(bool visible);
  void update();
  lv_obj_t *root() const { return root_; }
  uint8_t slide() const { return slideshow_.slide; }
  uint32_t generation() const { return generation_; }
  const generic::Snapshot &state() const { return state_; }
 private:
  static void timer(lv_timer_t *timer);
  static void fluid(lv_event_t *event);
  void detach();
  lv_obj_t *root_ = nullptr, *image_ = nullptr, *gas_ = nullptr, *title_ = nullptr,
      *temperature_ = nullptr, *detail_ = nullptr, *status_ = nullptr;
  lv_timer_t *timer_ = nullptr;
  lv_image_dsc_t descriptor_{};
  generic::Slideshow slideshow_;
  generic::Snapshot state_;
  int active_ = -1;
  uint32_t generation_ = 0, lastTick_ = 0, phase_ = 0;
  bool online_ = false;
};
