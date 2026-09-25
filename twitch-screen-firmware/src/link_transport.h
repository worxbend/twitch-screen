#pragma once

#include <stddef.h>
#include <stdint.h>

// All methods return promptly. The session and its callbacks stay on loop();
// platform event/DNS callbacks only publish atomic transport state.
class LinkTransport {
 public:
  enum class Connect : uint8_t { Pending, Ready, Failed };
  virtual ~LinkTransport() {}
  virtual void begin() = 0;
  virtual void poll() = 0;
  virtual uint32_t now() const = 0;
  virtual uint32_t jitter(uint32_t limit) = 0;
  virtual bool wifiConnected() const = 0;
  virtual bool takeWifiDisconnect() = 0;
  // Local disposal/resource work is not a failed network attempt.
  virtual bool readyForConnect() const { return true; }
  virtual bool startConnect() = 0;
  virtual Connect connectStatus() = 0;
  virtual void close() = 0;
  // 0 = would block, -1 = peer closed/error, positive = bytes transferred.
  virtual int read(uint8_t *data, size_t size) = 0;
  virtual int write(const uint8_t *data, size_t size) = 0;
  virtual void log(const char *line) = 0;
  virtual const char *deviceId() const = 0;
  virtual const char *firmwareVersion() const = 0;
};

LinkTransport &linkPlatform();
