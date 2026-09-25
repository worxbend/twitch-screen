//
// Host-compiled unit test for the firmware code that sits BETWEEN the TSB/3
// codec and the screen: the EVENT -> Notification binding (notification_wire.h)
// and the device queue with its sequence accounting (notify_queue.h).
//
// The codec itself is covered by test/test_proto_codec. This suite exists
// because the conformance audit's three most damaging findings were not codec
// bugs at all — they were in this layer:
//
//   item 3: NotifyKind was a 0..9 ordinal, so wire code 0x12 (FOLLOW) read as
//           out of range and every Twitch event drew as the same grey INFO card;
//   item 4: ttl_ds was decoded and then thrown away;
//   item 5: lastSeq advanced at decode time and the queue evicted the OLDEST
//           entry, so an event lost to a full queue counted as delivered and
//           replay could never bring it back.
//
// Every assertion below is driven either from the golden bytes of §18 or from
// the normative text of §6.4.2, §10.2 and §10.5.
//
// §17: this runs under a command the repository configures, which is the whole
// reason platformio.ini has an [env:native] and a `default_envs = esp32dev`:
//
//   ~/.platformio/penv/bin/pio test -e native
//
// It still compiles standalone, which is what makes it quick to bisect:
//
//   g++ -std=gnu++11 -Wall -Wextra -Wpedantic -Isrc -Itest/test_proto_codec
//       -o /tmp/tsblink test/test_link_wire/test_link_wire.cpp src/proto_codec.cpp
//   /tmp/tsblink
//
#include <stdio.h>
#include <string.h>

#include "notification.h"
#include "notification_wire.h"
#include "notify_queue.h"
#include "proto_codec.h"
#include "vectors.h"

