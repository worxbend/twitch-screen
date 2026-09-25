#include "link_transport.h"

#include <Arduino.h>
#include <WiFi.h>
#include <atomic>
#include <errno.h>
#include <lwip/dns.h>
#include <lwip/inet.h>
#include <lwip/sockets.h>
#include <lwip/tcpip.h>

#include "credentials.h"

#ifndef FIRMWARE_VERSION
#define FIRMWARE_VERSION "dev"
#endif

namespace {
// DNS and WiFi callbacks publish atomic state only; sockets and UI stay on loop.
enum class DnsState : uint8_t { Idle, Pending, Ready, Failed };
std::atomic<DnsState> dnsState{DnsState::Idle};
uint32_t dnsAddress = 0; // published before Ready with release/acquire ordering
std::atomic<bool> wifiDisconnected{false};

void dnsResult(const char *, const ip_addr_t *address, void *) {
  if (address && IP_IS_V4(address)) {
    dnsAddress = ip4_addr_get_u32(ip_2_ip4(address));
    dnsState.store(DnsState::Ready, std::memory_order_release);
  } else {
    dnsState.store(DnsState::Failed, std::memory_order_release);
  }
}
void startDns(void *) {
  ip_addr_t address;
  const err_t result = dns_gethostbyname_addrtype(
      SERVER_HOST, &address, dnsResult, nullptr, LWIP_DNS_ADDRTYPE_IPV4);
  if (result == ERR_OK) dnsResult(SERVER_HOST, &address, nullptr);
  else if (result != ERR_INPROGRESS) dnsResult(SERVER_HOST, nullptr, nullptr);
}

class Esp32Transport : public LinkTransport {
 public:
  void begin() override {
    const char *configured = DEVICE_ID;
    if (configured[0] != '\0') snprintf(deviceId_, sizeof(deviceId_), "%s", configured);
    else snprintf(deviceId_, sizeof(deviceId_), "lcd-%012llx",
                  (unsigned long long)ESP.getEfuseMac());
    WiFi.onEvent([](WiFiEvent_t event) {
      if (event == ARDUINO_EVENT_WIFI_STA_DISCONNECTED)
        wifiDisconnected.store(true, std::memory_order_release);
    });
    WiFi.mode(WIFI_STA);
    WiFi.setAutoReconnect(true);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    wifiAttemptAt_ = millis();
  }
  void poll() override {
    if (!wifiConnected() && millis() - wifiAttemptAt_ >= 15000) {
      wifiAttemptAt_ = millis();
      WiFi.reconnect();
    }
  }
  uint32_t now() const override { return millis(); }
  uint32_t jitter(uint32_t limit) override { return (uint32_t)random((long)limit); }
  bool wifiConnected() const override { return WiFi.status() == WL_CONNECTED; }
  bool takeWifiDisconnect() override {
    return wifiDisconnected.exchange(false, std::memory_order_acq_rel);
  }
  bool startConnect() override {
    close();
    // A timed-out DNS request stays alone until its callback finishes. Discard
    // its result before retrying: a late result cannot resurrect an old socket.
    if (dnsState.load(std::memory_order_acquire) == DnsState::Pending) return false;
    dnsState.store(DnsState::Pending, std::memory_order_release);
    resolving_ = true;
    if (tcpip_try_callback(startDns, nullptr) != ERR_OK) {
      dnsState.store(DnsState::Idle, std::memory_order_release);
      resolving_ = false;
      return false;
    }
    return true;
  }
  Connect connectStatus() override {
    if (resolving_) {
      const DnsState status = dnsState.load(std::memory_order_acquire);
      if (status == DnsState::Pending) return Connect::Pending;
      resolving_ = false;
      if (status != DnsState::Ready) return Connect::Failed;
      const uint32_t address = dnsAddress;
      dnsState.store(DnsState::Idle, std::memory_order_release);
      fd_ = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
      if (fd_ < 0) return Connect::Failed;
      int enabled = 1;
      if (::ioctl(fd_, FIONBIO, &enabled) != 0 ||
          ::setsockopt(fd_, IPPROTO_TCP, TCP_NODELAY, &enabled, sizeof(enabled)) != 0) {
        close(); return Connect::Failed;
      }
      ::setsockopt(fd_, SOL_SOCKET, SO_KEEPALIVE, &enabled, sizeof(enabled));
      sockaddr_in remote = {};
      remote.sin_family = AF_INET;
      remote.sin_port = htons(SERVER_PORT);
      remote.sin_addr.s_addr = address;
      if (::connect(fd_, (sockaddr *)&remote, sizeof(remote)) == 0) return Connect::Ready;
      if (errno != EINPROGRESS && errno != EWOULDBLOCK) {
        close(); return Connect::Failed;
      }
    }
    if (fd_ < 0) return Connect::Failed;
    fd_set writable, failed;
    FD_ZERO(&writable); FD_ZERO(&failed);
    FD_SET(fd_, &writable); FD_SET(fd_, &failed);
    timeval immediate = {0, 0};
    const int ready = ::select(fd_ + 1, nullptr, &writable, &failed, &immediate);
    if (ready < 0) return Connect::Failed;
    if (ready == 0) return Connect::Pending;
    int error = 0;
    socklen_t size = sizeof(error);
    return (::getsockopt(fd_, SOL_SOCKET, SO_ERROR, &error, &size) == 0 && error == 0)
        ? Connect::Ready : Connect::Failed;
  }
  void close() override {
    if (fd_ >= 0) ::close(fd_);
    fd_ = -1;
    resolving_ = false;
  }
  int read(uint8_t *data, size_t size) override {
    const int n = ::recv(fd_, data, size, MSG_DONTWAIT);
    if (n > 0) return n;
    if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) return 0;
    return -1;
  }
  int write(const uint8_t *data, size_t size) override {
    const int n = ::send(fd_, data, size, MSG_DONTWAIT);
    if (n >= 0) return n;
    return (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) ? 0 : -1;
  }
  void log(const char *line) override {
    const size_t size = strlen(line);
    if (Serial.availableForWrite() >= (int)size) Serial.write((const uint8_t *)line, size);
  }
  const char *deviceId() const override { return deviceId_; }
  const char *firmwareVersion() const override { return FIRMWARE_VERSION; }
 private:
  int fd_ = -1;
  bool resolving_ = false;
  uint32_t wifiAttemptAt_ = 0;
  char deviceId_[32] = {};
};
Esp32Transport transport;
} // namespace
LinkTransport &linkPlatform() { return transport; }
