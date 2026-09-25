#pragma once
//
// TSB/3 — Twitch Screen Binary Protocol, version 3.
// Device-side codec. Normative reference: docs/PROTOCOL.md.
//
// Design constraints, all load-bearing:
//   * no dynamic allocation anywhere, encode or decode;
//   * no exceptions, no RTTI, no <Arduino.h> (this header is freestanding C++11
//     so it also compiles on the host for the golden-vector test);
//   * every read is bounds-checked against the frame length BEFORE it happens;
//   * integers are assembled byte by byte — no pointer is ever cast into the
//     receive buffer (ESP32 Xtensa alignment + strict aliasing at -Os);
//   * strings are copied into fixed destinations and the last byte of the
//     receiver's own copy is always forced to NUL (§9.1);
//   * decoders return a result enum; there is no in-band sentinel that could be
//     mistaken for data.
//
// Everything multi-byte on this wire is LITTLE-ENDIAN (§2.1), in both
// directions, in headers and payloads alike. The encoders and decoders below
// serialise explicitly, so they are correct on a big-endian host too.
//
#include <stdint.h>
#include <stddef.h>

namespace tsb {

// ---------------------------------------------------------------------------
// §3, §5 — framing constants. Every one of these is a number in the spec.
// ---------------------------------------------------------------------------

static const uint8_t  MAGIC0          = 0xa7;
static const uint8_t  MAGIC1          = 0x53;
static const uint8_t  VERSION         = 3;
static const size_t   HEADER_SIZE     = 8;
static const uint16_t MAX_PAYLOAD     = 248;   // TSB3_MAX_PAYLOAD
static const uint16_t MAX_FRAME       = 256;   // TSB3_MAX_FRAME = 8 + 248
static const uint16_t MIN_RX_MAX      = 256;   // TSB3_MIN_RX_MAX

// §4.5 resync budget. Reaching either ends the session.
static const uint32_t RESYNC_MAX_CANDIDATES = 16;
static const uint32_t RESYNC_MAX_BYTES      = 4096;

// §3.2 header flags. Unknown bits MUST be ignored, never rejected.
static const uint8_t FLAG_REPLAY = 0x01;

// §6 message types, partitioned by direction.
enum MsgType : uint8_t {
  T_HELLO        = 0x01,  // device -> relay, 60 bytes
  T_PING_DEVICE  = 0x02,  // device -> relay, 4
  T_PONG_DEVICE  = 0x03,  // device -> relay, 4
  T_ACK          = 0x04,  // device -> relay, 4
  T_WELCOME      = 0x20,  // relay -> device, 24
  T_EVENT        = 0x21,  // relay -> device, 168
  T_STATS        = 0x22,  // relay -> device, 32
  T_PING_RELAY   = 0x23,  // relay -> device, 4
  T_PONG_RELAY   = 0x24,  // relay -> device, 4
  T_BYE          = 0x25   // relay -> device, 32, FROZEN SHAPE
};

// Base payload lengths (§6). `length < base` is a frame error; `length > base`
// decodes the first `base` bytes and ignores the tail (§16 rule 2).
static const uint16_t LEN_HELLO   = 60;
static const uint16_t LEN_WELCOME = 24;
static const uint16_t LEN_EVENT   = 168;
static const uint16_t LEN_STATS   = 32;
static const uint16_t LEN_TOKEN   = 4;   // PING / PONG / ACK
static const uint16_t LEN_BYE     = 32;

// §6.1 capability bits.
static const uint32_t CAP_ACK       = 0x00000001u;
static const uint32_t CAP_CHAT      = 0x00000002u;
static const uint32_t CAP_GENERIC   = 0x00000004u;
static const uint32_t CAP_UTF8_TEXT = 0x00000008u;

// §6.4 eflags bits. Bit 2 (0x04) and bits 5-7 (0xe0) are reserved: senders MUST
// set them to 0 and receivers MUST ignore them, so they have no name here.
static const uint8_t EF_TEXT_TRUNCATED      = 0x01;
static const uint8_t EF_ACTOR_TRUNCATED     = 0x02;
static const uint8_t EF_ANONYMOUS           = 0x08;
static const uint8_t EF_CHAT_COLOUR_PRESENT = 0x10;

// §6.4.1 event kinds.
enum EventKind : uint8_t {
  K_INFO         = 0x00,
  K_MESSAGE      = 0x01,
  K_WARNING      = 0x02,
  K_ALERT        = 0x03,
  K_STREAM_START = 0x10,
  K_STREAM_END   = 0x11,
  K_FOLLOW       = 0x12,
  K_SUB          = 0x13,
  K_GIFT         = 0x14,
  K_RAID         = 0x15,
  K_CHAT         = 0x16,
  K_BITS         = 0x17
  // 0x18 is permanently reserved (§6.4.1, §8): a v3 relay MUST NOT send it, and
  // a v3 device that somehow sees it applies the unknown-kind rule of §6.4.2
  // like any other unrecognised code. It is deliberately not named.
};

// §6.4 tier. 0 means NOT APPLICABLE, not Prime: a failed tier read must never
// encode a gifted Prime sub, which cannot exist.
enum SubTier : uint8_t {
  TIER_NONE  = 0,
  TIER_PRIME = 1,
  TIER_1     = 2,
  TIER_2     = 3,
  TIER_3     = 4
};
static const uint8_t TIER_MAX = 4;

// §6.7 BYE reason codes.
enum ByeCode : uint16_t {
  BYE_UNSUPPORTED_VERSION = 1,
  BYE_BAD_HANDSHAKE       = 2,
  BYE_INVALID_DEVICE_ID   = 3,
  BYE_INVALID_SEQUENCE    = 4,
  BYE_FRAMING_VIOLATION   = 5,
  BYE_FRAME_TOO_LARGE     = 6,
  BYE_DUPLICATE_HELLO     = 7,
  BYE_SERVER_SHUTDOWN     = 8,
  BYE_REPLACED            = 9,
  BYE_RATE_LIMIT          = 10,
  BYE_INVALID_PARAMETER   = 11,
  BYE_HANDSHAKE_TIMEOUT   = 12
};

// §6.4 ttl_ds — values above 6000 (10 min) SHOULD be clamped by the receiver.
static const uint16_t TTL_DS_MAX = 6000;

// ---------------------------------------------------------------------------
// Wire records (§15.1). Declared in wire order and asserted at build time.
// These are POD: no constructors, no default member initialisers, so they stay
// trivially copyable and `offsetof` stays well-defined.
// ---------------------------------------------------------------------------

struct TsbHeader {
  uint8_t  version;
  uint8_t  type;
  uint16_t length;   // payload bytes, excluding the 8-byte header
  uint8_t  flags;
};

struct TsbHello {            // == HELLO payload, 60 bytes
  uint32_t last_seq;         // +0
  uint32_t caps;             // +4
  uint16_t rx_max;           // +8
  uint16_t reserved0;        // +10
  char     device_id[32];    // +12
  char     fw_version[16];   // +44
};

struct TsbWelcome {          // == WELCOME payload, 24 bytes
  uint32_t latest_seq;       // +0
  uint32_t server_time;      // +4
  uint32_t session_id;       // +8
  uint16_t max_frame;        // +12
  uint16_t ping_interval_s;  // +14
  uint16_t idle_timeout_s;   // +16
  uint16_t replay_window;    // +18
  uint32_t caps;             // +20
};

// §6.4. `reserved1` (+12..+15) and `reserved2` (+23) are RESERVED, not fields:
// a sender MUST write zeros and a receiver MUST IGNORE their content entirely.
// They are held open (§8.1) for a future monetary amount, so this decoder copies
// them through byte for byte and neither validates nor rewrites them. Blanking
// bytes that a later version is entitled to use would defeat the whole point of
// holding them open: a v4 EVENT would arrive at a v3 device with its new field
// already erased.
struct TsbEvent {            // == EVENT payload, 168 bytes
  uint32_t seq;              // +0
  uint32_t ts;               // +4
  uint32_t value;            // +8
  uint8_t  reserved1[4];     // +12  §8.1 — carried verbatim, never interpreted
  uint16_t months;           // +16
  uint16_t ttl_ds;           // +18
  uint8_t  kind;             // +20
  uint8_t  tier;             // +21
  uint8_t  eflags;           // +22
  uint8_t  reserved2;        // +23  §8.1 — carried verbatim, never interpreted
  char     actor[48];        // +24
  char     text[96];         // +72
};

struct TsbStats {            // == STATS payload, 32 bytes
  uint32_t viewers;          // +0
  uint32_t msg_total;        // +4
  uint32_t uptime_s;         // +8
  uint32_t followers;        // +12
  uint32_t subs;             // +16
  uint32_t server_time;      // +20
  uint32_t stream_started_at;// +24
  uint16_t chat_rate;        // +28
  uint8_t  live;             // +30
  uint8_t  sflags;           // +31
};

struct TsbBye {              // == BYE payload, 32 bytes — FROZEN SHAPE
  uint16_t code;             // +0
  uint16_t detail;           // +2
  uint16_t retry_after_s;    // +4
  uint16_t reserved0;        // +6
  char     reason[24];       // +8
};

struct TsbToken {            // == PING / PONG / ACK payload, 4 bytes
  uint32_t value;            // +0  (token, or seq for ACK)
};

static_assert(sizeof(TsbHello) == 60, "TsbHello wire size");
static_assert(offsetof(TsbHello, device_id)  == 12, "TsbHello.device_id offset");
static_assert(offsetof(TsbHello, fw_version) == 44, "TsbHello.fw_version offset");

static_assert(sizeof(TsbWelcome) == 24, "TsbWelcome wire size");
static_assert(offsetof(TsbWelcome, max_frame) == 12, "TsbWelcome.max_frame offset");
static_assert(offsetof(TsbWelcome, caps)      == 20, "TsbWelcome.caps offset");

static_assert(sizeof(TsbEvent) == 168, "TsbEvent wire size");
static_assert(offsetof(TsbEvent, value)     ==  8, "TsbEvent.value offset");
static_assert(offsetof(TsbEvent, reserved1) == 12, "TsbEvent.reserved1 offset");
static_assert(offsetof(TsbEvent, months)    == 16, "TsbEvent.months offset");
static_assert(offsetof(TsbEvent, ttl_ds)    == 18, "TsbEvent.ttl_ds offset");
static_assert(offsetof(TsbEvent, kind)      == 20, "TsbEvent.kind offset");
static_assert(offsetof(TsbEvent, tier)      == 21, "TsbEvent.tier offset");
static_assert(offsetof(TsbEvent, eflags)    == 22, "TsbEvent.eflags offset");
static_assert(offsetof(TsbEvent, reserved2) == 23, "TsbEvent.reserved2 offset");
static_assert(offsetof(TsbEvent, actor)     == 24, "TsbEvent.actor offset");
static_assert(offsetof(TsbEvent, text)      == 72, "TsbEvent.text offset");

static_assert(sizeof(TsbStats) == 32, "TsbStats wire size");
static_assert(offsetof(TsbStats, stream_started_at) == 24, "TsbStats.stream_started_at offset");
static_assert(offsetof(TsbStats, chat_rate)         == 28, "TsbStats.chat_rate offset");
static_assert(offsetof(TsbStats, live)              == 30, "TsbStats.live offset");

static_assert(sizeof(TsbBye) == 32, "TsbBye wire size");
static_assert(offsetof(TsbBye, reason) == 8, "TsbBye.reason offset");

// ---------------------------------------------------------------------------
// Results
// ---------------------------------------------------------------------------

// Decode outcome. `Ok` is the only value on which the out-parameter is defined.
enum class DecodeResult : uint8_t {
  Ok = 0,
  Truncated,       // the supplied buffer is shorter than 8 + header.length
  BadMagic,        // §4.2
  HeaderCheckFail, // §4.2
  IllegalType,     // §4.2 — type == 0x00
  LengthOutOfRange,// §4.2 — length > 248
  UnknownType,     // §4.3 — well framed, this receiver has no decoder
  WrongDirection,  // §4.3 — a device-to-relay type arrived at the device
  ShortPayload,    // §4.3 — length < the type's base length
  InvalidField     // §4.3 — e.g. EVENT.seq == 0
};

// Encode outcome. Non-negative = bytes written; negative = failure.
typedef int32_t EncodeResult;
static const EncodeResult ENC_ERR_CAPACITY = -1;  // destination buffer too small
static const EncodeResult ENC_ERR_ARG      = -2;  // null pointer or illegal argument

const char *decodeResultName(DecodeResult r);

// ---------------------------------------------------------------------------
// §3.1 header check — positionally weighted on purpose. A plain additive sum
// collides on exactly the one-byte stream shift this exists to catch.
// ---------------------------------------------------------------------------
//   sum  = (1*b0 + 2*b1 + 3*b2 + 4*b3 + 5*b4 + 6*b5 + 7*b6) mod 256
//   hchk = 0xFF XOR sum
uint8_t headerCheck(const uint8_t *hdr);

// §4.2 — magic, hchk, type != 0, length <= 248. `version` is deliberately NOT
// validated here: an unknown version is a valid header with a version problem,
// which is what lets two peers of different versions exchange a BYE.
bool headerIsValid(const uint8_t *hdr);

// True when `type` is in the relay -> device range, i.e. legal inbound at the
// device. 0x01..0x1f arriving at the device is WrongDirection, not corruption.
bool typeIsInbound(uint8_t type);
bool typeIsOutbound(uint8_t type);

// Base payload length for a known type, or 0xffff when the type is unknown.
uint16_t baseLengthFor(uint8_t type);

// ---------------------------------------------------------------------------
// Strings
// ---------------------------------------------------------------------------

// §9.1 receiver rule: copy `width` bytes of a fixed-width wire field into a
// `width`-sized destination and force the destination's last byte to NUL. The
// sender is never trusted for termination.
void copyWireString(char *dst, const uint8_t *src, size_t width);

// §9.2 sender truncation, exactly as specified:
//   cap = width - 1; if the value fits, emit it; otherwise cut = cap - 3, walk
//   back while (b[cut] & 0xc0) == 0x80, emit b[0..cut) + "...".
// The field is always fully NUL-padded. Returns true when truncation happened,
// so the caller can set TEXT_TRUNCATED / ACTOR_TRUNCATED.
bool packWireString(char *field, size_t width, const char *src);

// ---------------------------------------------------------------------------
// Decoders. Each takes the PAYLOAD pointer and the payload length as delivered
// by FrameReader, bounds-checks before every read, and fills `out` only on Ok.
// `length > base` is accepted: the first `base` bytes decode, the tail is
// ignored (§16 rule 2).
// ---------------------------------------------------------------------------

DecodeResult decodeHello  (const uint8_t *payload, size_t length, TsbHello   &out);
DecodeResult decodeWelcome(const uint8_t *payload, size_t length, TsbWelcome &out);
DecodeResult decodeEvent  (const uint8_t *payload, size_t length, TsbEvent   &out);
DecodeResult decodeStats  (const uint8_t *payload, size_t length, TsbStats   &out);
DecodeResult decodeBye    (const uint8_t *payload, size_t length, TsbBye     &out);
DecodeResult decodeToken  (const uint8_t *payload, size_t length, TsbToken   &out);

// Reads and validates an 8-byte header from the front of a complete or partial
// frame. Does not require the payload to be present.
DecodeResult decodeHeader(const uint8_t *frame, size_t length, TsbHeader &out);

// One decoded inbound frame: header plus whichever payload the type selects.
// The union keeps this at 176 bytes rather than 268.
struct InboundFrame {
  TsbHeader header;
  union {
    TsbWelcome welcome;
    TsbEvent   event;
    TsbStats   stats;
    TsbBye     bye;
    TsbToken   token;   // PING 0x23 / PONG 0x24
  } as;
};

// Whole-frame convenience for the device: validates the header, checks the
// direction, then dispatches to the payload decoder. Returns UnknownType /
// WrongDirection / ShortPayload / InvalidField for the §4.3 cases, all of which
// mean "skip this frame and keep the link".
//
// NOTE: version is NOT checked here. §7 makes that a session-layer decision, so
// the caller inspects `out.header.version` itself — a BYE must stay readable
// from a peer whose version the device does not speak.
DecodeResult decodeInbound(const uint8_t *frame, size_t length, InboundFrame &out);

// Same, given the header and payload separately (what FrameReader hands out).
DecodeResult decodeInboundPayload(const TsbHeader &header, const uint8_t *payload,
                                  size_t length, InboundFrame &out);

// ---------------------------------------------------------------------------
// Encoders — device to relay only. Each writes a complete frame (header +
// payload) into the caller's buffer and returns the byte count, or a negative
// EncodeResult. Nothing allocates.
// ---------------------------------------------------------------------------

EncodeResult encodeHello(uint8_t *out, size_t capacity, const TsbHello &hello);
EncodeResult encodePing (uint8_t *out, size_t capacity, uint32_t token);
EncodeResult encodePong (uint8_t *out, size_t capacity, uint32_t token);
EncodeResult encodeAck  (uint8_t *out, size_t capacity, uint32_t seq);

// Escape hatch used by the four above and by tests: writes an arbitrary frame.
// `payload` may be null only when `length` is 0.
EncodeResult encodeFrame(uint8_t *out, size_t capacity, uint8_t type, uint8_t flags,
                         const uint8_t *payload, uint16_t length);

// Fills `hello` from the device identity, applying §9.2 truncation to the two
// string fields and setting rx_max = 256, reserved0 = 0.
void buildHello(TsbHello &hello, uint32_t lastSeq, uint32_t caps,
                const char *deviceId, const char *fwVersion);

// ---------------------------------------------------------------------------
// §14 counters
// ---------------------------------------------------------------------------

struct Counters {
  // Lifetime totals.
  uint32_t framesDecoded;         // complete frames consumed, including payload skips
  uint32_t bytesReceived;         // 8 + length per delivered frame
  uint32_t bytesSent;             // 8 + length per frame written (noteSent)
  uint32_t framesDropped;         // outbound frames that could not be written
  uint32_t resyncEvents;          // discarded octets during header resynchronisation
  uint32_t discardedBytesTotal;
  uint32_t framesOversizeSkipped;
  uint32_t framesUnknownType;
  uint32_t framesWrongDirection;
  uint32_t framesShortPayload;
  uint32_t framesInvalidField;
  // §4.5 budget counters. Both reset on every delivered frame and on every
  // oversized frame deliberately skipped.
  uint32_t rejectedCandidates;
  uint32_t discardedBytes;
};

// ---------------------------------------------------------------------------
// FrameReader — §4.1, the resumable HUNT / HEADER / BODY / SKIP state machine.
//
// Feed it whatever bytes the socket had; it never blocks, never allocates and
// keeps its state across calls, so a frame split over several TCP segments (or
// several linkLoop() iterations) resumes exactly where it stopped. A truncated
// frame simply leaves the reader waiting for more bytes — it cannot wedge, and
// it holds no timer of its own.
//
// Usage from link_client's read loop:
//
//     uint8_t chunk[256];
//     int n = client.read(chunk, sizeof(chunk));      // short counts are normal
//     size_t off = 0;
//     while (off < (size_t)n && !reader.isFatal()) {
//       off += reader.feed(chunk + off, (size_t)n - off);
//       if (reader.hasFrame()) {
//         tsb::InboundFrame f;
//         tsb::DecodeResult r = tsb::decodeInboundPayload(
//             reader.header(), reader.payload(), reader.payloadLength(), f);
//         reader.noteDecode(r);        // §4.3 counters
//         if (r == tsb::DecodeResult::Ok) dispatch(f);
//         reader.consumeFrame();
//       }
//     }
//     if (reader.isFatal()) { /* §4.5: close, log, back off */ }
// ---------------------------------------------------------------------------

class FrameReader {
 public:
  FrameReader() { reset(); }