namespace {

int checks = 0;
int failures = 0;

void ok(bool cond, const char *what) {
  ++checks;
  if (!cond) {
    ++failures;
    printf("  FAIL: %s\n", what);
  }
}

void okEq(unsigned long got, unsigned long want, const char *what) {
  ++checks;
  if (got != want) {
    ++failures;
    printf("  FAIL: %s: got %lu (0x%lx), want %lu (0x%lx)\n",
           what, got, got, want, want);
  }
}

void okStr(const char *got, const char *want, const char *what) {
  ++checks;
  if (strcmp(got, want) != 0) {
    ++failures;
    printf("  FAIL: %s: got \"%s\", want \"%s\"\n", what, got, want);
  }
}

void section(const char *s) { printf("- %s\n", s); }

// -------------------------------------------------------------------------
// §6.4.1, §6.4.2 — the kind is a WIRE CODE, not an ordinal.
// -------------------------------------------------------------------------

void testKindIsAWireCode() {
  section("§6.4.1 every defined kind keeps its wire code end to end");

  const uint8_t known[] = {0x00, 0x01, 0x02, 0x03, 0x10, 0x11,
                           0x12, 0x13, 0x14, 0x15, 0x16, 0x17};
  for (size_t i = 0; i < sizeof(known); ++i) {
    const uint8_t code = known[i];
    ok(kindIsKnown(code), "defined kind is known");
    // The enumerator's VALUE is the wire byte. This is the assertion that the
    // old 0..9 ordinal enum could not have passed.
    okEq((unsigned long)(uint8_t)kindFromCode(code), code,
         "kindFromCode round-trips the wire code");
  }
  okEq((unsigned long)(uint8_t)NotifyKind::Follow, 0x12, "FOLLOW is 0x12");
  okEq((unsigned long)(uint8_t)NotifyKind::StreamStart, 0x10, "STREAM_START is 0x10");
  okEq((unsigned long)(uint8_t)NotifyKind::StreamEnd, 0x11, "STREAM_END is 0x11");
  okEq((unsigned long)(uint8_t)NotifyKind::Chat, 0x16, "CHAT is 0x16");
  okEq((unsigned long)(uint8_t)NotifyKind::Bits, 0x17, "BITS is 0x17");

  section("§6.4.2 every other code folds to INFO, and 0x18 has no name");
  for (unsigned c = 0; c <= 0xff; ++c) {
    const uint8_t code = (uint8_t)c;
    bool isKnown = false;
    for (size_t i = 0; i < sizeof(known); ++i) if (known[i] == code) isKnown = true;
    if (isKnown) continue;
    ok(!kindIsKnown(code), "undefined kind is not known");
    ok(kindFromCode(code) == NotifyKind::Info, "undefined kind renders as INFO");
  }
  // §8: 0x18 is permanently reserved. It must behave as any other unknown code,
  // not as a named kind.
  ok(!kindIsKnown(0x18), "0x18 DONATION is reserved and unnamed");
  ok(kindFromCode(0x18) == NotifyKind::Info, "0x18 renders as INFO");

  section("§6.4 tier 0 is NOT APPLICABLE, not Prime");
  ok(tierName(0) == 0, "tier 0 has no name");
  okStr(tierName(1), "Prime", "tier 1 is Prime");
  okStr(tierName(2), "Tier 1", "tier 2 is Tier 1");
  okStr(tierName(3), "Tier 2", "tier 3 is Tier 2");
  okStr(tierName(4), "Tier 3", "tier 4 is Tier 3");
  ok(tierName(5) == 0, "tier 5 has no name");
  ok(tierName(255) == 0, "tier 255 has no name");
}

// -------------------------------------------------------------------------
// §18 golden bytes -> Notification, the exact path link_client takes.
// -------------------------------------------------------------------------

int boundVectors = 0;

void testGoldenEventsBindToCards() {
  section("§18 all ten EVENT vectors decode and bind to a renderable card");

  for (size_t i = 0; i < gv::EVENT_COUNT; ++i) {
    const gv::ExpEvent &e = gv::EVENT_VECTORS[i];

    tsb::InboundFrame f;
    const tsb::DecodeResult r = tsb::decodeInbound(e.frame, e.size, f);
    ok(r == tsb::DecodeResult::Ok, e.name);
    if (r != tsb::DecodeResult::Ok) continue;

    Notification n;
    notificationFromEvent(f.as.event, f.header.flags, n);
    ++boundVectors;

    okEq(n.seq, e.seq, e.name);
    okEq(n.ts, e.ts, e.name);
    okEq(n.value, e.value, e.name);
    okEq(n.months, e.months, e.name);
    okEq(n.ttl_ds, e.ttl_ds, e.name);
    okEq(n.tier, e.tier, e.name);
    okEq(n.eflags, e.eflags, e.name);
    okEq(n.wireKind, e.kind, e.name);
    okStr(n.actor, e.actor, e.name);
    okStr(n.text, e.text, e.name);

    // The finding that made every card grey: the kind must survive as the wire
    // code, so a defined kind must NOT arrive at the renderer as INFO.
    okEq((unsigned long)(uint8_t)n.kind, e.kind, "kind survives as the wire code");
    if (e.kind != 0x00) {
      ok(n.kind != NotifyKind::Info, "a defined non-INFO kind is not folded");
    }

    // §3.2: replay is a HEADER flag, not an eflag. V10 is the replayed one.
    ok(n.replay == ((e.flags & 0x01) != 0), "replay comes from the header flags");

    // §6.4: ttl_ds must reach the renderer, not be replaced by a hardcoded hold.
    ok(n.ttl_ds != 0, "the relay's ttl_ds is carried, not discarded");
  }

  // Spot-check the fields the renderer composes sentences from, against the
  // prose of §6.4.1 rather than against the table above.
  section("§6.4.1 the per-kind numeric meanings arrive intact");
  for (size_t i = 0; i < gv::EVENT_COUNT; ++i) {
    const gv::ExpEvent &e = gv::EVENT_VECTORS[i];
    tsb::InboundFrame f;
    if (tsb::decodeInbound(e.frame, e.size, f) != tsb::DecodeResult::Ok) continue;
    Notification n;
    notificationFromEvent(f.as.event, f.header.flags, n);

    switch (n.kind) {
      case NotifyKind::Raid:
        okEq(n.value, 128, "V10 RAID.value is the raider's viewer count");
        break;
      case NotifyKind::Bits:
        okEq(n.value, 1500, "V11 BITS.value is the bits count");
        break;
      case NotifyKind::Gift:
        okEq(n.value, 5, "V9 GIFT.value is the number of subs gifted");
        okEq(n.tier, 2, "V9 GIFT.tier is Tier 1");
        break;
      case NotifyKind::Sub:
        okEq(n.months, 14, "V8 SUB.months is cumulative");
        okEq(n.tier, 3, "V8 SUB.tier is Tier 2");
        break;
      case NotifyKind::StreamEnd:
        okEq(n.value, 3760, "V15 STREAM_END.value is the duration in seconds");
        break;
      case NotifyKind::Chat:
        // §6.4.1: CHAT.value is a colour and is meaningful only when the flag
        // is set, because black is a legal colour.
        if (n.eflags & tsb::EF_CHAT_COLOUR_PRESENT) {
          okEq(n.value, 0x00ff7f50u, "V12 CHAT.value is the name colour");
        }
        break;
      default:
        break;
    }
  }
}

// §16 rule 3 / §6.4.2: a kind this firmware has never heard of still draws.
void testUnknownKindStillDraws() {
  section("§6.4.2 an unknown kind renders as INFO from actor/text, not dropped");

  const uint8_t probes[] = {0x18, 0x19, 0x04, 0x3f, 0x7f, 0xff};
  for (size_t i = 0; i < sizeof(probes); ++i) {
    uint8_t frame[176];
    memcpy(frame, gv::EVENT_FOLLOW, sizeof(frame));
    frame[8 + 20] = probes[i];   // payload +20 is `kind`; hchk covers only the header

    tsb::InboundFrame f;
    const tsb::DecodeResult r = tsb::decodeInbound(frame, sizeof(frame), f);
    ok(r == tsb::DecodeResult::Ok, "unknown kind is not a decode error");
    if (r != tsb::DecodeResult::Ok) continue;

    Notification n;
    notificationFromEvent(f.as.event, f.header.flags, n);
    ok(n.kind == NotifyKind::Info, "unknown kind renders as INFO");
    okEq(n.wireKind, probes[i], "the raw code is kept for the log line");
    okStr(n.actor, gv::EVENT_FOLLOW_ACTOR, "actor is preserved for the headline");
    okEq(n.seq, 0x77, "sequence accounting still applies to an unknown kind");
  }
}

// §8.1: five bytes are held open for a future monetary amount. A v4 frame must
// reach this layer with them intact — and must not be interpreted here either.
void testReservedBytesAreNotTouched() {
  section("§8.1 a newer relay's reserved1/reserved2 survive the binding");

  uint8_t frame[176];
  memcpy(frame, gv::EVENT_BITS, sizeof(frame));
  frame[8 + 12] = 'E';  frame[8 + 13] = 'U';
  frame[8 + 14] = 'R';  frame[8 + 15] = 0x00;
  frame[8 + 23] = 2;

  tsb::InboundFrame f;
  ok(tsb::decodeInbound(frame, sizeof(frame), f) == tsb::DecodeResult::Ok,
     "non-zero reserved bytes are not a frame error");

  Notification n;
  notificationFromEvent(f.as.event, f.header.flags, n);

  // Checked AFTER the binding, deliberately: "ignore" means not acting on these
  // bytes, not erasing them. A binding layer that blanked its input would hand a
  // v4 EVENT to the renderer with the new field already destroyed, which is the
  // exact forward compatibility §8.1 exists to buy.
  okEq(f.as.event.reserved1[0], 'E', "reserved1 survives the binding");
  okEq(f.as.event.reserved1[1], 'U', "reserved1 survives the binding");
  okEq(f.as.event.reserved1[2], 'R', "reserved1 survives the binding");
  okEq(f.as.event.reserved1[3], 0, "reserved1 survives the binding");
  okEq(f.as.event.reserved2, 2, "reserved2 survives the binding");
  okEq(n.value, 1500, "the amount is not confused with value");
  okEq(n.wireKind, 0x17, "the kind is unaffected");
}

// -------------------------------------------------------------------------
// §10.2 and §10.5 — the queue, which is where replay is won or lost.
// -------------------------------------------------------------------------

Notification ev(uint32_t seq, uint8_t kind = 0x12) {
  Notification n;
  n.seq = seq;
  n.wireKind = kind;
  n.kind = kindFromCode(kind);
  snprintf(n.actor, sizeof(n.actor), "actor%lu", (unsigned long)seq);
  return n;
}

void testQueueRefusesNewestOnOverflow() {
  section("§10.5 a full queue refuses the NEWEST and never evicts the oldest");

  NotifyQueue<8> q;
  q.greet(100, 0xabcd);                 // fresh boot: baseline at 100
  okEq(q.lastSeq(), 100, "fresh boot re-baselines to latest_seq");

  for (uint32_t i = 1; i <= 8; ++i) {
    ok(q.offer(ev(100 + i)) == NotifyQueue<8>::Offer::Enqueued, "fits");
  }
  okEq(q.size(), 8, "queue is full");
  okEq(q.lastSeq(), 108, "last_seq advanced with each successful enqueue");
  ok(!q.gapOpen(), "no gap yet");

  // The ninth is refused. The v2 code dropped #101 to make room for it.
  ok(q.offer(ev(109)) == NotifyQueue<8>::Offer::Refused, "the newest is refused");
  okEq(q.size(), 8, "nothing was evicted to make room");
  okEq(q.refused(), 1, "the refusal is counted");
  okEq(q.lastSeq(), 108, "last_seq did NOT advance past the refused event");
  ok(q.gapOpen(), "a gap is open");

  // The oldest must still be there: that is what "refuse the newest" means.
  Notification out;
  ok(q.take(out), "the queue still has entries");
  okEq(out.seq, 101, "the OLDEST survived the overflow");

  // With room again, a later event is still shown — dropping it would lose more
  // — but the reported high-water mark stays frozen at the hole.
  ok(q.offer(ev(110)) == NotifyQueue<8>::Offer::Enqueued, "room again");
  okEq(q.lastSeq(), 108, "last_seq stays frozen at the gap");
  okEq(q.shown(), 9, "nine events were queued for display");

  // The next greet is what clears it: the replay burst starts at 109.
  q.greet(130, 0xabcd);
  ok(!q.gapOpen(), "the greet closes the gap");
  okEq(q.lastSeq(), 108, "a same-session greet does not re-baseline");
}

void testQueueAdvancesOnlyAfterEnqueue() {
  section("§10.5 last_seq advances only after a successful enqueue");

  NotifyQueue<2> q;
  q.greet(0, 1);
  okEq(q.lastSeq(), 0, "nothing seen yet");

  ok(q.offer(ev(5)) == NotifyQueue<2>::Offer::Enqueued, "first fits");
  okEq(q.lastSeq(), 5, "advanced");
  ok(q.offer(ev(6)) == NotifyQueue<2>::Offer::Enqueued, "second fits");
  okEq(q.lastSeq(), 6, "advanced");
  ok(q.offer(ev(7)) == NotifyQueue<2>::Offer::Refused, "third does not fit");
  okEq(q.lastSeq(), 6, "refused events do not advance the mark");

  // A replay of something already enqueued is a duplicate, not a refusal.
  ok(q.offer(ev(6)) == NotifyQueue<2>::Offer::Duplicate, "equal seq is a duplicate");
  ok(q.offer(ev(1)) == NotifyQueue<2>::Offer::Duplicate, "lower seq is a duplicate");
  okEq(q.refused(), 1, "a duplicate is not counted as a refusal");
}

void testBaselineRules() {
  section("§10.2 baseline and re-baseline, in the order the spec gives");

  // 1. Fresh boot.
  NotifyQueue<8> q;
  ok(q.greet(500, 0x1111) == NotifyQueue<8>::Greet::Rebaselined, "fresh boot");
  okEq(q.lastSeq(), 500, "adopts latest_seq");

  // 2. Same session, relay ahead: keep our mark and expect the replay burst.
  q.offer(ev(501));
  okEq(q.lastSeq(), 501, "enqueued 501");
  ok(q.greet(600, 0x1111) == NotifyQueue<8>::Greet::Resumed, "same session resumes");
  okEq(q.lastSeq(), 501, "mark is kept");

  // 3. A RESTARTED relay that has reached a HIGHER seq does NOT re-baseline, and
  //    this is the whole point of the corrected §10.2. The relay's replay test is
  //    `0 < last_seq <= latest_seq`, which it computes from the same two numbers;
  //    it has no session_id echo in HELLO and therefore cannot test anything else.
  //    An earlier draft re-baselined here, so the relay pushed up to 80 replayed
  //    EVENTs and this queue dropped every one of them as a duplicate: 14 kB on
  //    the wire, the outbound queue spent, and nothing drawn on the display.
  ok(q.greet(900, 0x2222) == NotifyQueue<8>::Greet::Resumed,
     "a new session that is ahead still resumes");
  okEq(q.lastSeq(), 501, "the mark is kept, so the replay burst is 502..900");
  ok(q.sessionChanged(), "the session change is still observed, for the log");

  // ... and the burst it was kept for is actually accepted.
  ok(q.offer(ev(502)) == NotifyQueue<8>::Offer::Enqueued,
     "the first replayed event of the new session is enqueued, not discarded");

  // 4. latest_seq < last_seq re-baselines: that sequence space no longer exists.
  ok(q.greet(10, 0x2222) == NotifyQueue<8>::Greet::Rebaselined, "relay behind");
  okEq(q.lastSeq(), 10, "adopts the lower mark");
  ok(!q.sessionChanged(), "same session id twice running is not a session change");

  // A re-baseline keeps whatever is already queued for display.
  okEq(q.size(), 2, "the queued cards were not discarded by re-baselining");

  // 5. The two tests are complements, over the whole interesting range. Whatever
  //    the device decides here, the relay decides the opposite with the same two
  //    numbers — which is the property that makes replay work at all.
  const uint32_t marks[] = {0, 1, 50, 117, 500, 501, 900};
  for (size_t i = 0; i < sizeof(marks) / sizeof(marks[0]); ++i) {
    for (size_t j = 0; j < sizeof(marks) / sizeof(marks[0]); ++j) {
      NotifyQueue<8> probe;
      probe.greet(marks[i], 0x1111);          // fresh boot adopts marks[i]
      const bool deviceResumes =
          probe.greet(marks[j], 0x3333) == NotifyQueue<8>::Greet::Resumed;
      // DeviceHubState.greet: `lastSeq.isAfter(SeqNo.Zero) && !lastSeq.isAfter(latestSequence)`
      const bool relayReplays = marks[i] > 0 && marks[i] <= marks[j];
      ok(deviceResumes == relayReplays,
         "the device's §10.2 test is the complement of the relay's §10.3 test");
    }
  }
}

// §18 V3 is the WELCOME the device actually greets on; drive the baseline with
// its real bytes rather than with invented numbers.
void testBaselineFromGoldenWelcome() {
  section("§18 V3 the golden WELCOME drives the baseline");

  tsb::InboundFrame f;
  ok(tsb::decodeInbound(gv::WELCOME, 32, f) == tsb::DecodeResult::Ok, "V3 decodes");
  okEq(f.as.welcome.latest_seq, 127, "latest_seq = 127");
  okEq(f.as.welcome.session_id, 0x9c4e17a0u, "session_id");

  NotifyQueue<8> q;
  q.greet(f.as.welcome.latest_seq, f.as.welcome.session_id);
  okEq(q.lastSeq(), 127, "fresh boot adopts 127");

  // §18: V2's last_seq = 117 with V3's latest_seq = 127 means the replay burst
  // is exactly the ten EVENT vectors, 118..127. Replay them in order and check
  // that a device resuming at 117 enqueues all ten and lands on 127.
  NotifyQueue<16> r;
  r.greet(200, 0x9c4e17a0u);          // fresh boot
  ok(r.greet(117, 0x9c4e17a0u) == NotifyQueue<16>::Greet::Rebaselined,
     "relay behind re-baselines to 117");
  okEq(r.lastSeq(), 117, "resuming at 117");

  int enqueued = 0;
  for (size_t i = 0; i < gv::EVENT_COUNT; ++i) {
    const gv::ExpEvent &e = gv::EVENT_VECTORS[i];
    tsb::InboundFrame ef;
    if (tsb::decodeInbound(e.frame, e.size, ef) != tsb::DecodeResult::Ok) continue;
    Notification n;
    notificationFromEvent(ef.as.event, ef.header.flags, n);
    if (r.offer(n) == NotifyQueue<16>::Offer::Enqueued) ++enqueued;
  }
  okEq((unsigned long)enqueued, 10, "all ten replayed events are enqueued");
  okEq(r.lastSeq(), 127, "the high-water mark lands on 127");
  ok(!r.gapOpen(), "no gap was opened");

  // Replaying the same burst again changes nothing.
  for (size_t i = 0; i < gv::EVENT_COUNT; ++i) {
    const gv::ExpEvent &e = gv::EVENT_VECTORS[i];
    tsb::InboundFrame ef;
    if (tsb::decodeInbound(e.frame, e.size, ef) != tsb::DecodeResult::Ok) continue;
    Notification n;
    notificationFromEvent(ef.as.event, ef.header.flags, n);
    ok(r.offer(n) == NotifyQueue<16>::Offer::Duplicate, "a re-replay is a duplicate");
  }
  okEq(r.lastSeq(), 127, "the mark is unchanged by duplicates");
}

// §6.5: the two numbers the idle screen never drew.
void testStatsCarriesFollowersAndSubs() {
  section("§6.5 STATS.followers and STATS.subs reach the caller");

  for (size_t i = 0; i < gv::STATS_COUNT; ++i) {
    const gv::ExpStats &s = gv::STATS_VECTORS[i];
    tsb::InboundFrame f;
    ok(tsb::decodeInbound(s.frame, s.size, f) == tsb::DecodeResult::Ok, s.name);
    okEq(f.as.stats.followers, s.followers, "followers");
    okEq(f.as.stats.subs, s.subs, "subs");
    okEq(f.as.stats.stream_started_at, s.stream_started_at, "stream_started_at");
    okEq(f.as.stats.server_time, s.server_time, "server_time");
  }

  // §6.5 local uptime anchoring: with both clocks known the device prefers the
  // absolute anchor, and V4's two timestamps are 3660 s apart (1 h 01 m).
  const gv::ExpStats &live = gv::STATS_VECTORS[0];
  okEq(live.server_time - live.stream_started_at, 3660,
       "V4's absolute anchor gives the same 3660 s as uptime_s");
  okEq(live.uptime_s, 3660, "and uptime_s agrees");
}

}  // namespace

int main() {
  printf("TSB/3 firmware link-layer test — EVENT binding and device queue\n\n");

  testKindIsAWireCode();
  testGoldenEventsBindToCards();
  testUnknownKindStillDraws();
  testReservedBytesAreNotTouched();
  testQueueRefusesNewestOnOverflow();
  testQueueAdvancesOnlyAfterEnqueue();
  testBaselineRules();
  testBaselineFromGoldenWelcome();
  testStatsCarriesFollowersAndSubs();

  printf("\n%d EVENT vectors bound to cards\n", boundVectors);
  if (boundVectors != 10) {
    ++failures;
    printf("FAIL: expected all 10 EVENT vectors to bind\n");
  }
  printf("%d checks, %d failures\n", checks, failures);
  return failures == 0 ? 0 : 1;
}
