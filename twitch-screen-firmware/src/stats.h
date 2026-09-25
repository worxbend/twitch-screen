#pragma once

#include <stdint.h>

// Live stream statistics pushed by the server (protocol v2, "stats" op).
struct StreamStats {
  bool live = false;
  uint32_t viewers = 0;
  uint32_t followers = 0;
  uint32_t subs = 0;
  uint32_t uptimeSec = 0;
  uint32_t chatRate = 0;  // messages per minute, 0..100+ (UI clamps)
  uint32_t msgTotal = 0;  // chat messages since stream start
};
