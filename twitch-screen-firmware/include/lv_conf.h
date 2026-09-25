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

/* Only these widgets are used; disable unrelated default widgets explicitly. */
#define LV_USE_ARC              1
#define LV_USE_LABEL            1
#define LV_USE_IMAGE            1

#define LV_USE_ANIMIMG          0
#define LV_USE_ARCLABEL         0
#define LV_USE_BAR              0
#define LV_USE_BUTTON           0
#define LV_USE_BUTTONMATRIX     0
#define LV_USE_CALENDAR         0
#define LV_USE_CANVAS           0
#define LV_USE_CHART            0
#define LV_USE_CHECKBOX         0
#define LV_USE_DROPDOWN         0
#define LV_USE_IMAGEBUTTON      0
#define LV_USE_KEYBOARD         0
#define LV_USE_LED              0
#define LV_USE_LINE             0
#define LV_USE_LIST             0
#define LV_USE_MENU             0
#define LV_USE_MSGBOX           0
#define LV_USE_ROLLER           0
#define LV_USE_SCALE            0
#define LV_USE_SLIDER           0
#define LV_USE_SPAN             0
#define LV_USE_SPINBOX          0
#define LV_USE_SPINNER          0
#define LV_USE_SWITCH           0
#define LV_USE_TABLE            0
#define LV_USE_TABVIEW          0
#define LV_USE_TEXTAREA         0
#define LV_USE_TILEVIEW         0
#define LV_USE_WIN              0


#define LV_USE_FLOAT            0

/* Report allocation/assert failures without a panic/reboot loop. */
#define LV_USE_ASSERT_MALLOC    1
#define LV_ASSERT_USE_CUSTOM_INCLUDE 1
#define LV_ASSERT_CUSTOM_INCLUDE "ui_fault.h"

#endif /* LV_CONF_H */
