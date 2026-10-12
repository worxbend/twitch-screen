#pragma once

#include "desk_controller.h"

namespace desk {

// Boundary order shared by host tests and the future Arduino/LVGL binding.
// Inputs owns clock sampling/calibration and dispatches already available link
// state. View renders Selection only; it must not independently arbitrate modes.
// No networking occurs in LVGL callbacks: dispatch() must be bounded/nonblocking.
// This adapter is not connected to main/ui_notify until complete B1 integration.
template <class Inputs, class View>
class ViewBoundary {
 public:
  ViewBoundary(Controller &controller, Inputs &inputs, View &view)
      : controller_(controller), inputs_(inputs), view_(view) {}
  void pump() {
    reconcile();
    view_.pump();
  }
  void beginExit() {
    inputs_.dispatch();
    const uint64_t now = inputs_.now();
    view_.apply(controller_.beginExit(now, inputs_.calibrated(now)));
    view_.reveal();
  }
  bool complete() {
    inputs_.dispatch();
    const uint64_t now = inputs_.now();
    const bool completed = controller_.complete(now, inputs_.calibrated(now));
    view_.apply(controller_.selection(now));
    if (completed) view_.reveal();
    return completed;
  }
 private:
  void reconcile() {
    inputs_.dispatch();
    const uint64_t now = inputs_.now();
    view_.apply(controller_.reconcile(now, inputs_.calibrated(now)));
  }
  Controller &controller_;
  Inputs &inputs_;
  View &view_;
};

}  // namespace desk
