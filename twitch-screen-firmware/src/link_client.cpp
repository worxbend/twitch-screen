#include "link_client.h"

#include <stdarg.h>
#include <stdio.h>
#include <string.h>
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
constexpr uint32_t WRITE_TIMEOUT_MS = 3000;
constexpr uint32_t STABLE_STREAM_MS = 60000;
constexpr uint32_t WELCOME_TIMEOUT_MS = 5000;
constexpr uint32_t PING_INTERVAL_MS   = 15000;
constexpr uint32_t RX_TIMEOUT_MS      = 45000;   // inbound silence of any kind
constexpr uint32_t BACKOFF_BASE_MS    = 1000;
constexpr uint32_t BACKOFF_MAX_MS     = 30000;
constexpr uint8_t BACKOFF_MAX_SHIFT = 5;

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

enum class State : uint8_t { Idle, Connecting, Connect, Streaming };

struct LinkSession {
  LinkTransport *io = nullptr;
  State state = State::Idle;
  const LinkHooks *hooks = nullptr;

  tsb::FrameReader reader;

  uint32_t nextAttemptAt = 0;
  uint8_t  failures      = 0;
  uint32_t connectedAt   = 0;
  uint32_t lastRxAt      = 0;
  uint32_t lastPingAt    = 0;
  uint32_t streamingAt   = 0;
  uint32_t lastSkipLogAt = 0;
  uint32_t skippedSinceLog = 0;

  // A fixed byte FIFO preserves frame order across short/nonblocking writes.
  uint8_t tx[256];
  size_t txSize = 0, txOffset = 0;
  uint32_t txStartedAt = 0;
  uint8_t rxChunk[128];
  size_t rxOffset = 0, rxSize = 0;
  bool eventPaused = false;
  uint32_t eventPausedAt = 0;
  uint32_t pauseLimitMs = 2u * EXPECTED_RELAY_IDLE_S * 1000u;

  uint32_t effectiveCaps = 0;   // WELCOME.caps — authoritative for this session
  uint32_t lastAckedSeq  = 0;
  uint32_t lastAckAt     = 0;
  bool     ackPending    = false;

  void logf(const char *format, ...) {
    char line[240];
    va_list args;
    va_start(args, format);
    vsnprintf(line, sizeof(line), format, args);
    va_end(args);
    io->log(line);
  }

  // Enqueue whole frames only. Partial writes remain in this bounded FIFO.
  bool writeFrame(const uint8_t *buf, size_t n) {
    if (n > sizeof(tx) - txSize) {
      reader.noteDropped();
      return false;
    }
    if (txSize == 0) { txStartedAt = io->now(); txOffset = 0; }
    if (txOffset + txSize + n > sizeof(tx)) {
      memmove(tx, tx + txOffset, txSize);
      txOffset = 0;
    }
    memcpy(tx + txOffset + txSize, buf, n);
    txSize += n;
    return true;
  }

  bool sendEncoded(const uint8_t *frame, tsb::EncodeResult result, const char *failure) {
    if (result.ok() && writeFrame(frame, result.size)) return true;
    teardown(failure);
    return false;
  }

  // §12.1 — 1 s doubling to a 30 s cap, plus up to 25 % jitter, forever.
  // `floorMs` raises the floor (BYE.retry_after_s); `forceMax` jumps straight to
  // the cap, which is what §7 requires of BYE(1 UNSUPPORTED_VERSION): a version
  // mismatch needs a reflash, not a retry, and hammering the relay buries the one
  // log line that explains it. BYE(9 REPLACED) takes the cap too, so two devices
  // sharing an id do not evict each other at 1 s.
  void scheduleRetry(uint32_t floorMs, bool forceMax) {
    if (failures < 31) ++failures;
    uint32_t backoff = forceMax
        ? BACKOFF_MAX_MS
        : (BACKOFF_BASE_MS << (failures > BACKOFF_MAX_SHIFT + 1 ? BACKOFF_MAX_SHIFT : (uint8_t)(failures - 1)));
    if (backoff > BACKOFF_MAX_MS) backoff = BACKOFF_MAX_MS;   // caps the ramp only
    if (backoff < floorMs) backoff = floorMs;   // §6.7: a minimum, never capped
    backoff += io->jitter(backoff / 4 + 1);   // jitter
    nextAttemptAt = io->now() + backoff;
    logf("[link] retry in %lu ms (attempt %u)\n",
                  (unsigned long)backoff, (unsigned)failures);
  }

