#include "ui_generic.h"
#include "ui_common.h"
#include <stdlib.h>
#include <src/misc/cache/instance/lv_image_cache.h>
#include <src/misc/cache/instance/lv_image_header_cache.h>
#ifdef ARDUINO
#include <Arduino.h>
#include "app_log.h"
#endif
namespace {
lv_obj_t *text(lv_obj_t *root,const lv_font_t *font,int width,int y) {
  auto *label=lv_label_create(root);lv_obj_set_size(label,width,lv_font_get_line_height(font)+4);
  lv_obj_set_style_text_font(label,font,0);lv_obj_set_style_text_color(label,lv_color_hex(0xfff4da),0);
  lv_obj_set_style_text_align(label,LV_TEXT_ALIGN_CENTER,0);lv_label_set_long_mode(label,LV_LABEL_LONG_MODE_DOTS);
  lv_obj_align(label,LV_ALIGN_CENTER,0,y);lv_label_set_text_static(label,"");return label;
}
}
void GenericView::build(lv_obj_t *parent) {
  destroy();root_=lv_obj_create(parent);lv_obj_remove_style_all(root_);clearDecor(root_);
  lv_obj_set_size(root_,240,240);lv_obj_center(root_);
  lv_obj_set_style_bg_color(root_,lv_color_hex(0x081b2b),0);lv_obj_set_style_bg_opa(root_,LV_OPA_COVER,0);
  image_=lv_image_create(root_);lv_image_set_pivot(image_,48,48);lv_image_set_scale(image_,640);
  // LVGL scales the source, not the native-resolution typography.
  gas_=lv_obj_create(root_);lv_obj_remove_style_all(gas_);clearDecor(gas_);
  lv_obj_set_size(gas_,240,240);lv_obj_center(gas_);lv_obj_add_event_cb(gas_,fluid,LV_EVENT_DRAW_MAIN,this);
  title_=text(root_,&lv_font_montserrat_20,168,-60);
  temperature_=text(root_,&lv_font_montserrat_48,180,0);
  detail_=text(root_,&lv_font_montserrat_20,168,60);
  lv_obj_t *overlays[]={title_,temperature_,detail_};
  for(auto *overlay:overlays) {
    lv_obj_set_style_bg_color(overlay,lv_color_black(),0);
    lv_obj_set_style_bg_opa(overlay,LV_OPA_70,0);
    lv_obj_set_style_radius(overlay,8,0);
    lv_obj_set_style_pad_all(overlay,2,0);
  }
  status_=text(root_,&lv_font_montserrat_14,136,86);
  lv_obj_set_style_bg_color(status_,lv_color_black(),0);lv_obj_set_style_bg_opa(status_,LV_OPA_70,0);
  lv_obj_set_style_radius(status_,8,0);
  lastTick_=lv_tick_get();timer_=lv_timer_create(timer,50,this);lv_timer_pause(timer_);
  lv_obj_set_hidden(root_,true);
}
void GenericView::detach() {
  if(active_<0)return;
  // Detach and drop both LVGL caches before returning immutable active pixels.
  lv_image_set_src(image_,nullptr);lv_image_cache_drop(&descriptor_);lv_image_header_cache_drop(&descriptor_);
  lv_obj_invalidate(image_);descriptor_={};generic::releaseActive(active_);active_=-1;
}
void GenericView::destroy() {
  generic::setVisible(false);
  if(timer_)lv_timer_delete(timer_);
  if(image_)detach();
  if(root_)lv_obj_delete(root_);
  root_=image_=gas_=title_=temperature_=detail_=status_=nullptr;timer_=nullptr;
  slideshow_={};generation_=phase_=0;online_=false;
  // Service is device-lifetime; at most one pending frame is retained while hidden.
}
void GenericView::setVisible(bool visible) {
  if(!root_||slideshow_.visible==visible)return;
  slideshow_.visibility(visible,lv_tick_get());lastTick_=lv_tick_get();
  generic::setVisible(visible);lv_obj_set_hidden(root_,!visible);
#ifdef ARDUINO
  appLog("[generic-visibility] visible=%u\n",static_cast<unsigned>(visible));
#endif
  if(visible){lv_timer_reset(timer_);lv_timer_resume(timer_);update();}else lv_timer_pause(timer_);
}
void GenericView::timer(lv_timer_t *timer) {static_cast<GenericView *>(lv_timer_get_user_data(timer))->update();}
void GenericView::fluid(lv_event_t *event) {
  auto &view=*static_cast<GenericView *>(lv_event_get_user_data(event));
  lv_area_t area;lv_obj_get_coords(view.gas_,&area);auto *layer=lv_event_get_layer(event);
  lv_draw_rect_dsc_t background;lv_draw_rect_dsc_init(&background);
  background.bg_color=lv_color_hex(0x210400);background.bg_opa=LV_OPA_COVER;lv_draw_rect(layer,&background,&area);
  // Layered broad lobes: hot yellow core dissolves into red gas, breathing/drifting
  // without a fictitious reading or a full-resolution raster allocation.
  const int angle=(view.phase_/35)%360;
  for(int lobe=0;lobe<3;++lobe) {
    const int a=(angle+lobe*120)%360;
    const int x=120+lv_trigo_cos(a)*38/32768,y=115+lv_trigo_sin(a)*30/32768;
    const int breath=lv_trigo_sin((angle*2+lobe*70)%360)*12/32768;
    for(int ring=0;ring<9;++ring) {
      const int radius=86-ring*7+breath;
      lv_draw_rect_dsc_t d;lv_draw_rect_dsc_init(&d);d.radius=LV_RADIUS_CIRCLE;
      d.bg_color=lv_color_hex(lobe==1?0xffcc32:lobe==2?0xff7b16:0xef2b08);
      d.bg_opa=static_cast<lv_opa_t>(lobe==1?18+ring*24:12+ring*8);
      const lv_area_t r={area.x1+x-radius,area.y1+y-radius,area.x1+x+radius,area.y1+y+radius};
      lv_draw_rect(layer,&d,&r);
    }
  }
}
void GenericView::update() {
  if(!root_||!slideshow_.visible)return;
  const uint32_t now=lv_tick_get(),elapsed=now-lastTick_;lastTick_=now;phase_+=elapsed>1000?50:elapsed;
  const auto previous=state_;state_=generic::snapshot();
#ifdef ARDUINO
  const uint32_t evidenceNow=millis();
#else
  const uint32_t evidenceNow=now;
#endif
  const auto internet=state_.state.internet(evidenceNow);const bool online=internet==generic::Internet::Online;
  if(online!=online_) {online_=online;slideshow_.slide=0;slideshow_.since=now;}
  const bool rotated=online&&slideshow_.tick(now);
  if(online&&(active_<0||rotated)) {
    const uint16_t *pixels=nullptr;uint32_t generation=0;
    const int next=generic::takePending(pixels,generation);
    if(next>=0) {
      const bool firstPhoto=active_<0;
      detach();active_=next;generation_=generation;
      if(firstPhoto){slideshow_.slide=0;slideshow_.since=now;}
      descriptor_.header.magic=LV_IMAGE_HEADER_MAGIC;descriptor_.header.cf=LV_COLOR_FORMAT_RGB565;
      descriptor_.header.w=96;descriptor_.header.h=96;descriptor_.header.stride=192;
      descriptor_.data_size=generic::PhotoPixels*2;descriptor_.data=reinterpret_cast<const uint8_t *>(pixels);
      lv_image_set_src(image_,&descriptor_);lv_obj_center(image_);lv_obj_invalidate(image_);
    }
  }
  const bool photograph=online&&active_>=0;
  lv_obj_set_hidden(image_,!photograph);lv_obj_set_hidden(gas_,online);
  if(!online)lv_obj_invalidate(gas_);
  char reading[48];const bool fresh=online&&state_.state.weatherFresh(evidenceNow);
  if(fresh) {
    const int value=state_.state.weather.centiC;
    snprintf(reading,sizeof(reading),"%s%d.%d",value<0?"-":"",abs(value)/100,(abs(value)%100)/10);
  }else strcpy(reading,"--");
  lv_obj_set_hidden(title_,!online);lv_obj_set_hidden(temperature_,!online);lv_obj_set_hidden(detail_,!online);
  if(online) {
    lv_label_set_text_static(title_,"Kyiv outdoors");
    char temperature[56];snprintf(temperature,sizeof(temperature),"%s C",reading);
    lv_label_set_text(temperature_,temperature);
    lv_label_set_text_static(detail_,fresh?generic::condition(state_.state.weather.code):"Weather unavailable");
  }
  const char *message=nullptr;
  if(internet==generic::Internet::NoWifi)message="No Wi-Fi";
  else if(state_.availability==generic::Availability::Unavailable)message="Service unavailable";
  else if(state_.availability==generic::Availability::WeatherOnly)message="Photo resources unavailable";
  else if(state_.memoryFailure)message="Photo resources unavailable";
  else if(state_.weatherError==generic::FetchError::Clock)message="Clock unavailable";
  else if(internet==generic::Internet::Checking)message="Checking internet";
  else if(internet==generic::Internet::Offline)message="No internet";
  else if(!fresh)message="Weather unavailable";
  else if(state_.weatherError!=generic::FetchError::None)message="Weather provider unavailable";
  else if(state_.photoError!=generic::FetchError::None)message="Photo provider unavailable";
  else if(active_<0)message="Loading photo";
  if(message)lv_label_set_text_static(status_,message);
  else lv_label_set_text_static(status_,"Picsum / Open-Meteo");
#ifdef ARDUINO
  static uint8_t lastInternet=255,lastSlide=255;static uint32_t lastGeneration=0;static bool lastFresh=false;
  if(lastInternet!=static_cast<uint8_t>(internet)||lastSlide!=slideshow_.slide||lastGeneration!=generation_||lastFresh!=fresh||
     previous.photoError!=state_.photoError||previous.weatherError!=state_.weatherError||previous.memoryFailure!=state_.memoryFailure||
     previous.photoGeneration!=state_.photoGeneration||previous.state.weather.at!=state_.state.weather.at) {
    appLog("[generic] wifi=%u internet=%u slide=%u generation=%lu weatherFresh=%u photo=%s weather=%s decodePeak=%lu memoryFailure=%u\n",
      state_.state.wifi,static_cast<unsigned>(internet),slideshow_.slide,(unsigned long)generation_,fresh,
      generic::errorName(state_.photoError),generic::errorName(state_.weatherError),(unsigned long)state_.decodePeak,state_.memoryFailure);
    if(previous.photoGeneration!=state_.photoGeneration||previous.memoryFailure!=state_.memoryFailure)
      appLog("[generic-resources] pendingGeneration=%lu heapBeforeDecode=%lu heapAfterDecode=%lu workerStackFree=%lu\n",
        (unsigned long)state_.photoGeneration,(unsigned long)state_.heapBeforeDecode,
        (unsigned long)state_.heapAfterDecode,(unsigned long)state_.workerStackFree);
    lastInternet=static_cast<uint8_t>(internet);lastSlide=slideshow_.slide;lastGeneration=generation_;lastFresh=fresh;
  }
#else
  (void)previous;
#endif
}
