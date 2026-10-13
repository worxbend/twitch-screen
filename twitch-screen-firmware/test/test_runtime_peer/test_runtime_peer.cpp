// Linux-only, explicitly invoked peer for actual relay/application pairing.
// Memory-display callbacks are real LVGL; they are not physical pixel evidence.
#include <lvgl.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include "desk_application.h"
#include "desk_ui_application.h"
#include "link_client.h"
#include "ui_notify.h"
#include "runtime_metrics.h"

namespace {
using Clock = std::chrono::steady_clock;
volatile std::sig_atomic_t reconnectSignal = 0;
void requestReconnect(int) { reconnectSignal = 1; }
unsigned reportsWritten = 0, observationErrors = 0;
void observeReport(const uint8_t *payload, size_t size) {
  desk::wire::Report report;
  if (desk::wire::decodeReport(payload, size, report) != tsb::DecodeResult::Ok) {
    ++observationErrors;
    return;
  }
  ++reportsWritten;
  printf("{\"event\":\"device_report_written\",\"epoch\":\"%016llx\",\"connection\":\"%016llx\",\"revision\":%lu,\"grantTotal\":%llu,\"processedSeq\":%lu,\"enqueuedSeq\":%lu,\"pending\":%u,\"phase\":%u,\"activeSeq\":%lu,\"remainingMs\":%lu,\"observedDeviceMs\":%llu,\"observedRelayLowerMs\":%llu,",
         static_cast<unsigned long long>(report.epoch), static_cast<unsigned long long>(report.connection),
         static_cast<unsigned long>(report.revision), static_cast<unsigned long long>(report.grant),
         static_cast<unsigned long>(report.processed), static_cast<unsigned long>(report.enqueued),
         static_cast<unsigned>(report.pending), static_cast<unsigned>(report.phase),
         static_cast<unsigned long>(report.entries[0].seq), static_cast<unsigned long>(report.remaining),
         static_cast<unsigned long long>(report.observedDevice), static_cast<unsigned long long>(report.observedRelayLower));
  printf("\"displayRevision\":%lu,\"sourceGeneration\":%lu,\"twitchEpisode\":%lu,\"weatherRevision\":%lu,\"cpuRevision\":%lu,\"baseScreen\":%u,\"reason\":%u,",
         static_cast<unsigned long>(report.display), static_cast<unsigned long>(report.generation),
         static_cast<unsigned long>(report.episode), static_cast<unsigned long>(report.weather),
         static_cast<unsigned long>(report.cpu), static_cast<unsigned>(report.base), static_cast<unsigned>(report.reason));
  printf("\"activeEpoch\":\"%016llx\",\"activeOrigin\":%u,\"expiredTotal\":%lu,\"suppressedTotal\":%lu,\"refusedTotal\":%lu,\"pendingEntries\":[",
         static_cast<unsigned long long>(report.entries[0].epoch), static_cast<unsigned>(report.entries[0].origin),
         static_cast<unsigned long>(report.expired), static_cast<unsigned long>(report.suppressed),
         static_cast<unsigned long>(report.refused));
  for (size_t index = 1; index <= report.pending; ++index) {
    const auto &entry = report.entries[index];
    printf("%s{\"epoch\":\"%016llx\",\"seq\":%lu,\"origin\":%u}", index == 1 ? "" : ",",
           static_cast<unsigned long long>(entry.epoch), static_cast<unsigned long>(entry.seq),
           static_cast<unsigned>(entry.origin));
  }
  printf("]}\n");
}
class SocketTransport final : public LinkTransport {
 public:
  SocketTransport(uint16_t port, size_t maximumWrite, size_t maximumRead, unsigned sendBuffer)
      : port_(port), maximumWrite_(maximumWrite), maximumRead_(maximumRead), sendBuffer_(sendBuffer), started_(Clock::now()) {}
  ~SocketTransport() override { close(); }
  void begin() override {}
  void poll() override {}
  uint32_t now() const override {
    return static_cast<uint32_t>(std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - started_).count());
  }
  uint32_t nowUs() const {
    return static_cast<uint32_t>(std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - started_).count());
  }
  uint32_t jitter(uint32_t) override { return 0; }
  bool wifiConnected() const override { return true; }
  bool takeWifiDisconnect() override { return false; }
  bool startConnect() override {
    close();
    socket_ = ::socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (socket_ < 0) return false;
    if (sendBuffer_) {
      const int requested = static_cast<int>(sendBuffer_);
      if (::setsockopt(socket_, SOL_SOCKET, SO_SNDBUF, &requested, sizeof(requested))) { close(); return false; }
      int actual = 0; socklen_t length = sizeof(actual);
      if (::getsockopt(socket_, SOL_SOCKET, SO_SNDBUF, &actual, &length)) { close(); return false; }
      printf("{\"event\":\"socket_send_buffer\",\"requestedBytes\":%u,\"actualBytes\":%d}\n", sendBuffer_, actual);
    }
    sockaddr_in address{};
    address.sin_family = AF_INET; address.sin_port = htons(port_);
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    const int result = ::connect(socket_, reinterpret_cast<sockaddr *>(&address), sizeof(address));
    return result == 0 || errno == EINPROGRESS;
  }
  Connect connectStatus() override {
    pollfd descriptor{socket_, POLLOUT, 0};
    const int result = ::poll(&descriptor, 1, 0);
    if (result < 0) return Connect::Failed;
    if (!result) return Connect::Pending;
    int error = 0; socklen_t length = sizeof(error);
    if (::getsockopt(socket_, SOL_SOCKET, SO_ERROR, &error, &length) || error) return Connect::Failed;
    return Connect::Ready;
  }
  void close() override {
    if (socket_ >= 0) ::close(socket_);
    socket_ = -1;
    written_.reset();
  }
  int read(uint8_t *data, size_t size) override {
    const ssize_t result = ::recv(socket_, data, std::min(size, maximumRead_), 0);
    if (result > 0) return static_cast<int>(result);
    return result < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) ? 0 : -1;
  }
  int write(const uint8_t *data, size_t size) override {
    const ssize_t result = ::send(socket_, data, std::min(size, maximumWrite_), MSG_NOSIGNAL);
    if (result >= 0) {
      size_t offset = 0;
      while (offset < static_cast<size_t>(result) && !written_.isFatal()) {
        offset += written_.feed(data + offset, static_cast<size_t>(result) - offset);
        if (written_.hasFrame()) {
          if (written_.header().type == desk::wire::DeviceReport)
            observeReport(written_.payload(), written_.payloadLength());
          written_.consumeFrame();
        }
      }
      if (written_.isFatal()) ++observationErrors;
      return static_cast<int>(result);
    }
    if (errno == EAGAIN || errno == EWOULDBLOCK) {
      ++blockedWrites_;
      if (!blockedSince_) {
        blockedSince_ = now() + 1;
        printf("{\"event\":\"socket_write_blocked\",\"atMs\":%lu,\"pendingBytes\":%u}\n",
               static_cast<unsigned long>(now()), static_cast<unsigned>(size));
      }
      return 0;
    }
    return errno == EINTR ? 0 : -1;
  }
  unsigned blockedWrites() const { return blockedWrites_; }
  void log(const char *line) override {
    fprintf(stderr, "%s\n", line);
    if (!strncmp(line, "[link] down:", sizeof("[link] down:") - 1))
      printf("{\"event\":\"link_down\",\"atMs\":%lu,\"writeTimeout\":%s,\"deskDeadline\":%s}\n",
             static_cast<unsigned long>(now()), strstr(line, "write timeout") ? "true" : "false",
             strstr(line, "desk clock/sync deadline") ? "true" : "false");
  }
  const char *deviceId() const override { return "firmware-runtime-peer"; }
  const char *firmwareVersion() const override { return "runtime-pairing"; }
 private:
  uint16_t port_;
  size_t maximumWrite_;
  size_t maximumRead_;
  unsigned sendBuffer_;
  unsigned blockedWrites_ = 0;
  uint32_t blockedSince_ = 0;
  int socket_ = -1;
  Clock::time_point started_;
  tsb::FrameReader written_;
};
bool profileRequested() {
  const char *value = getenv("FIRMWARE_PEER_PROFILE_READY");
  return !value || strcmp(value, "0");
}
desk::Controller controller;
desk::Receiver receiver(controller, 0x0500000000000001ULL, profileRequested());
SocketTransport *transport = nullptr;
unsigned greetings = 0, stats = 0, admitted = 0, started = 0, holds = 0, exits = 0, completed = 0;
bool negotiated = false;
void view() {
  desk::applyApplicationView(receiver, transport->now());
  const BaseView &base = uiBaseView();
  const auto selected = controller.selection(receiver.now());
  const bool cpuHidden = lv_obj_is_hidden(base.cpuView().root);
  const bool weatherHidden = lv_obj_is_hidden(base.weatherView().root);
  const StreamStats projected = receiver.applicationStats();
  const auto &cpu = receiver.cpu();
  const auto &weather = receiver.weather();
  const std::array<uint64_t, 13> key = {{static_cast<uint8_t>(base.selected()),
      static_cast<uint8_t>(selected.reason), base.covered(), cpuHidden, weatherHidden,
      controller.appliedControl().revision, controller.cpuRevision(), controller.connectionId(),
      receiver.stats().generation, receiver.stats().revision, projected.availability,
      controller.weatherRevision(), controller.relayEpoch()}};
  static std::array<uint64_t, 13> previous = {{UINT64_MAX}};
  if (key == previous) return;
  previous = key;
  printf("{\"event\":\"application_view\",\"epoch\":\"%016llx\",\"connection\":\"%016llx\",\"baseScreen\":%u,\"reason\":%u,\"covered\":%s,\"cpuVisible\":%s,\"weatherVisible\":%s,\"displayRevision\":%lu,\"cpuRevision\":%lu,\"statsSourceGeneration\":%lu,\"statsRevision\":%lu,\"statsAvailability\":%u}\n",
         static_cast<unsigned long long>(controller.relayEpoch()),
         static_cast<unsigned long long>(controller.connectionId()), static_cast<unsigned>(base.selected()),
         static_cast<unsigned>(selected.reason), base.covered() ? "true" : "false",
         cpuHidden ? "false" : "true", weatherHidden ? "false" : "true",
         static_cast<unsigned long>(controller.appliedControl().revision), static_cast<unsigned long>(controller.cpuRevision()),
         static_cast<unsigned long>(receiver.stats().generation), static_cast<unsigned long>(receiver.stats().revision),
         static_cast<unsigned>(projected.availability));
  // Numeric/presence observations contain no untrusted source text. This is
  // actual adapter state, not physical pixels or remote report acceptance.
  printf("{\"event\":\"application_telemetry\",\"atMs\":%lu,\"epoch\":\"%016llx\",\"connection\":\"%016llx\",\"displayRevision\":%lu,\"weatherRevision\":%lu,\"cpuRevision\":%lu,\"cpuBasisPoints\":%u,\"memoryBasisPoints\":%u,\"cpuPresence\":%u,\"temperatureCenti\":%d,\"weatherPresence\":%u,\"humidityPercent\":%u}\n",
         static_cast<unsigned long>(transport->now()), static_cast<unsigned long long>(controller.relayEpoch()),
         static_cast<unsigned long long>(controller.connectionId()),
         static_cast<unsigned long>(controller.appliedControl().revision),
         static_cast<unsigned long>(controller.weatherRevision()), static_cast<unsigned long>(controller.cpuRevision()),
         static_cast<unsigned>(cpu.cpu), static_cast<unsigned>(cpu.memory), static_cast<unsigned>(cpu.presence),
         static_cast<int>(weather.temperature), static_cast<unsigned>(weather.presence), static_cast<unsigned>(weather.humidity));
}
void welcome(const LinkWelcome &w) {
  desk::applyApplicationGreeting(controller, w);
  ++greetings; negotiated = (w.caps & desk::wire::Capability) != 0;
  printf("{\"event\":\"welcome\",\"caps\":%lu,\"desk\":%s,\"latestSeq\":%lu}\n",
         static_cast<unsigned long>(w.caps), negotiated ? "true" : "false", static_cast<unsigned long>(w.latestSeq));
}
bool notify(const Notification &n) {
  const desk::Admission result = controller.legacyAdmit(n);
  if (result == desk::Admission::Enqueued) {
    ++admitted;
    printf("{\"event\":\"enqueued\",\"seq\":%lu,\"replay\":%s}\n",
           static_cast<unsigned long>(n.seq), n.replay ? "true" : "false");
  }
  return result == desk::Admission::Enqueued || result == desk::Admission::Duplicate;
}
bool capacity() { return controller.pendingCount() < 8; }
uint32_t sequence() { return controller.legacySeq(); }
void statistics(const StreamStats &s) { ++stats; uiIdleSetStats(s); }
const LinkHooks hooks = {welcome, notify, capacity, statistics, sequence};
void observeLifecycle(unsigned phase) {
  const desk::Card *card = controller.active();
  if (!card) { ++observationErrors; return; }
  printf("{\"event\":\"card_lifecycle\",\"phase\":%u,\"epoch\":\"%016llx\",\"seq\":%lu,\"atMs\":%lu}\n",
         phase, static_cast<unsigned long long>(card->epoch),
         static_cast<unsigned long>(card->notification.seq), static_cast<unsigned long>(transport->now()));
}
void hold() {
  receiver.tick(transport->now());
  controller.hold(receiver.now());
  observeLifecycle(2); ++holds;
}
void exitCard() {
  view(); controller.beginExit(receiver.now(), receiver.calibrated());
  observeLifecycle(3); ++exits;
}
void complete() {
  view(); observeLifecycle(0);
  controller.complete(receiver.now(), receiver.calibrated()); ++completed;
}
const NotifyLifecycle lifecycle = {hold, exitCard, complete};
void flush(lv_display_t *display, const lv_area_t *, uint8_t *) { lv_display_flush_ready(display); }
uint32_t lvglNow() { return transport->now(); }
bool number(const char *name, unsigned minimum, unsigned maximum, unsigned &value) {
  const char *input = getenv(name);
  if (!input || !*input) return false;
  char *end = nullptr; errno = 0;
  const unsigned long parsed = strtoul(input, &end, 10);
  if (errno || *end || parsed < minimum || parsed > maximum) return false;
  value = static_cast<unsigned>(parsed); return true;
}
}
extern "C" void uiFaultHalt(const char *file, int line) {
  fprintf(stderr, "LVGL assertion: %s:%d\n", file, line); abort();
}
int main() {
  unsigned port = 0, duration = 0;
  if (!number("FIRMWARE_PEER_PORT", 1, 65535, port) || !number("FIRMWARE_PEER_SECONDS", 1, 300, duration)) {
    fprintf(stderr, "Set FIRMWARE_PEER_PORT and FIRMWARE_PEER_SECONDS (1..300); target is loopback only.\n");
    return 2;
  }
  unsigned reconnectAt = 0;
  if (getenv("FIRMWARE_PEER_RECONNECT_SECONDS") &&
      !number("FIRMWARE_PEER_RECONNECT_SECONDS", 1, duration, reconnectAt)) return 2;
  unsigned startDelay = 0;
  if (getenv("FIRMWARE_PEER_START_DELAY_SECONDS") &&
      !number("FIRMWARE_PEER_START_DELAY_SECONDS", 1, duration, startDelay)) return 2;
  const char *required = getenv("FIRMWARE_PEER_REQUIRE_DESK");
  if (required && strcmp(required, "0") && strcmp(required, "1")) return 2;
  const char *profile = getenv("FIRMWARE_PEER_PROFILE_READY");
  if (profile && strcmp(profile, "0") && strcmp(profile, "1")) return 2;
  const bool requireDesk = required && !strcmp(required, "1");
  if (requireDesk && !receiver.enabled()) return 2;
  unsigned maximumWrite = 256;
  if (getenv("FIRMWARE_PEER_MAX_WRITE_BYTES") &&
      !number("FIRMWARE_PEER_MAX_WRITE_BYTES", 1, 256, maximumWrite)) return 2;
  unsigned maximumRead = 256;
  if (getenv("FIRMWARE_PEER_MAX_READ_BYTES") &&
      !number("FIRMWARE_PEER_MAX_READ_BYTES", 1, 256, maximumRead)) return 2;
  unsigned sendBuffer = 0;
  if (getenv("FIRMWARE_PEER_SEND_BUFFER_BYTES") &&
      !number("FIRMWARE_PEER_SEND_BUFFER_BYTES", 1024, 65536, sendBuffer)) return 2;
  SocketTransport socket(static_cast<uint16_t>(port), maximumWrite, maximumRead, sendBuffer); transport = &socket;
  setvbuf(stdout, nullptr, _IOLBF, 0);
  lv_init();
  // Use the actual monotonic transport clock during callbacks too. Incrementing
  // once per loop made callback timestamps include variable rendering residence.
  lv_tick_set_cb(lvglNow);
  lv_display_t *display = lv_display_create(240, 240);
  std::array<uint8_t, 240 * 8 * 2> pixels{};
  lv_display_set_color_format(display, LV_COLOR_FORMAT_RGB565);
  lv_display_set_buffers(display, pixels.data(), nullptr, pixels.size(), LV_DISPLAY_RENDER_MODE_PARTIAL);
  lv_display_set_flush_cb(display, flush);
  uiBaseView().build(1); uiNotifyInit(); uiNotifySetLifecycle(&lifecycle);
  linkInit(&hooks, socket, &receiver);
  std::signal(SIGUSR1, requestReconnect);
  bool ready = false, reconnectRequested = false;
  uint64_t readyEpoch = 0, readyConnection = 0;
  size_t peakPending = 0;
  desk::RuntimeMetrics metrics;
  while (socket.now() < duration * 1000U) {
    metrics.loopStarted(socket.nowUs());
    if (!reconnectRequested && (reconnectSignal || (reconnectAt && socket.now() >= reconnectAt * 1000U))) {
      socket.close(); reconnectRequested = true;
      printf("{\"event\":\"reconnect_requested\"}\n");
    }
    linkLoop();
    const uint32_t uiStartedUs = socket.nowUs();
    view();
    const uint32_t now = socket.now();
    lv_timer_handler();
    if (linkDeskReady()) {
      if (readyEpoch != controller.relayEpoch() || readyConnection != controller.connectionId()) {
        readyEpoch = controller.relayEpoch(); readyConnection = controller.connectionId();
        printf("{\"event\":\"desk_ready\",\"epoch\":\"%016llx\",\"connection\":\"%016llx\",\"grantTotal\":%llu}\n",
               static_cast<unsigned long long>(controller.relayEpoch()),
               static_cast<unsigned long long>(controller.connectionId()),
               static_cast<unsigned long long>(controller.grantTotal()));
      }
      ready = true;
    }
    desk::Card card;
    if (controller.pendingCount() > peakPending) peakPending = controller.pendingCount();
    if (now >= startDelay * 1000U && !uiNotifyBusy() && controller.start(receiver.now(), card)) {
      const uint32_t startedAt = socket.now();
      ++started;
      const uint32_t holdMs = card.notification.ttl_ds ? uint32_t(card.notification.ttl_ds) * 100u
                                                      : kindPresentation(card.notification.kind).holdMs;
      printf("{\"event\":\"started\",\"seq\":%lu,\"epoch\":\"%016llx\",\"origin\":%u,\"replay\":%s,\"atMs\":%lu,\"holdMs\":%lu}\n",
             static_cast<unsigned long>(card.notification.seq), static_cast<unsigned long long>(card.epoch),
             static_cast<unsigned>(card.origin), card.notification.replay ? "true" : "false",
             static_cast<unsigned long>(startedAt), static_cast<unsigned long>(holdMs));
      // Match main.cpp: the active card no longer contributes to queue pressure.
      uiNotifySetBacklog(controller.pendingCount());
      uiNotifyShow(card.notification, card.origin != desk::Origin::Unknown);
    }
    metrics.uiFinished(uiStartedUs, socket.nowUs());
    // Same bounded application cadence as ESP32 loop; socket operations never block.
    ::poll(nullptr, 0, 5);
  }
  printf("{\"event\":\"runtime_metrics\",\"loopSamples\":%lu,\"loopP95UpperUs\":%lu,\"loopMaxUs\":%lu,\"uiSamples\":%lu,\"uiP95UpperUs\":%lu,\"uiMaxUs\":%lu,\"physicalVerified\":false}\n",
         static_cast<unsigned long>(metrics.loop().samples()), static_cast<unsigned long>(metrics.loop().p95UpperUs()),
         static_cast<unsigned long>(metrics.loop().maximum()), static_cast<unsigned long>(metrics.ui().samples()),
         static_cast<unsigned long>(metrics.ui().p95UpperUs()), static_cast<unsigned long>(metrics.ui().maximum()));
  printf("{\"event\":\"queue_observation\",\"peakPending\":%u}\n", static_cast<unsigned>(peakPending));
  printf("{\"event\":\"transport_observation\",\"blockedWrites\":%u,\"observationErrors\":%u}\n", socket.blockedWrites(), observationErrors);
  const bool success = greetings && !observationErrors && (requireDesk ? ready : stats > 0);
  printf("{\"event\":\"summary\",\"greetings\":%u,\"deskNegotiated\":%s,\"deskReadyObserved\":%s,\"stats\":%u,\"admittedClassic\":%u,\"started\":%u,\"holds\":%u,\"exits\":%u,\"completed\":%u,\"lastClassicSeq\":%lu,\"physicalVerified\":false,\"success\":%s}\n",
         greetings, negotiated ? "true" : "false", ready ? "true" : "false", stats, admitted,
         started, holds, exits, completed, static_cast<unsigned long>(controller.legacySeq()), success ? "true" : "false");
  // Process exit owns LVGL teardown even when an animation remains active.
  socket.close();
  return success ? 0 : 1;
}
