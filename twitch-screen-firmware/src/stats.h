#pragma once

#include <stdint.h>

// Live stream telemetry, populated from a TSB/3 STATS payload
// (docs/PROTOCOL.md §6.5). Declared in WIRE ORDER so the field list reads
// against the offset table; the v2 struct began with a bool and could not.
// STATS is absolute and self-contained: one captured frame fully explains the
// idle screen, and there is no delta or patch encoding to reassemble.
struct StreamStats {
  uint32_t viewers         = 0;  // +0
  uint32_t msgTotal        = 0;  // +4   chat messages since stream start (§13)
  uint32_t uptimeSec       = 0;  // +8   0 when offline
  uint32_t followers       = 0;  // +12
  uint32_t subs            = 0;  // +16
  uint32_t serverTime      = 0;  // +20  unix seconds at encode; 0 = unknown
  uint32_t streamStartedAt = 0;  // +24  unix seconds; 0 = offline or unknown
  uint16_t chatRate        = 0;  // +28  msgs/min; the UI clamps its gauge at 100
  uint8_t  live            = 0;  // +30  any non-zero means live
  uint8_t  sflags          = 0;  // +31  reserved; ignored
};
