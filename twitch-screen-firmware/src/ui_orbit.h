#pragma once

#include <lvgl.h>
#include <array>
#include <stdint.h>

// Reusable rendering adapter only. The DESK Controller chooses when this view
// is visible and which composition to use; no network or selection policy here.
class OrbitView {
 public:
  struct Frame {
    std::array<uint16_t, 3> rotation{};
    std::array<uint16_t, 3> radius{};
    std::array<uint32_t, 3> color{};
    uint64_t visibleMs = 0;
  };
  OrbitView() = default;
  OrbitView(const OrbitView &) = delete;
  OrbitView &operator=(const OrbitView &) = delete;
  void build(lv_obj_t *parent, uint32_t seed);
  // Call before deleting the parent/display or deinitializing LVGL.
  void destroy();
  void setVisible(bool visible);
  void setDisconnected(bool disconnected);
  lv_obj_t *root() const { return root_; }
  const Frame &frame() const { return frame_; }

 private:
  static void timer(lv_timer_t *timer);
  void update();
  void render();
  void retarget();
  uint32_t random();
  lv_obj_t *root_ = nullptr, *loss_ = nullptr;
  std::array<lv_obj_t *, 3> arcs_{}, tracks_{};
  std::array<lv_obj_t *, 6> segments_{};
  lv_obj_t *core_ = nullptr;
  std::array<lv_obj_t *, 4> facets_{};
  lv_timer_t *timer_ = nullptr;
  Frame frame_;
  std::array<uint32_t, 3> angle_{}, remainder_{}, from_{}, target_{};
  uint32_t seed_ = 1, lastTick_ = 0, breath_ = 0, breathRemainder_ = 0, blendMs_ = 0;
  bool visible_ = false, disconnected_ = false;
};
