#pragma once

#include <stdint.h>

// Initializes TFT_eSPI, the LVGL display (240x240, partial render buffers)
// and the backlight. Call once before any other LVGL code.
void lvPortInit();

// Feeds the LVGL tick from millis() and runs lv_timer_handler().
// Call every loop iteration.
void lvPortPump();
