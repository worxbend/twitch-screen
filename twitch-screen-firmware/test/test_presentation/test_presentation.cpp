#include <stdio.h>
#include <string.h>
#include "presentation.h"
#include "notification_wire.h"
#include "stats_render_plan.h"

namespace {

StreamStats nextStats(uint8_t live, uint32_t viewers, uint32_t msgs, uint32_t follows,
                      uint32_t subs, uint16_t chatRate) {
  StreamStats s;
  s.live = live; s.viewers = viewers; s.msgTotal = msgs;
  s.followers = follows; s.subs = subs; s.chatRate = chatRate;
  return s;
}

// Plans what the idle screen would write, then records it as shown, the way
// ui_idle.cpp applies a plan to the panel.
StatsRenderPlan apply(StatsShown &shown, const StreamStats &s, bool linkUp = true,
                      bool covered = false) {
  const StatsRenderPlan plan = planStatsRender(shown, s, linkUp, covered);
  commitStatsPlan(shown, plan);
  return plan;
}

}  // namespace

int main() {
  int checks = 0, failures = 0;
  auto check = [&](bool ok, const char *what) {
    ++checks; if (!ok) { ++failures; printf("FAIL: %s\n", what); }
  };
  struct Number { uint32_t value; const char *text; };
  const Number cases[] = {{0, "0"}, {999, "999"}, {1000, "1.0K"},
    {1049, "1.0K"}, {1099, "1.0K"}, {1100, "1.1K"}, {9949, "9.9K"}, {9950, "9.9K"}, {9999, "9.9K"}, {10000, "10K"}, {999999, "999K"}, {1000000, "1.0M"},
    {1099999, "1.0M"}, {1100000, "1.1M"}, {9999999, "9.9M"}, {10000000, "10M"}, {999999999, "999M"},
    {1000000000, "1.0B"}, {1099999999, "1.0B"}, {1100000000, "1.1B"}, {0xffffffffu, "4.2B"}};
  for (const auto &c : cases) {
    char text[16]; formatCount(text, sizeof(text), c.value);
    check(strcmp(text, c.text) == 0 && strnlen(text, sizeof(text)) <= 4, "bounded compact count");
  }
  for (unsigned code = 0; code <= 255; ++code) {
    const auto kind = kindFromCode(static_cast<uint8_t>(code));
    const auto &style = kindPresentation(kind);
    check(style.label && style.icon && style.holdMs >= 2500 && style.holdMs <= 5000,
          "every wire kind has complete bounded presentation including unknown fallback");
  }
  check(kindPresentation(NotifyKind::Chat).holdMs == 2500, "chat hold default");
  check(kindPresentation(NotifyKind::StreamStart).holdMs == 5000, "stream hold default");
  check(readableChatColor(0) == 0xffffff, "legal black color remains readable");
  check(readableChatColor(0x101010) == 0xffffff, "dark chatter color gets visible fallback");
  check(readableChatColor(0x00ff00) == 0x00ff00, "bright chatter color is retained");
  tsb::TsbEvent e = {}; e.kind = 0x16; e.seq = 1;
  e.eflags = tsb::EF_CHAT_COLOUR_PRESENT | tsb::EF_ANONYMOUS;
  snprintf(e.actor, sizeof(e.actor), "actor\n\t\x1b");
  snprintf(e.text, sizeof(e.text), "hello\rworld");
  Notification n; notificationFromEvent(e, 0, n);
  check(n.anonymous && n.chatColorPresent, "wire flags become presentation booleans");
  check(strcmp(n.actor, "actor   ") == 0, "actor controls cannot forge diagnostics or layout");
  check(strcmp(n.text, "hello world") == 0, "body controls use neutral spacing");
  // K-025: each STATS writes only the fields that changed, only in the visible group.
  {
    StatsShown shown; initStatsShown(shown);
    const StreamStats live = nextStats(1, 42, 1500, 7, 3, 12);
    StatsRenderPlan p = apply(shown, live);
    check(p.group == StatsGroup::Live, "live stats plan the live group");
    check(p.chat && p.foll && p.subs && strcmp(p.text.chat, "1.5K") == 0 &&
          strcmp(p.text.foll, "7") == 0 && strcmp(p.text.subs, "3") == 0,
          "first live stats write the changed live chips");
    check(p.viewers && p.viewersValue == 42 && p.arc && p.arcValue == 12 && p.uptime,
          "first live stats write viewers and arc and tick uptime");
    check(p.writes() == 5, "first live stats count five writes");
    check(strcmp(shown.offline.chat, "0") == 0, "live stats leave the offline mirror alone");
    p = apply(shown, live);
    check(p.writes() == 0 && !p.chat && !p.foll && !p.subs && !p.viewers && !p.arc,
          "identical live stats write nothing");
    p = apply(shown, nextStats(1, 42, 1600, 7, 3, 12));
    check(p.writes() == 1 && p.chat && strcmp(p.text.chat, "1.6K") == 0,
          "a changed message total writes only the chat chip");
    p = apply(shown, nextStats(1, 42, 1600, 7, 3, 250));
    check(p.writes() == 1 && p.arc && p.arcValue == 100, "chat rate alone writes the clamped arc");
    p = apply(shown, nextStats(1, 42, 1600, 7, 3, 300));
    check(p.writes() == 0, "a rate still above the clamp does not rewrite the arc");
  }
  {
    StatsShown shown; initStatsShown(shown);
    const StreamStats off = nextStats(0, 42, 12, 5, 2, 40);
    StatsRenderPlan p = apply(shown, off);
    check(p.group == StatsGroup::Offline && p.chat && p.foll && p.subs && p.writes() == 3,
          "offline stats write the offline chips only");
    check(!p.viewers && !p.arc && !p.uptime, "offline stats never touch viewers, arc or uptime");
    check(strcmp(shown.live.chat, "0") == 0, "offline stats leave the live mirror alone");
    p = apply(shown, off);
    check(p.writes() == 0, "identical offline stats write nothing");
  }
  {
    StatsShown shown; initStatsShown(shown);
    const StreamStats live = nextStats(1, 42, 1500, 7, 3, 12);
    const StreamStats off = nextStats(0, 0, 1500, 7, 3, 0);
    check(apply(shown, live, true, true).group == StatsGroup::None &&
          apply(shown, live, true, true).writes() == 0, "covered live stats write nothing");
    check(apply(shown, off, true, true).writes() == 0, "covered offline stats write nothing");
    check(apply(shown, live, false, false).group == StatsGroup::None &&
          apply(shown, live, false, false).writes() == 0, "connecting live stats write nothing");
    check(apply(shown, off, false, false).writes() == 0, "connecting offline stats write nothing");
    check(!planStatsRender(shown, live, false, false).uptime, "connecting does not tick uptime");
    check(strcmp(shown.live.chat, "0") == 0 && shown.viewers == -1 && shown.arc == 0,
          "hidden plans do not pretend anything was drawn");
  }
  {
    StatsShown shown; initStatsShown(shown);
    apply(shown, nextStats(1, 42, 1500, 7, 3, 12));
    StatsRenderPlan p = apply(shown, nextStats(0, 0, 1500, 7, 3, 0));
    check(p.group == StatsGroup::Offline && p.writes() == 3,
          "showing the offline group redraws the cached totals there");
    forgetLiveViewers(shown);
    p = apply(shown, nextStats(1, 42, 1500, 7, 3, 12));
    check(!p.chat && !p.foll && !p.subs, "live chips still match after an offline spell");
    check(p.viewers && p.viewersValue == 42 && !p.arc && p.writes() == 1,
          "forgotten viewers are written again on return to live");
  }
  {
    StatsShown shown; initStatsShown(shown);
    apply(shown, nextStats(1, 0, 1049, 1049, 1049, 0));
    const StatsRenderPlan p = apply(shown, nextStats(1, 0, 1099, 1099, 1099, 0));
    check(p.writes() == 0, "counts inside one rounding bucket write nothing");
  }
  for (int bits = 0; bits < 8; ++bits) {
    const bool linkUp = (bits & 1) != 0, covered = (bits & 2) != 0, live = (bits & 4) != 0;
    const StatsGroup expected = covered || !linkUp ? StatsGroup::None :
                                live ? StatsGroup::Live : StatsGroup::Offline;
    check(visibleStatsGroup(linkUp, covered, live) == expected,
          "one visibility rule for every link, cover and live combination");
  }
  // K-107: replayed cards skip the entrance animation (§6.4).
  for (int code = 0; code < 256; ++code) {
    const NotifyKind kind = kindFromCode(static_cast<uint8_t>(code));
    check(entranceFor(true, kind) == Entrance::None, "replay never animates, any kind");
    const bool severe = kind == NotifyKind::Warning || kind == NotifyKind::Alert;
    check(entranceFor(false, kind) == (severe ? Entrance::FlashThenSlide : Entrance::Slide),
          "live cards flash only for warning and alert, any code");
  }
  check(entranceFor(true, NotifyKind::Alert) == Entrance::None, "replayed alert skips flash");
  check(entranceFor(true, NotifyKind::Warning) == Entrance::None, "replayed warning skips flash");
  check(entranceFor(false, NotifyKind::Alert) == Entrance::FlashThenSlide, "live alert flashes");
  check(entranceFor(false, NotifyKind::Warning) == Entrance::FlashThenSlide,
        "live warning flashes");
  {
    const NotifyKind routine[] = {
        NotifyKind::Info, NotifyKind::Message, NotifyKind::StreamStart, NotifyKind::StreamEnd,
        NotifyKind::Follow, NotifyKind::Sub, NotifyKind::Gift, NotifyKind::Raid,
        NotifyKind::Chat, NotifyKind::Bits, kindFromCode(0x18), kindFromCode(0xff)};
    for (size_t i = 0; i < sizeof(routine) / sizeof(routine[0]); ++i)
      check(entranceFor(false, routine[i]) == Entrance::Slide, "routine live cards only slide");
  }
  // K-118: only severe live cards pay for the full-panel ring pulse during the hold.
  check(!ringPulses(Entrance::None), "replay ring is static");
  check(!ringPulses(Entrance::Slide), "routine ring is static");
  check(ringPulses(Entrance::FlashThenSlide), "severe ring pulses");
  for (int code = 0; code < 256; ++code) {
    const NotifyKind kind = kindFromCode(static_cast<uint8_t>(code));
    const bool severe = kind == NotifyKind::Warning || kind == NotifyKind::Alert;
    check(ringPulses(entranceFor(false, kind)) == severe,
          "only live warning/alert pulse the ring, any code");
    check(!ringPulses(entranceFor(true, kind)), "replay never pulses, any code");
  }
  {
    const NotifyKind routine[] = {
        NotifyKind::Info, NotifyKind::Message, NotifyKind::StreamStart, NotifyKind::StreamEnd,
        NotifyKind::Follow, NotifyKind::Sub, NotifyKind::Gift, NotifyKind::Raid,
        NotifyKind::Chat, NotifyKind::Bits, kindFromCode(0x18), kindFromCode(0xff)};
    for (size_t i = 0; i < sizeof(routine) / sizeof(routine[0]); ++i)
      check(!ringPulses(entranceFor(false, routine[i])), "routine live ring stays static");
  }
  printf("%d checks, %d failures\n", checks, failures);
  return failures != 0;
}
