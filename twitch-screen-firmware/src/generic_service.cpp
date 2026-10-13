#include "generic_service.h"
#include "generic_fetch.h"
#include "generic_photos.h"
#include <stdlib.h>
#include <string.h>
#ifdef ARDUINO
#include <Arduino.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <atomic>
#include <time.h>
#include <esp_sntp.h>
#include <lwip/dns.h>
#include <lwip/tcpip.h>
#include <freertos/semphr.h>
#endif
namespace generic {
namespace {
Snapshot published;
Slots slots;
uint16_t *pictures[2] = {};
#ifdef ARDUINO
SemaphoreHandle_t mutex = nullptr;
std::atomic<TaskHandle_t> task{nullptr};
std::atomic<Availability> availability{Availability::Unavailable};
uint8_t *workerBody=nullptr;
size_t workerBodyCapacity=0;
bool started=false;
std::atomic<bool> associated{false}, visible{false}, pictureDemand{true};
std::atomic<uint32_t> associationGeneration{0};
portMUX_TYPE associationLock=portMUX_INITIALIZER_UNLOCKED;
struct Guard { Guard() { xSemaphoreTake(mutex, portMAX_DELAY); } ~Guard(){ xSemaphoreGive(mutex); } };
void wake() { const auto handle=task.load();if(handle)xTaskNotifyGive(handle); }
#else
struct Guard {};
void wake() {}
#endif
}
Snapshot snapshot() {
#ifdef ARDUINO
  if(!mutex){
    Snapshot result;result.state.association(associated.load());
    result.associationRevision=associationGeneration.load();
    result.availability=availability.load();result.memoryFailure=true;
    result.photoError=result.weatherError=FetchError::Memory;return result;
  }
#endif
  Guard guard; (void)guard;
  Snapshot result=published;
#ifdef ARDUINO
  result.state.association(associated.load());
  if(result.associationRevision!=associationGeneration.load()) {
    result.state.evidence=false;result.state.attempted=false;
  }
#endif
  return result;
}
int takePending(const uint16_t *&pixels,uint32_t &generation) {
#ifdef ARDUINO
  if(!mutex)return -1;
#endif
  Guard guard;(void)guard;const int i=slots.pending();if(i<0)return -1;
  slots.activate(i); pixels=pictures[i];generation=slots.generation[i];
#ifdef ARDUINO
  pictureDemand.store(true);wake(); // Prefetch immediately, BEFORE the next one-minute rotation.
#endif
  return i;
}
void releaseActive(int i) {
#ifdef ARDUINO
  if(!mutex)return;
#endif
  {Guard guard;(void)guard;slots.release(i);}
#ifdef ARDUINO
  pictureDemand.store(true);
#endif
  wake();
}
#ifdef ARDUINO
namespace {
// SDK's CONFIG_MBEDTLS_CERTIFICATE_BUNDLE binary verified in libmbedtls.a.
// Arduino's adapter has separate state: pass the embedded pointer explicitly.
// https://github.com/espressif/arduino-esp32/blob/2.0.17/libraries/WiFiClientSecure/src/esp_crt_bundle.c
extern "C" const uint8_t bundleStart[] asm("_binary_x509_crt_bundle_start");
extern "C" const uint8_t bundleEnd[] asm("_binary_x509_crt_bundle_end");
struct DnsRequest {
  std::atomic<bool> outstanding{false}, done{false}, ok{false};
  uint32_t ip=0;char host[64]={};
} dns;
void dnsResult(const char *,const ip_addr_t *ip,void *) {
  // Only the TCP/IP thread writes ip; release/acquire done publishes it.
  dns.ok.store(ip&&IP_IS_V4(ip));
  if(dns.ok.load())dns.ip=ip4_addr_get_u32(ip_2_ip4(ip));
  dns.done.store(true);dns.outstanding.store(false);wake();
}
void dnsBegin(void *) {
  ip_addr_t ip;
  const err_t e=dns_gethostbyname(dns.host,&ip,dnsResult,nullptr);
  if(e==ERR_OK)dnsResult(nullptr,&ip,nullptr);
  else if(e!=ERR_INPROGRESS)dnsResult(nullptr,nullptr,nullptr);
}
bool cancelled(uint32_t generation) {return !associated.load()||!visible.load()||associationGeneration.load()!=generation;}
bool resolve(const char *host,IPAddress &address,uint32_t generation,uint32_t timeout) {
  if(dns.outstanding.load())return false; // Late callbacks keep stable storage, no use-after-free.
  strcpy(dns.host,host);dns.done.store(false);dns.ok.store(false);dns.outstanding.store(true);
  if(tcpip_try_callback(dnsBegin,nullptr)!=ERR_OK){dns.outstanding.store(false);return false;}
  const uint32_t begin=millis();
  while(!dns.done.load()&&!cancelled(generation)) {
    const uint32_t elapsed=millis()-begin;if(elapsed>=timeout)break;
    ulTaskNotifyTake(pdTRUE,pdMS_TO_TICKS(timeout-elapsed));
  }
  if(!dns.done.load()||!dns.ok.load())return false;
  address=IPAddress(dns.ip);return true;
}
struct EspTransport final : PublicTransport {
  WiFiClientSecure client;
  uint32_t begin, generation;
  explicit EspTransport(uint32_t g):begin(millis()),generation(g) {}
  uint32_t now() const override {return millis();}
  bool cancelled() const override {return !associated.load()||!visible.load()||associationGeneration.load()!=generation;}
  FetchError open(const char *host,uint32_t remaining) override {
    const uint32_t opened=millis();IPAddress address;
    if(!resolve(host,address,generation,remaining<DnsMs?remaining:DnsMs))
      return cancelled()?FetchError::Cancelled:millis()-opened>=remaining?FetchError::Deadline:FetchError::Dns;
    // The SDK connect/handshake/write stages cannot be cancelled mid-call. Only
    // start if their 1s/5s/1s limits plus scheduling margin fit in the whole chain.
    if(millis()-opened>=remaining||remaining-(millis()-opened)<8000)return FetchError::Deadline;
    client.setCACertBundle(bundleStart);client.setTimeout(1);client.setHandshakeTimeout(5);
    const uint32_t tlsStarted=millis(),heapBefore=ESP.getFreeHeap();
    const bool connected=client.connect(address,443,host,nullptr,nullptr,nullptr);
    char detail[96]={};const int tlsError=connected?0:client.lastError(detail,sizeof(detail));
    Serial.printf("[generic-tls] host=%s elapsedMs=%lu connected=%u error=%d heapBefore=%lu heapAfter=%lu detail=%s\n",
                  host,(unsigned long)(millis()-tlsStarted),(unsigned)connected,tlsError,
                  (unsigned long)heapBefore,(unsigned long)ESP.getFreeHeap(),detail);
    if(!connected)return FetchError::Tls;
    return FetchError::None;
  }
  bool write(const uint8_t *request,size_t size) override {return client.write(request,size)==size;}
  int read() override {
    for(;;) {
      if(cancelled()||millis()-begin>=RequestMs)return -2;
      if(client.available()) {uint8_t b;if(client.read(&b,1)==1)return b;}
      if(!client.connected())return -1;
      ulTaskNotifyTake(pdTRUE,pdMS_TO_TICKS(10)); // Bounded active request IO, not Wi-Fi polling.
    }
  }
  void close() override {client.stop();}
};
FetchError fetch(Url url,bool photo,uint8_t *body,size_t cap,size_t &size,uint32_t generation,PeerReached &peer) {
  EspTransport transport(generation);
  return fetchPublic(transport,url,photo,body,cap,size,&peer);
  // Adapter and complete TLS object's destructor run here BEFORE decode/JSON.
}
void associationEvent(WiFiEvent_t e) {
  if(e!=ARDUINO_EVENT_WIFI_STA_GOT_IP&&e!=ARDUINO_EVENT_WIFI_STA_DISCONNECTED&&e!=ARDUINO_EVENT_WIFI_STA_LOST_IP)return;
  portENTER_CRITICAL(&associationLock);
  associated.store(e==ARDUINO_EVENT_WIFI_STA_GOT_IP);associationGeneration.fetch_add(1);
  portEXIT_CRITICAL(&associationLock);wake();
}
void timeSynced(struct timeval *) {wake();}
void worker(void *) {
  uint32_t observedGeneration=UINT32_MAX,nextWeather=0,nextPhoto=0;
  bool weatherDue=true,photoDue=true,clockStarted=false;uint32_t associationAt=0;
  auto *body=workerBody;
  const bool photos=workerBodyCapacity==BodyLimit&&pictures[0]&&pictures[1];
  for(;;) {
    const uint32_t now=millis(),generation=associationGeneration.load();
    if(pictureDemand.exchange(false))photoDue=true;
    if(generation!=observedGeneration) {
      observedGeneration=generation;weatherDue=photoDue=true;associationAt=now;
      {Guard guard;published.state.association(associated.load());published.state.evidence=false;published.state.attempted=false;published.associationRevision=generation;}
      if(associated.load()&&!clockStarted){sntp_set_time_sync_notification_cb(timeSynced);configTime(0,0,"time.cloudflare.com","pool.ntp.org");clockStarted=true;}
    }
    if(!associated.load()||!visible.load()||!body){ulTaskNotifyTake(pdTRUE,portMAX_DELAY);continue;}
    if(time(nullptr)<1700000000) {
      const uint32_t elapsed=millis()-associationAt;
      {Guard guard;published.weatherError=FetchError::Clock;}
      ulTaskNotifyTake(pdTRUE,pdMS_TO_TICKS(elapsed<RequestMs?RequestMs-elapsed:RetryMs));continue;
    }
    if(weatherDue||static_cast<int32_t>(now-nextWeather)>=0) {
      weatherDue=false;
      Url url;strcpy(url.host,"api.open-meteo.com");
      strcpy(url.path,"/v1/forecast?latitude=50.45466&longitude=30.5238&current=temperature_2m,weather_code&temperature_unit=celsius&timeformat=unixtime");
      size_t size;PeerReached peer;auto error=fetch(url,false,body,2048,size,generation,peer);
      Weather weather;uint32_t sample=0;
      const uint32_t epoch=static_cast<uint32_t>(time(nullptr));
      if(error==FetchError::None&&(!parseWeather(body,size,weather,sample)||sample>epoch+300||static_cast<int64_t>(epoch)-sample>1800))error=FetchError::Json;
      {Guard guard;
        if(completeWeather(published.state,weather,peer,error,millis(),generation,associationGeneration.load(),!cancelled(generation))) {
          published.weatherError=error;
        }
        nextWeather=millis()+weatherDelay(published.state,millis(),error);
      }
    }
    if(cancelled(generation))continue;
    if(photos&&(photoDue||static_cast<int32_t>(millis()-nextPhoto)>=0)) {
      int slot;{Guard guard;slot=reservePhoto(slots,published.state,millis());}
      if(slot>=0) {
        photoDue=false;const uint32_t nonce=esp_random();const Url url=randomPhoto(nonce);
        size_t size;PeerReached peer;auto error=fetch(url,true,body,BodyLimit,size,generation,peer);DecodeMemory memory;
        uint32_t fingerprint=2166136261u;
        if(error==FetchError::None)for(size_t i=0;i<size;++i)fingerprint=(fingerprint^body[i])*16777619u;
        Serial.printf("[generic-photo] nonce=%lu bytes=%u fetch=%s fingerprint=%08lx\n",(unsigned long)nonce,(unsigned)size,errorName(error),(unsigned long)fingerprint);
        if(error==FetchError::None&&cancelled(generation))error=FetchError::Cancelled;
        const uint32_t beforeDecode=ESP.getFreeHeap();
        // Network transport is already destroyed. Only the reserved (not visible) slot is written.
        if(error==FetchError::None&&!cancelled(generation)&&!decodePhoto(body,size,pictures[slot],memory))
          error=memory.exhausted?FetchError::Memory:FetchError::Jpeg;
        {Guard guard;
          if(completePhoto(published.state,slots,slot,peer,error,generation,associationGeneration.load(),!cancelled(generation))) {
            published.photoGeneration=slots.generation[slot];
          }
          if(!cancelled(generation)) {
            published.photoError=error;published.memoryFailure=error==FetchError::Memory;
            if(error!=FetchError::None&&static_cast<int32_t>(nextWeather-millis())>static_cast<int32_t>(RetryMs))nextWeather=millis()+RetryMs;
          }
          published.decodePeak=memory.peak;
          published.heapBeforeDecode=beforeDecode;published.heapAfterDecode=ESP.getFreeHeap();
          published.workerStackFree=uxTaskGetStackHighWaterMark(nullptr);
        }
        nextPhoto=millis()+(error==FetchError::None?SlideMs:RetryMs);
      } else nextPhoto=millis()+SlideMs;
    }
    // Required refresh/backoff deadlines only. Visibility, association, SNTP and
    // returned picture slots wake the worker without a Wi-Fi polling timer.
    const uint32_t current=millis();
    const int32_t w=static_cast<int32_t>(nextWeather-current),p=photos?static_cast<int32_t>(nextPhoto-current):w;
    const uint32_t delayMs=static_cast<uint32_t>(w<1||p<1?1:(w<p?w:p));
    ulTaskNotifyTake(pdTRUE,pdMS_TO_TICKS(delayMs));
  }
}
struct EspStartup final : StartupOps {
  void observe() override {
    WiFi.onEvent(associationEvent);
    // One startup sample only; events maintain association thereafter.
    const uint32_t generation=associationGeneration.load();
    const bool initial=WiFi.status()==WL_CONNECTED;
    portENTER_CRITICAL(&associationLock);
    if(associationGeneration.load()==generation){associated.store(initial);associationGeneration.fetch_add(1);}
    portEXIT_CRITICAL(&associationLock);
  }
  void *createMutex() override{return xSemaphoreCreateMutex();}
  void deleteMutex(void *handle) override{vSemaphoreDelete(static_cast<SemaphoreHandle_t>(handle));}
  void *allocate(size_t n) override{return malloc(n);}
  void deallocate(void *p) override{free(p);}
  bool createWorker(const StartupResources &r) override {
    mutex=static_cast<SemaphoreHandle_t>(r.mutex);
    pictures[0]=static_cast<uint16_t *>(r.pictures[0]);pictures[1]=static_cast<uint16_t *>(r.pictures[1]);
    workerBody=static_cast<uint8_t *>(r.body);workerBodyCapacity=r.bodyCapacity;
    published.availability=r.availability;published.memoryFailure=r.availability!=Availability::Ready;
    if(published.memoryFailure)published.photoError=FetchError::Memory;
    if(bundleEnd-bundleStart<2){published.weatherError=FetchError::Tls;return false;}
    TaskHandle_t handle=nullptr;
    const bool ok=xTaskCreate(worker,"generic-net",12288,nullptr,1,&handle)==pdPASS;
    if(ok)task.store(handle);
    return ok;
  }
};
}
void start() {
  if(started)return;
  started=true;EspStartup ops;const auto resources=startWorker(ops);
  availability.store(resources.availability);
  if(resources.availability==Availability::Unavailable){
    mutex=nullptr;pictures[0]=pictures[1]=nullptr;workerBody=nullptr;workerBodyCapacity=0;
    published.availability=Availability::Unavailable;published.memoryFailure=true;
  }
  wake();
}
void setVisible(bool value) {if(visible.exchange(value)!=value)wake();}
#else
void start() {}
void setVisible(bool) {}
void testSnapshot(const Snapshot &value) {published=value;}
bool testPhoto(const uint8_t *data,size_t size) {
  const int i=slots.reserve();if(i<0)return false;
  if(!pictures[i])pictures[i]=static_cast<uint16_t *>(malloc(PhotoPixels*2));
  DecodeMemory memory;
  if(!pictures[i]||!decodePhoto(data,size,pictures[i],memory)){slots.abandon(i);return false;}
  slots.publish(i);published.photoGeneration=slots.generation[i];published.decodePeak=memory.peak;return true;
}
void testReset() {for(int i=0;i<2;++i){free(pictures[i]);pictures[i]=nullptr;}slots={};published={};}
#endif
}
