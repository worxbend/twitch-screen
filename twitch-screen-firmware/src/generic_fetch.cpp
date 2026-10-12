#include "generic_fetch.h"
#include <stdio.h>
#include <string.h>
namespace generic {
namespace {
struct Close { PublicTransport &transport; ~Close(){transport.close();} };
struct Reading { PublicTransport *transport; uint32_t begin; };
int byte(void *ctx) {
  auto &r=*static_cast<Reading *>(ctx);
  if(r.transport->cancelled()||r.transport->now()-r.begin>=RequestMs)return -2;
  return r.transport->read();
}
}
FetchError fetchPublic(PublicTransport &transport,Url url,bool photo,uint8_t *body,size_t cap,size_t &size,PeerReached *peer) {
  const uint32_t begin=transport.now();size=0;
  if(peer)*peer={};
  for(unsigned redirects=0;redirects<=3;++redirects) {
    if(strcmp(url.host,"picsum.photos")&&strcmp(url.host,"fastly.picsum.photos")&&strcmp(url.host,"api.open-meteo.com"))return FetchError::Redirect;
    if(transport.cancelled())return FetchError::Cancelled;
    const uint32_t elapsed=transport.now()-begin;
    if(elapsed>=RequestMs)return FetchError::Deadline;
    Response response;FetchError error;
    {
      Close guard{transport};
      error=transport.open(url.host,RequestMs-elapsed);
      if(error!=FetchError::None)return error;
      if(transport.cancelled())return FetchError::Cancelled;
      if(transport.now()-begin>=RequestMs)return FetchError::Deadline;
      if(peer){peer->reached=true;peer->at=transport.now();}
      char request[576];const int n=snprintf(request,sizeof(request),
          "GET %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: twitch-screen/1\r\nAccept: %s\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n",
          url.path,url.host,photo?"image/jpeg":"application/json");
      if(n<=0||static_cast<size_t>(n)>=sizeof(request)||!transport.write(reinterpret_cast<const uint8_t *>(request),n))return FetchError::Http;
      Reading r{&transport,begin};error=readResponse(byte,&r,body,cap,response);
    } // close on every success/failure/redirect before parsing/decoding outside.
    if(transport.cancelled())return FetchError::Cancelled;
    if(transport.now()-begin>=RequestMs)return FetchError::Deadline;
    if(error!=FetchError::None)return error;
    if(response.status==200){size=response.size;return FetchError::None;}
    if(!photo||redirects==3||!photoUrl(response.location,url))return FetchError::Redirect;
  }
  return FetchError::Redirect;
}
}
