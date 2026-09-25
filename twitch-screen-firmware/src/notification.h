#pragma once

#include <stdint.h>
#include <string.h>

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
  bool     replay   = false;  // header flags.REPLAY (§3.2), not an eflag
  char     actor[48] = {0};   // EVENT +24, matches char[48] on the wire (§5)
  char     text[96]  = {0};   // EVENT +72, matches char[96] on the wire (§5)
};

// §6.4.1 — is this a kind this firmware knows how to draw specifically?
inline bool kindIsKnown(uint8_t code) {
  switch (code) {
    case 0x00: case 0x01: case 0x02: case 0x03:
    case 0x10: case 0x11: case 0x12: case 0x13:
    case 0x14: case 0x15: case 0x16: case 0x17:
      return true;
    default:
      return false;   // includes 0x18, permanently reserved
  }
}

// §6.4.2 — the validator that replaces v2's kindFromString(). An unknown kind
// MUST render as INFO from actor/text; it MUST NOT be dropped and MUST NOT
// close the link. Keep the raw byte in Notification::wireKind for the log line.
inline NotifyKind kindFromCode(uint8_t code) {
  return kindIsKnown(code) ? (NotifyKind)code : NotifyKind::Info;
}

inline const char *kindLabel(NotifyKind k) {
  switch (k) {
    case NotifyKind::Message:     return "MESSAGE";
    case NotifyKind::Warning:     return "WARNING";
    case NotifyKind::Alert:       return "ALERT";
    case NotifyKind::StreamStart: return "STREAM LIVE";
    case NotifyKind::StreamEnd:   return "STREAM ENDED";
    case NotifyKind::Follow:      return "FOLLOW";
    case NotifyKind::Sub:         return "SUB";
    case NotifyKind::Gift:        return "GIFT";
    case NotifyKind::Raid:        return "RAID";
    case NotifyKind::Chat:        return "CHAT";
    case NotifyKind::Bits:        return "BITS";
    default:                      return "INFO";
  }
}

// §6.4 tier: 0 is NOT APPLICABLE, not Prime. Returns nullptr when there is no
// tier to render, so a caller never prints "Tier 0".
inline const char *tierName(uint8_t tier) {
  switch (tier) {
    case 1: return "Prime";
    case 2: return "Tier 1";
    case 3: return "Tier 2";
    case 4: return "Tier 3";
    default: return 0;   // 0 = n/a; >4 is normalised to 0 by the decoder
  }
}
