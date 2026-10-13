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
#include <esp_system.h>
#include <esp_task_wdt.h>
#include <esp_heap_caps.h>
#include "runtime_metrics.h"

#include "app_log.h"
#include "notification.h"
#include "desk_receiver.h"
#include "desk_application.h"
#include "desk_ui_application.h"
#include "link_client.h"
#include "lv_port.h"
#include "ui_idle.h"
#include "ui_notify.h"

namespace {

// §5: the device notification queue holds at least 8 entries.
constexpr size_t QUEUE_CAP = 8;
constexpr uint32_t WATCHDOG_TIMEOUT_S = 10;
constexpr uint32_t STACK_REPORT_MS = 60000;

// One controller owns classic and DESK pending cards and active lifecycle.
// DESK remains disabled until actual UI/lifecycle initialization supplies the
// boot identity. Session readiness still requires calibrated coherent SYNC_END.
desk::Controller controller;
desk::Receiver receiver(controller, 0, true);
desk::RuntimeMetrics runtimeMetrics;

void reconcileView() {
  desk::applyApplicationView(receiver, millis());
  // Transition-only, secret-free diagnostics. Application observations are not
  // proof of LCD pixels; appLog drops rather than blocks on a full UART.
  static uint8_t lastBase = UINT8_MAX, lastReason = UINT8_MAX;
  static uint32_t lastWeather = 0;
  const desk::Selection selected = controller.selection(receiver.now());
  const uint8_t base = static_cast<uint8_t>(selected.base);
  const uint8_t reason = static_cast<uint8_t>(selected.reason);
  const uint32_t weather = selected.base == desk::Base::Weather ? controller.weatherRevision() : 0;
  if (base != lastBase || reason != lastReason || weather != lastWeather) {
    appLog("[view] base=%u reason=%u deskReady=%u weatherRevision=%lu covered=%u\n",
           (unsigned)base, (unsigned)reason, (unsigned)receiver.ready(),
           (unsigned long)weather, (unsigned)uiBaseView().covered());
    lastBase = base; lastReason = reason; lastWeather = weather;
  }
}
void onHold() { receiver.tick(millis()); controller.hold(receiver.now()); }
void onExit() {
  reconcileView();
  controller.beginExit(receiver.now(), receiver.calibrated());
}
void onComplete() {
  reconcileView();
  controller.complete(receiver.now(), receiver.calibrated());
}
const NotifyLifecycle LIFECYCLE = {onHold, onExit, onComplete};

// §10.2, applied in the order the specification gives.
void onWelcome(const LinkWelcome &w) {
  const desk::ApplicationGreeting g = desk::applyApplicationGreeting(controller, w);
  if (g == desk::ApplicationGreeting::Desk) return;
  if (controller.legacySessionChanged()) {
    appLog("[app] relay session changed: %08lx\n", (unsigned long)w.sessionId);
  }
  if (g == desk::ApplicationGreeting::Rebaselined) {
    appLog("[app] re-baseline: last_seq=%lu session=%08lx\n",
                  (unsigned long)controller.legacySeq(), (unsigned long)w.sessionId);
  } else {
    appLog("[app] resuming at last_seq=%lu, expecting replay of up to %u\n",
                  (unsigned long)controller.legacySeq(), (unsigned)w.replayWindow);
  }
}

bool onNotify(const Notification &n) {
  const desk::Admission r = controller.legacyAdmit(n);

  if (r == desk::Admission::Duplicate) return true;

  if (r == desk::Admission::Refused) {
    // §10.5 rule 2: the newest is refused and the high-water mark does not move,
    // so the next greet replays this event instead of stepping over it.
    appLog("[app] queue full, refused #%lu %s (last_seq stays %lu, "
                  "%lu refused, %lu enqueued)\n",
                  (unsigned long)n.seq, kindLabel(n.kind),
                  (unsigned long)controller.legacySeq(), (unsigned long)controller.refusedTotal(),
                  (unsigned long)controller.legacyAccepted());
    return false;
  }

  if (!kindIsKnown(n.wireKind)) {
    // §6.4.2: rendered as INFO from actor/text, never dropped.
    appLog("[app] new #%lu unknown kind 0x%02x -> INFO: %s\n",
                  (unsigned long)n.seq, (unsigned)n.wireKind, n.actor);
  } else {
    appLog("[app] new #%lu %s%s: %s\n", (unsigned long)n.seq,
                  kindLabel(n.kind), n.replay ? " (replay)" : "", n.actor);
  }
  return true;
}

bool canReceiveNotify() { return controller.pendingCount() < QUEUE_CAP; }

uint32_t getLastSeq() { return controller.legacySeq(); }

void onStats(const StreamStats &s) { uiIdleSetStats(s); }

const LinkHooks HOOKS = {onWelcome, onNotify, canReceiveNotify, onStats, getLastSeq};

}  // namespace

