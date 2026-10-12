#include "generic_input.h"
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <math.h>
#include <ctype.h>
#include <limits.h>
namespace generic {
const char *errorName(FetchError e) {
  static const char *const names[] = {"ok", "dns", "tls", "deadline", "http", "headers", "oversized", "truncated", "redirect", "json", "jpeg", "memory", "clock", "cancelled"};
  const unsigned i = static_cast<unsigned>(e); return i < sizeof(names)/sizeof(*names) ? names[i] : "unknown";
}
bool photoUrl(const char *s, Url &url) {
  if (!s || strncmp(s, "https://", 8)) return false;
  for (const char *p = s; *p; ++p) if (*p <= 32 || *p >= 127 || *p == '\\' || *p == '#' || *p == '@') return false;
  const char *slash = strchr(s + 8, '/'); if (!slash) return false;
  const size_t n = static_cast<size_t>(slash - s - 8);
  if (n >= sizeof(url.host) || strlen(slash) >= sizeof(url.path)) return false;
  memcpy(url.host, s + 8, n); url.host[n] = 0;
  if (strcmp(url.host, "picsum.photos") && strcmp(url.host, "fastly.picsum.photos")) return false;
  strcpy(url.path, slash); return true;
}
namespace {
bool number(const char *s, size_t &value, unsigned base = 10) {
  if (!*s) return false;
  value = 0;
  for (; *s; ++s) {
    unsigned d;
    if (*s >= '0' && *s <= '9') d = *s - '0';
    else if (base == 16 && *s >= 'a' && *s <= 'f') d = *s - 'a' + 10;
    else if (base == 16 && *s >= 'A' && *s <= 'F') d = *s - 'A' + 10;
    else return false;
    if (d >= base || value > (SIZE_MAX - d) / base) return false;
    value = value * base + d;
  }
  return true;
}
struct Reader {
  ReadByte read; void *context; size_t headers = 0; FetchError error = FetchError::None;
  bool line(char *out, size_t cap) {
    size_t n = 0;
    for (;;) {
      const int c = read(context);
      if (c < 0) { error = c == -2 ? FetchError::Deadline : FetchError::Truncated; return false; }
      if (++headers > 2048 || n + 1 >= cap) { error = FetchError::Headers; return false; }
      if (c == '\r') {
        const int lf = read(context); ++headers;
        if (lf != '\n') { error = lf == -2 ? FetchError::Deadline : FetchError::Headers; return false; }
        out[n] = 0; return true;
      }
      if (c < 32 || c > 126) { error = FetchError::Headers; return false; }
      out[n++] = static_cast<char>(c);
    }
  }
  FetchError bytes(uint8_t *body, size_t n) {
    for (size_t i = 0; i < n; ++i) { const int c = read(context);
      if (c < 0) return c == -2 ? FetchError::Deadline : FetchError::Truncated;
      body[i] = static_cast<uint8_t>(c);
    } return FetchError::None;
  }
};
}
FetchError readResponse(ReadByte read, void *ctx, uint8_t *body, size_t cap, Response &out) {
  out = {}; Reader r; r.read=read; r.context=ctx; char line[512];
  if (!r.line(line, sizeof(line))) return r.error;
  if (strlen(line) < 12 || (strncmp(line, "HTTP/1.1 ", 9) && strncmp(line, "HTTP/1.0 ", 9)) ||
      !isdigit(line[9]) || !isdigit(line[10]) || !isdigit(line[11]) || (line[12] && line[12] != ' ')) return FetchError::Http;
  out.status = (line[9]-'0')*100 + (line[10]-'0')*10 + line[11]-'0';
  bool lengthSeen = false, chunked = false, typeSeen = false; size_t length = 0;
  for (;;) {
    if (!r.line(line, sizeof(line))) return r.error;
    if (!*line) break;
    char *colon = strchr(line, ':'); if (!colon || colon == line) return FetchError::Headers;
    *colon = 0;
    for (char *p = line; *p; ++p) { if (!isalnum(*p) && *p != '-') return FetchError::Headers; *p = static_cast<char>(tolower(*p)); }
    char *value = colon + 1; while (*value == ' ') ++value;
    size_t n = strlen(value); while (n && value[n-1] == ' ') value[--n] = 0;
    if (!strcmp(line, "content-length")) {
      if (lengthSeen || !number(value, length)) return FetchError::Headers;
      lengthSeen = true;
      if (length > cap) return FetchError::TooLarge;
    } else if (!strcmp(line, "transfer-encoding")) {
      if (chunked || strcmp(value, "chunked")) return FetchError::Headers;
      chunked = true;
    } else if (!strcmp(line, "location")) {
      if (*out.location || n >= sizeof(out.location)) return FetchError::Headers;
      strcpy(out.location, value);
    } else if (!strcmp(line, "content-type")) {
      if (typeSeen) return FetchError::Headers;
      typeSeen = true;
      // Only public-provider response formats. No compressed or HTML bodies.
      if (strncmp(value, "image/jpeg", 10) && strncmp(value, "application/json", 16)) return FetchError::Http;
    } else if (!strcmp(line, "content-encoding") && strcmp(value, "identity")) return FetchError::Http;
  }
  if (chunked && lengthSeen) return FetchError::Headers;
  if (out.status == 301 || out.status == 302 || out.status == 303 || out.status == 307 || out.status == 308)
    return *out.location ? FetchError::None : FetchError::Redirect;
  if (out.status != 200 || !typeSeen) return FetchError::Http;
  if (chunked) {
    for (unsigned chunks = 0; chunks < 256; ++chunks) {
      r.headers = 0;
      if (!r.line(line, sizeof(line))) return r.error;
      // Chunk extensions rejected rather than interpreted permissively.
      size_t n; if (!number(line, n, 16)) return FetchError::Headers;
      if (n > cap - out.size) return FetchError::TooLarge;
      if (!n) {
        r.headers = 0;
        do { if (!r.line(line, sizeof(line))) return r.error; } while (*line);
        return out.size ? FetchError::None : FetchError::Truncated;
      }
      auto e = r.bytes(body + out.size, n); if (e != FetchError::None) return e;
      out.size += n;
      const int cr = read(ctx), lf = read(ctx);
      if (cr != '\r' || lf != '\n') return cr == -2 || lf == -2 ? FetchError::Deadline : FetchError::Truncated;
    }
    return FetchError::Headers;
  }
  if (lengthSeen) { out.size = length; return length ? r.bytes(body, length) : FetchError::Truncated; }
  for (;;) { const int c = read(ctx);
    if (c == -2) return FetchError::Deadline;
    if (c == -1) return out.size ? FetchError::None : FetchError::Truncated;
    if (out.size == cap) return FetchError::TooLarge;
    body[out.size++] = static_cast<uint8_t>(c);
  }
}

namespace {
// Validating recursive descent with fixed token arena and depth. String escapes
// are decoded for semantic keys; duplicate keys are rejected in every object.
struct Token { unsigned start = 0, length = 0, end = 0; char kind = 0; };
struct Json {
  const uint8_t *s; size_t size, pos = 0; Token t[128]; unsigned count = 0;
  void ws() { while (pos < size && (s[pos]==' ' || s[pos]=='\r' || s[pos]=='\n' || s[pos]=='\t')) ++pos; }
  bool string(char *out, size_t cap) {
    if (pos >= size || s[pos++] != '"') return false;
    size_t n = 0;
    while (pos < size) {
      unsigned c = s[pos++];
      if (c == '"') { if (out) out[n] = 0; return true; }
      if (c < 32) return false;
      if (c == '\\') {
        if (pos == size) return false;
        c = s[pos++];
        if (c == 'u') {
          unsigned u = 0;
          for (int i=0;i<4;++i) { if (pos == size) return false; unsigned h = s[pos++];
            if (h>='0'&&h<='9') h-='0'; else if(h>='a'&&h<='f') h=h-'a'+10;
            else if(h>='A'&&h<='F') h=h-'A'+10; else return false;
            u=u*16+h;
          }
          // Keys and units only need ASCII/degree; other escapes remain valid JSON.
          if (u > 127) { if (out) return false; c = '?'; } else c = u;
        } else if (c == 'b') c = 8; else if (c == 'f') c = 12; else if (c == 'n') c = 10;
        else if (c == 'r') c = 13; else if (c == 't') c = 9;
        else if (c != '"' && c != '\\' && c != '/') return false;
      }
      if (out) { if (n+1 >= cap) return false; out[n++] = static_cast<char>(c); }
    }
    return false;
  }
  bool text(unsigned i, char *out, size_t cap) {
    const size_t save = pos; pos = t[i].start; const bool ok = string(out, cap); pos = save; return ok;
  }
  int value(unsigned depth) {
    ws(); if (depth > 8 || count == 128 || pos == size) return -1;
    const unsigned i = count++; t[i].start = pos; const char c = s[pos]; t[i].kind = c;
    if (c == '{' || c == '[') {
      ++pos; ws(); const char close = c=='{' ? '}' : ']';
      unsigned keys[64], keyCount = 0;
      if (pos < size && s[pos] != close) for (;;) {
        if (c == '{') {
          if (pos == size || s[pos] != '"' || keyCount == 64) return -1;
          const int k = value(depth+1); if (k < 0) return -1;
          char key[96]; if (!text(k,key,sizeof(key))) return -1;
          for (unsigned j=0;j<keyCount;++j) { char old[96]; if (!text(keys[j],old,sizeof(old)) || !strcmp(key,old)) return -1; }
          keys[keyCount++] = k; ws(); if (pos == size || s[pos++] != ':') return -1;
        }
        if (value(depth+1) < 0) return -1;
        ws(); if (pos == size) return -1;
        if (s[pos] != ',') break;
        ++pos; ws();
      }
      if (pos == size || s[pos++] != close) return -1;
    } else if (c == '"') { if (!string(nullptr,0)) return -1; }
    else if (c == 't' || c == 'f' || c == 'n') {
      const char *literal = c=='t'?"true":c=='f'?"false":"null"; const size_t n = strlen(literal);
      if (n > size-pos || memcmp(s+pos,literal,n)) return -1;
      pos += n;
    } else {
      t[i].kind = 'N'; if (s[pos]=='-') ++pos;
      if (pos == size) return -1;
      if (s[pos]=='0') ++pos;
      else { if (s[pos]<'1'||s[pos]>'9') return -1; do {++pos;} while(pos<size&&isdigit(s[pos])); }
      if (pos<size&&s[pos]=='.') { ++pos; const size_t begin=pos; while(pos<size&&isdigit(s[pos])) ++pos; if(begin==pos)return -1; }
      if(pos<size&&(s[pos]=='e'||s[pos]=='E')) { ++pos;if(pos<size&&(s[pos]=='+'||s[pos]=='-'))++pos;
        const size_t begin=pos;while(pos<size&&isdigit(s[pos]))++pos;if(begin==pos)return -1; }
    }
    t[i].length = pos-t[i].start; t[i].end = count; return i;
  }
  int field(int object, const char *key) {
    if (object < 0 || t[object].kind != '{') return -1;
    for (unsigned i=object+1;i<t[object].end;) {
      char name[96]; if (!text(i,name,sizeof(name))) return -1;
      const unsigned v=i+1; if (!strcmp(name,key)) return v; i=t[v].end;
    } return -1;
  }
  bool numeric(int i, double &out) {
    if(i<0||t[i].kind!='N'||t[i].length>=48)return false;
    char buffer[48];memcpy(buffer,s+t[i].start,t[i].length);buffer[t[i].length]=0;
    char *end;out=strtod(buffer,&end);return !*end&&isfinite(out);
  }
};
}
bool parseWeather(const uint8_t *s, size_t n, Weather &weather, uint32_t &epoch) {
  if (!s || n > 2048) return false;
  Json j; j.s=s; j.size=n; const int root=j.value(0);j.ws();if(root<0||j.pos!=n)return false;
  const int current=j.field(root,"current"), units=j.field(root,"current_units");
  const int unit=j.field(units,"temperature_2m"), timeUnit=j.field(units,"time");
  char time[24];if(timeUnit<0||!j.text(timeUnit,time,sizeof(time))||strcmp(time,"unixtime"))return false;
  if(unit<0||j.t[unit].kind!='"')return false;
  const auto &u=j.t[unit];
  const bool celsius=(u.length==5&&!memcmp(s+u.start,"\"\xc2\xb0" "C\"",5)) ||
      (u.length==9&&!memcmp(s+u.start,"\"\\u00b0C\"",9));
  if(!celsius)return false;
  double temperature, code, sample, interval;
  if(!j.numeric(j.field(current,"temperature_2m"),temperature)||temperature < -90||temperature > 65 ||
     !j.numeric(j.field(current,"weather_code"),code)||code<0||code>99||floor(code)!=code ||
     !j.numeric(j.field(current,"time"),sample)||sample<1600000000||sample>UINT32_MAX||floor(sample)!=sample ||
     !j.numeric(j.field(current,"interval"),interval)||interval<1||interval>3600||floor(interval)!=interval)return false;
  weather.centiC=static_cast<int16_t>(round(temperature*100));weather.code=static_cast<uint8_t>(code);
  weather.valid=true;epoch=static_cast<uint32_t>(sample);return true;
}
bool validateJpeg(const uint8_t *s,size_t n) {
  if(!s||n<4||n>BodyLimit||s[0]!=255||s[1]!=0xd8||s[n-2]!=255||s[n-1]!=0xd9)return false;
  size_t p=2;bool frame=false,scan=false;unsigned scans=0,segments=0;
  while(p<n) {
    if(s[p++]!=255)return false;
    while(p<n&&s[p]==255)++p;
    if(p==n)return false;
    const unsigned marker=s[p++];
    if(marker==0xd9)return p==n&&frame&&scan;
    if(marker==0||marker==0xd8||(marker>=0xd0&&marker<=0xd7)||++segments>256)return false;
    if(p+2>n)return false;
    const size_t len=(s[p]<<8)|s[p+1];if(len<2||len>n-p)return false;
    if(marker==0xc0||marker==0xc2) {
      if(frame||len<8||s[p+2]!=8||((s[p+3]<<8)|s[p+4])!=PhotoSide||((s[p+5]<<8)|s[p+6])!=PhotoSide)return false;
      const unsigned components=s[p+7];if((components!=1&&components!=3)||len!=8+3*components)return false;
      for(unsigned i=0;i<components;++i) { const unsigned sample=s[p+9+i*3];
        if((sample>>4)<1||(sample>>4)>2||(sample&15)<1||(sample&15)>2)return false; }
      frame=true;
    } else if(marker>=0xc0&&marker<=0xcf&&marker!=0xc4)return false;
    p+=len;
    if(marker==0xda) {
      if(!frame||++scans>32)return false;
      scan=true;
      while(p<n) {
        if(s[p]!=255) {++p;continue;}
        if(p+1>=n)return false;
        if(s[p+1]==0||(s[p+1]>=0xd0&&s[p+1]<=0xd7)) {p+=2;continue;}
        break;
      }
    }
  }return false;
}
}
