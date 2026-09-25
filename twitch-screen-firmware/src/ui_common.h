#pragma once

#include <lvgl.h>
#include <stdio.h>
#include <string.h>

constexpr int PANEL = 240;

inline void clearDecor(lv_obj_t *object) {
  lv_obj_set_style_border_width(object, 0, 0);
  lv_obj_remove_style(object, nullptr, LV_PART_SCROLLBAR);
  lv_obj_set_scrollable(object, false);
  lv_obj_set_clickable(object, false);
}

inline void setLabelIfChanged(lv_obj_t *label, const char *text) {
  if (strcmp(lv_label_get_text(label), text) != 0) lv_label_set_text(label, text);
}

template <size_t N>
inline void setStaticLabel(lv_obj_t *label, char (&storage)[N], const char *text) {
  if (strcmp(storage, text) == 0) return;
  snprintf(storage, N, "%s", text);
  lv_label_set_text_static(label, storage);
}