void setup() {
  Serial.setTxBufferSize(1024);
  Serial.begin(115200);
  appLog("[app] reset reason=%d\n", (int)esp_reset_reason());

  lvPortInit();
  uiBaseView().build(1);
  uiNotifyInit();
  uiNotifySetLifecycle(&LIFECYCLE);

  // Ephemeral correlation identity, not a credential. Bind it only when the
  // real base widgets and notification lifecycle are ready for the full profile.
  const uint64_t boot = (static_cast<uint64_t>(esp_random()) << 32) | esp_random();
  const bool initialized = desk::initializeApplicationBoot(receiver, boot ? boot : 1);
  appLog("[app] desk runtime=%s\n", initialized && receiver.enabled() ? "ready" : "classic fallback");
  reconcileView();
  lvPortPump(); // render local disconnected orbit before starting network work
  // Observe association only; link transport remains the sole Wi-Fi reconnect owner.
  generic::start();
  linkInit(&HOOKS, linkPlatform(), &receiver);
  // IDF 4.4 updates an existing TWDT configuration; do not assume success.
  const esp_err_t watchdog = esp_task_wdt_init(WATCHDOG_TIMEOUT_S, true);
  if (watchdog == ESP_OK) enableLoopWDT();
  const esp_err_t subscribed = esp_task_wdt_status(nullptr);
  appLog("[app] watchdog init=%s loop=%s timeout=%lus\n",
         esp_err_to_name(watchdog), esp_err_to_name(subscribed),
         (unsigned long)WATCHDOG_TIMEOUT_S);
}

void loop() {
  runtimeMetrics.loopStarted(micros());
  // Dispatch input and reconcile local deadlines before LVGL timers can reveal.
  linkLoop();
  const uint32_t uiStartedUs = micros();
  reconcileView();
  lvPortPump();

  desk::Card next;
  if (!uiNotifyBusy() && controller.start(receiver.now(), next)) {
    uiNotifySetBacklog(controller.pendingCount());
    uiNotifyShow(next.notification, next.origin != desk::Origin::Unknown);
  }

  runtimeMetrics.uiFinished(uiStartedUs, micros());

  static uint32_t lastStackReportAt = 0;
  if (millis() - lastStackReportAt >= STACK_REPORT_MS) {
    lastStackReportAt = millis();
    // Sampled minima, not continuous heap/fragmentation observations. ESP-IDF
    // reports task stack high-water in bytes; hardware acceptance stays separate.
    runtimeMetrics.sampleResources(heap_caps_get_free_size(MALLOC_CAP_8BIT),
        heap_caps_get_largest_free_block(MALLOC_CAP_8BIT), uxTaskGetStackHighWaterMark(nullptr));
    appLog("[app] loop stack minimum free=%lu B\n",
           (unsigned long)runtimeMetrics.stackMinimum());
    appLog("[metrics-loop] uptimeMs=%lu samples=%lu p95UpperUs=%lu maxUs=%lu saturated=%u\n",
           (unsigned long)lastStackReportAt, (unsigned long)runtimeMetrics.loop().samples(),
           (unsigned long)runtimeMetrics.loop().p95UpperUs(), (unsigned long)runtimeMetrics.loop().maximum(),
           (unsigned)runtimeMetrics.loop().saturated());
    appLog("[metrics-ui] uptimeMs=%lu samples=%lu p95UpperUs=%lu maxUs=%lu saturated=%u\n",
           (unsigned long)lastStackReportAt, (unsigned long)runtimeMetrics.ui().samples(),
           (unsigned long)runtimeMetrics.ui().p95UpperUs(), (unsigned long)runtimeMetrics.ui().maximum(),
           (unsigned)runtimeMetrics.ui().saturated());
    appLog("[metrics-resources] uptimeMs=%lu sampledFreeHeapMinBytes=%lu "
           "sampledLargestBlockMinBytes=%lu stackMinBytes=%lu\n",
           (unsigned long)lastStackReportAt, (unsigned long)runtimeMetrics.freeHeapMinimum(),
           (unsigned long)runtimeMetrics.largestBlockMinimum(), (unsigned long)runtimeMetrics.stackMinimum());
  }
  delay(5);
}
