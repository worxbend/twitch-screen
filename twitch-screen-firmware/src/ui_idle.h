#pragma once

#include <stdbool.h>
#include "stats.h"

// Idle screen "Twitch Orbit": full-ring chat-activity gauge on the panel
// edge, pulsing LIVE pill + uptime, Glitch logo, big animated viewer count,
// stat chips. Offline variant shows the dimmed Glitch.
void uiIdleBuild();

// Feed the latest stream stats (updates widgets, animates transitions).
void uiIdleSetStats(const StreamStats &s);

// Link state: shows a subtle "LINK DOWN" hint when the TCP link is lost.
void uiIdleSetOnline(bool online);
