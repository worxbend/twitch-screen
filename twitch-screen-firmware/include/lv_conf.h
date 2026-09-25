#ifndef LV_CONF_H
#define LV_CONF_H

/* Color */
#define LV_COLOR_FORMAT_DEFAULT LV_COLOR_FORMAT_RGB565

/* Memory: heap-backed (64 KB static pool would overflow DRAM on WROOM-32) */
#define LV_USE_STDLIB_MALLOC    LV_STDLIB_CLIB
#define LV_USE_STDLIB_STRING    LV_STDLIB_CLIB
#define LV_USE_STDLIB_SPRINTF   LV_STDLIB_CLIB

#define LV_USE_OS               LV_OS_NONE

/* Dark theme by default */
#define LV_USE_THEME_DEFAULT    1
#define LV_THEME_DEFAULT_DARK   1

/* Fonts actually used by the UI */
#define LV_FONT_MONTSERRAT_14   1
#define LV_FONT_MONTSERRAT_20   1
#define LV_FONT_MONTSERRAT_28   1
#define LV_FONT_MONTSERRAT_48   1
#define LV_FONT_DEFAULT         &lv_font_montserrat_14

/* Widgets used */
#define LV_USE_ARC              1
#define LV_USE_LABEL            1

#define LV_USE_FLOAT            1

#endif /* LV_CONF_H */
