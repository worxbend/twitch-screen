#pragma once
#include "generic_input.h"
namespace generic {
constexpr uint32_t RequestMs = 15000, DnsMs = 3000;
// Hermetic seam around the exact production request/redirect/deadline policy.
// Adapter owns certificate validation, DNS and TLS; close destroys TLS state.
class PublicTransport {
 public:
  virtual ~PublicTransport() = default;
  virtual uint32_t now() const = 0;
  virtual bool cancelled() const = 0;
  virtual FetchError open(const char *host, uint32_t remainingMs) = 0;
  virtual bool write(const uint8_t *request, size_t size) = 0;
  virtual int read() = 0;
  virtual void close() = 0;
};
// Evidence is only set after authenticated TLS to an allowlisted public host.
// Content/provider failure does not erase it; callers still fence association/visibility.
struct PeerReached { bool reached = false; uint32_t at = 0; };
FetchError fetchPublic(PublicTransport &transport, Url url, bool photo,
                      uint8_t *body, size_t cap, size_t &size, PeerReached *peer = nullptr);
}
