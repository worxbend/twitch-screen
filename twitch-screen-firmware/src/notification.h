#pragma once

#include <Arduino.h>
#include <string.h>

enum class NotifyKind : uint8_t {
  Info,
  Message,
  Warning,
  Alert,
  // Twitch event kinds
  Follow,
  Sub,
  Gift,
  Raid,
  Chat,
  Bits,
};

struct Notification {
  uint32_t seq = 0;
  NotifyKind kind = NotifyKind::Info;
  char title[48] = {0};
  char body[96] = {0};
};

inline NotifyKind kindFromString(const char *s) {
  if (!strcmp(s, "follow"))  return NotifyKind::Follow;
  if (!strcmp(s, "sub"))     return NotifyKind::Sub;
  if (!strcmp(s, "gift"))    return NotifyKind::Gift;
  if (!strcmp(s, "raid"))    return NotifyKind::Raid;
  if (!strcmp(s, "chat"))    return NotifyKind::Chat;
  if (!strcmp(s, "bits"))    return NotifyKind::Bits;
  if (!strcmp(s, "message")) return NotifyKind::Message;
  if (!strcmp(s, "warning")) return NotifyKind::Warning;
  if (!strcmp(s, "alert"))   return NotifyKind::Alert;
  return NotifyKind::Info;
}

inline const char *kindLabel(NotifyKind k) {
  switch (k) {
    case NotifyKind::Follow:  return "FOLLOW";
    case NotifyKind::Sub:     return "SUB";
    case NotifyKind::Gift:    return "GIFT";
    case NotifyKind::Raid:    return "RAID";
    case NotifyKind::Chat:    return "CHAT";
    case NotifyKind::Bits:    return "BITS";
    case NotifyKind::Message: return "MESSAGE";
    case NotifyKind::Warning: return "WARNING";
    case NotifyKind::Alert:   return "ALERT";
    default:                  return "INFO";
  }
}
