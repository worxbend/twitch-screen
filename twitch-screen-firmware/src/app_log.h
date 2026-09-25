#pragma once

#include <stdarg.h>
#include <stdio.h>
#include "link_transport.h"

// Drop a diagnostic if the UART cannot accept it immediately, just like link logs.
inline void appLog(const char *format, ...) {
  char line[240];
  va_list args;
  va_start(args, format);
  vsnprintf(line, sizeof(line), format, args);
  va_end(args);
  linkPlatform().log(line);
}
