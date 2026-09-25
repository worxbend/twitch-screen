// Round-LCD notification client. Protocol TSB/3: persistent TCP on port 8099,
// fixed-offset binary frames, relay push. See docs/PROTOCOL.md — it is the
// contract, and this file implements §10.2 (baseline) and §10.5 (the queue).
// UI: LVGL (ui_idle / ui_notify).
//
// Wiring (matches build_flags in platformio.ini).
// DevKit V1 silkscreen "D<n>" == GPIO<n>; GPIO16 is silkscreened "RX2".
//   LCD pin  ->  Silkscreen  (GPIO)
//   VCC      ->  3V3
//   GND      ->  GND
//   DIN      ->  D23         (23, VSPI MOSI)
//   CLK      ->  D18         (18, VSPI SCK)
//   CS       ->  D5          (5)
//   DC       ->  RX2         (16)
//   RST      ->  D4          (4)
//   BL       ->  D15         (15, PWM dimmable; tie to 3V3 for always-on)

#include <Arduino.h>

#include "credentials.h"
#include "notification.h"
#include "notify_queue.h"
#include "link_client.h"
#include "lv_port.h"
#include "ui_idle.h"
#include "ui_notify.h"

namespace {

// §5: the device notification queue holds at least 8 entries.
constexpr size_t QUEUE_CAP = 8;

// The queue and the sequence accounting live in notify_queue.h so that §10.2
// and §10.5 are covered by the host test rather than only by this comment.
NotifyQueue<QUEUE_CAP> queue;

// §10.2, applied in the order the specification gives.
void onWelcome(const LinkWelcome &w) {
  const NotifyQueue<QUEUE_CAP>::Greet g = queue.greet(w.latestSeq, w.sessionId);
  if (g == NotifyQueue<QUEUE_CAP>::Greet::Rebaselined) {
    Serial.printf("[app] re-baseline: last_seq=%lu session=%08lx\n",
                  (unsigned long)queue.lastSeq(), (unsigned long)w.sessionId);
  } else {
    Serial.printf("[app] resuming at last_seq=%lu, expecting replay of up to %u\n",
                  (unsigned long)queue.lastSeq(), (unsigned)w.replayWindow);
  }
}

void onNotify(const Notification &n) {
  const NotifyQueue<QUEUE_CAP>::Offer r = queue.offer(n);

  if (r == NotifyQueue<QUEUE_CAP>::Offer::Duplicate) return;

  if (r == NotifyQueue<QUEUE_CAP>::Offer::Refused) {
    // §10.5 rule 2: the newest is refused and the high-water mark does not move,
    // so the next greet replays this event instead of stepping over it.
    Serial.printf("[app] queue full, refused #%lu %s (last_seq stays %lu, "
                  "%lu refused, %lu shown)\n",
                  (unsigned long)n.seq, kindLabel(n.kind),
                  (unsigned long)queue.lastSeq(), (unsigned long)queue.refused(),
                  (unsigned long)queue.shown());
    return;
  }

  if (!kindIsKnown(n.wireKind)) {
    // §6.4.2: rendered as INFO from actor/text, never dropped.
    Serial.printf("[app] new #%lu unknown kind 0x%02x -> INFO: %s\n",
                  (unsigned long)n.seq, (unsigned)n.wireKind, n.actor);
  } else {
    Serial.printf("[app] new #%lu %s%s: %s\n", (unsigned long)n.seq,
                  kindLabel(n.kind), n.replay ? " (replay)" : "", n.actor);
  }
}

uint32_t getLastSeq() { return queue.lastSeq(); }

void onStats(const StreamStats &s) { uiIdleSetStats(s); }

const LinkHooks HOOKS = {onWelcome, onNotify, onStats, getLastSeq};

}  // namespace

void setup() {
  Serial.begin(115200);

  lvPortInit();
  uiIdleBuild();
  uiNotifyInit();

  linkInit(&HOOKS);
  bool online = wifiEnsureConnected(15000);
  uiIdleSetOnline(online);
}

void loop() {
  lvPortPump();

  bool wifiOk = wifiEnsureConnected();
  linkLoop();

  uiIdleSetOnline(wifiOk && linkIsUp());

  Notification n;
  if (!uiNotifyBusy() && queue.take(n)) {
    uiNotifyShow(n);
  }

  delay(5);
}
