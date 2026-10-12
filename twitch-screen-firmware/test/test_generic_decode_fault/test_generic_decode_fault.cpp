#include "generic_input.h"
#include <array>
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

extern "C" void uiFaultHalt(const char *,int){std::abort();}

namespace {
unsigned checks=0, failures=0, rejected=0, entropyCuts=0, realScans=0;
void check(bool value,const char *name) { ++checks; if(!value){++failures;std::printf("FAIL %s\n",name);} }
std::vector<uint8_t> read(const char *path) {
  FILE *f=std::fopen(path,"rb"); if(!f){std::perror(path);std::exit(2);}
  std::vector<uint8_t> bytes;int c;while((c=std::fgetc(f))!=EOF)bytes.push_back(static_cast<uint8_t>(c));std::fclose(f);return bytes;
}
void valid(const std::vector<uint8_t> &bytes,const char *reference,bool oom=false) {
  std::array<uint16_t,generic::PhotoPixels> pixels;pixels.fill(0x5a69);
  generic::DecodeMemory memory;
  check(generic::validateJpeg(bytes.data(),bytes.size()),"valid control framing");
  const bool decoded=generic::decodePhoto(bytes.data(),bytes.size(),pixels.data(),memory);
  check(decoded!=oom,oom?"legitimate layout exceeds aggregate budget":"legitimate JPEG decodes");
  check(memory.live==0&&memory.peak<=generic::DecodeLimit,"valid/OOM bounded cleanup");
  if(oom) {
    check(memory.exhausted,"coefficient/output OOM is reported");
    check(std::all_of(pixels.begin(),pixels.end(),[](uint16_t p){return p==0x5a69;}),"OOM leaves output unchanged");
  } else {
    auto expected=read(reference);
    check(expected.size()==pixels.size()*sizeof(uint16_t),"pristine upstream reference size");
    check(expected.size()==sizeof(pixels)&&!std::memcmp(pixels.data(),expected.data(),expected.size()),"RGB565 exactly equals pristine upstream");
    std::printf("valid bytes=%zu peak=%zu live=%zu reference=%s\n",bytes.size(),memory.peak,memory.live,reference);
  }
}
void reject(const std::vector<uint8_t> &bytes,const char *name) {
  std::array<uint16_t,generic::PhotoPixels> out;out.fill(0x5a69);
  generic::DecodeMemory memory;
  // Publication remains contingent on the real decoder result, not framing.
  unsigned publications=0;
  if(generic::decodePhoto(bytes.data(),bytes.size(),out.data(),memory))++publications;
  else ++rejected;
  check(publications==0,name);
  check(std::all_of(out.begin(),out.end(),[](uint16_t p){return p==0x5a69;}),"failure leaves output unchanged");
  check(memory.live==0,"failure frees every allocation");check(memory.peak<=generic::DecodeLimit,"aggregate allocation cap");
}
}
int main(int argc,char **argv) {
  const char *fixture=argc>1?argv[1]:"test/test_generic_view/photo96.jpg";
  auto photo=read(fixture);
  check(photo.size()==2732,"actual 2732-byte provider fixture");
  check(photo[438]==255&&photo[439]==0xda,"actual first SOS at438");
  std::array<uint16_t,generic::PhotoPixels> out{};generic::DecodeMemory memory;
  check(generic::decodePhoto(photo.data(),photo.size(),out.data(),memory),"untouched real progressive decodes");
  check(memory.live==0&&memory.peak<=generic::DecodeLimit,"valid fixture bounded cleanup");
  valid(photo,"test/test_generic_view/photo96.rgb565");
  check(generic::decodePhoto(photo.data(),photo.size(),out.data(),memory,24*1024),
        "real progressive photo decodes with no contiguous allocation larger than 24KiB");
  const auto reference=read("test/test_generic_view/photo96.rgb565");
  check(memory.live==0&&reference.size()==sizeof(out)&&!std::memcmp(out.data(),reference.data(),reference.size()),
        "fragmented-heap decode cleans up and exactly matches upstream RGB565");
  auto markerless=photo;markerless.resize(markerless.size()-2);reject(markerless,"markerless truncation remains rejected");
  auto dimensions=photo;dimensions[369]=95;reject(dimensions,"bad exact96x96 dimensions rejected");
  auto oversized=photo;oversized.resize(generic::BodyLimit+1);reject(oversized,"encoded8KiB bound remains enforced");
  for(const char *type:{"baseline","progressive"})for(unsigned sampling=0;sampling<3;++sampling)for(unsigned restart:{0u,3u}) {
    char jpg[256],reference[256];
    std::snprintf(jpg,sizeof(jpg),"test/test_generic_decode_fault/color-%s-s%u-r%u.jpg",type,sampling,restart);
    std::snprintf(reference,sizeof(reference),"test/test_generic_decode_fault/color-%s-s%u-r%u.rgb565",type,sampling,restart);
    const bool oom=!std::strcmp(type,"progressive")&&sampling<2;
    valid(read(jpg),reference,oom);
  }
  for(const char *type:{"baseline","progressive"})for(unsigned restart:{0u,3u}) {
    char jpg[256],reference[256];
    std::snprintf(jpg,sizeof(jpg),"test/test_generic_decode_fault/gray-%s-s0-r%u.jpg",type,restart);
    std::snprintf(reference,sizeof(reference),"test/test_generic_decode_fault/gray-%s-s0-r%u.rgb565",type,restart);
    valid(read(jpg),reference);
  }
  for(size_t body:{size_t(454),size_t(455),size_t(456)}) {
    std::vector<uint8_t> shortPhoto(photo.begin(),photo.begin()+body-2);
    shortPhoto.push_back(255);shortPhoto.push_back(0xd9);
    check(generic::validateJpeg(shortPhoto.data(),shortPhoto.size()),"first-scan truncated framing remains valid");
    char name[80];std::snprintf(name,sizeof(name),"first-scan entropy truncated body%zu rejected",body);reject(shortPhoto,name);
  }
  // Locate actual SOS segments and shorten entropy well inside each scan.
  for(size_t i=2;i+4<photo.size();++i) if(photo[i]==255&&photo[i+1]==0xda) {
    const size_t start=i+2+(photo[i+2]*256+photo[i+3]);
    ++realScans;
    size_t end=start;
    while(end+1<photo.size()) {
      if(photo[end]==255&&photo[end+1]!=0&&!(photo[end+1]>=0xd0&&photo[end+1]<=0xd7))break;
      end+=photo[end]==255?2:1;
    }
    for(size_t cut=start;cut<end;++cut) {
      std::vector<uint8_t> prefix(photo.begin(),photo.begin()+cut);
      prefix.push_back(255);prefix.push_back(0xd9);
      ++entropyCuts;
      reject(prefix,"every real inside-entropy prefix rejected");
    }
    const size_t cut=start+(end-start)/2;
    std::vector<uint8_t> shortened(photo.begin(),photo.begin()+cut);
    shortened.push_back(255);shortened.push_back(0xd9);
    check(generic::validateJpeg(shortened.data(),shortened.size()),"later-scan truncated framing remains valid");
    reject(shortened,"entropy truncated inside actual scan");
    auto spliced=photo;spliced.erase(spliced.begin()+cut,spliced.begin()+end);
    reject(spliced,"shortened entropy with remaining original scans rejected");
  }
  auto unexpected=photo;unexpected.insert(unexpected.begin()+453,{255,0xd0});
  check(generic::validateJpeg(unexpected.data(),unexpected.size()),"unexpected RST is framing-valid");
  reject(unexpected,"RST at non-MCU boundary rejected");
  // The generator uses libjpeg restart intervals of three MCUs/blocks.
  for(const char *kind:{"color-baseline-s2-r3.jpg","color-progressive-s2-r3.jpg","gray-baseline-s0-r3.jpg","gray-progressive-s0-r3.jpg"}) {
    char path[256];std::snprintf(path,sizeof(path),"test/test_generic_decode_fault/%s",kind);
    auto restarted=read(path);size_t rst=0;
    for(size_t i=0;i+1<restarted.size();++i)if(restarted[i]==255&&restarted[i+1]==0xd0){rst=i;break;}
    check(rst!=0,"generated control has real restart markers");
    check(generic::decodePhoto(restarted.data(),restarted.size(),out.data(),memory),"valid restart control decodes");
    check(memory.live==0&&memory.peak<=generic::DecodeLimit,"restart control allocation cleanup");
    auto wrong=restarted;wrong[rst+1]=0xd1;reject(wrong,"out-of-sequence first restart rejected");
    for(size_t i=rst+2;i+1<restarted.size();++i)if(restarted[i]==255&&restarted[i+1]>=0xd0&&restarted[i+1]<=0xd7) {
      auto sequence=restarted;sequence[i+1]=static_cast<uint8_t>(0xd0+((sequence[i+1]-0xd0+1)&7));
      reject(sequence,"out-of-sequence later restart rejected");
    }
    auto noDri=restarted;
    for(size_t i=2;i+5<noDri.size();++i)if(noDri[i]==255&&noDri[i+1]==0xdd) {
      noDri.erase(noDri.begin()+i,noDri.begin()+i+6);break;
    }
    reject(noDri,"restart without DRI rejected");
    auto missing=restarted;missing.erase(missing.begin()+rst,missing.begin()+rst+2);reject(missing,"missing restart rejected");
    auto early=restarted;early.insert(early.begin()+rst-1,{255,0xd0});reject(early,"premature restart rejected");
    auto endMarker=restarted;endMarker[rst+1]=0xd9;endMarker.resize(rst+2);reject(endMarker,"premature EOI at restart boundary rejected");
  }
  check(realScans==10,"all ten actual progressive scans exercised");
  std::printf("realScans=%u insideEntropyCuts=%u rejectedCases=%u\n",realScans,entropyCuts,rejected);
  std::printf("%u checks, %u failures\n",checks,failures);return failures?1:0;
}
