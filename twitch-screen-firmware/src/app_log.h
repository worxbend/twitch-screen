#pragma once

#include <stdarg.h>
#include <stdio.h>
#include <array>
#include "link_transport.h"

// Drop a diagnostic if the UART cannot accept it immediately, just like link logs.
__attribute__((format(printf, 1, 2))) inline void appLog(const char *format, ...) {
  std::array<char, 240> line;
  va_list args;
  va_start(args, format);
  vsnprintf(line.data(), line.size(), format, args);
  va_end(args);
  linkPlatform().log(line.data());
}
