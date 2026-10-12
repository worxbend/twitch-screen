// Test-only adapter compiled against an immutable baseline firmware snapshot.
// Exercises original LinkSession/NotifyQueue over real TCP, not LVGL or pixels.
#include <arpa/inet.h>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include "link_client.h"
#include "notify_queue.h"

namespace {
class Transport final : public LinkTransport {
 public:
  explicit Transport(unsigned port) : port_(port), origin_(Clock::now()) {}
  ~Transport() override { close(); }
  void begin() override {}
  void poll() override {}
  uint32_t now() const override {
    return static_cast<uint32_t>(std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now()-origin_).count());
  }
  uint32_t jitter(uint32_t) override { return 0; }
  bool wifiConnected() const override { return true; }
  bool takeWifiDisconnect() override { return false; }
  bool startConnect() override {
    close(); fd_ = socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd_ < 0) return false;
    sockaddr_in address{};
    address.sin_family=AF_INET; address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    address.sin_port=htons(static_cast<uint16_t>(port_));
    return connect(fd_, reinterpret_cast<sockaddr *>(&address), sizeof(address))==0 || errno==EINPROGRESS;
  }
  Connect connectStatus() override {
    pollfd p{fd_, POLLOUT, 0};
    if (::poll(&p, 1, 0)<=0) return Connect::Pending;
    int error=0; socklen_t length=sizeof(error);
    return getsockopt(fd_, SOL_SOCKET, SO_ERROR, &error, &length)||error ? Connect::Failed : Connect::Ready;
  }
  void close() override { if(fd_>=0) ::close(fd_); fd_=-1; }
  int read(uint8_t *data, size_t size) override {
    const ssize_t count=recv(fd_, data, size, 0);
    return count>0 ? static_cast<int>(count) : count<0 && (errno==EAGAIN || errno==EINTR) ? 0 : -1;
  }
  int write(const uint8_t *data, size_t size) override {
    const ssize_t count=send(fd_, data, size, MSG_NOSIGNAL);
    return count>=0 ? static_cast<int>(count) : errno==EAGAIN || errno==EINTR ? 0 : -1;
  }
  void log(const char *line) override { fprintf(stderr, "%s\n", line); }
  const char *deviceId() const override { return "baseline-runtime-peer"; }
  const char *firmwareVersion() const override { return "baseline-b30b91f"; }
 private:
  using Clock=std::chrono::steady_clock;
  unsigned port_; int fd_=-1; Clock::time_point origin_;
};
NotifyQueue<8> queue;
unsigned welcomes=0, statistics=0, admitted=0, dequeued=0;
void welcome(const LinkWelcome &w) {
  queue.greet(w.latestSeq,w.sessionId); ++welcomes;
  printf("{\"event\":\"welcome\",\"caps\":%lu,\"desk\":false,\"latestSeq\":%lu}\n", static_cast<unsigned long>(w.caps), static_cast<unsigned long>(w.latestSeq));
}
bool notify(const Notification &n) {
  const auto result=queue.offer(n);
  if(result==NotifyQueue<8>::Offer::Enqueued) {
    ++admitted;
    printf("{\"event\":\"enqueued\",\"seq\":%lu}\n", static_cast<unsigned long>(n.seq));
  }
  return result!=NotifyQueue<8>::Offer::Refused;
}
bool capacity() { return queue.size()<8; }
void stats(const StreamStats &) { ++statistics; }
uint32_t sequence() { return queue.lastSeq(); }
const LinkHooks hooks={welcome,notify,capacity,stats,sequence};
}
int main(int argc,char **argv) {
  if(argc!=2) return 2;
  char *end=nullptr; const unsigned long port=strtoul(argv[1],&end,10);
  if(!end || *end || !port || port>65535) return 2;
  setvbuf(stdout,nullptr,_IOLBF,0);
  Transport transport(static_cast<unsigned>(port)); linkInit(&hooks,transport);
  bool reconnected=false;
  while(transport.now()<10000) {
    if(!reconnected && transport.now()>=4000) { transport.close(); reconnected=true; }
    linkLoop();
    Notification n;
    if(queue.take(n)) { ++dequeued; printf("{\"event\":\"dequeued\",\"seq\":%lu}\n", static_cast<unsigned long>(n.seq)); }
    ::poll(nullptr,0,5);
  }
  printf("{\"event\":\"baseline_summary\",\"welcomes\":%u,\"stats\":%u,\"admitted\":%u,\"dequeued\":%u}\n",welcomes,statistics,admitted,dequeued);
  return welcomes>=2 && statistics && admitted==2 && dequeued==2 ? 0 : 1;
}
