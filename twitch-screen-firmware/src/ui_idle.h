#pragma once

#include <stdbool.h>
#include "stats.h"

// Idle screen "Twitch Orbit": full-ring chat-activity gauge on the panel
// edge, pulsing LIVE pill + uptime, Glitch logo, big animated viewer count,
// stat chips. Offline variant shows the dimmed Glitch.
void uiIdleBuild();

// Feed the latest stream stats (updates widgets, animates transitions).
void uiIdleSetStats(const StreamStats &s);

// Full-screen CONNECTING until WiFi and the TSB/3 WELCOME handshake are ready.
void uiIdleSetOnline(bool online);
