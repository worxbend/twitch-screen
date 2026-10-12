#pragma once

#include "desk_controller.h"
#include "link_client.h"
#include "desk_message_codec.h"

namespace desk {

enum class ApplicationGreeting : uint8_t { Desk, Rebaselined, Resumed };

// Receiver owns negotiated DESK synchronization. The application's classic
// callback must not reset that session or relabel its retained card identities.
inline ApplicationGreeting applyApplicationGreeting(Controller &controller, const LinkWelcome &welcome) {
  if (welcome.caps & wire::Capability) return ApplicationGreeting::Desk;
  const Controller::LegacyGreet result = controller.legacyGreeting(welcome.latestSeq, welcome.sessionId);
  return result == Controller::LegacyGreet::Rebaselined
      ? ApplicationGreeting::Rebaselined : ApplicationGreeting::Resumed;
}

}  // namespace desk
