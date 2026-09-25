#include "link_client.h"

#include <WiFi.h>
#include <lwip/sockets.h>
#include <lwip/tcp.h>

#include "credentials.h"
#include "notification_wire.h"
#include "proto_codec.h"

// TSB/3 device side. Normative reference: docs/PROTOCOL.md. Section numbers in
// the comments below point at it; where this file and that document disagree,
// that document wins.
//
// There is no JSON here and no character-oriented API anywhere near the socket
// (§2): a partial multi-byte sequence handed to a Reader/String would be
// mangled before the codec ever saw it. Bytes only.

namespace {

// §12 — every timer value is unchanged from v2; only the encoding changed.
constexpr uint32_t CONNECT_TIMEOUT_MS = 3000;
constexpr uint32_t WELCOME_TIMEOUT_MS = 5000;
constexpr uint32_t PING_INTERVAL_MS   = 15000;
constexpr uint32_t RX_TIMEOUT_MS      = 45000;   // inbound silence of any kind
constexpr uint32_t BACKOFF_BASE_MS    = 1000;
constexpr uint32_t BACKOFF_MAX_MS     = 30000;

// §12 — the relay's own timers, which WELCOME reports (§6.2). They differ from
// the device's on purpose (the relay is more patient), so a WELCOME is checked
// against these, not against PING_INTERVAL_MS / RX_TIMEOUT_MS.
constexpr uint16_t EXPECTED_RELAY_PING_S = 20;
constexpr uint16_t EXPECTED_RELAY_IDLE_S = 90;

// §6.6 — ACK at most once per 200 ms, coalesced to the highest seq.
constexpr uint32_t ACK_MIN_INTERVAL_MS = 200;

// Bytes lifted off the socket per linkLoop() call. A replay burst of eighty
// EVENTs is 14 kB; draining it in one go would stall LVGL for the duration, so
// it is spread over consecutive 5 ms iterations instead. TCP does the holding.
constexpr uint32_t RX_BUDGET_PER_LOOP = 1024;

// §6.1 capabilities this build actually has. CAP_UTF8_TEXT is deliberately NOT
// set: the built fonts are Montserrat 14/20/28/48, which cover 0x20..0x7e plus
// two extras, so the relay must fold to printable ASCII for us (§9.3). Claiming
// a font we do not have would render squares, not text.
constexpr uint32_t DEVICE_CAPS =
    tsb::CAP_ACK | tsb::CAP_CHAT | tsb::CAP_GENERIC;

// Diagnostics only, never parsed by the relay (§6.1).
constexpr const char *FW_VERSION = "1.0.0";

enum class State : uint8_t { Idle, Connect, Streaming };

WiFiClient sock;
State state = State::Idle;
const LinkHooks *hooks = nullptr;

tsb::FrameReader reader;

uint32_t nextAttemptAt = 0;
uint8_t  failures      = 0;
uint32_t connectedAt   = 0;
uint32_t lastRxAt      = 0;
uint32_t lastPingAt    = 0;

// Set by a BYE so that teardown() can apply §7 / §12.1 without a second path.
uint32_t byeFloorMs   = 0;
bool     byeForceMax  = false;

uint32_t effectiveCaps = 0;   // WELCOME.caps — authoritative for this session
uint32_t lastAckedSeq  = 0;
uint32_t lastAckAt     = 0;
bool     ackPending    = false;

// §14: bytesSent counts 8 + length per frame, which is exactly `n` here.
bool writeFrame(const uint8_t *buf, size_t n) {
  if (sock.write(buf, n) != n) {
    reader.noteDropped();
    return false;
  }
  reader.noteSent((uint32_t)n);
  return true;
}

// §12.1 — 1 s doubling to a 30 s cap, plus up to 25 % jitter, forever.
// `floorMs` raises the floor (BYE.retry_after_s); `forceMax` jumps straight to
// the cap, which is what §7 requires of BYE(1 UNSUPPORTED_VERSION): a version
// mismatch needs a reflash, not a retry, and hammering the relay buries the one
// log line that explains it.
void scheduleRetry(uint32_t floorMs, bool forceMax) {
  if (failures < 31) ++failures;
  uint32_t backoff = forceMax
      ? BACKOFF_MAX_MS
      : (BACKOFF_BASE_MS << (failures > 6 ? 5 : (uint8_t)(failures - 1)));
  if (backoff > BACKOFF_MAX_MS) backoff = BACKOFF_MAX_MS;   // caps the ramp only
  if (backoff < floorMs) backoff = floorMs;   // §6.7: a minimum, never capped
  backoff += random(0, (long)(backoff / 4 + 1));   // jitter
  nextAttemptAt = millis() + backoff;
  Serial.printf("[link] retry in %lu ms (attempt %u)\n",
                (unsigned long)backoff, (unsigned)failures);
}

void logCounters() {
  const tsb::Counters &c = reader.counters();
  Serial.printf("[link] counters: rx=%lu frames/%lu B tx=%lu B dropped=%lu "
                "resync=%lu discarded=%lu oversize=%lu unknown=%lu wrongdir=%lu "
                "short=%lu invalid=%lu\n",
                (unsigned long)c.framesDecoded, (unsigned long)c.bytesReceived,
                (unsigned long)c.bytesSent, (unsigned long)c.framesDropped,
                (unsigned long)c.resyncEvents,
                (unsigned long)c.discardedBytesTotal,
                (unsigned long)c.framesOversizeSkipped,
                (unsigned long)c.framesUnknownType,
                (unsigned long)c.framesWrongDirection,
                (unsigned long)c.framesShortPayload,
                (unsigned long)c.framesInvalidField);
}

void teardown(const char *reason) {
  if (state != State::Idle) {
    Serial.printf("[link] down: %s\n", reason);
    logCounters();
  }
  sock.stop();
  state = State::Idle;
  // Drops any half-assembled frame and the §4.5 budget. The §14 lifetime
  // totals survive: they are what diagnoses a link that keeps flapping.
  reader.reset(false);
  effectiveCaps = 0;
  ackPending = false;
  lastAckedSeq = 0;

  const uint32_t floorMs = byeFloorMs;
  const bool forceMax = byeForceMax;
  byeFloorMs = 0;
  byeForceMax = false;
  scheduleRetry(floorMs, forceMax);
}

void sendHello() {
  tsb::TsbHello h;
  tsb::buildHello(h, hooks->getLastSeq(), DEVICE_CAPS, DEVICE_ID, FW_VERSION);
  uint8_t frame[tsb::HEADER_SIZE + tsb::LEN_HELLO];
  const tsb::EncodeResult n = tsb::encodeHello(frame, sizeof(frame), h);
  if (n < 0 || !writeFrame(frame, (size_t)n)) {
    teardown("hello write failed");
    return;
  }
  Serial.printf("[link] hello sent, last_seq=%lu caps=0x%02lx\n",
                (unsigned long)h.last_seq, (unsigned long)DEVICE_CAPS);
}

void sendPing() {
  uint8_t frame[tsb::HEADER_SIZE + tsb::LEN_TOKEN];
  const tsb::EncodeResult n =
      tsb::encodePing(frame, sizeof(frame), millis() / 1000);
  if (n < 0 || !writeFrame(frame, (size_t)n)) teardown("ping write failed");
}

// §6.3 — a PONG echoes the token byte for byte and must go out as soon as the
// reader returns, never coalesced behind other work.
void sendPong(uint32_t token) {
  uint8_t frame[tsb::HEADER_SIZE + tsb::LEN_TOKEN];
  const tsb::EncodeResult n = tsb::encodePong(frame, sizeof(frame), token);
  if (n < 0 || !writeFrame(frame, (size_t)n)) teardown("pong write failed");
}

// §6.6 — informational, rate limited, coalesced to the highest seq the app has
// actually enqueued. The relay never makes delivery conditional on it.
void maybeSendAck() {
  if (!ackPending || (effectiveCaps & tsb::CAP_ACK) == 0) return;
  const uint32_t now = millis();
  if (now - lastAckAt < ACK_MIN_INTERVAL_MS) return;

  const uint32_t seq = hooks->getLastSeq();
  ackPending = false;
  lastAckAt = now;
  if (seq == 0 || seq == lastAckedSeq) return;

  uint8_t frame[tsb::HEADER_SIZE + tsb::LEN_TOKEN];
  const tsb::EncodeResult n = tsb::encodeAck(frame, sizeof(frame), seq);
  if (n < 0 || !writeFrame(frame, (size_t)n)) {
    teardown("ack write failed");
    return;
  }
  lastAckedSeq = seq;
}

void handleWelcome(const tsb::TsbWelcome &w) {
  state = State::Streaming;
  failures = 0;                 // the connection proved good; reset the ramp
  effectiveCaps = w.caps;
  lastAckedSeq = 0;

  // §6.2: max_frame below 256 is logged and otherwise ignored — we keep using
  // 256, which every v3 peer must accept.
  if (w.max_frame < tsb::MIN_RX_MAX) {
    Serial.printf("[link] relay max_frame=%u < 256, ignoring\n",
                  (unsigned)w.max_frame);
  }
  // §6.2: the device keeps its own timers; WELCOME reports the relay's. They
  // are compared with the relay's §12 defaults, not with the device's own
  // (which differ on purpose). A mismatch is a real operational fault —
  // someone edited application.conf and believed devices followed.
  if (w.ping_interval_s != 0 && w.ping_interval_s != EXPECTED_RELAY_PING_S) {
    Serial.printf("[link] note: relay ping_interval=%us, expected %us\n",
                  (unsigned)w.ping_interval_s, (unsigned)EXPECTED_RELAY_PING_S);
  }
  if (w.idle_timeout_s != 0 && w.idle_timeout_s != EXPECTED_RELAY_IDLE_S) {
    Serial.printf("[link] note: relay idle_timeout=%us, expected %us\n",
                  (unsigned)w.idle_timeout_s, (unsigned)EXPECTED_RELAY_IDLE_S);
  }
  // The invariant that actually matters: the relay must wait longer than our
  // ping interval, or it drops a healthy device.
  if (w.idle_timeout_s != 0 &&
      (uint32_t)w.idle_timeout_s * 1000UL <= PING_INTERVAL_MS) {
    Serial.printf("[link] WARNING: relay idle_timeout=%us <= device ping "
                  "interval %lus; the relay will drop this link\n",
                  (unsigned)w.idle_timeout_s,
                  (unsigned long)(PING_INTERVAL_MS / 1000));
  }

  LinkWelcome lw;
  lw.latestSeq    = w.latest_seq;
  lw.serverTime   = w.server_time;
  lw.sessionId    = w.session_id;
  lw.caps         = w.caps;
  lw.replayWindow = w.replay_window;
  hooks->onWelcome(lw);

  Serial.printf("[link] welcomed: latest_seq=%lu session=%08lx caps=0x%02lx\n",
                (unsigned long)w.latest_seq, (unsigned long)w.session_id,
                (unsigned long)w.caps);
}

void handleEvent(const tsb::TsbEvent &e, uint8_t frameFlags) {
  Notification n;
  notificationFromEvent(e, frameFlags, n);   // notification_wire.h, host-tested
  hooks->onNotify(n);
  ackPending = true;   // ACK reports whatever the app actually enqueued (§10.5)
}

void handleStats(const tsb::TsbStats &st) {
  StreamStats s;
  s.viewers         = st.viewers;
  s.msgTotal        = st.msg_total;
  s.uptimeSec       = st.uptime_s;
  s.followers       = st.followers;
  s.subs            = st.subs;
  s.serverTime      = st.server_time;
  s.streamStartedAt = st.stream_started_at;
  s.chatRate        = st.chat_rate;
  s.live            = st.live;     // decoder already folded non-zero to 1
  s.sflags          = st.sflags;
  hooks->onStats(s);
}

// §6.7 — BYE is advisory and always the last frame on the connection. Log it,
// close, do not answer.
void handleBye(const tsb::TsbBye &b) {
  Serial.printf("[link] BYE code=%u detail=%u retry_after=%us reason=\"%s\"\n",
                (unsigned)b.code, (unsigned)b.detail,
                (unsigned)b.retry_after_s, b.reason);
  byeFloorMs  = (uint32_t)b.retry_after_s * 1000UL;
  byeForceMax = (b.code == tsb::BYE_UNSUPPORTED_VERSION);
  if (byeForceMax) {
    Serial.println("[link] version refused by relay: this device needs a reflash");
  }
  teardown("bye");
}

void dispatch(const tsb::InboundFrame &f) {
  switch (f.header.type) {
    case tsb::T_WELCOME:    handleWelcome(f.as.welcome); break;
    case tsb::T_EVENT:      handleEvent(f.as.event, f.header.flags); break;
    case tsb::T_STATS:      handleStats(f.as.stats); break;
    case tsb::T_PING_RELAY: sendPong(f.as.token.value); break;
    case tsb::T_PONG_RELAY: break;   // liveness already noted via lastRxAt
    case tsb::T_BYE:        handleBye(f.as.bye); break;
    default:                break;   // decodeInboundPayload never returns Ok here
  }
}

// One complete frame is sitting in the reader. Decode it, apply the session
// layer rules the codec deliberately leaves to the caller (§7 version, §11.2
// handshake strictness), then release it.
void deliverFrame() {
  const tsb::TsbHeader h = reader.header();

  // §12: ANY inbound frame of any type resets the idle timer, including one
  // that is about to be skipped under §4.3.
  lastRxAt = millis();

  tsb::InboundFrame f;
  const tsb::DecodeResult r = tsb::decodeInboundPayload(
      h, reader.payload(), reader.payloadLength(), f);
  reader.noteDecode(r);   // §4.3 counters
  reader.consumeFrame();  // releases the frame and resets the §4.5 budget

  // §7: exact version equality, checked by the session layer rather than by
  // header validation — that is precisely what lets a BYE stay readable from a
  // peer whose version we do not speak, so BYE is exempted here.
  if (h.version != tsb::VERSION && h.type != tsb::T_BYE) {
    Serial.printf("[link] protocol version %u, expected %u\n",
                  (unsigned)h.version, (unsigned)tsb::VERSION);
    teardown("version mismatch");
    return;
  }

  // §11.2: before WELCOME, tolerance is suspended. The first inbound frame must
  // be WELCOME or BYE; anything else is a teardown, not a skip.
  if (state == State::Connect) {
    if (h.type != tsb::T_WELCOME && h.type != tsb::T_BYE) {
      Serial.printf("[link] handshake: expected WELCOME, got type 0x%02x\n",
                    (unsigned)h.type);
      teardown("bad handshake");
      return;
    }
    if (r != tsb::DecodeResult::Ok) {
      Serial.printf("[link] handshake: %s\n", tsb::decodeResultName(r));
      teardown("bad handshake");
      return;
    }
  }

  if (r != tsb::DecodeResult::Ok) {
    // §4.3, §4.6: well framed but not usable. Skip the payload, count it, keep
    // the link. A malformed FRAME must not kill the link.
    Serial.printf("[link] frame skipped: type=0x%02x len=%u %s\n",
                  (unsigned)h.type, (unsigned)h.length,
                  tsb::decodeResultName(r));
    return;
  }

  dispatch(f);
}

void readAvailable() {
  uint8_t chunk[128];
  uint32_t budget = RX_BUDGET_PER_LOOP;

  while (state != State::Idle && budget > 0) {
    const int avail = sock.available();
    if (avail <= 0) break;

    size_t want = (size_t)avail;
    if (want > sizeof(chunk)) want = sizeof(chunk);
    if (want > budget) want = budget;

    // A socket read returns a short count whenever fewer bytes are buffered
    // than were asked for (§2); the reader accumulates across calls, so a
    // partial frame simply leaves it waiting.
    const int n = sock.read(chunk, want);
    if (n <= 0) break;
    budget -= (uint32_t)n;

    size_t off = 0;
    while (off < (size_t)n) {
      const size_t used = reader.feed(chunk + off, (size_t)n - off);
      off += used;
      if (reader.isFatal()) {
        // §4.5: the budget is blown, so the STREAM — not merely a frame — is
        // untrustworthy. Close, log, back off. Nothing is sent on a stream we
        // cannot read.
        Serial.printf("[link] resync budget blown: %lu candidates, %lu bytes\n",
                      (unsigned long)reader.counters().rejectedCandidates,
                      (unsigned long)reader.counters().discardedBytes);
        teardown("framing violation");
        return;
      }
      if (reader.hasFrame()) {
        deliverFrame();
        if (state == State::Idle) return;   // deliverFrame tore the link down
      } else if (used == 0) {
        break;   // defensive: no progress is possible without more bytes
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
  sock.setOption(TCP_NODELAY, &one);    // §2, MUST
  // §2, SHOULD — a second net only. setOption() is IPPROTO_TCP-level, so the
  // SOL_SOCKET option goes through setSocketOption().
  sock.setSocketOption(SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(one));

  state = State::Connect;
  reader.reset(false);   // fresh framing state; §14 lifetime totals survive
  effectiveCaps = 0;
  ackPending = false;
  connectedAt = lastRxAt = lastPingAt = millis();
  Serial.println("[link] connected");

  // §11.1: HELLO immediately after connect, before reading anything.
  sendHello();
}

}  // namespace

void linkInit(const LinkHooks *h) { hooks = h; }

bool linkIsUp() { return state == State::Streaming; }

void linkLoop() {
  if (!hooks) return;

  // WiFi loss tears the socket down immediately; reconnection follows WiFi.
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
  if (state == State::Idle) return;   // tore down while reading

  const uint32_t now = millis();
  if (state == State::Connect && now - connectedAt > WELCOME_TIMEOUT_MS) {
    teardown("welcome timeout");
    return;
  }
  if (now - lastRxAt > RX_TIMEOUT_MS) {
    teardown("heartbeat timeout");
    return;
  }
  if (now - lastPingAt > PING_INTERVAL_MS) {
    lastPingAt = now;
    sendPing();
    if (state == State::Idle) return;
  }
  maybeSendAck();
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
