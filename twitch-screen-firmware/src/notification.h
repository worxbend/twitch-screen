#pragma once

#include <stdint.h>
#include <stddef.h>

// One notification card, populated from a TSB/3 EVENT payload
// (docs/PROTOCOL.md §6.4). This is the *display* record, not the wire record:
// tsb::TsbEvent is the wire record and lives in proto_codec.h.
//
// The enumerator values below ARE the wire codes from §6.4.1. They are not an
// ordinal 0..9 list: `kind` is the byte the relay wrote at EVENT payload offset
// +20, so a kind added to the protocol can never silently renumber the ones
// already flashed onto a device. 0x18 is permanently reserved (§6.4.1, §8) and
// is deliberately absent.
enum class NotifyKind : uint8_t {
  Info        = 0x00,
  Message     = 0x01,
  Warning     = 0x02,
  Alert       = 0x03,
  StreamStart = 0x10,
  StreamEnd   = 0x11,
  Follow      = 0x12,
  Sub         = 0x13,
  Gift        = 0x14,
  Raid        = 0x15,
  Chat        = 0x16,
  Bits        = 0x17
};

struct Notification {
  uint32_t seq      = 0;   // EVENT +0,  §10.1 — never narrowed
  uint32_t ts       = 0;   // EVENT +4,  unix seconds at the relay, 0 = unknown
  uint32_t value    = 0;   // EVENT +8,  meaning fixed per kind (§6.4.1)
  uint16_t months   = 0;   // EVENT +16
  uint16_t ttl_ds   = 0;   // EVENT +18, display time in 100 ms units; 0 = default
  NotifyKind kind   = NotifyKind::Info;  // EVENT +20, validated (§6.4.2)
  uint8_t  wireKind = 0;   // EVENT +20 verbatim, so a folded unknown still logs
  uint8_t  tier     = 0;   // EVENT +21, 0 = n/a, 1 = Prime, 2..4 = Tier 1..3
  uint8_t  eflags   = 0;   // EVENT +22, unknown bits ignored (§6.4)
  bool     anonymous = false;
  bool     chatColorPresent = false;
  bool     replay   = false;  // header flags.REPLAY (§3.2), not an eflag
  char     actor[48] = {0};   // EVENT +24, matches char[48] on the wire (§5)
  char     text[96]  = {0};   // EVENT +72, matches char[96] on the wire (§5)
};

// Wire ordinals stay stable; reserved slots are empty. Presentation policy lives
// in one table shared by the display and host tests, without an LVGL dependency.
struct KindPresentation {
  const char *label;
  const char *icon;
  uint32_t color;
  uint32_t holdMs;
};
constexpr KindPresentation KIND_PRESENTATIONS[] = {
  {"INFO", "i", 0x26C6DA, 3500},
  {"MESSAGE", "M", 0x66BB6A, 3500},
  {"WARNING", "!", 0xFFA726, 3500},
  {"ALERT", "!", 0xEF5350, 3500},
  {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
  {"STREAM LIVE", "\xef\x81\x8b", 0x00E676, 5000}, // LV_SYMBOL_PLAY
  {"STREAM ENDED", "\xef\x81\x8d", 0x78909C, 5000}, // LV_SYMBOL_STOP
  {"FOLLOW", "F", 0x9146FF, 3500},
  {"SUB", "S", 0xFFB300, 3500},
  {"GIFT", "G", 0xFF75E6, 3500},
  {"RAID", "R", 0xEB0400, 3500},
  {"CHAT", "C", 0x26C6DA, 2500},
  {"BITS", "B", 0xBF94FF, 3500}
};
static_assert(sizeof(KIND_PRESENTATIONS) / sizeof(KIND_PRESENTATIONS[0]) ==
              static_cast<size_t>(NotifyKind::Bits) + 1, "kind presentation coverage");

inline bool kindIsKnown(uint8_t code) {
  return code < sizeof(KIND_PRESENTATIONS) / sizeof(KIND_PRESENTATIONS[0]) &&
         KIND_PRESENTATIONS[code].label != nullptr;
}

inline NotifyKind kindFromCode(uint8_t code) {
  return kindIsKnown(code) ? static_cast<NotifyKind>(code) : NotifyKind::Info;
}

inline const KindPresentation &kindPresentation(NotifyKind kind) {
  return KIND_PRESENTATIONS[static_cast<uint8_t>(kindFromCode(static_cast<uint8_t>(kind)))];
}

inline const char *kindLabel(NotifyKind kind) { return kindPresentation(kind).label; }

// §6.4 tier: 0 is NOT APPLICABLE, not Prime. Returns nullptr when there is no
// tier to render, so a caller never prints "Tier 0".
inline const char *tierName(uint8_t tier) {
  switch (tier) {
    case 1: return "Prime";
    case 2: return "Tier 1";
    case 3: return "Tier 2";
    case 4: return "Tier 3";
    default: return nullptr;   // 0 = n/a; >4 is normalised to 0 by the decoder
  }
}
