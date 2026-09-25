#include <stdio.h>
#include <string.h>
#include "presentation.h"
#include "notification_wire.h"

int main() {
  int checks = 0, failures = 0;
  auto check = [&](bool ok, const char *what) {
    ++checks; if (!ok) { ++failures; printf("FAIL: %s\n", what); }
  };
  struct Number { uint32_t value; const char *text; };
  const Number cases[] = {{0, "0"}, {999, "999"}, {1000, "1.0K"},
    {9999, "9.9K"}, {10000, "10K"}, {999999, "999K"}, {1000000, "1.0M"},
    {9999999, "9.9M"}, {10000000, "10M"}, {999999999, "999M"},
    {1000000000, "1.0B"}, {0xffffffffu, "4.2B"}};
  for (const auto &c : cases) {
    char text[16]; formatCount(text, sizeof(text), c.value);
    check(strcmp(text, c.text) == 0 && strlen(text) <= 4, "bounded compact count");
  }
  check(readableChatColor(0) == 0xffffff, "legal black color remains readable");
  check(readableChatColor(0x101010) == 0xffffff, "dark chatter color gets visible fallback");
  check(readableChatColor(0x00ff00) == 0x00ff00, "bright chatter color is retained");
  tsb::TsbEvent e = {}; e.kind = 0x16; e.seq = 1;
  e.eflags = tsb::EF_CHAT_COLOUR_PRESENT | tsb::EF_ANONYMOUS;
  strcpy(e.actor, "actor\n\t\x1b"); strcpy(e.text, "hello\rworld");
  Notification n; notificationFromEvent(e, 0, n);
  check(n.anonymous && n.chatColorPresent, "wire flags become presentation booleans");
  check(strcmp(n.actor, "actor   ") == 0, "actor controls cannot forge diagnostics or layout");
  check(strcmp(n.text, "hello world") == 0, "body controls use neutral spacing");
  printf("%d checks, %d failures\n", checks, failures);
  return failures != 0;
}
