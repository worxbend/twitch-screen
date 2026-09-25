// Round-LCD notification client. Protocol v2: persistent TCP, server push.
// See docs/PROTOCOL.md. UI: LVGL (ui_gauge / ui_idle / ui_notify).
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
#include "link_client.h"
#include "lv_port.h"
#include "ui_idle.h"
#include "ui_notify.h"

namespace {

constexpr size_t QUEUE_CAP = 8;

Notification queueBuf[QUEUE_CAP];
size_t qHead = 0, qCount = 0;

uint32_t lastSeq = 0;
bool baselineDone = false;
uint32_t totalReceived = 0;

void enqueue(const Notification &n) {
  if (qCount == QUEUE_CAP) {  // drop oldest
    qHead = (qHead + 1) % QUEUE_CAP;
    qCount--;
  }
  queueBuf[(qHead + qCount) % QUEUE_CAP] = n;
  qCount++;
}

bool dequeue(Notification &n) {
  if (qCount == 0) return false;
  n = queueBuf[qHead];
  qHead = (qHead + 1) % QUEUE_CAP;
  qCount--;
  return true;
}

void onWelcome(uint32_t latestSeq, uint32_t) {
  if (!baselineDone || latestSeq < lastSeq) {
    // Fresh boot, or server restarted and lost state: adopt its seq.
    lastSeq = latestSeq;
    baselineDone = true;
    Serial.printf("[app] baseline set, latest_seq=%lu\n", (unsigned long)lastSeq);
  }
}

void onNotify(const Notification &n) {
  if (n.seq <= lastSeq) return;  // duplicate or replay of seen item
  lastSeq = n.seq;
  totalReceived++;
  Serial.printf("[app] new #%lu %s: %s\n", (unsigned long)n.seq,
                kindLabel(n.kind), n.title);
  enqueue(n);
}

uint32_t getLastSeq() { return lastSeq; }

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
  if (!uiNotifyBusy() && dequeue(n)) {
    uiNotifyShow(n);
  }

  delay(5);
}