  // Drops all partial state and clears the budget counters. Lifetime counters
  // survive unless `alsoCounters` is true. Call on every fresh connection.
  void reset(bool alsoCounters = true);

  // Consumes bytes from `src` and stops as soon as a complete frame is ready or
  // the resync budget is blown. Returns the number of bytes consumed, which is
  // 0 only when n == 0 or a frame is already pending collection.
  size_t feed(const uint8_t *src, size_t n);

  bool hasFrame() const { return state_ == Ready; }
  bool isFatal()  const { return state_ == Fatal; }

  // Valid only while hasFrame().
  const TsbHeader &header()       const { return header_; }
  const uint8_t   *payload()      const { return rx_; }
  uint16_t         payloadLength()const { return need_; }

  // Releases the pending frame and returns to HUNT. Resets the resync budget:
  // a frame that decoded, or that was skipped under §4.3, proves the framing
  // invariant still holds.
  void consumeFrame();

  // Folds a §4.3 outcome into the counters. Safe to call with Ok.
  void noteDecode(DecodeResult r);

  // §14 outbound side. The reader owns the one Counters block so a single
  // struct describes the link; the writer reports into it.
  void noteSent(uint32_t frameBytes) { counters_.bytesSent += frameBytes; }
  void noteDropped() { ++counters_.framesDropped; }

  const Counters &counters() const { return counters_; }

 private:
  enum State : uint8_t { Hunt, Header, Body, Skip, Ready, Fatal };

  void discard(uint32_t n);
  bool budgetBlown() const;
  void shiftWindow();
  void resetBudget();

  State     state_;
  uint8_t   hdr_[HEADER_SIZE];
  uint16_t  held_;                        // bytes held in hdr_
  alignas(4) uint8_t rx_[MAX_PAYLOAD];    // payload
  uint16_t  have_, need_;                 // bytes held in / required for rx_
  uint32_t  skipRemaining_;
  TsbHeader header_;
  Counters  counters_;
};

}  // namespace tsb
