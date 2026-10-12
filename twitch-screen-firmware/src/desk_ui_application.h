#pragma once

#include "desk_receiver.h"
#include "ui_base.h"
#include "ui_idle.h"
#include "ui_notify.h"

namespace desk {
// Called only after constructing every base widget and binding the complete
// notification lifecycle. Failure leaves boot identity zero: HELLO stays classic.
inline bool initializeApplicationBoot(Receiver &receiver, uint64_t boot) {
  if (!uiBaseView().built() || !uiNotifyReady()) return false;
  return receiver.initializeBootIdentity(boot);
}

// Shared by the Arduino loop and actual LVGL integration tests. Every render and
// reveal samples time and applies current policy before uncovered pixels render.
inline void applyApplicationView(Receiver &receiver, uint32_t rawMillis) {
  receiver.tick(rawMillis);
  const Selection selected = receiver.controller().reconcile(receiver.now(), receiver.calibrated());
  if (receiver.negotiated()) uiIdleApplyStats(receiver.applicationStats());
  uiBaseView().apply(selected, receiver.weather(), receiver.cpu());
}
}  // namespace desk
