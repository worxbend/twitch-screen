#include "generic_input.h"
#include <stdlib.h>
#include <string.h>
#include <stddef.h>
#ifdef ARDUINO
#include <Arduino.h>
#include <esp_heap_caps.h>
#endif
namespace {
constexpr size_t DecodeBudget = generic::DecodeLimit;
struct alignas(max_align_t) Allocation { size_t size; };
generic::DecodeMemory usage;
size_t singleAllocationLimit=DecodeBudget;
void *allocationFailure(const char *reason,size_t charge) {
  usage.exhausted=true;
#ifdef ARDUINO
  Serial.printf("[generic-decode] failure=%s requested=%u live=%u free=%u largest=%u\n",
                reason,(unsigned)charge,(unsigned)usage.live,
                (unsigned)heap_caps_get_free_size(MALLOC_CAP_8BIT),
                (unsigned)heap_caps_get_largest_free_block(MALLOC_CAP_8BIT));
#else
  (void)reason;(void)charge;
#endif
  return nullptr;
}
void *boundedMalloc(size_t n) {
  if(n>SIZE_MAX-sizeof(Allocation))return allocationFailure("overflow",n);
  const size_t charge=n+sizeof(Allocation);
  if(charge>DecodeBudget-usage.live||charge>singleAllocationLimit)return allocationFailure("budget",charge);
  auto *a=static_cast<Allocation *>(malloc(charge));
  if(!a)return allocationFailure("heap",charge);
  a->size=charge;usage.live+=charge; if(usage.live>usage.peak)usage.peak=usage.live;return a+1;
}
void boundedFree(void *p) {if(!p)return;auto *a=static_cast<Allocation *>(p)-1;usage.live-=a->size;free(a);}
__attribute__((unused)) void *boundedRealloc(void *p,size_t n) {
  if(!p)return boundedMalloc(n);
  auto *a=static_cast<Allocation *>(p)-1;const size_t old=a->size;
  if(n>SIZE_MAX-sizeof(Allocation)){usage.exhausted=true;return nullptr;}
  const size_t charge=n+sizeof(Allocation);
  if(charge>DecodeBudget-(usage.live-old)||charge>singleAllocationLimit){usage.exhausted=true;return nullptr;}
  auto *next=static_cast<Allocation *>(realloc(a,charge));if(!next){usage.exhausted=true;return nullptr;}
  next->size=charge;usage.live=usage.live-old+charge;if(usage.live>usage.peak)usage.peak=usage.live;return next+1;
}
}
#define STBI_ONLY_JPEG
#define STBI_NO_STDIO
#define STBI_NO_SIMD
#define STBI_JPEG_RGB565_OUTPUT
#define STBI_NO_HDR
#define STBI_NO_LINEAR
#define STBI_MAX_DIMENSIONS 96
#define STBI_MALLOC(n) boundedMalloc(n)
#define STBI_REALLOC(p,n) boundedRealloc(p,n)
#define STBI_FREE(p) boundedFree(p)
#define STB_IMAGE_IMPLEMENTATION
#include "../lib/vendor/progressive_jpeg/stb_system.h"
namespace generic {
bool decodePhoto(const uint8_t *data,size_t n,uint16_t *out,DecodeMemory &memory,size_t allocationLimit) {
  usage={};singleAllocationLimit=allocationLimit;
  if(!out||!validateJpeg(data,n)){memory=usage;return false;}
  int w=0,h=0,components=0;
  auto *pixels=stbi_load_from_memory(data,static_cast<int>(n),&w,&h,&components,3);
  const bool ok=pixels&&w==static_cast<int>(PhotoSide)&&h==static_cast<int>(PhotoSide);
  if(ok)memcpy(out,pixels,PhotoPixels*sizeof(uint16_t));
  stbi_image_free(pixels); memory=usage; return ok && !memory.live;
}
}
