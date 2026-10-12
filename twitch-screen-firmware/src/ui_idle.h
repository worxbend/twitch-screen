#pragma once

#include "stats.h"

// Idle screen "Twitch Orbit": full-ring chat-activity gauge on the panel
// edge, pulsing LIVE pill + uptime, Glitch logo, big animated viewer count,
// stat chips. Offline variant shows the dimmed Glitch.
void uiIdleBuild();

// Feed the latest stream stats (updates widgets, animates transitions).
void uiIdleSetStats(const StreamStats &s);
// Pre-render projection compare-skips identical samples without reanchoring uptime.
void uiIdleApplyStats(const StreamStats &s);

// Rendering selection only. The Controller owns connectivity/base arbitration.
void uiIdleSetVisible(bool visible);
bool uiIdleVisible();

// Suspend hidden timers/animations while a notification covers the screen.
void uiIdleSetCovered(bool covered);
