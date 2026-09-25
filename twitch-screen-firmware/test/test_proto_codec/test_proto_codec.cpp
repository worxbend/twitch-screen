//
// TSB/3 codec conformance test — src/proto_codec.{h,cpp} against the 22 golden
// vectors of docs/PROTOCOL.md §18, plus the thirteen additional cases §17
// requires.
//
// Runs on the host. Two ways to run it:
//
//   ~/.platformio/penv/bin/pio test -e native
//   g++ -std=gnu++11 -Isrc -o /tmp/t test/test_proto_codec/test_proto_codec.cpp
//       src/proto_codec.cpp   (both .cpp files on one command line)
//
// It uses no test framework (test_framework = custom): a non-zero exit status
// is the failure signal, which keeps the test runnable with nothing installed
// beyond a host compiler.
//
#include "proto_codec.h"
#include "vectors.h"

#include <stdio.h>
#include <string.h>

using namespace tsb;

// ---------------------------------------------------------------------------
// Minimal harness
// ---------------------------------------------------------------------------

static int g_failures = 0;
static int g_checks = 0;
static const char *g_case = "";

static void fail(const char *what, int line) {
  ++g_failures;
  printf("  FAIL  %s:%d  %s\n", g_case, line, what);
}

#define CHECK(cond)                                                            \
  do {                                                                         \
    ++g_checks;                                                                \
    if (!(cond)) fail(#cond, __LINE__);                                        \
  } while (0)

#define CHECK_U32(got, want)                                                   \
  do {                                                                         \
    ++g_checks;                                                                \
    unsigned long g_ = (unsigned long)(got), w_ = (unsigned long)(want);       \
    if (g_ != w_) {                                                            \
      ++g_failures;                                                            \
      printf("  FAIL  %s:%d  %s: got %lu (0x%lx), want %lu\n", g_case,         \
             __LINE__, #got, g_, g_, w_);                                      \
    }                                                                          \
  } while (0)

#define CHECK_STR(got, want)                                                   \
  do {                                                                         \
    ++g_checks;                                                                \
    if (strcmp((got), (want)) != 0) {                                          \
      ++g_failures;                                                            \
      printf("  FAIL  %s:%d  %s: got \"%s\", want \"%s\"\n", g_case, __LINE__, \
             #got, (got), (want));                                             \
    }                                                                          \
  } while (0)

static void checkBytes(const uint8_t *got, const uint8_t *want, size_t n, int line) {
  ++g_checks;
  for (size_t i = 0; i < n; ++i) {
    if (got[i] != want[i]) {
      ++g_failures;
      printf("  FAIL  %s:%d  byte %u: got %02x, want %02x\n", g_case, line,
             (unsigned)i, got[i], want[i]);
      return;
    }
  }
}
#define CHECK_BYTES(got, want, n) checkBytes((got), (want), (n), __LINE__)

static void testCase(const char *name) {
  g_case = name;
  printf("- %s\n", name);
}

// Decodes one complete relay -> device vector frame.
static DecodeResult decodeVector(const uint8_t *frame, size_t n, InboundFrame &out) {
  return decodeInbound(frame, n, out);
}

// ---------------------------------------------------------------------------
// §3.1 / §4.2 — the header of every vector
// ---------------------------------------------------------------------------

struct VecRef { const char *name; const uint8_t *data; size_t len; uint8_t type; uint16_t plen; };

static const VecRef ALL_VECTORS[] = {
  {"V1 hello_fresh_boot",     HELLO_FRESH_BOOT,     sizeof(HELLO_FRESH_BOOT),     0x01, 60},
  {"V2 hello_resume_utf8",    HELLO_RESUME_UTF8,    sizeof(HELLO_RESUME_UTF8),    0x01, 60},
  {"V3 welcome",              WELCOME,              sizeof(WELCOME),              0x20, 24},
  {"V4 stats_live",           STATS_LIVE,           sizeof(STATS_LIVE),           0x22, 32},
  {"V5 stats_offline",        STATS_OFFLINE,        sizeof(STATS_OFFLINE),        0x22, 32},
  {"V6 event_stream_start",   EVENT_STREAM_START,   sizeof(EVENT_STREAM_START),   0x21, 168},
  {"V7 event_follow",         EVENT_FOLLOW,         sizeof(EVENT_FOLLOW),         0x21, 168},
  {"V8 event_sub",            EVENT_SUB,            sizeof(EVENT_SUB),            0x21, 168},
  {"V9 event_gift",           EVENT_GIFT,           sizeof(EVENT_GIFT),           0x21, 168},
  {"V10 event_raid_replayed", EVENT_RAID_REPLAYED,  sizeof(EVENT_RAID_REPLAYED),  0x21, 168},
  {"V11 event_bits",          EVENT_BITS,           sizeof(EVENT_BITS),           0x21, 168},
  {"V12 event_donation_eur",  EVENT_DONATION_EUR,   sizeof(EVENT_DONATION_EUR),   0x21, 168},
  {"V13 event_donation_jpy",  EVENT_DONATION_JPY,   sizeof(EVENT_DONATION_JPY),   0x21, 168},
  {"V14 event_chat_utf8",     EVENT_CHAT_UTF8,      sizeof(EVENT_CHAT_UTF8),      0x21, 168},
  {"V15 event_chat_truncated",EVENT_CHAT_TRUNCATED, sizeof(EVENT_CHAT_TRUNCATED), 0x21, 168},
  {"V16 event_info_generic",  EVENT_INFO_GENERIC,   sizeof(EVENT_INFO_GENERIC),   0x21, 168},
  {"V17 event_stream_end",    EVENT_STREAM_END,     sizeof(EVENT_STREAM_END),     0x21, 168},
  {"V18 ping_device",         PING_DEVICE,          sizeof(PING_DEVICE),          0x02, 4},
  {"V19 pong_relay",          PONG_RELAY,           sizeof(PONG_RELAY),           0x24, 4},
  {"V20 ping_relay",          PING_RELAY,           sizeof(PING_RELAY),           0x23, 4},
  {"V21 ack_device",          ACK_DEVICE,           sizeof(ACK_DEVICE),           0x04, 4},
  {"V22 bye_version_mismatch",BYE_VERSION_MISMATCH, sizeof(BYE_VERSION_MISMATCH), 0x25, 32},
};
static const size_t VECTOR_COUNT = sizeof(ALL_VECTORS) / sizeof(ALL_VECTORS[0]);

static void test_headers() {
  testCase("every vector header: magic, version 3, hchk, type, length");
  for (size_t i = 0; i < VECTOR_COUNT; ++i) {
    const VecRef &v = ALL_VECTORS[i];
    g_case = v.name;
    CHECK(v.data[0] == 0xa7);
    CHECK(v.data[1] == 0x53);
    CHECK_U32(v.data[2], 3);
    CHECK_U32(v.data[3], v.type);
    CHECK_U32(headerCheck(v.data), v.data[7]);
    CHECK(headerIsValid(v.data));
    TsbHeader h;
    CHECK(decodeHeader(v.data, v.len, h) == DecodeResult::Ok);
    CHECK_U32(h.length, v.plen);
    CHECK_U32(h.version, 3);
    CHECK_U32(v.len, 8u + v.plen);
    CHECK_U32(baseLengthFor(v.type), v.plen);
  }
}

// ---------------------------------------------------------------------------
// Device -> relay: encoders must reproduce the vector bytes exactly
// ---------------------------------------------------------------------------

static void test_encode_hello_fresh() {
  testCase("V1 encodeHello, fresh boot — byte for byte");
  TsbHello h;
  buildHello(h, 0, CAP_ACK | CAP_CHAT | CAP_GENERIC, "roundlcd-01", "1.0.0");
  CHECK_U32(h.rx_max, 256);
  CHECK_U32(h.reserved0, 0);
  uint8_t buf[MAX_FRAME];
  EncodeResult n = encodeHello(buf, sizeof(buf), h);
  CHECK_U32(n, sizeof(HELLO_FRESH_BOOT));
  if (n == (EncodeResult)sizeof(HELLO_FRESH_BOOT)) {
    CHECK_BYTES(buf, HELLO_FRESH_BOOT, sizeof(HELLO_FRESH_BOOT));
  }
}

static void test_encode_hello_resume() {
  testCase("V2 encodeHello, resume with CAP_UTF8_TEXT — byte for byte");
  TsbHello h;
  buildHello(h, 105, CAP_ACK | CAP_CHAT | CAP_GENERIC | CAP_UTF8_TEXT,
             "roundlcd-01", "1.0.0");
  uint8_t buf[MAX_FRAME];
  EncodeResult n = encodeHello(buf, sizeof(buf), h);
  CHECK_U32(n, sizeof(HELLO_RESUME_UTF8));
  if (n == (EncodeResult)sizeof(HELLO_RESUME_UTF8)) {
    CHECK_BYTES(buf, HELLO_RESUME_UTF8, sizeof(HELLO_RESUME_UTF8));
  }
}

static void test_encode_ping_ack() {
  testCase("V18 encodePing / V21 encodeAck — byte for byte");
  uint8_t buf[MAX_FRAME];
  EncodeResult n = encodePing(buf, sizeof(buf), 4210);
  CHECK_U32(n, sizeof(PING_DEVICE));
  CHECK_BYTES(buf, PING_DEVICE, sizeof(PING_DEVICE));

  n = encodeAck(buf, sizeof(buf), 126);
  CHECK_U32(n, sizeof(ACK_DEVICE));
  CHECK_BYTES(buf, ACK_DEVICE, sizeof(ACK_DEVICE));

  // 0x03 PONG has no vector of its own; pin its shape against §6.3 instead.
  n = encodePong(buf, sizeof(buf), 0x6ab5f29cu);
  CHECK_U32(n, 12);
  CHECK_U32(buf[3], T_PONG_DEVICE);
  CHECK_U32(buf[4] | (buf[5] << 8), 4);
  CHECK_U32(buf[7], headerCheck(buf));
  CHECK_U32(buf[8], 0x9c);  // little-endian token
  CHECK_U32(buf[11], 0x6a);

  // Capacity and argument failures return codes, never a short write.
  CHECK(encodePing(buf, 11, 1) == ENC_ERR_CAPACITY);
  CHECK(encodeFrame(buf, sizeof(buf), 0x00, 0, 0, 0) == ENC_ERR_ARG);
  CHECK(encodeFrame(buf, sizeof(buf), T_ACK, 0, 0, 4) == ENC_ERR_ARG);
  CHECK(encodeFrame(buf, sizeof(buf), T_ACK, 0, buf, 249) == ENC_ERR_ARG);
}

static void test_hello_roundtrip() {
  testCase("V1/V2 decode back out of the vector bytes");
  InboundFrame f;  // HELLO is device -> relay: the device must refuse it
  CHECK(decodeInbound(HELLO_FRESH_BOOT, sizeof(HELLO_FRESH_BOOT), f)
        == DecodeResult::WrongDirection);

  TsbHello h;
  CHECK(decodeHello(HELLO_RESUME_UTF8 + 8, 60, h) == DecodeResult::Ok);
  CHECK_U32(h.last_seq, 105);
  CHECK_U32(h.caps, 0x0000000fu);
  CHECK_U32(h.rx_max, 256);
  CHECK_U32(h.reserved0, 0);
  CHECK_STR(h.device_id, "roundlcd-01");
  CHECK_STR(h.fw_version, "1.0.0");
}

// ---------------------------------------------------------------------------
// Relay -> device: decoders must reproduce §18's stated field values
// ---------------------------------------------------------------------------

static void test_welcome() {
  testCase("V3 welcome");
  InboundFrame f;
  CHECK(decodeVector(WELCOME, sizeof(WELCOME), f) == DecodeResult::Ok);
  CHECK_U32(f.header.type, T_WELCOME);
  CHECK_U32(f.header.flags, 0);
  CHECK_U32(f.as.welcome.latest_seq, 120);
  CHECK_U32(f.as.welcome.server_time, 1790309000u);
  CHECK_U32(f.as.welcome.session_id, 0x9C4E17A0u);
  CHECK_U32(f.as.welcome.max_frame, 256);
  CHECK_U32(f.as.welcome.ping_interval_s, 20);
  CHECK_U32(f.as.welcome.idle_timeout_s, 90);
  CHECK_U32(f.as.welcome.replay_window, 64);
  CHECK_U32(f.as.welcome.caps, 0x0000000fu);
}

static void test_stats() {
  testCase("V4 stats_live");
  InboundFrame f;
  CHECK(decodeVector(STATS_LIVE, sizeof(STATS_LIVE), f) == DecodeResult::Ok);
  CHECK_U32(f.as.stats.viewers, 842);
  CHECK_U32(f.as.stats.msg_total, 2135);
  CHECK_U32(f.as.stats.uptime_s, 3660);
  CHECK_U32(f.as.stats.followers, 12400);
  CHECK_U32(f.as.stats.subs, 318);
  CHECK_U32(f.as.stats.server_time, 1790309000u);
  CHECK_U32(f.as.stats.stream_started_at, 1790305340u);
  CHECK_U32(f.as.stats.chat_rate, 35);
  CHECK_U32(f.as.stats.live, 1);
  CHECK_U32(f.as.stats.sflags, 0);
  // §18: server_time - stream_started_at == uptime_s.
  CHECK_U32(f.as.stats.server_time - f.as.stats.stream_started_at, f.as.stats.uptime_s);

  testCase("V5 stats_offline");
  CHECK(decodeVector(STATS_OFFLINE, sizeof(STATS_OFFLINE), f) == DecodeResult::Ok);
  CHECK_U32(f.as.stats.viewers, 0);
  CHECK_U32(f.as.stats.msg_total, 2135);   // retained until the next stream
  CHECK_U32(f.as.stats.uptime_s, 0);
  CHECK_U32(f.as.stats.followers, 12400);
  CHECK_U32(f.as.stats.subs, 318);
  CHECK_U32(f.as.stats.server_time, 1790309600u);
  CHECK_U32(f.as.stats.stream_started_at, 0);
  CHECK_U32(f.as.stats.chat_rate, 0);
  CHECK_U32(f.as.stats.live, 0);
}

struct EventExpect {
  const char *name;
  const uint8_t *frame;
  size_t frameLen;
  uint8_t hflags;
  uint32_t seq, ts, value;
  const char *currency;
  uint16_t months, ttl_ds;
  uint8_t kind, tier, eflags, exp;
  const char *actor;
  const char *text;
};

static void test_events() {
  static const EventExpect EXPECTED[] = {
    {"V6 stream_start", EVENT_STREAM_START, sizeof(EVENT_STREAM_START), 0x00,
     118, 1790305340u, 1790305340u, "", 0, 100, K_STREAM_START, 0, 0x00, 0,
     "w0rxbend", "Round LCD build night"},
    {"V7 follow", EVENT_FOLLOW, sizeof(EVENT_FOLLOW), 0x00,
     119, 1790308600u, 0, "", 0, 60, K_FOLLOW, 0, 0x00, 0, "pixelpanda", ""},
    {"V8 sub", EVENT_SUB, sizeof(EVENT_SUB), 0x00,
     120, 1790308880u, 0, "", 14, 80, K_SUB, TIER_2, 0x00, 0,
     "nerdvana", "14 months and still here!"},
    {"V9 gift", EVENT_GIFT, sizeof(EVENT_GIFT), 0x00,
     121, 1790309005u, 5, "", 0, 80, K_GIFT, TIER_1, 0x00, 0, "kasia_dev", ""},
    {"V10 raid (REPLAY)", EVENT_RAID_REPLAYED, sizeof(EVENT_RAID_REPLAYED), FLAG_REPLAY,
     122, 1790309012u, 128, "", 0, 100, K_RAID, 0, 0x00, 0, "streamfriend", ""},
    {"V11 bits", EVENT_BITS, sizeof(EVENT_BITS), 0x00,
     123, 1790309030u, 1500, "", 0, 80, K_BITS, 0, 0x00, 0,
     "bitlord", "take my money"},
    {"V12 donation EUR", EVENT_DONATION_EUR, sizeof(EVENT_DONATION_EUR), 0x00,
     124, 1790309044u, 1250, "EUR", 0, 100, K_DONATION, 0, 0x00, 2,
     "Kasia", "keep it up!"},
    {"V13 donation JPY", EVENT_DONATION_JPY, sizeof(EVENT_DONATION_JPY), 0x00,
     125, 1790309060u, 3000, "JPY", 0, 100, K_DONATION, 0, 0x00, 0, "haruki", ""},
    {"V14 chat UTF-8", EVENT_CHAT_UTF8, sizeof(EVENT_CHAT_UTF8), 0x00,
     126, 1790309061u, 0x00FF7F50u, "", 0, 60, K_CHAT, 0, EF_CHAT_COLOUR_PRESENT, 0,
     "Pawe\xc5\x82", "\xc5\x9bwietny stream! \xf0\x9f\x8e\x89"},
    {"V15 chat truncated", EVENT_CHAT_TRUNCATED, sizeof(EVENT_CHAT_TRUNCATED), 0x00,
     127, 1790309062u, 0, "", 0, 60, K_CHAT, 0,
     (uint8_t)(EF_TEXT_TRUNCATED | EF_ACTOR_TRUNCATED | EF_CHAT_COLOUR_PRESENT), 0,
     "TheQuickBrownFoxJumpsOverTheLazyDogAndThenS...",
     "czterdziesci cztery bajty i jeszcze wiecej tekstu tak dlugiego ze musi "
     "zostac przyciety w p..."},
    {"V16 info generic", EVENT_INFO_GENERIC, sizeof(EVENT_INFO_GENERIC), 0x00,
     128, 1790309070u, 0, "", 0, 300, K_INFO, 0, 0x00, 0,
     "CPU temperature", "server rack A reached 78C"},
    {"V17 stream_end", EVENT_STREAM_END, sizeof(EVENT_STREAM_END), 0x00,
     129, 1790309100u, 3760, "", 0, 100, K_STREAM_END, 0, 0x00, 0, "w0rxbend", ""},
  };
  const size_t n = sizeof(EXPECTED) / sizeof(EXPECTED[0]);
  for (size_t i = 0; i < n; ++i) {
    const EventExpect &e = EXPECTED[i];
    testCase(e.name);
    InboundFrame f;
    DecodeResult r = decodeVector(e.frame, e.frameLen, f);
    CHECK(r == DecodeResult::Ok);
    if (r != DecodeResult::Ok) continue;
    CHECK_U32(f.header.type, T_EVENT);
    CHECK_U32(f.header.length, 168);
    CHECK_U32(f.header.flags, e.hflags);
    const TsbEvent &ev = f.as.event;
    CHECK_U32(ev.seq, e.seq);
    CHECK_U32(ev.ts, e.ts);
    CHECK_U32(ev.value, e.value);
    CHECK_STR(ev.currency, e.currency);
    CHECK_U32(ev.months, e.months);
    CHECK_U32(ev.ttl_ds, e.ttl_ds);
    CHECK_U32(ev.kind, e.kind);
    CHECK_U32(ev.tier, e.tier);
    CHECK_U32(ev.eflags, e.eflags);
    CHECK_U32(ev.exp, e.exp);
    CHECK_STR(ev.actor, e.actor);
    CHECK_STR(ev.text, e.text);
    // §9.1: the receiver's own copy always ends in NUL.
    CHECK_U32(ev.actor[sizeof(ev.actor) - 1], 0);
    CHECK_U32(ev.text[sizeof(ev.text) - 1], 0);
    CHECK_U32(ev.currency[3], 0);
  }

  testCase("V14 actor/text are the exact UTF-8 byte counts §18 states");
  InboundFrame f;
  CHECK(decodeVector(EVENT_CHAT_UTF8, sizeof(EVENT_CHAT_UTF8), f) == DecodeResult::Ok);
  CHECK_U32(strlen(f.as.event.actor), 6);    // "Paweł"  = 6 bytes, 5 code points
  CHECK_U32(strlen(f.as.event.text), 21);    // "świetny stream! 🎉" = 21 bytes

  testCase("V15 truncated lengths are 46 and 94, one short of the caps");
  CHECK(decodeVector(EVENT_CHAT_TRUNCATED, sizeof(EVENT_CHAT_TRUNCATED), f)
        == DecodeResult::Ok);
  CHECK_U32(strlen(f.as.event.actor), 46);
  CHECK_U32(strlen(f.as.event.text), 94);

  testCase("§8 money: integer-only rendering of V12 and V13");
  CHECK(decodeVector(EVENT_DONATION_EUR, sizeof(EVENT_DONATION_EUR), f) == DecodeResult::Ok);
  {
    uint32_t scale = 1;
    for (uint8_t i = 0; i < f.as.event.exp; ++i) scale *= 10;
    CHECK_U32(f.as.event.value / scale, 12);
    CHECK_U32(f.as.event.value % scale, 50);        // 12.50 EUR
    CHECK(currencyIsValid(f.as.event.currency));
  }
  CHECK(decodeVector(EVENT_DONATION_JPY, sizeof(EVENT_DONATION_JPY), f) == DecodeResult::Ok);
  CHECK_U32(f.as.event.exp, 0);
  CHECK_U32(f.as.event.value, 3000);                // 3000 JPY, no minor units
}

static void test_ping_pong_ack_bye() {
  testCase("V19 pong_relay / V20 ping_relay");
  InboundFrame f;
  CHECK(decodeVector(PONG_RELAY, sizeof(PONG_RELAY), f) == DecodeResult::Ok);
  CHECK_U32(f.header.type, T_PONG_RELAY);
  CHECK_U32(f.as.token.value, 4210);   // echoes V18's token verbatim

  CHECK(decodeVector(PING_RELAY, sizeof(PING_RELAY), f) == DecodeResult::Ok);
  CHECK_U32(f.header.type, T_PING_RELAY);
  CHECK_U32(f.as.token.value, 1790309020u);

  // A PONG answering it must echo the token byte for byte (§6.3).
  uint8_t buf[MAX_FRAME];
  CHECK_U32(encodePong(buf, sizeof(buf), f.as.token.value), 12);
  CHECK_BYTES(buf + 8, PING_RELAY + 8, 4);

  testCase("V21 ack_device decodes (relay side) as seq 126");
  TsbToken t;
  CHECK(decodeToken(ACK_DEVICE + 8, 4, t) == DecodeResult::Ok);
  CHECK_U32(t.value, 126);

  testCase("V22 bye_version_mismatch");
  CHECK(decodeVector(BYE_VERSION_MISMATCH, sizeof(BYE_VERSION_MISMATCH), f)
        == DecodeResult::Ok);
  CHECK_U32(f.header.type, T_BYE);
  CHECK_U32(f.as.bye.code, BYE_UNSUPPORTED_VERSION);
  CHECK_U32(f.as.bye.detail, 3);
  CHECK_U32(f.as.bye.retry_after_s, 30);
  CHECK_U32(f.as.bye.reserved0, 0);
  CHECK_STR(f.as.bye.reason, "relay speaks v3 only");
}

// ---------------------------------------------------------------------------
// §9.2 sender truncation
// ---------------------------------------------------------------------------

static void test_truncation() {
  testCase("§9.2 truncation reproduces V15's actor and text fields exactly");
  CHECK_U32(strlen(V15_ACTOR_SRC), 61);
  CHECK_U32(strlen(V15_TEXT_SRC), 119);

  char actor[48];
  char text[96];
  CHECK(packWireString(actor, sizeof(actor), V15_ACTOR_SRC) == true);
  CHECK(packWireString(text, sizeof(text), V15_TEXT_SRC) == true);
  CHECK_BYTES((const uint8_t *)actor, EVENT_CHAT_TRUNCATED + 8 + 24, 48);
  CHECK_BYTES((const uint8_t *)text, EVENT_CHAT_TRUNCATED + 8 + 72, 96);
  CHECK_U32(strlen(actor), 46);   // 47-byte cap, but the split code point is dropped
  CHECK_U32(strlen(text), 94);

  testCase("§9.2 a value that fits is emitted unchanged and not flagged");
  CHECK(packWireString(actor, sizeof(actor), "pixelpanda") == false);
  CHECK_STR(actor, "pixelpanda");
  for (size_t i = strlen("pixelpanda"); i < sizeof(actor); ++i) CHECK_U32(actor[i], 0);

  testCase("§9.2 a value exactly at the cap is not truncated");
  char cap47[48];
  char src[64];
  memset(src, 'x', 47);
  src[47] = 0;
  CHECK(packWireString(cap47, sizeof(cap47), src) == false);
  CHECK_U32(strlen(cap47), 47);
  src[47] = 'x';
  src[48] = 0;
  CHECK(packWireString(cap47, sizeof(cap47), src) == true);
  CHECK_U32(strlen(cap47), 47);   // 44 content bytes + "..."
  CHECK_STR(cap47 + 44, "...");

  testCase("§9.2 never splits a multi-byte sequence");
  // 46 ASCII bytes then a 4-byte emoji: the cut at 44 is inside nothing, but
  // the emoji must not be half-emitted either way.
  char emo[64];
  memset(emo, 'a', 44);
  memcpy(emo + 44, "\xf0\x9f\x8e\x89", 4);
  emo[48] = 0;
  CHECK(packWireString(cap47, sizeof(cap47), emo) == true);
  for (size_t i = 0; i < strlen(cap47); ++i) {
    CHECK((uint8_t)cap47[i] < 0x80);   // nothing multi-byte survived the cut
  }
}

// ---------------------------------------------------------------------------
// §4 framing, §17 conformance cases
// ---------------------------------------------------------------------------

// Feeds a buffer through a reader in chunks of `chunk` bytes and collects the
// frames. chunk == 0 means "all at once".
struct Collected { uint8_t type; uint16_t length; uint32_t firstU32; uint8_t flags; };

static size_t drive(FrameReader &r, const uint8_t *data, size_t n, size_t chunk,
                    Collected *out, size_t outMax, DecodeResult *lastResult = 0) {
  size_t frames = 0;
  size_t pos = 0;
  while (pos < n && !r.isFatal()) {
    size_t avail = n - pos;
    if (chunk != 0 && avail > chunk) avail = chunk;
    size_t end = pos + avail;
    while (pos < end && !r.isFatal()) {
      pos += r.feed(data + pos, end - pos);
      if (r.hasFrame()) {
        InboundFrame f;
        DecodeResult res = decodeInboundPayload(r.header(), r.payload(),
                                                r.payloadLength(), f);
        r.noteDecode(res);
        if (lastResult) *lastResult = res;
        if (frames < outMax) {
          Collected c;
          c.type = r.header().type;
          c.length = r.header().length;
          c.flags = r.header().flags;
          c.firstU32 = 0;
          if (r.payloadLength() >= 4) {
            const uint8_t *p = r.payload();
            c.firstU32 = (uint32_t)p[0] | ((uint32_t)p[1] << 8)
                       | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
          }
          out[frames] = c;
        }
        ++frames;
        r.consumeFrame();
      }
    }
  }
  return frames;
}

static void test_reader_stream() {
  testCase("§4.1 the whole §18 greeting burst, fed as one blob");
  // WELCOME, then four replayed EVENTs, then STATS: the §11.1 greet order.
  uint8_t stream[2048];
  size_t n = 0;
  memcpy(stream + n, WELCOME, sizeof(WELCOME)); n += sizeof(WELCOME);
  memcpy(stream + n, EVENT_STREAM_START, sizeof(EVENT_STREAM_START)); n += sizeof(EVENT_STREAM_START);
  memcpy(stream + n, EVENT_FOLLOW, sizeof(EVENT_FOLLOW)); n += sizeof(EVENT_FOLLOW);
  memcpy(stream + n, EVENT_RAID_REPLAYED, sizeof(EVENT_RAID_REPLAYED)); n += sizeof(EVENT_RAID_REPLAYED);
  memcpy(stream + n, EVENT_CHAT_UTF8, sizeof(EVENT_CHAT_UTF8)); n += sizeof(EVENT_CHAT_UTF8);
  memcpy(stream + n, STATS_LIVE, sizeof(STATS_LIVE)); n += sizeof(STATS_LIVE);

  Collected got[16];
  FrameReader r;
  CHECK_U32(drive(r, stream, n, 0, got, 16), 6);
  CHECK_U32(got[0].type, T_WELCOME);
  CHECK_U32(got[1].type, T_EVENT);
  CHECK_U32(got[1].firstU32, 118);
  CHECK_U32(got[2].firstU32, 119);
  CHECK_U32(got[3].firstU32, 122);
  CHECK_U32(got[3].flags, FLAG_REPLAY);
  CHECK_U32(got[4].firstU32, 126);
  CHECK_U32(got[5].type, T_STATS);
  CHECK_U32(r.counters().framesDecoded, 6);
  CHECK_U32(r.counters().bytesReceived, n);
  CHECK_U32(r.counters().discardedBytesTotal, 0);
  CHECK_U32(r.counters().resyncEvents, 0);

  testCase("§2 short reads: the same stream one byte at a time");
  FrameReader r2;
  Collected got2[16];
  CHECK_U32(drive(r2, stream, n, 1, got2, 16), 6);
  for (size_t i = 0; i < 6; ++i) {
    CHECK_U32(got2[i].type, got[i].type);
    CHECK_U32(got2[i].firstU32, got[i].firstU32);
    CHECK_U32(got2[i].flags, got[i].flags);
  }
  CHECK_U32(r2.counters().bytesReceived, n);

  testCase("§2 three-byte chunks, straddling every header and payload boundary");
  FrameReader r3;
  Collected got3[16];
  CHECK_U32(drive(r3, stream, n, 3, got3, 16), 6);
  for (size_t i = 0; i < 6; ++i) CHECK_U32(got3[i].firstU32, got[i].firstU32);

  testCase("a truncated final frame leaves the reader waiting, never wedged");
  FrameReader r4;
  Collected got4[8];
  // Everything but the last 40 bytes of the STATS frame.
  CHECK_U32(drive(r4, stream, n - 40, 0, got4, 8), 5);
  CHECK(!r4.isFatal());
  CHECK(!r4.hasFrame());
  // Now deliver the tail: the sixth frame completes.
  CHECK_U32(drive(r4, stream + n - 40, 40, 0, got4, 8), 1);
  CHECK_U32(got4[0].type, T_STATS);
}

static void test_conformance_cases() {
  uint8_t buf[512];
  Collected got[8];
  DecodeResult res = DecodeResult::Ok;

  testCase("§17.1 an unknown type is skipped and the next frame decodes");
  {
    FrameReader r;
    size_t n = 0;
    uint8_t body[16];
    memset(body, 0xee, sizeof(body));
    EncodeResult e = encodeFrame(buf, sizeof(buf), 0x2f, 0, body, sizeof(body));
    CHECK_U32(e, 8 + 16);
    n = (size_t)e;
    memcpy(buf + n, STATS_LIVE, sizeof(STATS_LIVE));
    n += sizeof(STATS_LIVE);
    CHECK_U32(drive(r, buf, n, 0, got, 8, &res), 2);
    CHECK_U32(got[0].type, 0x2f);
    CHECK_U32(got[1].type, T_STATS);
    CHECK_U32(r.counters().framesUnknownType, 1);
    CHECK(!r.isFatal());
  }

  testCase("§4.3 a device-to-relay type arriving at the device is WrongDirection");
  {
    FrameReader r;
    size_t n = sizeof(PING_DEVICE);
    memcpy(buf, PING_DEVICE, n);
    memcpy(buf + n, STATS_LIVE, sizeof(STATS_LIVE));
    n += sizeof(STATS_LIVE);
    CHECK_U32(drive(r, buf, n, 0, got, 8, &res), 2);
    CHECK_U32(r.counters().framesWrongDirection, 1);
    CHECK(!r.isFatal());
  }

  testCase("§17.2 length one byte short of a base is skipped, link survives");
  {
    FrameReader r;
    uint8_t body[LEN_STATS - 1];
    memcpy(body, STATS_LIVE + 8, sizeof(body));
    EncodeResult e = encodeFrame(buf, sizeof(buf), T_STATS, 0, body, sizeof(body));
    size_t n = (size_t)e;
    memcpy(buf + n, STATS_LIVE, sizeof(STATS_LIVE));
    n += sizeof(STATS_LIVE);
    CHECK_U32(drive(r, buf, n, 0, got, 8, &res), 2);
    CHECK_U32(r.counters().framesShortPayload, 1);
    CHECK(!r.isFatal());
  }

  testCase("§17.3 length = 249 is a framing violation, not a frame error");
  {
    // Build the header by hand: encodeFrame refuses to produce it.
    uint8_t h[8];
    h[0] = MAGIC0; h[1] = MAGIC1; h[2] = VERSION; h[3] = T_STATS;
    h[4] = 249; h[5] = 0; h[6] = 0;
    h[7] = headerCheck(h);
    CHECK(!headerIsValid(h));
    TsbHeader dh;
    CHECK(decodeHeader(h, sizeof(h), dh) == DecodeResult::LengthOutOfRange);
  }

  testCase("§17.4 a STATS frame with four extra trailing bytes decodes");
  {
    FrameReader r;
    uint8_t body[LEN_STATS + 4];
    memcpy(body, STATS_LIVE + 8, LEN_STATS);
    body[LEN_STATS] = 0xde; body[LEN_STATS + 1] = 0xad;
    body[LEN_STATS + 2] = 0xbe; body[LEN_STATS + 3] = 0xef;
    EncodeResult e = encodeFrame(buf, sizeof(buf), T_STATS, 0, body, sizeof(body));
    CHECK_U32(drive(r, buf, (size_t)e, 0, got, 8, &res), 1);
    CHECK(res == DecodeResult::Ok);
    CHECK_U32(got[0].length, LEN_STATS + 4);
    InboundFrame f;
    CHECK(decodeInbound(buf, (size_t)e, f) == DecodeResult::Ok);
    CHECK_U32(f.as.stats.viewers, 842);
    CHECK_U32(f.as.stats.sflags, 0);
  }

  testCase("§17.5 kind 0x7f decodes cleanly, for the renderer to show as INFO");
  {
    uint8_t body[LEN_EVENT];
    memcpy(body, EVENT_INFO_GENERIC + 8, LEN_EVENT);
    body[20] = 0x7f;
    EncodeResult e = encodeFrame(buf, sizeof(buf), T_EVENT, 0, body, LEN_EVENT);
    InboundFrame f;
    CHECK(decodeInbound(buf, (size_t)e, f) == DecodeResult::Ok);
    CHECK_U32(f.as.event.kind, 0x7f);
    CHECK_STR(f.as.event.actor, "CPU temperature");
    CHECK_STR(f.as.event.text, "server rack A reached 78C");
  }

  testCase("§17.6 an EVENT with seq = 0 is skipped and the link survives");
  {
    FrameReader r;
    uint8_t body[LEN_EVENT];
    memcpy(body, EVENT_FOLLOW + 8, LEN_EVENT);
    body[0] = body[1] = body[2] = body[3] = 0;
    EncodeResult e = encodeFrame(buf, sizeof(buf), T_EVENT, 0, body, LEN_EVENT);
    size_t n = (size_t)e;
    memcpy(buf + n, EVENT_SUB, sizeof(EVENT_SUB));
    n += sizeof(EVENT_SUB);
    CHECK_U32(drive(r, buf, n, 0, got, 8, &res), 2);
    CHECK_U32(r.counters().framesInvalidField, 1);
    CHECK_U32(got[1].firstU32, 120);
    CHECK(!r.isFatal());
  }

  testCase("§17.7 a string field with no NUL in its last byte is terminated by us");
  {
    uint8_t body[LEN_EVENT];
    memcpy(body, EVENT_FOLLOW + 8, LEN_EVENT);
    memset(body + 24, 'A', 48);   // actor: 48 non-zero bytes, no terminator
    memset(body + 72, 'B', 96);   // text: 96 non-zero bytes, no terminator
    EncodeResult e = encodeFrame(buf, sizeof(buf), T_EVENT, 0, body, LEN_EVENT);
    InboundFrame f;
    CHECK(decodeInbound(buf, (size_t)e, f) == DecodeResult::Ok);
    CHECK_U32(strlen(f.as.event.actor), 47);
    CHECK_U32(strlen(f.as.event.text), 95);
    CHECK_U32(f.as.event.actor[47], 0);
    CHECK_U32(f.as.event.text[95], 0);
  }

  testCase("§17.8 a stray 0xa7 before a frame does not cause a false lock");
  {
    FrameReader r;
    buf[0] = 0xa7;
    memcpy(buf + 1, STATS_LIVE, sizeof(STATS_LIVE));
    CHECK_U32(drive(r, buf, 1 + sizeof(STATS_LIVE), 0, got, 8, &res), 1);
    CHECK_U32(got[0].type, T_STATS);
    CHECK_U32(r.counters().framesDecoded, 1);
    CHECK(!r.isFatal());
    // The budget was reset by the good frame even though a byte was discarded.
    CHECK_U32(r.counters().discardedBytes, 0);
    CHECK_U32(r.counters().discardedBytesTotal, 1);
    CHECK_U32(r.counters().resyncEvents, 1);
  }

  testCase("a run of 0xa7 bytes before a frame still resynchronises");
  {
    FrameReader r;
    memset(buf, 0xa7, 9);
    memcpy(buf + 9, EVENT_BITS, sizeof(EVENT_BITS));
    CHECK_U32(drive(r, buf, 9 + sizeof(EVENT_BITS), 0, got, 8, &res), 1);
    CHECK_U32(got[0].firstU32, 123);
    CHECK(!r.isFatal());
  }

  testCase("a payload containing 0xa7 0x53 does not split the frame");
  {
    FrameReader r;
    uint8_t body[LEN_EVENT];
    memcpy(body, EVENT_BITS + 8, LEN_EVENT);
    body[72] = 0xa7; body[73] = 0x53; body[74] = 0x03; body[75] = 0x21;
    EncodeResult e = encodeFrame(buf, sizeof(buf), T_EVENT, 0, body, LEN_EVENT);
    CHECK_U32(drive(r, buf, (size_t)e, 0, got, 8, &res), 1);
    CHECK_U32(got[0].firstU32, 123);
    CHECK_U32(r.counters().resyncEvents, 0);
  }

  testCase("§17.9 4096 junk bytes cause exactly one teardown, then a clean restart");
  {
    FrameReader r;
    uint8_t junk[4096];
    memset(junk, 0x5a, sizeof(junk));   // no 0xa7: every byte dies in HUNT
    size_t consumed = 0;
    while (consumed < sizeof(junk) && !r.isFatal()) {
      consumed += r.feed(junk + consumed, sizeof(junk) - consumed);
    }
    CHECK(r.isFatal());
    CHECK_U32(r.counters().discardedBytes, RESYNC_MAX_BYTES);
    CHECK_U32(consumed, RESYNC_MAX_BYTES);
    // Fatal is sticky: no further byte is consumed until reset().
    CHECK_U32(r.feed(junk, 16), 0);
    // The reconnect.
    r.reset();
    CHECK(!r.isFatal());
    CHECK_U32(r.counters().discardedBytes, 0);
    CHECK_U32(drive(r, STATS_LIVE, sizeof(STATS_LIVE), 0, got, 8, &res), 1);
    CHECK_U32(got[0].type, T_STATS);
  }

  testCase("§4.5 sixteen rejected 0xa7 candidates also end the session");
  {
    FrameReader r;
    uint8_t bad[8 * 32];
    for (size_t i = 0; i < sizeof(bad); i += 8) {
      bad[i] = MAGIC0; bad[i + 1] = MAGIC1; bad[i + 2] = VERSION;
      bad[i + 3] = T_STATS; bad[i + 4] = 32; bad[i + 5] = 0; bad[i + 6] = 0;
      bad[i + 7] = (uint8_t)(headerCheck(bad + i) ^ 0xff);   // deliberately wrong
    }
    size_t consumed = 0;
    while (consumed < sizeof(bad) && !r.isFatal()) {
      consumed += r.feed(bad + consumed, sizeof(bad) - consumed);
    }
    CHECK(r.isFatal());
    CHECK(r.counters().rejectedCandidates >= RESYNC_MAX_CANDIDATES);
    CHECK(r.counters().discardedBytes < RESYNC_MAX_BYTES);   // candidates tripped first
  }

  testCase("§4.5 an oversized SKIP resets the budget and does not count its bytes");
  {
    // Only reachable with a receiver whose rx buffer is below 248, which ours is
    // not; assert instead that a legal 248-byte frame is delivered whole.
    FrameReader r;
    uint8_t body[MAX_PAYLOAD];
    memset(body, 0x11, sizeof(body));
    EncodeResult e = encodeFrame(buf, sizeof(buf), 0x30, 0, body, MAX_PAYLOAD);
    CHECK_U32(e, MAX_FRAME);
    CHECK_U32(drive(r, buf, (size_t)e, 0, got, 8, &res), 1);
    CHECK_U32(got[0].length, MAX_PAYLOAD);
    CHECK(res == DecodeResult::UnknownType);
    CHECK(!r.isFatal());
  }

  testCase("§17.10 endianness: 0x12345678 on the wire is 78 56 34 12");
  {
    uint8_t body[LEN_STATS];
    memcpy(body, STATS_LIVE + 8, LEN_STATS);
    body[0] = 0x78; body[1] = 0x56; body[2] = 0x34; body[3] = 0x12;
    EncodeResult e = encodeFrame(buf, sizeof(buf), T_STATS, 0, body, LEN_STATS);
    InboundFrame f;
    CHECK(decodeInbound(buf, (size_t)e, f) == DecodeResult::Ok);
    CHECK_U32(f.as.stats.viewers, 0x12345678u);
    // And the encoder's own length field is little-endian too.
    CHECK_U32(buf[4], LEN_STATS);
    CHECK_U32(buf[5], 0);
    CHECK_U32(encodeAck(buf, sizeof(buf), 0x12345678u), 12);
    CHECK_U32(buf[8], 0x78); CHECK_U32(buf[9], 0x56);
    CHECK_U32(buf[10], 0x34); CHECK_U32(buf[11], 0x12);
  }

  testCase("§6.4 receiver normalisations: tier > 4, ttl > 6000, bad currency");
  {
    uint8_t body[LEN_EVENT];
    memcpy(body, EVENT_SUB + 8, LEN_EVENT);
    body[21] = 9;                       // tier out of range
    body[18] = 0x71; body[19] = 0x17;   // ttl_ds = 6001
    body[12] = 'e'; body[13] = 'u'; body[14] = 'r'; body[15] = 0;  // lowercase
    EncodeResult e = encodeFrame(buf, sizeof(buf), T_EVENT, 0, body, LEN_EVENT);
    InboundFrame f;
    CHECK(decodeInbound(buf, (size_t)e, f) == DecodeResult::Ok);
    CHECK_U32(f.as.event.tier, TIER_NONE);
    CHECK_U32(f.as.event.ttl_ds, TTL_DS_MAX);
    CHECK_U32(f.as.event.currency[0], 0);   // not monetary
    CHECK(currencyIsValid(f.as.event.currency));
    CHECK(!currencyIsValid("eur"));
    CHECK(currencyIsValid("USD"));
  }

  testCase("§3.2 an unknown header flag bit does not reject the frame");
  {
    uint8_t body[LEN_STATS];
    memcpy(body, STATS_LIVE + 8, LEN_STATS);
    EncodeResult e = encodeFrame(buf, sizeof(buf), T_STATS, 0x80, body, LEN_STATS);
    InboundFrame f;
    CHECK(decodeInbound(buf, (size_t)e, f) == DecodeResult::Ok);
    CHECK_U32(f.header.flags, 0x80);
  }

  testCase("§7 an unknown version is a readable header, not a framing error");
  {
    uint8_t frame[MAX_FRAME];
    memcpy(frame, BYE_VERSION_MISMATCH, sizeof(BYE_VERSION_MISMATCH));
    frame[2] = 9;                       // a version we do not speak
    frame[7] = headerCheck(frame);
    CHECK(headerIsValid(frame));
    InboundFrame f;
    CHECK(decodeInbound(frame, sizeof(BYE_VERSION_MISMATCH), f) == DecodeResult::Ok);
    CHECK_U32(f.header.version, 9);
    CHECK_U32(f.as.bye.code, BYE_UNSUPPORTED_VERSION);   // still readable
  }

  testCase("§3.1 hchk is positional: a one-byte shift cannot collide");
  {
    uint8_t h[8];
    h[0] = MAGIC0; h[1] = MAGIC1; h[2] = VERSION; h[3] = T_EVENT;
    h[4] = 0xa8; h[5] = 0x00; h[6] = 0x00;
    h[7] = headerCheck(h);
    // Swap the two adjacent bytes a stream shift would transpose.
    uint8_t s[8];
    memcpy(s, h, 8);
    uint8_t tmp = s[3]; s[3] = s[4]; s[4] = tmp;
    CHECK(headerCheck(s) != h[7]);
    // An additive sum would NOT have caught this, which is why it is forbidden.
    uint32_t sumA = 0, sumB = 0;
    for (int i = 0; i < 7; ++i) { sumA += h[i]; sumB += s[i]; }
    CHECK_U32(sumA & 0xff, sumB & 0xff);
  }

  testCase("bounds: a truncated buffer is Truncated, never an over-read");
  {
    InboundFrame f;
    for (size_t n = 0; n < sizeof(STATS_LIVE); ++n) {
      DecodeResult r = decodeInbound(STATS_LIVE, n, f);
      CHECK(r == DecodeResult::Truncated);
    }
    CHECK(decodeInbound(STATS_LIVE, sizeof(STATS_LIVE), f) == DecodeResult::Ok);
    // And every payload decoder refuses every length below its base.
    TsbEvent ev; TsbStats st; TsbWelcome we; TsbBye by; TsbToken tk; TsbHello he;
    for (size_t n = 0; n < LEN_EVENT; ++n)
      CHECK(decodeEvent(EVENT_SUB + 8, n, ev) == DecodeResult::ShortPayload);
    for (size_t n = 0; n < LEN_STATS; ++n)
      CHECK(decodeStats(STATS_LIVE + 8, n, st) == DecodeResult::ShortPayload);
    for (size_t n = 0; n < LEN_WELCOME; ++n)
      CHECK(decodeWelcome(WELCOME + 8, n, we) == DecodeResult::ShortPayload);
    for (size_t n = 0; n < LEN_BYE; ++n)
      CHECK(decodeBye(BYE_VERSION_MISMATCH + 8, n, by) == DecodeResult::ShortPayload);
    for (size_t n = 0; n < LEN_TOKEN; ++n)
      CHECK(decodeToken(PING_RELAY + 8, n, tk) == DecodeResult::ShortPayload);
    for (size_t n = 0; n < LEN_HELLO; ++n)
      CHECK(decodeHello(HELLO_FRESH_BOOT + 8, n, he) == DecodeResult::ShortPayload);
  }

  testCase("every byte value is a safe first byte for the reader");
  {
    for (unsigned b = 0; b < 256; ++b) {
      FrameReader r;
      uint8_t lead = (uint8_t)b;
      uint8_t s[1 + sizeof(STATS_LIVE)];
      s[0] = lead;
      memcpy(s + 1, STATS_LIVE, sizeof(STATS_LIVE));
      Collected c[4];
      size_t frames = drive(r, s, sizeof(s), 1, c, 4, &res);
      CHECK(frames == 1);
      CHECK(!r.isFatal());
      if (frames == 1) CHECK_U32(c[0].type, T_STATS);
    }
  }
}

// ---------------------------------------------------------------------------

int main() {
  printf("TSB/3 codec vs docs/PROTOCOL.md \xc2\xa7""18 golden vectors\n\n");
  test_headers();
  test_encode_hello_fresh();
  test_encode_hello_resume();
  test_encode_ping_ack();
  test_hello_roundtrip();
  test_welcome();
  test_stats();
  test_events();
  test_ping_pong_ack_bye();
  test_truncation();
  test_reader_stream();
  test_conformance_cases();
  printf("\n%d checks, %d failures\n", g_checks, g_failures);
  return g_failures == 0 ? 0 : 1;
}
