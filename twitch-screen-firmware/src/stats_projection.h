#pragma once

#include "proto_codec.h"
#include "stats.h"

// Explicit application projection; StreamStats is not a wire-layout cast.
inline StreamStats projectStats(const tsb::TsbStats &st, uint8_t availability = 1) {
  StreamStats s;
  s.viewers = st.viewers;
  s.msgTotal = st.msg_total;
  s.uptimeSec = st.uptime_s;
  s.followers = st.followers;
  s.subs = st.subs;
  s.serverTime = st.server_time;
  s.streamStartedAt = st.stream_started_at;
  s.chatRate = st.chat_rate;
  s.live = st.live;
  s.sflags = st.sflags;
  s.availability = availability;
  return s;
}
