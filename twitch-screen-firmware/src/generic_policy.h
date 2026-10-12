#pragma once
#include "generic_fetch.h"
namespace generic {
enum class Availability : uint8_t { Ready, WeatherOnly, Unavailable };
// Platform-independent orchestration used by the actual worker, not a test model.
inline bool completeAttempt(State &state,const PeerReached &peer,FetchError error,
                            uint32_t requestedGeneration,uint32_t currentGeneration,bool enabled) {
  if(!enabled||requestedGeneration!=currentGeneration||error==FetchError::Cancelled)return false;
  if(peer.reached)state.success(peer.at);
  if(error!=FetchError::None)state.failure();
  return true;
}
inline bool completeWeather(State &state,Weather weather,const PeerReached &peer,FetchError error,uint32_t now,
                            uint32_t requestedGeneration,uint32_t currentGeneration,bool enabled) {
  if(!completeAttempt(state,peer,error,requestedGeneration,currentGeneration,enabled))return false;
  if(error==FetchError::None&&weather.valid){weather.at=now;state.weather=weather;}
  return true;
}
inline bool completePhoto(State &state,Slots &slots,int slot,const PeerReached &peer,FetchError error,
                          uint32_t requestedGeneration,uint32_t currentGeneration,bool enabled) {
  const bool accepted=completeAttempt(state,peer,error,requestedGeneration,currentGeneration,enabled);
  if(accepted&&error==FetchError::None)return slots.publish(slot);
  slots.abandon(slot);return false;
}
inline int reservePhoto(Slots &slots,const State &state,uint32_t now) {
  // A pending frame has never been handed to LVGL. Active pixels/cache ownership
  // stay intact. Reclaim only when a fresh public probe is needed for recovery.
  if(state.internet(now)!=Internet::Online) {
    const int pending=slots.pending();
    if(pending>=0)slots.owner[pending]=Owner::Free;
  }
  return slots.reserve();
}
inline uint32_t weatherDelay(const State &state,uint32_t now,FetchError error) {
  if(error!=FetchError::None||state.internet(now)!=Internet::Online)return RetryMs;
  const uint32_t age=now-state.reachableAt;
  // Independent refresh before the 90s evidence expires, even if photo slots
  // are full. 30s retry on errors; never a tight connectivity polling loop.
  return age<60000?60000-age:RetryMs;
}
struct StartupResources {
  void *mutex=nullptr;void *pictures[2]={};void *body=nullptr;size_t bodyCapacity=0;
  Availability availability=Availability::Unavailable;
};
class StartupOps {
 public:
  virtual ~StartupOps()=default;
  virtual void observe()=0;
  virtual void *createMutex()=0;
  virtual void deleteMutex(void *)=0;
  virtual void *allocate(size_t)=0;
  virtual void deallocate(void *)=0;
  virtual bool createWorker(const StartupResources &)=0;
};
inline void cleanupStartup(StartupOps &ops,StartupResources &r) {
  ops.deallocate(r.pictures[0]);ops.deallocate(r.pictures[1]);ops.deallocate(r.body);
  if(r.mutex)ops.deleteMutex(r.mutex);
  r={};
}
inline StartupResources startWorker(StartupOps &ops) {
  ops.observe(); // Event observation is independent of all resource failures.
  StartupResources r;r.mutex=ops.createMutex();if(!r.mutex)return r;
  r.pictures[0]=ops.allocate(PhotoPixels*2);r.pictures[1]=ops.allocate(PhotoPixels*2);
  if(r.pictures[0]&&r.pictures[1]){r.body=ops.allocate(BodyLimit);r.bodyCapacity=BodyLimit;}
  if(!r.pictures[0]||!r.pictures[1]||!r.body) {
    ops.deallocate(r.pictures[0]);ops.deallocate(r.pictures[1]);r.pictures[0]=r.pictures[1]=nullptr;
    r.body=ops.allocate(2048);r.bodyCapacity=2048;r.availability=Availability::WeatherOnly;
  }else r.availability=Availability::Ready;
  if(!r.body||!ops.createWorker(r))cleanupStartup(ops,r);
  return r;
}
}
