#pragma once
#include "generic_input.h"
#include <stdio.h>
#include <string.h>

namespace generic {
// Ask Picsum to choose a random image for each network request. A new nonce
// prevents reuse of a cached random redirect; the signed Fastly URL is supplied
// by the provider and checked by the existing HTTPS redirect allowlist.
inline Url randomPhoto(uint32_t nonce) {
  Url url;
  strcpy(url.host,"picsum.photos");
  snprintf(url.path,sizeof(url.path),"/96/96.jpg?random=%lu",static_cast<unsigned long>(nonce));
  return url;
}
}
