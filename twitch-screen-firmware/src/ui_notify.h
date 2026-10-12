#pragma once

#include "notification.h"

// Full-screen notification overlay with LVGL slide/fade animations.
// Call uiNotifyInit() once; then uiNotifyShow() whenever uiNotifyBusy()
// is false. The overlay dismisses itself after a hold period.
struct NotifyLifecycle {
  void (*hold)();
  void (*beginExit)();
  void (*complete)();
};
// Loop-owned callbacks run at actual animation/timer boundaries, before reveal.
void uiNotifySetLifecycle(const NotifyLifecycle *lifecycle);
void uiNotifyInit();
// Actual widgets and all controller lifecycle callbacks must exist before DESK.
bool uiNotifyReady();
bool uiNotifyBusy();
// DESK content layout is opt-in per card; retained classic cards keep their layout.
void uiNotifyShow(const Notification &n, bool deskLayout = false);
