#pragma once

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

#include "notification.h"

// At most four content characters across the full u32 range. Bottom chips have
// about 45 px each at font 14, so six-digit K strings cannot fit.
inline void formatCount(char *out, size_t size, uint32_t value) {
  if (value < 1000) {
    snprintf(out, size, "%lu", (unsigned long)value);
    return;
  }
  const uint32_t unit = value >= 1000000000u ? 1000000000u :
                        value >= 1000000u ? 1000000u : 1000u;
  const char suffix = unit == 1000000000u ? 'B' : unit == 1000000u ? 'M' : 'K';
  if (value / unit < 10)
    snprintf(out, size, "%c.%c%c", (int)('0' + value / unit),
             (int)('0' + (value % unit) / (unit / 10)), suffix);
  else snprintf(out, size, "%lu%c", (unsigned long)(value / unit), suffix);
}

// Simple perceptual brightness floor against the card's black background.
// This is a display policy, not a change to the wire color or its presence bit.
inline uint32_t readableChatColor(uint32_t rgb) {
  rgb &= 0x00ffffffu;
  const uint32_t brightness = 299u * (rgb >> 16) +
      587u * ((rgb >> 8) & 255u) + 114u * (rgb & 255u);
  return brightness >= 90000u ? rgb : 0x00ffffffu;
}

// How a card arrives. PROTOCOL.md §6.4: a receiver SHOULD render a replayed card
// without the entrance animation, so replay shows at once and leaves at once.
// Full-screen attention (the flash) is reserved for severity.
enum class Entrance : uint8_t { None, Slide, FlashThenSlide };

inline Entrance entranceFor(bool replay, NotifyKind kind) {
  if (replay) return Entrance::None;
  return kind == NotifyKind::Warning || kind == NotifyKind::Alert ? Entrance::FlashThenSlide
                                                                  : Entrance::Slide;
}

// K-118: the hold-time accent-ring pulse is an object-wide style opa animation. Below COVER it
// forces layered rendering, which repaints nearly the whole 240x240 panel on every frame for the
// whole hold. Only severity (the cards that also flash) pays for it; routine and replayed cards
// keep a static ring. Keyed off Entrance so severity is defined in one place and replay can never
// pulse.
inline bool ringPulses(Entrance e) { return e == Entrance::FlashThenSlide; }
