#include "generic_policy.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <set>
using namespace generic;
namespace {
unsigned checks=0, failures=0;
void check(bool ok,const char *name){++checks;if(!ok){++failures;printf("FAIL %s\n",name);}}
struct Transport : PublicTransport {
  uint32_t clock=100; bool cancel=false; FetchError error=FetchError::None;
  std::string wire="HTTP/1.1 503 Unavailable\r\n\r\n"; size_t offset=0; unsigned closed=0,opened=0;
  uint32_t now() const override{return clock;}
  bool cancelled() const override{return cancel;}
  FetchError open(const char *,uint32_t) override{++opened;return error;}
  bool write(const uint8_t *,size_t) override{return true;}
  int read() override{return offset<wire.size()?static_cast<unsigned char>(wire[offset++]):-1;}
  void close() override{++closed;}
};
void connectivity(){
  State s;s.association(true);s.success(100);
  check(s.internet(90100)==Internet::Offline,"evidence expires within 90s");
  Url url;photoUrl("https://picsum.photos/96/96",url);uint8_t body[2048];size_t size;
  for(auto error:{FetchError::Http,FetchError::Json,FetchError::Memory,FetchError::Jpeg}){
    Transport t;PeerReached peer;
    if(error!=FetchError::Http)t.wire="HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}";
    auto fetched=fetchPublic(t,url,true,body,sizeof(body),size,&peer);
    Weather w;uint32_t epoch;
    if(error==FetchError::Json)check(!parseWeather(body,size,w,epoch),"actual unusable JSON rejected");
    check(peer.reached&&peer.at==100&&t.closed==1,"authenticated public peer evidence survives provider/content/local failure");
    check(fetched==(error==FetchError::Http?FetchError::Http:FetchError::None),"real fetch outcome");
    State state;state.association(true);Weather unusable;
    check(completeWeather(state,unusable,peer,error,100,7,7,true),"current association accepts weather attempt");
    check(state.internet(100)==Internet::Online&&!state.weather.valid,"TLS proves internet without fresh weather");
    check(weatherDelay(state,100,error)<=RetryMs,"provider failure schedules independent bounded weather refresh");
    State stale;stale.association(true);
    check(!completeAttempt(stale,peer,error,7,8,true)&&!stale.evidence&&!stale.attempted,"association generation fence rejects old TLS");
    check(!completeAttempt(stale,peer,error,7,7,false)&&!stale.evidence,"hidden cancellation rejects evidence");
  }
  for(auto error:{FetchError::Dns,FetchError::Tls}){
    Transport t;t.error=error;PeerReached peer;peer.reached=true;
    check(fetchPublic(t,url,true,body,sizeof(body),size,&peer)==error&&!peer.reached,"DNS/TLS failure cannot renew reused evidence output");
    State state;state.association(true);completeAttempt(state,peer,error,1,1,true);
    check(state.internet(100)==Internet::Offline,"transport failure without evidence is offline");
  }
  Transport rogue;strcpy(url.host,"evil.example");PeerReached peer;
  check(fetchPublic(rogue,url,true,body,sizeof(body),size,&peer)==FetchError::Redirect&&!peer.reached&&rogue.opened==0,"unallowlisted peer cannot establish reachability");
  State state;state.association(true);state.success(100);
  check(weatherDelay(state,100,FetchError::None)==60000,"weather probe due before reachability expiry even with cached photos");
  Slots slots;int active=slots.reserve();slots.publish(active);slots.activate(active);
  int pending=slots.reserve();slots.publish(pending);
  state.failure();check(state.internet(90100)==Internet::Offline,"expired evidence plus failed weather");
  int writing=reservePhoto(slots,state,90100);
  check(writing==pending&&slots.owner[active]==Owner::Active,"offline recovery safely discards only never-visible pending frame");
  PeerReached reached;reached.reached=true;reached.at=90101;
  check(completePhoto(state,slots,writing,reached,FetchError::None,1,1,true),"successful photo/public path restores evidence and publication");
  check(state.internet(90101)==Internet::Online&&slots.pending()==pending&&slots.owner[active]==Owner::Active,"cached active pixels survive recovery");
  writing=-1; // No writing slot: stale completion cannot alter publication or evidence.
  PeerReached late;late.reached=true;late.at=180000;
  check(!completePhoto(state,slots,writing,late,FetchError::None,1,2,true)&&state.reachableAt==90101,"photo completion generation fence");
}
struct Startup : StartupOps {
  unsigned allocations=0,failAllocation=0,observeCount=0,mutexDeletes=0,tasks=0;
  bool mutexOk=true,taskOk=true;unsigned failMask=0;std::set<void *> live;
  void observe() override{++observeCount;}
  void *createMutex() override{return mutexOk?this:nullptr;}
  void deleteMutex(void *) override{++mutexDeletes;}
  void *allocate(size_t size) override{++allocations;if(allocations==failAllocation||(failMask&(1u<<allocations)))return nullptr;void *p=malloc(size);live.insert(p);return p;}
  void deallocate(void *p) override{if(p){check(live.erase(p)==1,"startup frees each allocation exactly once");free(p);}}
  bool createWorker(const StartupResources &r) override{++tasks;check(r.mutex&&r.body&&r.bodyCapacity>=2048,"worker always has synchronization and bounded weather body");return taskOk;}
};
void startup(){
  {Startup ops;ops.mutexOk=false;auto r=startWorker(ops);check(ops.observeCount==1&&r.availability==Availability::Unavailable&&ops.tasks==0&&ops.live.empty(),"mutex failure still observes actual association");}
  for(unsigned fault=1;fault<=3;++fault){Startup ops;ops.failAllocation=fault;auto r=startWorker(ops);
    check(ops.observeCount==1&&r.availability==Availability::WeatherOnly&&r.bodyCapacity==2048&&!r.pictures[0]&&!r.pictures[1]&&ops.tasks==1,"slot/body allocation failure degrades to safe weather-only worker");
    cleanupStartup(ops,r);check(ops.live.empty()&&ops.mutexDeletes==1,"weather-only exact cleanup");}
  {Startup ops;ops.failMask=(1u<<3)|(1u<<4);auto r=startWorker(ops);check(r.availability==Availability::Unavailable&&ops.observeCount==1&&ops.tasks==0&&ops.live.empty()&&ops.mutexDeletes==1,"full and bounded weather body failure clean up without losing observer");}
  {Startup ops;ops.failMask=(1u<<1)|(1u<<2)|(1u<<3);auto r=startWorker(ops);check(r.availability==Availability::Unavailable&&ops.observeCount==1&&ops.tasks==0&&ops.live.empty()&&ops.mutexDeletes==1,"both slots and weather buffer failure exact cleanup");}
  {Startup ops;ops.taskOk=false;auto r=startWorker(ops);check(r.availability==Availability::Unavailable&&ops.observeCount==1&&ops.live.empty()&&ops.mutexDeletes==1&&!r.mutex&&!r.body,"task failure cleans all resources but association observer survives");}
  {Startup ops;auto r=startWorker(ops);check(r.availability==Availability::Ready&&r.bodyCapacity==BodyLimit&&ops.live.size()==3,"full startup bounded resources");cleanupStartup(ops,r);check(ops.live.empty()&&ops.mutexDeletes==1,"full startup cleanup");}
}
}
#if defined(LV_CONF_INCLUDE_SIMPLE)
unsigned policyViews();
#endif
int main(){connectivity();startup();
#if defined(LV_CONF_INCLUDE_SIMPLE)
  failures+=policyViews();
#endif
  printf("%u checks, %u failures\n",checks,failures);return failures?1:0;
}
