#pragma once

#include <stdint.h>
#include <string.h>

#include "presentation.h"
#include "stats.h"

// Every per-STATS decision of the idle screen, kept free of Arduino and LVGL so
// the host suite can pin it: which stat group is on screen, and which of its
// labels and gauge actually need a write. ui_idle.cpp applies the plan and then
// commits it into the mirror of what the panel shows. STATS arrives every 5 s
// and usually repeats itself, so an identical frame must cost zero writes, and
// a hidden group is never drawn behind the one that is visible.

enum class StatsGroup : uint8_t { None, Live, Offline };

// None while the cover screen or the connecting screen is up.
inline StatsGroup visibleStatsGroup(bool linkUp, bool covered, bool live) {
  if (covered || !linkUp) return StatsGroup::None;
  return live ? StatsGroup::Live : StatsGroup::Offline;
}

// Longest formatCount text is four characters (see presentation.h).
struct ChipTexts {
  char chat[8];
  char foll[8];
  char subs[8];
};

// Mirror of what is actually on the panel.
struct StatsShown {
  ChipTexts live;
  ChipTexts offline;
  int64_t viewers;  // -1 = unknown: the next live frame writes without a count-up
  int32_t arc;
};

inline void initChipTexts(ChipTexts &t) {
  strcpy(t.chat, "0");
  strcpy(t.foll, "0");
  strcpy(t.subs, "0");
}

// Matches what the build functions put on screen: "0" chips and an empty gauge.
inline void initStatsShown(StatsShown &shown) {
  initChipTexts(shown.live);
  initChipTexts(shown.offline);
  shown.viewers = -1;
  shown.arc = 0;
}

struct StatsRenderPlan {
  StatsGroup group;
  bool chat, foll, subs;  // chips of `group` to write
  ChipTexts text;         // valid where the matching flag is set
  bool viewers;           // Live only
  uint32_t viewersValue;
  bool arc;               // Live only
  int32_t arcValue;       // clamped to the gauge's 0..100
  bool uptime;            // Live only; the tick itself compare-skips the label

  int writes() const {
    return (chat ? 1 : 0) + (foll ? 1 : 0) + (subs ? 1 : 0) + (viewers ? 1 : 0) +
           (arc ? 1 : 0);
  }
};

inline bool planChip(char (&out)[8], const char *shown, uint32_t value) {
  formatCount(out, sizeof(out), value);
  return strcmp(out, shown) != 0;
}

inline StatsRenderPlan planStatsRender(const StatsShown &shown, const StreamStats &s,
                                       bool linkUp, bool covered) {
  StatsRenderPlan plan;
  memset(&plan, 0, sizeof(plan));
  plan.group = visibleStatsGroup(linkUp, covered, s.live != 0);
  if (plan.group == StatsGroup::None) return plan;
  const bool live = plan.group == StatsGroup::Live;
  const ChipTexts &chips = live ? shown.live : shown.offline;
  plan.chat = planChip(plan.text.chat, chips.chat, s.msgTotal);
  plan.foll = planChip(plan.text.foll, chips.foll, s.followers);
  plan.subs = planChip(plan.text.subs, chips.subs, s.subs);
  if (!live) return plan;
  plan.uptime = true;
  plan.viewersValue = s.viewers;
  plan.viewers = shown.viewers != (int64_t)s.viewers;
  plan.arcValue = s.chatRate > 100 ? 100 : (int32_t)s.chatRate;
  plan.arc = shown.arc != plan.arcValue;
  return plan;
}

inline void commitStatsPlan(StatsShown &shown, const StatsRenderPlan &plan) {
  if (plan.group == StatsGroup::None) return;
  ChipTexts &chips = plan.group == StatsGroup::Live ? shown.live : shown.offline;
  if (plan.chat) strcpy(chips.chat, plan.text.chat);
  if (plan.foll) strcpy(chips.foll, plan.text.foll);
  if (plan.subs) strcpy(chips.subs, plan.text.subs);
  if (plan.viewers) shown.viewers = plan.viewersValue;
  if (plan.arc) shown.arc = plan.arcValue;
}

// Leaving the live screen drops the count-up origin, so the next live frame
// writes its viewers straight away instead of animating from a stale value.
inline void forgetLiveViewers(StatsShown &shown) { shown.viewers = -1; }
