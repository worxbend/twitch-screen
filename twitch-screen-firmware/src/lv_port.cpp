#include "lv_port.h"

#include <Arduino.h>
#include <array>
#include <esp_task_wdt.h>
#include "app_log.h"
#include "ui_common.h"
#include <TFT_eSPI.h>
#include <lvgl.h>

namespace {


constexpr int BUF_LINES = 40;
constexpr size_t DRAW_BYTES = PANEL * BUF_LINES * 2; // RGB565, 19,200 bytes
uint32_t lastTickAt = 0;

TFT_eSPI tft;

// Static so they never end up on a task stack.
alignas(LV_DRAW_BUF_ALIGN) std::array<uint8_t, DRAW_BYTES> drawBuf;

void flushCb(lv_display_t *disp, const lv_area_t *area, uint8_t *pxMap) {
  uint32_t w = area->x2 - area->x1 + 1;
  uint32_t h = area->y2 - area->y1 + 1;

  tft.startWrite();
  tft.setAddrWindow(area->x1, area->y1, w, h);
  tft.pushPixels((uint16_t *)pxMap, w * h);
  tft.endWrite();

  lv_display_flush_ready(disp);
}

}  // namespace

void lvPortInit() {
  pinMode(TFT_BL, OUTPUT);
  digitalWrite(TFT_BL, TFT_BACKLIGHT_ON);

  tft.init();
  tft.setRotation(0);
  tft.fillScreen(TFT_BLACK);

  lv_init();
  lastTickAt = millis();

  lv_display_t *disp = lv_display_create(PANEL, PANEL);
  if (!disp) uiFaultHalt(__FILE__, __LINE__);
  // GC9A01 wants RGB565 MSB-first over SPI; LVGL renders little-endian.
  // Render straight into the swapped format so flush needs no byte juggling.
  lv_display_set_color_format(disp, LV_COLOR_FORMAT_RGB565_SWAPPED);
  lv_display_set_flush_cb(disp, flushCb);
  lv_display_set_buffers(disp, drawBuf.data(), nullptr, drawBuf.size(),
                         LV_DISPLAY_RENDER_MODE_PARTIAL);

  lv_obj_set_style_bg_color(lv_screen_active(), lv_color_black(), 0);
  appLog("[display] synchronous RGB565 buffer=%u B\n", (unsigned)drawBuf.size());
}

void lvPortPump() {
  uint32_t now = millis();
  lv_tick_inc(now - lastTickAt);
  lastTickAt = now;
  lv_timer_handler();
}

extern "C" void uiFaultHalt(const char *file, int line) {
  // LVGL can no longer render safely. Keep the UART/idle task alive and retain
  // the diagnostic instead of repeating an allocation-triggered boot panic.
  for (;;) {
    appLog("[display] LVGL failure %s:%d; rendering halted\n", file, line);
    for (unsigned i = 0; i < 50; ++i) {
      if (esp_task_wdt_status(nullptr) == ESP_OK) esp_task_wdt_reset();
      delay(100);
    }
  }
}
