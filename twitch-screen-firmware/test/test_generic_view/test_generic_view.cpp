#include "generic_input.h"
#include "generic_fetch.h"
#include "generic_photos.h"
#include "generic_service.h"
#include "ui_generic.h"
#include "ui_base.h"
#include <lvgl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <array>
#include <vector>
#include <string>
namespace {
unsigned checks=0,failures=0;
void check(bool ok,const char *name){++checks;if(!ok){++failures;printf("FAIL %s\n",name);}}
std::vector<uint8_t> fixture(const char *path){
  FILE *f=fopen(path,"rb");check(f!=nullptr,"fixture file exists");if(!f)return {};
  std::vector<uint8_t> result;int c;while((c=fgetc(f))!=EOF)result.push_back(c);fclose(f);return result;
}
struct Input{std::string s;size_t position=0;int terminal=-1;};
int read(void *ctx){auto &in=*static_cast<Input *>(ctx);if(in.position==in.s.size())return in.terminal;return static_cast<uint8_t>(in.s[in.position++]);}
generic::FetchError response(const std::string &wire,size_t capacity=8192,int terminal=-1){
  Input in;in.s=wire;in.terminal=terminal;std::array<uint8_t,8192> body{};generic::Response r;
  return generic::readResponse(read,&in,body.data(),capacity,r);
}
bool weather(const std::string &json){generic::Weather w;uint32_t epoch;return generic::parseWeather(reinterpret_cast<const uint8_t *>(json.data()),json.size(),w,epoch);}
struct FaultTransport final : generic::PublicTransport {
  uint32_t clock=0,openDelay=0,readDelay=0;bool hidden=false,shortWrite=false,live=false;
  unsigned opens=0,closes=0;size_t offset=0;generic::FetchError failure=generic::FetchError::None;
  std::vector<std::string> replies;std::string request;
  uint32_t now() const override{return clock;}
  bool cancelled() const override{return hidden;}
  generic::FetchError open(const char *,uint32_t) override{live=true;++opens;clock+=openDelay;offset=0;return failure;}
  bool write(const uint8_t *s,size_t n) override{request.assign(reinterpret_cast<const char *>(s),n);return !shortWrite;}
  int read() override{clock+=readDelay;if(opens>replies.size()||offset==replies[opens-1].size())return -1;return static_cast<uint8_t>(replies[opens-1][offset++]);}
  void close() override{live=false;++closes;}
};
void requests(){
  using namespace generic;Url url;photoUrl("https://picsum.photos/96/96",url);std::array<uint8_t,8192> body{};size_t size;
  for(auto failure:{FetchError::Dns,FetchError::Tls,FetchError::Memory}){
    FaultTransport t;t.failure=failure;check(fetchPublic(t,url,true,body.data(),body.size(),size)==failure&&!t.live&&t.closes==1,"DNS/TLS/allocation failure closes transport before decode");}
  FaultTransport cancelled;cancelled.hidden=true;check(fetchPublic(cancelled,url,true,body.data(),body.size(),size)==FetchError::Cancelled&&cancelled.opens==0,"hidden/covered request does no DNS or TLS work");
  const std::string redirect="HTTP/1.1 302 Found\r\nLocation: https://fastly.picsum.photos/id/1/96/96.jpg\r\n\r\n";
  const std::string ok="HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: 3\r\n\r\nabc";
  FaultTransport t;t.replies={redirect,ok};check(fetchPublic(t,url,true,body.data(),body.size(),size)==FetchError::None&&size==3&&t.opens==2&&t.closes==2&&!t.live,"owned redirect chain closes every TLS stage");
  for(uint32_t nonce:{0u,1u,UINT32_MAX}) {
    const Url random=randomPhoto(nonce);char address[sizeof(random.host)+sizeof(random.path)+8];
    snprintf(address,sizeof(address),"https://%s%s",random.host,random.path);
    Url parsed;check(photoUrl(address,parsed)&&!strcmp(parsed.host,"picsum.photos")&&strstr(parsed.path,"?random="),"every photo starts at the live random Picsum endpoint");
    check(strcmp(random.path,randomPhoto(nonce+1).path)!=0,"new nonce produces a distinct uncached random request, including rollover");
    FaultTransport picker;picker.replies={ok};
    check(fetchPublic(picker,random,true,body.data(),body.size(),size)==FetchError::None&&picker.request.find(std::string("GET ")+random.path+" HTTP/1.1\r\n")!=std::string::npos,"production transport sends random nonce over network");
    FaultTransport cdn;cdn.replies={redirect,ok};
    check(fetchPublic(cdn,random,true,body.data(),body.size(),size)==FetchError::None&&cdn.opens==2&&cdn.closes==2&&cdn.request.find("Host: fastly.picsum.photos\r\n")!=std::string::npos,"random redirect is followed to Fastly with both TLS objects closed");
  }
  check(t.request.find("Host: fastly.picsum.photos\r\n")!=std::string::npos&&t.request.find("Connection: close\r\n")!=std::string::npos,"redirect uses correct hostname and no HTTP keepalive");
  FaultTransport downgrade;downgrade.replies={"HTTP/1.1 302 Found\r\nLocation: http://picsum.photos/96/96\r\n\r\n"};check(fetchPublic(downgrade,url,true,body.data(),body.size(),size)==FetchError::Redirect&&downgrade.opens==1&&!downgrade.live,"no TLS downgrade connection attempted");
  FaultTransport loop;loop.replies={redirect,redirect,redirect,redirect,redirect};check(fetchPublic(loop,url,true,body.data(),body.size(),size)==FetchError::Redirect&&loop.opens==4&&loop.closes==4,"redirect hop limit exact");
  FaultTransport weatherRedirect;weatherRedirect.replies={redirect};check(fetchPublic(weatherRedirect,url,false,body.data(),body.size(),size)==FetchError::Redirect&&weatherRedirect.opens==1,"weather provider redirects rejected");
  FaultTransport deadline;deadline.openDelay=15000;check(fetchPublic(deadline,url,true,body.data(),body.size(),size)==FetchError::Deadline&&!deadline.live&&deadline.closes==1,"whole-request deadline applies to DNS/TLS stage");
  FaultTransport slow;slow.replies={ok};slow.readDelay=500;check(fetchPublic(slow,url,true,body.data(),body.size(),size)==FetchError::Deadline&&!slow.live,"trickled headers cannot renew total deadline");
  FaultTransport shortWrite;shortWrite.shortWrite=true;check(fetchPublic(shortWrite,url,true,body.data(),body.size(),size)==FetchError::Http&&!shortWrite.live,"short request write closes TLS on every path");
}
void logic(){
  using namespace generic;
  // Exhaustive network-vs-relay truth table: relay state must not enter local reachability.
  for(bool relay:{false,true})for(bool wifi:{false,true})for(bool evidence:{false,true})for(bool attempted:{false,true}) {
    State state;state.wifi=wifi;state.evidence=evidence;state.attempted=attempted;state.reachableAt=100;
    const auto expected=!wifi?Internet::NoWifi:evidence?Internet::Online:attempted?Internet::Offline:Internet::Checking;
    check(state.internet(101)==expected,relay?"relay up independent network truth table":"relay absent independent network truth table");
  }
  State state;state.association(true);check(state.internet(0)==Internet::Checking,"association alone is not internet");
  state.success(100);state.weather.valid=true;state.weather.at=100;
  state.failure();check(state.internet(101)==Internet::Online,"failed photo does not invalidate weather reachability");
  check(state.internet(100+ReachableMs-1)==Internet::Online&&state.internet(100+ReachableMs)==Internet::Offline,"bounded internet evidence deadline exact");
  state.success(700000);check(!state.weatherFresh(700000)&&state.internet(700000)==Internet::Online,"fresh photo reachability cannot freshen stale weather");
  state.association(false);check(state.internet(700001)==Internet::NoWifi&&!state.weatherFresh(700001),"Wi-Fi loss removes online presentation");
  state.association(true);check(state.internet(700002)==Internet::Checking,"Wi-Fi return rechecks instead of trusting cached images");
  state.success(700003);check(state.internet(700003)==Internet::Online,"resume on real provider success");
  State wrap;wrap.association(true);wrap.success(UINT32_MAX-100);check(wrap.internet(99)==Internet::Online,"millis rollover freshness");
  check(SlideMs==60000,"photos rotate once per minute");
  Slideshow show;show.visibility(true,100);check(!show.tick(100+SlideMs-1)&&show.slide==0,"first photo remains for the full minute");
  check(show.tick(100+SlideMs)&&show.slide==1,"second photo at exactly one minute");
  check(show.tick(100+2*SlideMs)&&show.slide==0,"third interval rotates photo slot, never weather-only");
  check(show.tick(100+3*SlideMs)&&show.slide==1,"every interval remains a photo interval");
  show.visibility(false,100+3*SlideMs+1000);check(!show.tick(1000000)&&show.slide==1,"hidden/covered slideshow pauses");
  show.visibility(true,1000000);check(!show.tick(1000000+SlideMs-1)&&show.tick(1000000+SlideMs),"resume grants complete one-minute slide");
  Slideshow slideWrap;const uint32_t nearWrap=UINT32_MAX-1000;
  slideWrap.visibility(true,nearWrap);
  check(!slideWrap.tick(uint32_t(nearWrap+SlideMs-1))&&slideWrap.slide==0,"one-minute slide stays intact across millis wrap until deadline");
  check(slideWrap.tick(uint32_t(nearWrap+SlideMs))&&slideWrap.slide==1,"one-minute rollover deadline rotates once");
  check(!slideWrap.tick(uint32_t(nearWrap+SlideMs)),"same clock sample cannot rotate twice");
  check(slideWrap.tick(uint32_t(nearWrap+10*SlideMs))&&slideWrap.slide==0,"late renderer rotates only once rather than skipping prefetched photos");
  check(!slideWrap.tick(uint32_t(nearWrap+11*SlideMs-1)),"late rotation starts another full minute instead of catch-up churn");
  Slots slots;const int a=slots.reserve();check(a==0&&slots.pending()==-1,"writing never exposed");
  check(slots.publish(a)&&slots.pending()==a&&slots.activate(a),"pending-to-active handshake");
  const int b=slots.reserve();check(b==1&&slots.publish(b)&&slots.reserve()==-1,"two fixed slots bound pending work");
  slots.release(a);check(slots.reserve()==-1,"pending frame prevents extra prefetch");slots.activate(b);
  check(slots.reserve()==a&&slots.owner[b]==Owner::Active,"returned active slot writable without touching next active");
  slots.abandon(a);check(slots.owner[a]==Owner::Free,"download failure relinquishes writing ownership");
}
void parsers(const std::vector<uint8_t> &photo,const std::vector<uint8_t> &json){
  using namespace generic;
  const std::string head="HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\n";
  check(response(head+"Content-Length: 3\r\n\r\nabc")==FetchError::None,"bounded fixed HTTP body");
  check(response(head+"Content-Length: 8193\r\n\r\n")==FetchError::TooLarge,"oversized declared body rejected before allocation");
  check(response(head+"Content-Length: 5\r\n\r\nabc")==FetchError::Truncated,"truncated HTTP body");
  check(response(head+"Content-Length: 5\r\n\r\nabc",8192,-2)==FetchError::Deadline,"public probe deadline propagated");
  check(response(head+"Transfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n")==FetchError::None,"bounded chunked body");
  check(response(head+"Transfer-Encoding: chunked\r\n\r\n2001\r\n")==FetchError::TooLarge,"oversized chunk rejected");
  check(response(head+"Content-Length: 3\r\nContent-Length: 3\r\n\r\nabc")==FetchError::Headers,"duplicate lengths fail closed");
  check(response(head+"Content-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\n")==FetchError::Headers,"ambiguous length/chunk headers rejected");
  check(response("HTTP/1.1 503 Unavailable\r\n\r\n")==FetchError::Http,"provider HTTP error classified");
  check(response(head+"Huge: "+std::string(2050,'x')+"\r\n\r\n")==FetchError::Headers,"headers/line bounds");
  check(response(head+"\r\nabc")==FetchError::None,"bounded close-delimited body");
  check(response(head+"\r\n"+std::string(8193,'x'))==FetchError::TooLarge,"close-delimited capacity bound");
  check(response("HTTP/1.1 302 Found\r\nLocation: https://fastly.picsum.photos/id/1/96/96.jpg\r\n\r\n")==FetchError::None,"redirect parsed without draining body");
  Url url;check(photoUrl("https://fastly.picsum.photos/id/1/96/96.jpg?hmac=x",url),"Picsum owned CDN redirect allowed");
  for(const char *s:{"http://picsum.photos/96/96","https://picsum.photos.evil.org/96/96","https://evil.org/96/96","https://picsum.photos:443/96/96","https://user@picsum.photos/96/96","https://picsum.photos/\r\nFoo:x","https://picsum.photos\\evil.org/x"})check(!photoUrl(s,url),"redirect hostname/scheme/request injection rejected");
  Weather w;uint32_t epoch;
  check(parseWeather(json.data(),json.size(),w,epoch)&&w.centiC==1090&&w.code==61&&epoch==1791816300,"actual saved Open-Meteo schema by keys");
  const std::string valid="{\"current\":{\"interval\":900,\"weather_code\":3,\"temperature_2m\":-1.2,\"time\":1791816300},\"current_units\":{\"time\":\"unixtime\",\"temperature_2m\":\"\\u00b0C\"}}";
  check(weather(valid),"reordered fields and escaped Celsius parse");
  for(const char *s:{"{}","{\"current\":null}","{\"current\":{\"temperature_2m\":null}}","{\"current\":{},\"current\":{}}","{\"x\":NaN}","{\"x\":01}","{\"x\":1,}","{\"x\":\"\\q\"}"})check(!weather(s),"malformed/missing/duplicate/null JSON rejected");
  for(const char *replacement:{"null","\"10.9\"","999","1e999"}){
    std::string bad(valid);const auto pos=bad.find("-1.2");bad.replace(pos,4,replacement);check(!weather(bad),"weather number semantics bounded");}
  check(!weather(valid+"trailing")&&!weather(std::string(2049,' ')),"JSON trailing/size bounds");
  check(validateJpeg(photo.data(),photo.size()),"actual Picsum progressive JPEG framing and dimensions validated");
  bool progressive=false;for(size_t i=0;i+1<photo.size();++i)if(photo[i]==255&&photo[i+1]==0xc2)progressive=true;
  check(progressive,"fixture really SOF2, not baseline replacement");
  std::array<uint16_t,PhotoPixels> pixels{};DecodeMemory memory;
  check(decodePhoto(photo.data(),photo.size(),pixels.data(),memory),"actual progressive decode succeeds");
  check(memory.live==0&&memory.peak<=DecodeLimit&&!memory.exhausted,"progressive allocator budget and teardown");
  const auto golden=fixture("test/test_generic_view/photo96.rgb565");
  check(golden.size()==PhotoPixels*2,"pristine pinned upstream pixel reference exists");
  if(golden.size()==PhotoPixels*2)for(size_t i=0;i<PhotoPixels;++i)
    check(pixels[i]==static_cast<uint16_t>(golden[i*2]|(golden[i*2+1]<<8)),"local coefficient lifecycle patch preserves every upstream pixel");
  printf("actual progressive sample bytes=%zu decode peak=%zu live=%zu\n",photo.size(),memory.peak,memory.live);
  auto truncated=photo;truncated.pop_back();check(!decodePhoto(truncated.data(),truncated.size(),pixels.data(),memory)&&!memory.live,"truncated JPEG rejected without leaks");
  auto wrong=photo;for(size_t i=0;i+9<wrong.size();++i)if(wrong[i]==255&&wrong[i+1]==0xc2){wrong[i+8]=97;break;}
  check(!decodePhoto(wrong.data(),wrong.size(),pixels.data(),memory)&&!memory.live,"wrong dimensions rejected before coefficients allocation");
  auto expensive=photo;for(size_t i=0;i+12<expensive.size();++i)if(expensive[i]==255&&expensive[i+1]==0xc2){expensive[i+11]=0x11;break;}
  const auto retained=pixels;
  check(!decodePhoto(expensive.data(),expensive.size(),pixels.data(),memory)&&memory.exhausted&&memory.live==0,
    "unaffordable progressive coefficients hit aggregate cap and clean up");
  check(retained==pixels,"failed decoder never overwrites last valid RGB565 slot");
  std::vector<uint8_t> oversized(8193,0);check(!validateJpeg(oversized.data(),oversized.size()),"oversized encoded JPEG rejected");
  // Deterministic corruption sweep exercises real decoder cleanup and parser fail paths.
  for(size_t i=2;i<photo.size();i+=23){auto damaged=photo;damaged[i]^=0xff;decodePhoto(damaged.data(),damaged.size(),pixels.data(),memory);check(memory.live==0&&memory.peak<=DecodeLimit,"corrupt progressive inputs free all decoder allocations");}
}
std::array<uint16_t,240*240> framebuffer{};
void flush(lv_display_t *d,const lv_area_t *a,uint8_t *pixels){const auto stride=lv_draw_buf_width_to_stride(lv_area_get_width(a),LV_COLOR_FORMAT_RGB565);
  for(int y=a->y1;y<=a->y2;++y)for(int x=a->x1;x<=a->x2;++x){uint16_t p;memcpy(&p,pixels+(y-a->y1)*stride+(x-a->x1)*2,2);if(x>=0&&x<240&&y>=0&&y<240)framebuffer[y*240+x]=p;}
  lv_display_flush_ready(d);
}
void capture(const char *name){lv_obj_invalidate(lv_screen_active());lv_refr_now(nullptr);const char *dir=getenv("TS_UI_CAPTURE_DIR");if(!dir)return;
  char path[1024];snprintf(path,sizeof(path),"%s/%s.ppm",dir,name);FILE *f=fopen(path,"wb");check(f!=nullptr,"PPM destination opens");if(!f)return;
  fprintf(f,"P6\n# Actual Picsum fixture and Open-Meteo fixture / real LVGL RGB565 flush\n240 240\n255\n");
  for(auto p:framebuffer){const uint8_t r=(p>>11)&31,g=(p>>5)&63,b=p&31;const uint8_t rgb[]={static_cast<uint8_t>((r<<3)|(r>>2)),static_cast<uint8_t>((g<<2)|(g>>4)),static_cast<uint8_t>((b<<3)|(b>>2))};check(fwrite(rgb,3,1,f)==1,"PPM pixel write");}check(!fclose(f),"PPM closes");
}
void views(const std::vector<uint8_t> &photo,const std::vector<uint8_t> &json){
  lv_init();auto *display=lv_display_create(240,240);lv_display_set_color_format(display,LV_COLOR_FORMAT_RGB565);
  std::array<uint8_t,240*40*2> draw{};lv_display_set_buffers(display,draw.data(),nullptr,draw.size(),LV_DISPLAY_RENDER_MODE_PARTIAL);lv_display_set_flush_cb(display,flush);
  GenericView view;view.build(lv_screen_active());generic::Snapshot s;s.state.association(true);s.state.success(lv_tick_get());uint32_t epoch;
  check(generic::parseWeather(json.data(),json.size(),s.state.weather,epoch),"real weather fixture feeds view");s.state.weather.at=lv_tick_get();
  generic::testSnapshot(s);check(generic::testPhoto(photo.data(),photo.size()),"real progressive frame published to fixed slot");view.setVisible(true);view.update();
  check(view.generation()==1&&view.slide()==0,"renderer consumes first immutable frame");
  lv_obj_update_layout(view.root());
  for(int child:{2,3,4}) {
    auto *overlay=lv_obj_get_child(view.root(),child);
    check(!lv_obj_is_hidden(overlay),"weather text overlays the first photo, not only the third interval");
    check(lv_obj_get_style_bg_opa(overlay,LV_PART_MAIN)>LV_OPA_TRANSP&&lv_obj_get_style_bg_opa(overlay,LV_PART_MAIN)<LV_OPA_COVER,"weather text has translucent contrast without replacing the photo");
    lv_area_t area;lv_obj_get_coords(overlay,&area);
    for(int x:{area.x1,area.x2})for(int y:{area.y1,area.y2})check((x-120)*(x-120)+(y-120)*(y-120)<=120*120,"weather overlay corners stay inside the circular LCD");
  }
  check(strstr(lv_label_get_text(lv_obj_get_child(view.root(),3))," C")!=nullptr,"weather overlay includes explicit Celsius unit");
  capture("online-photo-overlay");
  unsigned colors=0;const auto pixel=framebuffer[100*240+100];for(auto p:framebuffer)if(p!=pixel)++colors;check(colors>10000,"real photo produces nonconstant rendered pixels");
  check(generic::testPhoto(photo.data(),photo.size()),"prefetch occupies sole pending slot");check(!generic::testPhoto(photo.data(),photo.size()),"third frame cannot queue unbounded");
  lv_tick_inc(generic::SlideMs-1);view.update();check(view.slide()==0&&view.generation()==1,"UI photo holds until the last millisecond of its minute");
  lv_tick_inc(1);view.update();check(view.slide()==1&&view.generation()==2,"UI photo rotates at exact one-minute boundary");
  check(generic::testPhoto(photo.data(),photo.size()),"next random photo prefetch is available for every interval");
  // Simulate fresh authenticated reachability from that prefetch, independently
  // of retaining the active frame; cached photos must not renew evidence.
  s.state.success(lv_tick_get());generic::testSnapshot(s);
  lv_tick_inc(generic::SlideMs);view.update();check(view.slide()==0&&view.generation()==3,"third interval is another photo, never a weather-only slide");
  check(!lv_obj_is_hidden(lv_obj_get_child(view.root(),0)),"photo remains visible on the former weather interval");
  for(int child:{2,3,4})check(!lv_obj_is_hidden(lv_obj_get_child(view.root(),child)),"weather labels overlay every photo");
  capture("online-third-photo-overlay");
  view.setVisible(false);lv_tick_inc(1000);view.update();check(view.slide()==0&&lv_obj_is_hidden(view.root()),"covered/other base pauses real renderer");view.setVisible(true);check(view.slide()==0,"uncover preserves current photo");
  lv_tick_inc(generic::SlideMs);s.state.success(lv_tick_get());generic::testSnapshot(s);view.update();check(view.slide()==1&&view.generation()==3,"failed/missing download retains valid photo while weather still proves reachability");
  s.state.association(false);generic::testSnapshot(s);view.update();capture("offline-red-yellow");check(view.state().state.internet(lv_tick_get())==generic::Internet::NoWifi,"Wi-Fi loss shows fluid, not cached online photo");
  s.state.association(true);generic::testSnapshot(s);view.update();capture("checking-internet");check(view.state().state.internet(lv_tick_get())==generic::Internet::Checking,"Wi-Fi return checking composition");
  s.state.success(lv_tick_get());s.state.weather.at=0;generic::testSnapshot(s);view.update();check(view.generation()==3,"cached photo resumes only after new internet evidence");
  s.photoError=generic::FetchError::Http;generic::testSnapshot(s);view.update();capture("photo-provider-unavailable");
  check(view.state().state.internet(lv_tick_get())==generic::Internet::Online,"weather success keeps internet online across photo failure");
  s.photoError=generic::FetchError::None;
  s.state.evidence=false;s.state.attempted=true;generic::testSnapshot(s);view.update();capture("no-internet");
  check(view.state().state.internet(lv_tick_get())==generic::Internet::Offline,"public failures show No internet with Wi-Fi still associated");
  s.state.success(lv_tick_get());generic::testSnapshot(s);view.update();
  lv_tick_inc(generic::WeatherFreshMs);s.state.success(lv_tick_get());generic::testSnapshot(s);view.update();check(!view.state().state.weatherFresh(lv_tick_get()),"UI stale weather unavailable with current photo reachability");
  view.destroy();check(generic::testPhoto(photo.data(),photo.size()),"renderer teardown cache-detaches then frees active slot for worker");
  // Actual BaseView binding: same local presentation, no wire/controller enum edits.
  BaseView base;base.build(1);desk::wire::State ws,cs;desk::Selection selection;
  selection.base=desk::Base::DisconnectedOrbit;base.apply(selection,ws,cs);base.generic().update();
  check(base.selected()==desk::Base::DisconnectedOrbit&&base.generic().state().state.internet(lv_tick_get())==generic::Internet::Online,
    "relay absent remains DisconnectedOrbit while local internet presentation is online");
  selection.base=desk::Base::ConnectedOrbit;base.apply(selection,ws,cs);
  check(!lv_obj_is_hidden(base.generic().root()),"admin Generic choice binds this same renderer");
  base.setCovered(true);check(lv_obj_is_hidden(base.generic().root()),"notification card covers and pauses Generic binding");base.setCovered(false);
  selection.base=desk::Base::Cpu;base.apply(selection,ws,cs);check(lv_obj_is_hidden(base.generic().root()),"explicit CPU choice overrides local screensaver");
  selection.base=desk::Base::Weather;base.apply(selection,ws,cs);check(lv_obj_is_hidden(base.generic().root()),"explicit Weather choice overrides local screensaver");
  selection.base=desk::Base::Twitch;base.apply(selection,ws,cs);check(lv_obj_is_hidden(base.generic().root()),"Twitch overrides local screensaver");
  base.destroy();generic::testReset();
  lv_display_delete(display);lv_deinit();
}
}
extern "C" void uiFaultHalt(const char *,int){abort();}
int main(){auto photo=fixture("test/test_generic_view/photo96.jpg"),json=fixture("test/test_generic_view/weather.json");logic();requests();parsers(photo,json);views(photo,json);
  printf("%u checks, %u failures\n",checks,failures);return failures?1:0;}
