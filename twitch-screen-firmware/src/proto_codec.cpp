//
// TSB/3 device-side codec — see proto_codec.h and docs/PROTOCOL.md.
//
// Receiver normalisations applied by the decoders, all of them spec rules so
// that the caller never has to remember them:
//   EVENT.seq == 0        -> DecodeResult::InvalidField, frame skipped (§6.4)
//   EVENT.tier > 4        -> 0, "no tier" (§6.4)
//   EVENT.ttl_ds > 6000   -> clamped to 6000 (§6.4, SHOULD)
//   STATS.live != 0       -> 1 (§6.5)
//   every string field    -> last byte of OUR copy forced to NUL (§9.1)
//
// EVENT.reserved1 (+12..+15) and EVENT.reserved2 (+23) are NOT normalised and
// NOT validated. §6.4 and §8.1 say senders MUST write zero and receivers MUST
// IGNORE the content; ignoring it means not acting on it, not erasing it. These
// five bytes are the space held open for a future monetary amount, so a decoder
// that blanked them would hand a v4 frame to the renderer with the new field
// already destroyed — exactly the forward compatibility §8.1 exists to buy.
// They are therefore copied through byte for byte and never looked at again.
//
#include "proto_codec.h"

#include <string.h>

namespace tsb {

// ---------------------------------------------------------------------------
// Byte-level primitives. Every multi-byte value is assembled from individual
// bytes, little-endian, so nothing ever depends on the buffer's alignment or on
// the host's byte order.
// ---------------------------------------------------------------------------

static inline uint16_t rdU16(const uint8_t *p) {
  return (uint16_t)((uint16_t)p[0] | ((uint16_t)p[1] << 8));
}

static inline uint32_t rdU32(const uint8_t *p) {
  return (uint32_t)p[0]
       | ((uint32_t)p[1] << 8)
       | ((uint32_t)p[2] << 16)
       | ((uint32_t)p[3] << 24);
}

static inline void wrU16(uint8_t *p, uint16_t v) {
  p[0] = (uint8_t)(v & 0xff);
  p[1] = (uint8_t)((v >> 8) & 0xff);
}

static inline void wrU32(uint8_t *p, uint32_t v) {
  p[0] = (uint8_t)(v & 0xff);
  p[1] = (uint8_t)((v >> 8) & 0xff);
  p[2] = (uint8_t)((v >> 16) & 0xff);
  p[3] = (uint8_t)((v >> 24) & 0xff);
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

uint8_t headerCheck(const uint8_t *hdr) {
  // Positional weights (§3.1): a one-byte stream shift moves `type` into
  // `length` and back, which a plain additive sum would not notice.
  uint32_t sum = 0;
  for (uint32_t i = 0; i < 7; ++i) {
    sum += (i + 1) * (uint32_t)hdr[i];
  }
  return (uint8_t)(0xFFu ^ (sum & 0xFFu));
}

static DecodeResult validateHeader(const uint8_t *hdr) {
  if (hdr[0] != MAGIC0 || hdr[1] != MAGIC1) return DecodeResult::BadMagic;
  if (hdr[7] != headerCheck(hdr)) return DecodeResult::HeaderCheckFail;
  if (hdr[3] == 0) return DecodeResult::IllegalType;
  if (rdU16(hdr + 4) > MAX_PAYLOAD) return DecodeResult::LengthOutOfRange;
  return DecodeResult::Ok; // version is session policy, not framing
}

bool headerIsValid(const uint8_t *hdr) {
  return hdr != nullptr && validateHeader(hdr) == DecodeResult::Ok;
}

bool typeIsInbound(uint8_t type)  { return type >= 0x20 && type <= 0x3f; }
bool typeIsOutbound(uint8_t type) { return type >= 0x01 && type <= 0x1f; }

uint16_t baseLengthFor(uint8_t type) {
  switch (type) {
    case T_HELLO:       return LEN_HELLO;
    case T_PING_DEVICE: return LEN_TOKEN;
    case T_PONG_DEVICE: return LEN_TOKEN;
    case T_ACK:         return LEN_TOKEN;
    case T_WELCOME:     return LEN_WELCOME;
    case T_EVENT:       return LEN_EVENT;
    case T_STATS:       return LEN_STATS;
    case T_PING_RELAY:  return LEN_TOKEN;
    case T_PONG_RELAY:  return LEN_TOKEN;
    case T_BYE:         return LEN_BYE;
    default:            return 0xffff;
  }
}

const char *decodeResultName(DecodeResult r) {
  switch (r) {
    case DecodeResult::Ok:               return "ok";
    case DecodeResult::Truncated:        return "truncated";
    case DecodeResult::BadMagic:         return "bad-magic";
    case DecodeResult::HeaderCheckFail:  return "header-check";
    case DecodeResult::IllegalType:      return "illegal-type";
    case DecodeResult::LengthOutOfRange: return "length-range";
    case DecodeResult::UnknownType:      return "unknown-type";
    case DecodeResult::WrongDirection:   return "wrong-direction";
    case DecodeResult::ShortPayload:     return "short-payload";
    case DecodeResult::InvalidField:     return "invalid-field";
  }
  return "?";
}

DecodeResult decodeHeader(const uint8_t *frame, size_t length, TsbHeader &out) {
  if (frame == nullptr || length < HEADER_SIZE)       return DecodeResult::Truncated;
  const DecodeResult result = validateHeader(frame);
  if (result != DecodeResult::Ok) return result;
  out = {frame[2], frame[3], rdU16(frame + 4), frame[6]};
  return DecodeResult::Ok;
}

// ---------------------------------------------------------------------------
// Strings
// ---------------------------------------------------------------------------

void copyWireString(char *dst, const uint8_t *src, size_t width) {
  if (dst == nullptr || width == 0) return;
  if (src != nullptr) {
    memcpy(dst, src, width);
  } else {
    memset(dst, 0, width);
  }
  dst[width - 1] = '\0';   // §9.1: never trust the sender for termination
}

bool packWireString(char *field, size_t width, const char *src) {
  if (field == nullptr || width == 0) return false;
  memset(field, 0, width);
  if (src == nullptr || width == 1) return false;

  const size_t cap = width - 1;
  const size_t len = strnlen(src, width);  // only len <= cap matters
  if (len <= cap) {
    memcpy(field, src, len);
    return false;
  }
  // §9.2, exactly: cut back to a code-point boundary, then append "...".
  const auto *b = reinterpret_cast<const uint8_t *>(src);
  size_t cut = (cap >= 3) ? (cap - 3) : 0;
  while (cut > 0 && (b[cut] & 0xc0) == 0x80) {
    --cut;
  }
  memcpy(field, src, cut);
  size_t dots = (cap - cut < 3) ? (cap - cut) : 3;
  for (size_t i = 0; i < dots; ++i) field[cut + i] = '.';
  return true;
}

// ---------------------------------------------------------------------------
// Payload decoders. Each one bounds-checks the whole record up front; every
// field read below is therefore inside a buffer already proven long enough.
// ---------------------------------------------------------------------------

DecodeResult decodeHello(const uint8_t *p, size_t length, TsbHello &out) {
  if (p == nullptr)              return DecodeResult::ShortPayload;
  if (length < LEN_HELLO)  return DecodeResult::ShortPayload;
  out.last_seq  = rdU32(p + 0);
  out.caps      = rdU32(p + 4);
  out.rx_max    = rdU16(p + 8);
  out.reserved0 = rdU16(p + 10);
  copyWireString(out.device_id,  p + 12, sizeof(out.device_id));
  copyWireString(out.fw_version, p + 44, sizeof(out.fw_version));
  return DecodeResult::Ok;
}

DecodeResult decodeWelcome(const uint8_t *p, size_t length, TsbWelcome &out) {
  if (p == nullptr)               return DecodeResult::ShortPayload;
  if (length < LEN_WELCOME) return DecodeResult::ShortPayload;
  out.latest_seq      = rdU32(p + 0);
  out.server_time     = rdU32(p + 4);
  out.session_id      = rdU32(p + 8);
  out.max_frame       = rdU16(p + 12);
  out.ping_interval_s = rdU16(p + 14);
  out.idle_timeout_s  = rdU16(p + 16);
  out.replay_window   = rdU16(p + 18);
  out.caps            = rdU32(p + 20);
  return DecodeResult::Ok;
}

DecodeResult decodeEvent(const uint8_t *p, size_t length, TsbEvent &out) {
  if (p == nullptr)             return DecodeResult::ShortPayload;
  if (length < LEN_EVENT) return DecodeResult::ShortPayload;

  const uint32_t seq = rdU32(p + 0);
  if (seq == 0) return DecodeResult::InvalidField;   // §6.4: seq 0 is illegal

  out.seq    = seq;
  out.ts     = rdU32(p + 4);
  out.value  = rdU32(p + 8);
  // §8.1: carried verbatim. Not parsed, not validated, not rewritten.
  out.reserved1[0] = p[12];
  out.reserved1[1] = p[13];
  out.reserved1[2] = p[14];
  out.reserved1[3] = p[15];
  out.months = rdU16(p + 16);
  out.ttl_ds = rdU16(p + 18);
  out.kind   = p[20];
  out.tier   = p[21];
  out.eflags = p[22];
  out.reserved2 = p[23];   // §8.1: carried verbatim, likewise.
  copyWireString(out.actor, p + 24, sizeof(out.actor));
  copyWireString(out.text,  p + 72, sizeof(out.text));

  if (out.tier > TIER_MAX)     out.tier = TIER_NONE;
  if (out.ttl_ds > TTL_DS_MAX) out.ttl_ds = TTL_DS_MAX;
  return DecodeResult::Ok;
}

DecodeResult decodeStats(const uint8_t *p, size_t length, TsbStats &out) {
  if (p == nullptr)             return DecodeResult::ShortPayload;
  if (length < LEN_STATS) return DecodeResult::ShortPayload;
  out.viewers           = rdU32(p + 0);
  out.msg_total         = rdU32(p + 4);
  out.uptime_s          = rdU32(p + 8);
  out.followers         = rdU32(p + 12);
  out.subs              = rdU32(p + 16);
  out.server_time       = rdU32(p + 20);
  out.stream_started_at = rdU32(p + 24);
  out.chat_rate         = rdU16(p + 28);
  out.live              = p[30] ? 1 : 0;   // §6.5: any non-zero means live
  out.sflags            = p[31];
  return DecodeResult::Ok;
}

DecodeResult decodeBye(const uint8_t *p, size_t length, TsbBye &out) {
  if (p == nullptr)           return DecodeResult::ShortPayload;
  if (length < LEN_BYE) return DecodeResult::ShortPayload;
  out.code          = rdU16(p + 0);
  out.detail        = rdU16(p + 2);
  out.retry_after_s = rdU16(p + 4);
  out.reserved0     = rdU16(p + 6);
  copyWireString(out.reason, p + 8, sizeof(out.reason));
  return DecodeResult::Ok;
}

DecodeResult decodeToken(const uint8_t *p, size_t length, TsbToken &out) {
  if (p == nullptr)             return DecodeResult::ShortPayload;
  if (length < LEN_TOKEN) return DecodeResult::ShortPayload;
  out.value = rdU32(p + 0);
  return DecodeResult::Ok;
}

DecodeResult decodeInboundPayload(const TsbHeader &header, const uint8_t *payload,
                                  size_t length, InboundFrame &out) {
  out.header = header;
  if (typeIsOutbound(header.type)) return DecodeResult::WrongDirection;
  switch (header.type) {
    case T_WELCOME:    return decodeWelcome(payload, length, out.as.welcome);
    case T_EVENT:      return decodeEvent  (payload, length, out.as.event);
    case T_STATS:      return decodeStats  (payload, length, out.as.stats);
    case T_BYE:        return decodeBye    (payload, length, out.as.bye);
    case T_PING_RELAY:
    case T_PONG_RELAY: return decodeToken  (payload, length, out.as.token);
    default:           return DecodeResult::UnknownType;
  }
}

DecodeResult decodeInbound(const uint8_t *frame, size_t length, InboundFrame &out) {
  TsbHeader h;
  const DecodeResult hr = decodeHeader(frame, length, h);
  if (hr != DecodeResult::Ok) return hr;
  if (length < HEADER_SIZE + (size_t)h.length) return DecodeResult::Truncated;
  return decodeInboundPayload(h, frame + HEADER_SIZE, h.length, out);
}

// ---------------------------------------------------------------------------
// Encoders — device to relay
// ---------------------------------------------------------------------------

EncodeResult encodeFrame(uint8_t *out, size_t capacity, uint8_t type, uint8_t flags,
                         const uint8_t *payload, uint16_t length) {
  if (out == nullptr)                        return {0, EncodeError::Argument};
  if (type == 0x00)                    return {0, EncodeError::Argument};
  if (length > MAX_PAYLOAD)            return {0, EncodeError::Argument};
  if (length != 0 && payload == nullptr)     return {0, EncodeError::Argument};
  const size_t total = HEADER_SIZE + (size_t)length;
  if (capacity < total)                return {0, EncodeError::Capacity};

  out[0] = MAGIC0;
  out[1] = MAGIC1;
  out[2] = VERSION;
  out[3] = type;
  wrU16(out + 4, length);
  out[6] = flags;
  out[7] = headerCheck(out);
  if (length != 0) memcpy(out + HEADER_SIZE, payload, length);
  return {static_cast<uint16_t>(total), EncodeError::None};
}

EncodeResult encodeHello(uint8_t *out, size_t capacity, const TsbHello &h) {
  std::array<uint8_t, LEN_HELLO> p = {};
  wrU32(p.data() + 0, h.last_seq);
  wrU32(p.data() + 4, h.caps);
  wrU16(p.data() + 8, h.rx_max);
  wrU16(p.data() + 10, h.reserved0);
  // The struct's own fields are already NUL-padded to their full width by
  // buildHello()/packWireString(); copy the whole field, then guarantee the
  // final byte of each is NUL on the wire (§9).
  memcpy(p.data() + 12, h.device_id,  sizeof(h.device_id));
  memcpy(p.data() + 44, h.fw_version, sizeof(h.fw_version));
  p[12 + sizeof(h.device_id) - 1]  = 0;
  p[44 + sizeof(h.fw_version) - 1] = 0;
  return encodeFrame(out, capacity, T_HELLO, 0, p.data(), LEN_HELLO);
}

static EncodeResult encodeU32Frame(uint8_t *out, size_t capacity, uint8_t type, uint32_t v) {
  std::array<uint8_t, LEN_TOKEN> p;
  wrU32(p.data(), v);
  return encodeFrame(out, capacity, type, 0, p.data(), LEN_TOKEN);
}

EncodeResult encodePing(uint8_t *out, size_t capacity, uint32_t token) {
  return encodeU32Frame(out, capacity, T_PING_DEVICE, token);
}

EncodeResult encodePong(uint8_t *out, size_t capacity, uint32_t token) {
  return encodeU32Frame(out, capacity, T_PONG_DEVICE, token);
}

EncodeResult encodeAck(uint8_t *out, size_t capacity, uint32_t seq) {
  return encodeU32Frame(out, capacity, T_ACK, seq);
}

void buildHello(TsbHello &hello, uint32_t lastSeq, uint32_t caps,
                const char *deviceId, const char *fwVersion) {
  hello.last_seq  = lastSeq;
  hello.caps      = caps;
  hello.rx_max    = MIN_RX_MAX;
  hello.reserved0 = 0;
  packWireString(hello.device_id,  sizeof(hello.device_id),  deviceId);
  packWireString(hello.fw_version, sizeof(hello.fw_version), fwVersion);
  for (char &ch : hello.device_id) {
    const auto byte = static_cast<uint8_t>(ch);
    if (byte && (byte < 0x20 || byte > 0x7e)) ch = '_';
  }
  for (char &ch : hello.fw_version) {
    const auto byte = static_cast<uint8_t>(ch);
    if (byte && (byte < 0x20 || byte > 0x7e)) ch = '_';
  }
}

// ---------------------------------------------------------------------------
// FrameReader — §4.1
// ---------------------------------------------------------------------------

void FrameReader::reset(bool alsoCounters) {
  state_ = Hunt;
  held_ = 0;
  have_ = 0;
  need_ = 0;
  skipRemaining_ = 0;
  header_.version = 0;
  header_.type = 0;
  header_.length = 0;
  header_.flags = 0;
  hdr_.fill(0);
  if (alsoCounters) {
    memset(&counters_, 0, sizeof(counters_));
  } else {
    resetBudget();
  }
}

void FrameReader::resetBudget() {
  counters_.rejectedCandidates = 0;
  counters_.discardedBytes = 0;
}

bool FrameReader::budgetBlown() const {
  return counters_.rejectedCandidates >= RESYNC_MAX_CANDIDATES
      || counters_.discardedBytes >= RESYNC_MAX_BYTES;
}

void FrameReader::discard(uint32_t n) {
  counters_.resyncEvents += n;
  counters_.discardedBytes += n;
  counters_.discardedBytesTotal += n;
}

// §4.4: an invalid candidate must NOT consume eight bytes. Shift by exactly one
// and rescan. Since a frame can only begin with MAGIC0, bytes that are not
// MAGIC0 are then dropped immediately rather than waiting for the window to
// refill — same outcome, same discard accounting, far fewer stalls.
void FrameReader::shiftWindow() {
  memmove(hdr_.data(), hdr_.data() + 1, HEADER_SIZE - 1);
  held_ = HEADER_SIZE - 1;
  discard(1);
  while (held_ > 0 && hdr_[0] != MAGIC0) {
    memmove(hdr_.data(), hdr_.data() + 1, (size_t)held_ - 1);
    --held_;
    discard(1);
  }
  if (held_ == 0) state_ = Hunt;
}

size_t FrameReader::feedHunt(uint8_t b) {
  if (b == MAGIC0) {
    hdr_[0] = b;
    held_ = 1;
    state_ = Header;
  } else {
    discard(1);
    if (budgetBlown()) state_ = Fatal;
  }
  return 1;
}

size_t FrameReader::feedHeader(const uint8_t *src, size_t avail) {
  const size_t want = HEADER_SIZE - (size_t)held_;
  const size_t take = avail < want ? avail : want;
  if (take == 0) return 0;               // need more bytes than we were given
  memcpy(hdr_.data() + held_, src, take);
  held_ = (uint16_t)(held_ + take);
  if (held_ == HEADER_SIZE) headerComplete();
  return take;
}

void FrameReader::headerComplete() {
  if (decodeHeader(hdr_.data(), HEADER_SIZE, header_) != DecodeResult::Ok) {
    if (hdr_[0] == MAGIC0) ++counters_.rejectedCandidates;
    shiftWindow();
    if (budgetBlown()) state_ = Fatal;
    return;
  }
  have_ = 0;                              // §4.1: MUST reset before BODY
  need_ = header_.length;
  if (need_ > (uint16_t)rx_.size()) {
    // Unreachable between two v3 peers (length <= 248 is a header validity
    // condition), kept so a future larger frame is skipped rather than
    // overrunning rx_.
    skipRemaining_ = need_;
    state_ = Skip;
  } else if (need_ == 0) {
    state_ = Ready;
  } else {
    state_ = Body;
  }
}

size_t FrameReader::feedBody(const uint8_t *src, size_t avail) {
  auto take = static_cast<size_t>(need_ - have_);
  if (take > avail) take = avail;
  memcpy(rx_.data() + have_, src, take);
  have_ = (uint16_t)(have_ + take);
  if (have_ == need_) state_ = Ready;
  return take;
}

size_t FrameReader::feedSkip(size_t avail) {
  auto take = static_cast<size_t>(skipRemaining_);
  if (take > avail) take = avail;
  skipRemaining_ -= (uint32_t)take;
  if (skipRemaining_ == 0) {
    ++counters_.framesOversizeSkipped;
    resetBudget();                        // §4.5: a skipped frame was framed
    held_ = 0;
    state_ = Hunt;
  }
  return take;
}

size_t FrameReader::feed(const uint8_t *src, size_t n) {
  if (src == nullptr) return 0;
  size_t i = 0;
  while (i < n) {
    size_t used = 0;
    switch (state_) {
      case Hunt:   used = feedHunt(src[i]); break;
      case Header: used = feedHeader(src + i, n - i); break;
      case Body:   used = feedBody(src + i, n - i); break;
      case Skip:   used = feedSkip(n - i); break;
      default:     return i;              // Ready or Fatal
    }
    if (used == 0) return i;
    i += used;
  }
  return i;
}

void FrameReader::consumeFrame() {
  if (state_ != Ready) return;
  ++counters_.framesDecoded;
  counters_.bytesReceived += (uint32_t)HEADER_SIZE + (uint32_t)need_;
  resetBudget();                              // §4.5
  held_ = 0;
  have_ = 0;
  need_ = 0;
  state_ = Hunt;
}

void FrameReader::noteDecode(DecodeResult r) {
  switch (r) {
    case DecodeResult::UnknownType:    ++counters_.framesUnknownType;    break;
    case DecodeResult::WrongDirection: ++counters_.framesWrongDirection; break;
    case DecodeResult::ShortPayload:   ++counters_.framesShortPayload;   break;
    case DecodeResult::InvalidField:   ++counters_.framesInvalidField;   break;
    default: break;
  }
}

}  // namespace tsb
