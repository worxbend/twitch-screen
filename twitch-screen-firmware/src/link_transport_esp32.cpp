#include "link_transport.h"
#include "dns_lookup.h"

#include <Arduino.h>
#include <WiFi.h>
#include <atomic>
#include <errno.h>
#include <esp_system.h>
#include <lwip/dns.h>
#include <lwip/inet.h>
#include <lwip/sockets.h>
#include <lwip/tcpip.h>

#include "credentials.h"

#ifndef FIRMWARE_VERSION
#define FIRMWARE_VERSION "dev"
#endif

namespace {
// DNS/WiFi callbacks publish atomic state only. Socket I/O and UI stay on loop;
// descriptor disposal alone transfers to the bounded close worker.
DnsLookup dnsLookup;
constexpr uint32_t CLOSE_STACK_BYTES = 3072;
constexpr uint32_t WIFI_RETRY_MS = 15000;
std::atomic<bool> wifiDisconnected{false};
std::atomic<int> closingFd{-1};
TaskHandle_t closeTask = nullptr;

// lwIP close can wait for TCP progress even on O_NONBLOCK sockets. The pinned
// SDK disables SO_LINGER, so one bounded worker owns descriptor disposal. No
// new socket may be created until it finishes; there is no unbounded task/FD
// accumulation during outages. The worker never accesses session or UI state.
void closeWorker(void *) {
  for (;;) {
    ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
    const int fd = closingFd.load(std::memory_order_acquire);
    if (fd >= 0) ::close(fd);
    closingFd.store(-1, std::memory_order_release);
  }
}

void dnsResult(const char *, const ip_addr_t *address, void *context) {
  const uint32_t request = static_cast<uint32_t>(reinterpret_cast<uintptr_t>(context));
  const bool found = address && IP_IS_V4(address);
  dnsLookup.complete(request, found, found ? ip4_addr_get_u32(ip_2_ip4(address)) : 0);
}
void startDns(void *context) {
  const uint32_t request = static_cast<uint32_t>(reinterpret_cast<uintptr_t>(context));
  if (!dnsLookup.matches(request)) return;
  ip_addr_t address;
  const err_t result = dns_gethostbyname_addrtype(
      SERVER_HOST, &address, dnsResult, context, LWIP_DNS_ADDRTYPE_IPV4);
  if (result == ERR_OK) dnsResult(SERVER_HOST, &address, context);
  else if (result != ERR_INPROGRESS) dnsResult(SERVER_HOST, nullptr, context);
}

class Esp32Transport : public LinkTransport {
 public:
  void begin() override {
    if (!closeTask && xTaskCreate(closeWorker, "link-close", CLOSE_STACK_BYTES, nullptr, 1,
                                 &closeTask) != pdPASS) {
      closeTask = nullptr;
      log("[link] close worker allocation failed; connections disabled\n");
    }
    const char *configured = DEVICE_ID;
    if (configured[0] != '\0') snprintf(deviceId_, sizeof(deviceId_), "%s", configured);
    else snprintf(deviceId_, sizeof(deviceId_), "lcd-%012llx",
                  (unsigned long long)ESP.getEfuseMac());
    WiFi.onEvent([](WiFiEvent_t event) {
      if (event == ARDUINO_EVENT_WIFI_STA_DISCONNECTED)
        wifiDisconnected.store(true, std::memory_order_release);
    });
    WiFi.mode(WIFI_STA);
    // One owner retries association; do not race Arduino auto-reconnect.
    WiFi.setAutoReconnect(false);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    wifiAttemptAt_ = millis();
  }
  void poll() override {
    if (!wifiConnected() && millis() - wifiAttemptAt_ >= WIFI_RETRY_MS) {
      wifiAttemptAt_ = millis();
      WiFi.reconnect();
    }
  }
  uint32_t now() const override { return millis(); }
  uint32_t jitter(uint32_t limit) override { return limit ? esp_random() % limit : 0; }
  bool wifiConnected() const override { return WiFi.status() == WL_CONNECTED; }
  bool takeWifiDisconnect() override {
    return wifiDisconnected.exchange(false, std::memory_order_acq_rel);
  }
  bool readyForConnect() const override {
    return closeTask && closingFd.load(std::memory_order_acquire) < 0;
  }
  bool startConnect() override {
    if (!readyForConnect()) return false;
    close();
    const uint32_t request = dnsLookup.begin();
    resolving_ = true;
    if (tcpip_try_callback(startDns, reinterpret_cast<void *>(static_cast<uintptr_t>(request))) != ERR_OK) {
      close();
      return false;
    }
    return true;
  }
  Connect connectStatus() override {
    if (resolving_) {
      const DnsLookup::State status = dnsLookup.state();
      if (status == DnsLookup::State::Pending) return Connect::Pending;
      resolving_ = false;
      if (status != DnsLookup::State::Ready) return Connect::Failed;
      const uint32_t address = dnsLookup.address();
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
    if (fd_ >= 0) {
      // startConnect forbids acquiring another descriptor until this slot is
      // released. Ownership crosses only at release/acquire atomic operations.
      closingFd.store(fd_, std::memory_order_release);
      if (closeTask) xTaskNotifyGive(closeTask);
      else log("[link] close worker unavailable; descriptor retained\n");
    }
    fd_ = -1;
    resolving_ = false;
    // The session connection timeout deadlines DNS too, including lost callbacks.
    dnsLookup.cancel();
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
