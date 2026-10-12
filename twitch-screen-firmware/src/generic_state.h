#pragma once
#include <stdint.h>
#include <stddef.h>

// Local presentation only. Relay/controller enums and reports remain untouched.
namespace generic {
constexpr uint32_t SlideMs = 5000, WeatherRefreshMs = 300000;
constexpr uint32_t WeatherFreshMs = 600000, ReachableMs = 90000, RetryMs = 30000;
constexpr size_t PhotoSide = 96, PhotoPixels = PhotoSide * PhotoSide, BodyLimit = 8192;
constexpr size_t DecodeLimit = 64 * 1024;
enum class Internet : uint8_t { NoWifi, Checking, Offline, Online };
struct Weather { int16_t centiC = 0; uint8_t code = 0; bool valid = false; uint32_t at = 0; };
struct State {
  bool wifi = false, evidence = false, attempted = false;
  uint32_t reachableAt = 0;
  Weather weather;
  void association(bool connected) {
    if (wifi == connected) return;
    wifi = connected; evidence = attempted = false;
    // A previous association is never proof of current connectivity.
  }
  void success(uint32_t now) { if (wifi) { evidence = true; reachableAt = now; } }
  void failure() { attempted = true; }
  Internet internet(uint32_t now) const {
    if (!wifi) return Internet::NoWifi;
    if (evidence && now - reachableAt < ReachableMs) return Internet::Online;
    return attempted || evidence ? Internet::Offline : Internet::Checking;
  }
  bool weatherFresh(uint32_t now) const { return wifi && weather.valid && now - weather.at < WeatherFreshMs; }
};
inline const char *condition(unsigned code) {
  if (code == 0) return "Clear";
  if (code <= 2) return "Partly cloudy";
  if (code == 3) return "Cloudy";
  if (code == 45 || code == 48) return "Fog";
  if (code >= 51 && code <= 57) return "Drizzle";
  if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "Rain";
  if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "Snow";
  if (code >= 95 && code <= 99) return "Thunderstorm";
  return "Unknown";
}
struct Slideshow {
  bool visible = false; uint8_t slide = 0; uint32_t since = 0;
  void visibility(bool value, uint32_t now) { if (value != visible) { visible = value; since = now; } }
  bool tick(uint32_t now) {
    if (!visible || now - since < SlideMs) return false;
    slide = (slide + 1) % 2; since = now; return true;
  }
};
enum class Owner : uint8_t { Free, Writing, Pending, Active };
// All calls require the service mutex. The renderer detaches/cache-drops before release.
struct Slots {
  Owner owner[2] = {Owner::Free, Owner::Free}; uint32_t generation[2] = {}; uint32_t next = 0;
  int reserve() { if(pending()>=0)return -1; for (int i = 0; i < 2; ++i) if (owner[i] == Owner::Free) { owner[i] = Owner::Writing; return i; } return -1; }
  void abandon(int i) { if (i >= 0 && owner[i] == Owner::Writing) owner[i] = Owner::Free; }
  bool publish(int i) { if (i < 0 || owner[i] != Owner::Writing) return false;
    owner[i] = Owner::Pending; generation[i] = ++next; return true; }
  int pending() const { for (int i = 0; i < 2; ++i) if (owner[i] == Owner::Pending) return i; return -1; }
  bool activate(int i) { if (i < 0 || owner[i] != Owner::Pending) return false; owner[i] = Owner::Active; return true; }
  void release(int i) { if (i >= 0 && owner[i] == Owner::Active) owner[i] = Owner::Free; }
};
}
