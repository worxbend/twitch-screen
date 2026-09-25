#pragma once

#include <Arduino.h>
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
// Everything on the read path is non-blocking from loop()'s point of view: the
// codec's FrameReader is a resumable state machine over a static buffer, so a
// frame split across TCP segments resumes exactly where it stopped (§4.1). Only
// the (rare) connect attempt blocks, up to ~3 s.

// WELCOME (§6.2). The device keeps its own timers, so ping_interval_s and
// idle_timeout_s are informational and are only logged on mismatch.
struct LinkWelcome {
  uint32_t latestSeq;     // highest seq the relay ever assigned; 0 = nothing yet
  uint32_t serverTime;    // unix seconds, 0 = unknown
  uint32_t sessionId;     // changes on every relay process start (§10.2)
  uint32_t caps;          // the effective intersection; authoritative (§6.1)
  uint16_t replayWindow;  // durable events the relay retains (§10.3)
};

struct LinkHooks {
  // The relay greeted us. Apply the §10.2 baseline / re-baseline rules here:
  // fresh boot, a changed sessionId, or latestSeq < ours all mean re-baseline.
  void (*onWelcome)(const LinkWelcome &w);
  // An EVENT arrived (live push or replayed backlog; see Notification::replay).
  // §10.5: the app advances its high-water mark only if it actually enqueues
  // this event, and the link reads that mark back through getLastSeq().
  void (*onNotify)(const Notification &n);
  // A STATS frame arrived (stream telemetry, absolute).
  void (*onStats)(const StreamStats &s);
  // Highest seq the app has successfully ENQUEUED FOR DISPLAY. Sent in HELLO to
  // drive replay, and echoed in ACK (§6.6).
  uint32_t (*getLastSeq)();
};

void linkInit(const LinkHooks *hooks);
// Drives the state machine: IDLE -> CONNECT -> STREAMING. Call every loop
// iteration; only the connect attempt blocks, up to ~3 s.
void linkLoop();
// True once WELCOME has been received, i.e. the session is in STREAMING.
bool linkIsUp();

// WiFi helper used by the link layer (kept here so main stays thin).
bool wifiEnsureConnected(uint32_t timeoutMs = 10000);
