#include "link_client.h"

#include <WiFi.h>
#include <ArduinoJson.h>
#include <lwip/sockets.h>
#include <lwip/tcp.h>

#include "credentials.h"

namespace {

constexpr uint32_t CONNECT_TIMEOUT_MS = 3000;
constexpr uint32_t WELCOME_TIMEOUT_MS = 5000;
constexpr uint32_t PING_INTERVAL_MS = 15000;
constexpr uint32_t RX_TIMEOUT_MS = 45000;
constexpr uint32_t BACKOFF_BASE_MS = 1000;
constexpr uint32_t BACKOFF_MAX_MS = 30000;
constexpr size_t LINE_CAP = 512;

enum class State : uint8_t { Idle, Streaming };

WiFiClient sock;
State state = State::Idle;
const LinkHooks *hooks = nullptr;

uint32_t nextAttemptAt = 0;
uint8_t failures = 0;
bool welcomed = false;
uint32_t connectedAt = 0;
uint32_t lastRxAt = 0;
uint32_t lastPingAt = 0;

char lineBuf[LINE_CAP];
size_t lineLen = 0;

bool sendLine(const String &s) {
  size_t n = sock.print(s);
  n += sock.print('\n');
  return n == s.length() + 1;
}

void teardown(const char *reason) {
  if (state == State::Streaming) {
    Serial.printf("[link] down: %s\n", reason);
  }
  sock.stop();
  state = State::Idle;
  welcomed = false;
  lineLen = 0;

  failures = failures < 31 ? failures + 1 : failures;
  uint32_t backoff = BACKOFF_BASE_MS << (failures > 5 ? 5 : failures);
  if (backoff > BACKOFF_MAX_MS) backoff = BACKOFF_MAX_MS;
  backoff += random(0, backoff / 4 + 1);  // jitter
  nextAttemptAt = millis() + backoff;
  Serial.printf("[link] retry in %lu ms (attempt %u)\n",
                (unsigned long)backoff, failures);
}

void sendHello() {
  String s = String("{\"op\":\"hello\",\"device\":\"") + DEVICE_ID +
             "\",\"proto\":2,\"last_seq\":" + hooks->getLastSeq() + "}";
  if (!sendLine(s)) teardown("hello write failed");
}

void sendPing() {
  String s = String("{\"op\":\"ping\",\"t\":") + (millis() / 1000) + "}";
  if (!sendLine(s)) teardown("ping write failed");
}

void handleLine() {
  JsonDocument doc;
  if (deserializeJson(doc, lineBuf)) {
    Serial.printf("[link] bad frame: %.60s\n", lineBuf);
    return;
  }
  lastRxAt = millis();

  const char *op = doc["op"] | "";
  if (!strcmp(op, "welcome")) {
    welcomed = true;
    failures = 0;  // connection proved good, reset backoff
    hooks->onWelcome(doc["latest_seq"] | 0UL, doc["server_time"] | 0UL);
    Serial.printf("[link] welcomed, latest_seq=%lu\n",
                  (unsigned long)(doc["latest_seq"] | 0UL));
  } else if (!strcmp(op, "notify")) {
    Notification n;
    n.seq = doc["seq"] | 0UL;
    n.kind = kindFromString(doc["type"] | "info");
    strlcpy(n.title, doc["title"] | "", sizeof(n.title));
    strlcpy(n.body, doc["body"] | "", sizeof(n.body));
    hooks->onNotify(n);
  } else if (!strcmp(op, "stats")) {
    StreamStats s;
    s.live = doc["live"] | false;
    s.viewers = doc["viewers"] | 0UL;
    s.followers = doc["followers"] | 0UL;
    s.subs = doc["subs"] | 0UL;
    s.uptimeSec = doc["uptime_s"] | 0UL;
    s.chatRate = doc["chat_rate"] | 0UL;
    s.msgTotal = doc["msg_total"] | 0UL;
    hooks->onStats(s);
  } else if (!strcmp(op, "ping")) {
    String s = String("{\"op\":\"pong\",\"t\":") + (doc["t"] | 0UL) + "}";
    if (!sendLine(s)) teardown("pong write failed");
  } else if (!strcmp(op, "pong")) {
    // liveness already noted via lastRxAt
  }
}

void readAvailable() {
  while (sock.available()) {
    char c = (char)sock.read();
    if (c == '\n') {
      lineBuf[lineLen] = '\0';
      if (lineLen > 0) handleLine();
      lineLen = 0;
    } else if (c != '\r') {
      if (lineLen < LINE_CAP - 1) {
        lineBuf[lineLen++] = c;
      } else {
        teardown("oversized frame");
        return;
      }
    }
  }
}

void attemptConnect() {
  Serial.printf("[link] connecting %s:%d ...\n", SERVER_HOST, SERVER_PORT);

  sock.stop();
  sock = WiFiClient();
  sock.setTimeout(CONNECT_TIMEOUT_MS / 1000);

  if (!sock.connect(SERVER_HOST, SERVER_PORT, CONNECT_TIMEOUT_MS)) {
    teardown("connect failed");
    return;
  }

  int one = 1;
  sock.setOption(TCP_NODELAY, &one);

  state = State::Streaming;
  welcomed = false;
  connectedAt = lastRxAt = lastPingAt = millis();
  Serial.println("[link] connected");
  sendHello();
}

}  // namespace

void linkInit(const LinkHooks *h) { hooks = h; }

bool linkIsUp() { return state == State::Streaming && welcomed; }

void linkLoop() {
  if (!hooks) return;

  if (WiFi.status() != WL_CONNECTED) {
    if (state != State::Idle) teardown("wifi lost");
    return;
  }

  if (state == State::Idle) {
    if ((int32_t)(millis() - nextAttemptAt) >= 0) attemptConnect();
    return;
  }

  if (!sock.connected() && !sock.available()) {
    teardown("peer closed");
    return;
  }

  readAvailable();
  if (state != State::Streaming) return;  // tore down while reading

  uint32_t now = millis();
  if (!welcomed && now - connectedAt > WELCOME_TIMEOUT_MS) {
    teardown("welcome timeout");
  } else if (now - lastRxAt > RX_TIMEOUT_MS) {
    teardown("heartbeat timeout");
  } else if (now - lastPingAt > PING_INTERVAL_MS) {
    lastPingAt = now;
    sendPing();
  }
}

bool wifiEnsureConnected(uint32_t timeoutMs) {
  if (WiFi.status() == WL_CONNECTED) return true;

  Serial.println("[wifi] reconnecting...");
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  uint32_t start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < timeoutMs) {
    delay(250);
  }
  Serial.printf("[wifi] %s\n", WiFi.status() == WL_CONNECTED ? "connected" : "FAILED");
  return WiFi.status() == WL_CONNECTED;
}
