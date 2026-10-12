#pragma once
#include "generic_policy.h"
namespace generic {
struct Snapshot {
  State state; FetchError photoError = FetchError::None, weatherError = FetchError::None;
  Availability availability = Availability::Ready;
  uint32_t associationRevision = 0;
  uint32_t heapBeforeDecode = 0, heapAfterDecode = 0, workerStackFree = 0;
  uint32_t photoGeneration = 0; size_t decodePeak = 0; bool memoryFailure = false;
};
// Device-lifetime task. No LVGL calls in this API or worker. All shared snapshots,
// slots and publication are mutex-protected; active pixels are immutable.
void start();
void setVisible(bool visible);
Snapshot snapshot();
int takePending(const uint16_t *&pixels, uint32_t &generation);
void releaseActive(int slot);
#ifndef ARDUINO
void testSnapshot(const Snapshot &value);
bool testPhoto(const uint8_t *data, size_t size);
void testReset();
#endif
}
