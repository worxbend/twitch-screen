#pragma once

#include <Arduino.h>
#include "notification.h"
#include "stats.h"

// Persistent, self-healing TCP link to the notification server
// (protocol v2, docs/PROTOCOL.md). Never gives up: death detection via
// heartbeat/timeout/read-write errors, reconnect with exponential backoff.

struct LinkHooks {
  // Server answered our hello. Re-baseline here when latestSeq < your seq.
  void (*onWelcome)(uint32_t latestSeq, uint32_t serverTime);
  // A notify frame arrived (new push or replayed backlog).
  void (*onNotify)(const Notification &n);
  // A stats frame arrived (stream telemetry).
  void (*onStats)(const StreamStats &s);
  // Highest seq the app has processed; sent in hello for replay.
  uint32_t (*getLastSeq)();
};

void linkInit(const LinkHooks *hooks);
// Drives the state machine. Call every loop iteration; only the (rare)
// connect attempt blocks, up to ~3 s.
void linkLoop();
// True when connected and welcomed.
bool linkIsUp();

// WiFi helper used by the link layer (kept here so main stays thin).
bool wifiEnsureConnected(uint32_t timeoutMs = 10000);
