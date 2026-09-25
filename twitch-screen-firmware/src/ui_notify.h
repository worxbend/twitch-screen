#pragma once

#include "notification.h"

// Full-screen notification overlay with LVGL slide/fade animations.
// Call uiNotifyInit() once; then uiNotifyShow() whenever uiNotifyBusy()
// is false. The overlay dismisses itself after a hold period.
void uiNotifyInit();
bool uiNotifyBusy();
void uiNotifyShow(const Notification &n);
