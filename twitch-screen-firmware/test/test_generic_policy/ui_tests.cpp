#if defined(LV_CONF_INCLUDE_SIMPLE)
#include "ui_generic.h"
#include <array>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <string>
namespace {
unsigned failures=0;
void expect(bool value,const char *name){if(!value){++failures;printf("FAIL %s\n",name);}}
void flush(lv_display_t *display,const lv_area_t *,uint8_t *){lv_display_flush_ready(display);}
bool labelContains(lv_obj_t *root,const char *part){
  for(uint32_t i=0;i<lv_obj_get_child_count(root);++i){auto *o=lv_obj_get_child(root,i);
    if(lv_obj_check_type(o,&lv_label_class)&&strstr(lv_label_get_text(o),part))return true;}
  return false;
}
}
extern "C" void uiFaultHalt(const char *,int){abort();}
unsigned policyViews(){
  lv_init();auto *display=lv_display_create(240,240);lv_display_set_color_format(display,LV_COLOR_FORMAT_RGB565);
  std::array<uint8_t,240*40*2> draw{};lv_display_set_buffers(display,draw.data(),nullptr,draw.size(),LV_DISPLAY_RENDER_MODE_PARTIAL);lv_display_set_flush_cb(display,flush);
  GenericView view;view.build(lv_screen_active());generic::Snapshot s;s.state.association(true);s.availability=generic::Availability::Unavailable;s.memoryFailure=true;
  generic::testSnapshot(s);view.setVisible(true);
  expect(view.state().state.wifi&&labelContains(view.root(),"Service unavailable"),"associated startup failure renders explicit service unavailable");
  s.availability=generic::Availability::WeatherOnly;s.state.success(lv_tick_get());s.state.weather.valid=true;s.state.weather.at=lv_tick_get();s.state.weather.code=95;
  generic::testSnapshot(s);view.update();expect(labelContains(view.root(),"Photo resources"),"weather-only startup renders explicit resource unavailability");
  expect(!lv_obj_is_hidden(lv_obj_get_child(view.root(),2)),"weather-only worker presents weather rather than an empty photo slide");
  s.availability=generic::Availability::Ready;s.memoryFailure=false;s.photoError=generic::FetchError::None;
  generic::testSnapshot(s);view.update();lv_obj_update_layout(view.root());
  auto *status=lv_obj_get_child(view.root(),5);
  expect(lv_label_get_long_mode(status)==LV_LABEL_LONG_MODE_DOTS,"bounded status uses ellipsis");
  lv_area_t area;lv_obj_get_coords(status,&area);
  for(int x:{area.x1,area.x2})for(int y:{area.y1,area.y2})
    expect((x-120)*(x-120)+(y-120)*(y-120)<=120*120,"all status pill corners inside circular panel");
  // Real decoder/renderer: retain active and never-visible pending, then expire
  // connectivity. The production shared reserve/complete policy is tested in main.
  FILE *f=fopen("test/test_generic_view/photo96.jpg","rb");expect(f!=nullptr,"saved photo fixture opens");
  std::array<uint8_t,generic::BodyLimit> photo{};const size_t size=f?fread(photo.data(),1,photo.size(),f):0;if(f)fclose(f);
  auto expensive=photo;
  for(size_t i=0;i+12<size;++i)if(expensive[i]==255&&expensive[i+1]==0xc2){expensive[i+11]=0x11;break;}
  struct PhotoTransport final : generic::PublicTransport {
    std::string wire;size_t offset=0;
    uint32_t now() const override{return 100;}
    bool cancelled() const override{return false;}
    generic::FetchError open(const char *,uint32_t) override{return generic::FetchError::None;}
    bool write(const uint8_t *,size_t) override{return true;}
    int read() override{return offset<wire.size()?static_cast<unsigned char>(wire[offset++]):-1;}
    void close() override{}
  } transport;
  transport.wire="HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: "+std::to_string(size)+"\r\n\r\n"+std::string(reinterpret_cast<const char *>(expensive.data()),size);
  generic::Url url;generic::photoUrl("https://picsum.photos/96/96",url);
  generic::PeerReached peer;size_t downloaded=0;std::array<uint8_t,generic::BodyLimit> body{};
  expect(generic::fetchPublic(transport,url,true,body.data(),body.size(),downloaded,&peer)==generic::FetchError::None,"actual oversized-coefficient JPEG downloads through production fetch seam");
  std::array<uint16_t,generic::PhotoPixels> pixels{};generic::DecodeMemory memory;
  expect(!generic::decodePhoto(body.data(),downloaded,pixels.data(),memory)&&memory.exhausted&&memory.live==0,"actual decoder budget OOM fully cleans allocations");
  generic::State network;network.association(true);generic::Slots slots;const int writing=slots.reserve();
  expect(!generic::completePhoto(network,slots,writing,peer,generic::FetchError::Memory,1,1,true)&&slots.pending()<0&&slots.owner[writing]==generic::Owner::Free,"decoder OOM never publishes incomplete photo");
  expect(network.internet(100)==generic::Internet::Online&&!network.weather.valid,"download TLS evidence survives actual local decoder OOM with no weather success");
  lv_tick_inc(4000);s.state.success(lv_tick_get());generic::testSnapshot(s);
  expect(generic::testPhoto(photo.data(),size),"first decoded photo enters publication slot");view.update();
  expect(view.slide()==0&&view.generation()==1,"first late photo starts new full slide clock");
  expect(generic::testPhoto(photo.data(),size),"prefetched photo enters bounded pending slot");
  lv_tick_inc(4999);view.update();expect(view.slide()==0&&view.generation()==1,"first photo holds 4999ms after loading");
  lv_tick_inc(1);view.update();expect(view.slide()==1&&view.generation()==2,"prefetched photo displayed exactly at first-photo 5s deadline");
  view.destroy();generic::testReset();lv_display_delete(display);lv_deinit();
  printf("policy UI failures=%u\n",failures);return failures;
}
#endif
