#pragma once

#include <string.h>

#include "notification.h"
#include "proto_codec.h"

// The one place an EVENT payload becomes a display card (docs/PROTOCOL.md §6.4).
// It lives in a header, freestanding and free of <Arduino.h>, so the host test
// can drive it with the golden bytes of §18 and prove that 0x12 really does
// arrive at the renderer as FOLLOW.
inline void notificationFromEvent(const tsb::TsbEvent &e, uint8_t frameFlags,
                                  Notification &n) {
  n.seq      = e.seq;
  n.ts       = e.ts;
  n.value    = e.value;
  n.months   = e.months;
  n.ttl_ds   = e.ttl_ds;              // decoder has already clamped it to 6000
  n.wireKind = e.kind;                // the raw byte, kept for the log line
  n.kind     = kindFromCode(e.kind);  // §6.4.2: unknown folds to INFO, never drops
  n.tier     = e.tier;                // decoder has already folded tier > 4 to 0
  n.eflags   = e.eflags;
  n.replay   = (frameFlags & tsb::FLAG_REPLAY) != 0;   // §3.2, header not eflags

  // §9.1. The codec already forced the last byte of ITS copy to NUL; this copy
  // is the same width and forces its own, because a receiver never trusts a
  // sender for termination and never trusts a second-hand buffer either.
  memcpy(n.actor, e.actor, sizeof(n.actor));
  n.actor[sizeof(n.actor) - 1] = '\0';
  memcpy(n.text, e.text, sizeof(n.text));
  n.text[sizeof(n.text) - 1] = '\0';

  // reserved1 (+12..+15) and reserved2 (+23) are deliberately NOT read. §8.1
  // holds those five bytes open for a future monetary amount; a v4 EVENT must
  // reach a v3 device with them intact, which means not interpreting them here
  // any more than the decoder rewrote them there.
}
