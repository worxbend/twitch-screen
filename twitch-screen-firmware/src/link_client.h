#pragma once

#include "link_transport.h"
#include "notification.h"
#include "stats.h"

// Persistent, self-healing TCP link to the relay, speaking TSB/3 — the binary
// protocol specified in docs/PROTOCOL.md. This replaces the NDJSON v2 reader in
// every respect; v2 and v3 peers cannot interoperate (§0) and the cutover is a
// flag day: flash the device and restart the relay together.
//
// The link never gives up. Death is detected by heartbeat, silence timeout,
// read/write error, BYE or WiFi loss, and every one of them leads to backoff
// and another attempt (§12.1), never to a permanent stop.
//
// DNS, connect, RX and TX are polled with deadlines. A full notification queue
// pauses EVENT consumption while loop() keeps rendering and servicing output.
// WELCOME reports relay timers; the device retains its own fixed timers.
struct LinkWelcome {
  uint32_t latestSeq;     // highest seq the relay ever assigned; 0 = nothing yet
  uint32_t serverTime;    // unix seconds, 0 = unknown
  uint32_t sessionId;     // changes on every relay process start (§10.2)
  uint32_t caps;          // the effective intersection; authoritative (§6.1)
  uint16_t replayWindow;  // durable events the relay retains (§10.3)
};

struct LinkHooks {
  // The relay greeted us. Apply the §10.2 baseline / re-baseline rules here:
  // fresh boot or latestSeq < ours means re-baseline; sessionId is diagnostic.
  void (*onWelcome)(const LinkWelcome &w);
  // An EVENT arrived (live push or replayed backlog; see Notification::replay).
  // §10.5: the app advances its high-water mark only if it actually enqueues
  // this event, and the link reads that mark back through getLastSeq().
  bool (*onNotify)(const Notification &n);
  // False pauses the next EVENT without consuming it or its sequence number.
  bool (*canReceiveNotify)();
  // A STATS frame arrived (stream telemetry, absolute).
  void (*onStats)(const StreamStats &s);
  // Highest seq the app has successfully ENQUEUED FOR DISPLAY. Sent in HELLO to
  // drive replay, and echoed in ACK (§6.6).
  uint32_t (*getLastSeq)();
};

void linkInit(const LinkHooks *hooks, LinkTransport &transport);
// Drives IDLE -> CONNECTING -> WELCOME -> STREAMING. Call every loop.
void linkLoop();
// True once WELCOME has been received, i.e. the session is in STREAMING.
bool linkIsUp();