  void logCounters() {
    const tsb::Counters &c = reader.counters();
    logf("[link] counters: rx=%lu frames/%lu B tx=%lu B dropped=%lu "
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

  void teardown(const char *reason, uint32_t floorMs = 0, bool forceMax = false) {
    if (state != State::Idle) {
      logf("[link] down: %s\n", reason);
      logCounters();
    }
    io->close();
    txSize = txOffset = rxSize = rxOffset = 0;
    eventPaused = false;
    state = State::Idle;
    // Drops any half-assembled frame and the §4.5 budget. The §14 lifetime
    // totals survive: they are what diagnoses a link that keeps flapping.
    reader.reset(false);
    effectiveCaps = 0;
    ackPending = false;
    lastAckedSeq = 0;

    scheduleRetry(floorMs, forceMax);
  }

  void sendHello() {
    tsb::TsbHello h;
    tsb::buildHello(h, hooks->getLastSeq(), DEVICE_CAPS, io->deviceId(), io->firmwareVersion());
    uint8_t frame[tsb::HEADER_SIZE + tsb::LEN_HELLO];
    const tsb::EncodeResult n = tsb::encodeHello(frame, sizeof(frame), h);
    if (!sendEncoded(frame, n, "hello write failed")) return;
    logf("[link] hello sent, last_seq=%lu caps=0x%02lx\n",
                  (unsigned long)h.last_seq, (unsigned long)DEVICE_CAPS);
  }

  void sendPing() {
    uint8_t frame[tsb::HEADER_SIZE + tsb::LEN_TOKEN];
    const tsb::EncodeResult n =
        tsb::encodePing(frame, sizeof(frame), io->now() / 1000);
    sendEncoded(frame, n, "ping write failed");
  }

  // §6.3 — a PONG echoes the token byte for byte and must go out as soon as the
  // reader returns, never coalesced behind other work.
  void sendPong(uint32_t token) {
    uint8_t frame[tsb::HEADER_SIZE + tsb::LEN_TOKEN];
    const tsb::EncodeResult n = tsb::encodePong(frame, sizeof(frame), token);
    sendEncoded(frame, n, "pong write failed");
  }

  // §6.6 — informational, rate limited, coalesced to the highest seq the app has
  // actually enqueued. The relay never makes delivery conditional on it.
  void maybeSendAck() {
    if (!ackPending || (effectiveCaps & tsb::CAP_ACK) == 0) return;
    const uint32_t now = io->now();
    if (now - lastAckAt < ACK_MIN_INTERVAL_MS) return;

    const uint32_t seq = hooks->getLastSeq();
    ackPending = false;
    lastAckAt = now;
    if (seq == 0 || seq == lastAckedSeq) return;

    uint8_t frame[tsb::HEADER_SIZE + tsb::LEN_TOKEN];
    const tsb::EncodeResult n = tsb::encodeAck(frame, sizeof(frame), seq);
    if (!sendEncoded(frame, n, "ack write failed")) return;
    lastAckedSeq = seq;
  }

  void handleWelcome(const tsb::TsbWelcome &w) {
    state = State::Streaming;
    streamingAt = io->now();      // reset the ramp only after a stable interval
    effectiveCaps = w.caps & DEVICE_CAPS;
    pauseLimitMs = 2u * (w.idle_timeout_s ? w.idle_timeout_s : EXPECTED_RELAY_IDLE_S) * 1000u;
    lastAckedSeq = 0;

    logf("[link] relay timers: ping=%us idle=%us\n",
         (unsigned)w.ping_interval_s, (unsigned)w.idle_timeout_s);

    // §6.2: max_frame below 256 is logged and otherwise ignored — we keep using
    // 256, which every v3 peer must accept.
    if (w.max_frame < tsb::MIN_RX_MAX) {
      logf("[link] relay max_frame=%u < 256, ignoring\n",
                    (unsigned)w.max_frame);
    }
    // §6.2: the device keeps its own timers; WELCOME reports the relay's. They
    // are compared with the relay's §12 defaults, not with the device's own
    // (which differ on purpose). A mismatch is a real operational fault —
    // someone edited application.conf and believed devices followed.
    if (w.ping_interval_s != 0 && w.ping_interval_s != EXPECTED_RELAY_PING_S) {
      logf("[link] note: relay ping_interval=%us, expected %us\n",
                    (unsigned)w.ping_interval_s, (unsigned)EXPECTED_RELAY_PING_S);
    }
    if (w.idle_timeout_s != 0 && w.idle_timeout_s != EXPECTED_RELAY_IDLE_S) {
      logf("[link] note: relay idle_timeout=%us, expected %us\n",
                    (unsigned)w.idle_timeout_s, (unsigned)EXPECTED_RELAY_IDLE_S);
    }
    // The invariant that actually matters: the relay must wait longer than our
    // ping interval, or it drops a healthy device.
    if (w.idle_timeout_s != 0 &&
        (uint32_t)w.idle_timeout_s * 1000UL <= PING_INTERVAL_MS) {
      logf("[link] WARNING: relay idle_timeout=%us <= device ping "
                    "interval %lus; the relay will drop this link\n",
                    (unsigned)w.idle_timeout_s,
                    (unsigned long)(PING_INTERVAL_MS / 1000));
    }

    LinkWelcome lw;
    lw.latestSeq    = w.latest_seq;
    lw.serverTime   = w.server_time;
    lw.sessionId    = w.session_id;
    lw.caps         = effectiveCaps;
    lw.replayWindow = w.replay_window;
    hooks->onWelcome(lw);

    logf("[link] welcomed: latest_seq=%lu session=%08lx caps=0x%02lx\n",
                  (unsigned long)w.latest_seq, (unsigned long)w.session_id,
                  (unsigned long)w.caps);
  }

  void handleEvent(const tsb::TsbEvent &e, uint8_t frameFlags) {
    Notification n;
    notificationFromEvent(e, frameFlags, n);   // notification_wire.h, host-tested
    if (!hooks->onNotify(n)) {
      teardown("notification refused; reconnect for replay");
      return;
    }
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
    char reason[sizeof(b.reason)];
    memcpy(reason, b.reason, sizeof(reason));
    reason[sizeof(reason) - 1] = '\0';
    replaceDisplayControls(reason);
    logf("[link] BYE code=%u detail=%u retry_after=%us reason=\"%s\"\n",
                  (unsigned)b.code, (unsigned)b.detail,
                  (unsigned)b.retry_after_s, reason);
    const uint32_t floorMs = (uint32_t)b.retry_after_s * 1000UL;
    const bool forceMax = (b.code == tsb::BYE_UNSUPPORTED_VERSION ||
                   b.code == tsb::BYE_REPLACED);
    if (b.code == tsb::BYE_UNSUPPORTED_VERSION) {
      logf("[link] version refused by relay: this device needs a reflash");
    }
    teardown("bye", floorMs, forceMax);
  }

  void dispatch(const tsb::InboundFrame &f) {
    switch (f.header.type) {
      case tsb::T_WELCOME:
        if (state == State::Streaming) teardown("duplicate welcome");
        else handleWelcome(f.as.welcome);
        break;
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
  bool checkVersion(const tsb::TsbHeader &h) {
    if (h.version == tsb::VERSION || h.type == tsb::T_BYE) return true;
    logf("[link] protocol version %u, expected %u\n",
         (unsigned)h.version, (unsigned)tsb::VERSION);
    teardown("version mismatch");
    return false;
  }

  bool maybePauseEvent(const tsb::TsbHeader &h) {
    if (state == State::Streaming && h.type == tsb::T_EVENT &&
        !hooks->canReceiveNotify()) {
      if (!eventPaused) eventPausedAt = io->now();
      eventPaused = true;
      return true;
    }
    eventPaused = false;
    return false;
  }

  bool enforceHandshakeStrictness(const tsb::TsbHeader &h, tsb::DecodeResult result) {
    if (state != State::Connect) return true;
    if ((h.type == tsb::T_WELCOME || h.type == tsb::T_BYE) &&
        result == tsb::DecodeResult::Ok) return true;
    logf("[link] handshake: type=0x%02x %s\n", (unsigned)h.type,
         tsb::decodeResultName(result));
    teardown("bad handshake");
    return false;
  }

  void noteSkippedFrame(const tsb::TsbHeader &h, tsb::DecodeResult result) {
    ++skippedSinceLog;
    if (io->now() - lastSkipLogAt < 1000) return;
    logf("[link] skipped %lu frame(s); last type=0x%02x %s\n",
         (unsigned long)skippedSinceLog, (unsigned)h.type,
         tsb::decodeResultName(result));
    skippedSinceLog = 0;
    lastSkipLogAt = io->now();
  }

  void deliverFrame() {
    const tsb::TsbHeader h = reader.header();
    if (!checkVersion(h) || maybePauseEvent(h)) return;
    // ANY complete frame, including skipped payloads, proves inbound liveness.
    lastRxAt = io->now();
    tsb::InboundFrame frame;
    const tsb::DecodeResult result = tsb::decodeInboundPayload(
        h, reader.payload(), reader.payloadLength(), frame);
    reader.noteDecode(result);
    reader.consumeFrame();
    if (!enforceHandshakeStrictness(h, result)) return;
    if (result != tsb::DecodeResult::Ok) { noteSkippedFrame(h, result); return; }
    dispatch(frame);
  }

  void readAvailable() {
    uint32_t budget = RX_BUDGET_PER_LOOP;
    while (state != State::Idle && budget > 0) {
      if (reader.hasFrame()) {
        deliverFrame();
        if (state == State::Idle || eventPaused) return;
      }
      if (rxOffset == rxSize) {
        const int n = io->read(rxChunk, sizeof(rxChunk));
        if (n < 0) { teardown("peer closed/read failed"); return; }
        if (n == 0) return;
        rxOffset = 0;
        rxSize = (size_t)n;
      }
      size_t want = rxSize - rxOffset;
      if (want > budget) want = budget;
      const size_t used = reader.feed(rxChunk + rxOffset, want);
      rxOffset += used;
      budget -= used;
      if (reader.isFatal()) { teardown("framing violation"); return; }
      if (used == 0 && !reader.hasFrame()) return;
    }
  }

  void flushOutput() {
    if (txSize == 0) return;
    if (io->now() - txStartedAt >= WRITE_TIMEOUT_MS) {
      teardown("write timeout");
      return;
    }
    const int written = io->write(tx + txOffset, txSize);
    if (written < 0) { teardown("write failed"); return; }
    if (written == 0) return;
    reader.noteSent((uint32_t)written);
    txSize -= (size_t)written;
    txOffset += (size_t)written;
    // Deadline bounds total pending output, not a trickle's inter-write gap.
  }

  void attemptConnect() {
    if (!io->readyForConnect()) return;
    logf("[link] connecting...\n");
    if (!io->startConnect()) { teardown("connect unavailable"); return; }
    state = State::Connecting;
    connectedAt = io->now();
  }

  void finishConnect() {
    const LinkTransport::Connect result = io->connectStatus();
    if (result == LinkTransport::Connect::Failed) {
      teardown("connect failed");
    } else if (result == LinkTransport::Connect::Ready) {
      state = State::Connect;
      reader.reset(false);
      effectiveCaps = 0;
      ackPending = false;
      connectedAt = lastRxAt = lastPingAt = io->now();
      logf("[link] connected\n");
      sendHello();
    } else if (io->now() - connectedAt >= CONNECT_TIMEOUT_MS) {
      teardown("connect timeout");
    }
  }

  void init(const LinkHooks *h, LinkTransport &transport) {
    *this = LinkSession{};
    hooks = h;
    io = &transport;
    io->begin();
  }

  bool isUp() const { return state == State::Streaming; }

  void loop() {
    if (!hooks || !io) return;
    io->poll();
    const bool wifiLost = io->takeWifiDisconnect();
    if (wifiLost || !io->wifiConnected()) {
      if (state != State::Idle) teardown("wifi lost");
      return;
    }
    if (state == State::Idle) {
      if ((int32_t)(io->now() - nextAttemptAt) >= 0) attemptConnect();
      return;
    }
    if (state == State::Connecting) {
      finishConnect();
      if (state != State::Connect) return;
    }
    flushOutput();
    if (state == State::Idle) return;
    readAvailable();
    if (state == State::Idle) return;

    const uint32_t now = io->now();
    if (state == State::Connect && now - connectedAt > WELCOME_TIMEOUT_MS) {
      teardown("welcome timeout");
      return;
    }
    // Backpressure can hide peer heartbeats, but cannot keep a dead peer online
    // forever. The original pause timestamp survives repeated loop iterations.
    if (eventPaused && now - eventPausedAt >= pauseLimitMs) {
      teardown("notification pause timeout");
      return;
    }
    if (!eventPaused && now - lastRxAt > RX_TIMEOUT_MS) {
      teardown("heartbeat timeout");
      return;
    }
    if (state == State::Streaming && now - streamingAt >= STABLE_STREAM_MS)
      failures = 0;
    if (now - lastPingAt > PING_INTERVAL_MS) {
      lastPingAt = now;
      sendPing();
      if (state == State::Idle) return;
    }
    maybeSendAck();
    if (state != State::Idle) flushOutput();
  }

};
LinkSession session;
}  // namespace

void linkInit(const LinkHooks *hooks, LinkTransport &transport) { session.init(hooks, transport); }
bool linkIsUp() { return session.isUp(); }
void linkLoop() { session.loop(); }
