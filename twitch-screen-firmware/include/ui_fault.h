#pragma once

#ifdef __cplusplus
extern "C" {
#endif
void uiFaultHalt(const char *file, int line) __attribute__((noreturn));
#ifdef __cplusplus
}
#endif

#define LV_ASSERT_HANDLER uiFaultHalt(__FILE__, __LINE__);
