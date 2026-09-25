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
size_t txSize = 0;
uint32_t txStartedAt = 0;
uint8_t rxChunk[128];
size_t rxOffset = 0, rxSize = 0;
bool eventPaused = false;

// Set by a BYE so that teardown() can apply §7 / §12.1 without a second path.
uint32_t byeFloorMs   = 0;
bool     byeForceMax  = false;

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
  if (txSize == 0) txStartedAt = io->now();
  memcpy(tx + txSize, buf, n);
  txSize += n;
  return true;
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
      : (BACKOFF_BASE_MS << (failures > 6 ? 5 : (uint8_t)(failures - 1)));
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

void teardown(const char *reason) {
  if (state != State::Idle) {
    logf("[link] down: %s\n", reason);
    logCounters();
  }
  io->close();
  txSize = rxSize = rxOffset = 0;
  eventPaused = false;
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
  tsb::buildHello(h, hooks->getLastSeq(), DEVICE_CAPS, io->deviceId(), io->firmwareVersion());
  uint8_t frame[tsb::HEADER_SIZE + tsb::LEN_HELLO];
  const tsb::EncodeResult n = tsb::encodeHello(frame, sizeof(frame), h);
  if (n < 0 || !writeFrame(frame, (size_t)n)) {
    teardown("hello write failed");
    return;
  }
  logf("[link] hello sent, last_seq=%lu caps=0x%02lx\n",
                (unsigned long)h.last_seq, (unsigned long)DEVICE_CAPS);
}

void sendPing() {
  uint8_t frame[tsb::HEADER_SIZE + tsb::LEN_TOKEN];
  const tsb::EncodeResult n =
      tsb::encodePing(frame, sizeof(frame), io->now() / 1000);
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
  const uint32_t now = io->now();
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
  streamingAt = io->now();      // reset the ramp only after a stable interval
  effectiveCaps = w.caps;
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
  lw.caps         = w.caps;
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
  byeFloorMs  = (uint32_t)b.retry_after_s * 1000UL;
  byeForceMax = (b.code == tsb::BYE_UNSUPPORTED_VERSION ||
                 b.code == tsb::BYE_REPLACED);
  if (b.code == tsb::BYE_UNSUPPORTED_VERSION) {
    logf("[link] version refused by relay: this device needs a reflash");
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
  // §7: exact version equality, checked by the session layer rather than by
  // header validation — that is precisely what lets a BYE stay readable from a
  // peer whose version we do not speak, so BYE is exempted here.
  if (h.version != tsb::VERSION && h.type != tsb::T_BYE) {
    logf("[link] protocol version %u, expected %u\n",
                  (unsigned)h.version, (unsigned)tsb::VERSION);
    teardown("version mismatch");
    return;
  }

  // Retain the full EVENT until a display slot exists. The same frame is
  // reconsidered next loop; it has not been decoded, counted or acknowledged.
  if (state == State::Streaming && h.type == tsb::T_EVENT &&
      !hooks->canReceiveNotify()) {
    eventPaused = true;
    return;
  }
  eventPaused = false;

  // §12: ANY inbound frame of any type resets the idle timer, including one
  // that is about to be skipped under §4.3.
  lastRxAt = io->now();

  tsb::InboundFrame f;
  const tsb::DecodeResult r = tsb::decodeInboundPayload(
      h, reader.payload(), reader.payloadLength(), f);
  reader.noteDecode(r);   // §4.3 counters
  reader.consumeFrame();  // releases the frame and resets the §4.5 budget

  // §11.2: before WELCOME, tolerance is suspended. The first inbound frame must
  // be WELCOME or BYE; anything else is a teardown, not a skip.
  if (state == State::Connect) {
    if (h.type != tsb::T_WELCOME && h.type != tsb::T_BYE) {
      logf("[link] handshake: expected WELCOME, got type 0x%02x\n",
                    (unsigned)h.type);
      teardown("bad handshake");
      return;
    }
    if (r != tsb::DecodeResult::Ok) {
      logf("[link] handshake: %s\n", tsb::decodeResultName(r));
      teardown("bad handshake");
      return;
    }
  }

  if (r != tsb::DecodeResult::Ok) {
    // §4.3, §4.6: well framed but not usable. Skip the payload, count it, keep
    // the link. A malformed FRAME must not kill the link.
    ++skippedSinceLog;
    if (io->now() - lastSkipLogAt >= 1000) {
      logf("[link] skipped %lu frame(s); last type=0x%02x %s\n",
           (unsigned long)skippedSinceLog, (unsigned)h.type,
           tsb::decodeResultName(r));
      skippedSinceLog = 0;
      lastSkipLogAt = io->now();
    }
    return;
  }

  dispatch(f);
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
  const int written = io->write(tx, txSize);
  if (written < 0) { teardown("write failed"); return; }
  if (written == 0) return;
  reader.noteSent((uint32_t)written);
  txSize -= (size_t)written;
  memmove(tx, tx + written, txSize);
  // Deadline bounds total pending output, not a trickle's inter-write gap.
}

void attemptConnect() {
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

}  // namespace

void linkInit(const LinkHooks *h, LinkTransport &transport) {
  hooks = h;
  io = &transport;
  state = State::Idle;
  reader.reset(true);
  nextAttemptAt = 0;
  failures = 0;
  byeFloorMs = 0;
  byeForceMax = false;
  effectiveCaps = lastAckedSeq = lastAckAt = 0;
  ackPending = eventPaused = false;
  txSize = rxOffset = rxSize = 0;
  skippedSinceLog = lastSkipLogAt = 0;
  io->begin();
}

bool linkIsUp() { return state == State::Streaming; }

void linkLoop() {
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
  // A local full queue can intentionally hide inbound heartbeats. Keep sending
  // ours; apply the normal silence timeout once EVENT consumption resumes.
  if (eventPaused) lastRxAt = now;
  if (now - lastRxAt > RX_TIMEOUT_MS) {
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
