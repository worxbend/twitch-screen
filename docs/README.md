<div align="center">
<h1>📖 The Twitch Screen guidebook</h1>
<p><strong>From “cute little screen” to “I built that.” ✨</strong><br>
A home for streamers, makers, and the people who read the source for fun.</p>
<p><a href="../README.md">🏠 Project overview</a> · <a href="guides/first-simulated-stream.md">🚀 First run</a> · <a href="guides/troubleshooting.md">🧯 Get unstuck</a></p>
</div>

Twitch Screen has three pieces: a relay running on a computer, ESP32 firmware driving a round LCD, and a printable enclosure. You can learn the relay first without buying parts or registering a Twitch app.

## 🧭 Choose your starting point

| Your starting point | Your path |
| :--- | :--- |
| **Curious, no hardware yet** | [First simulated stream](guides/first-simulated-stream.md) → [Architecture](reference/architecture.md) |
| **Building from scratch** | [Parts & assembly](guides/device-build.md) → [Relay setup](guides/relay-setup.md) → [Firmware setup](guides/firmware-setup.md) → [Twitch app setup](guides/twitch-app-setup.md) |
| **Already have an assembled device** | [Relay setup](guides/relay-setup.md) → [Firmware setup](guides/firmware-setup.md) → [Twitch app setup](guides/twitch-app-setup.md) → [Everyday use](guides/device-use.md) |
| **Developing or contributing** | [Developer guide](guides/development.md) → [Architecture](reference/architecture.md) → [Contribution guide](../CONTRIBUTING.md) |

## 🛠️ The complete build journey

Follow these milestones in order. Each guide supplies the detailed steps and checks.

| Step | Guide | Checkpoint before moving on |
| :--- | :--- | :--- |
| **1 · Meet the software** 🧪 | [First simulated stream](guides/first-simulated-stream.md) | Relay health responds; simulated stats and activity appear |
| **2 · Gather and measure** 📏 | [Device build](guides/device-build.md) | Your actual ESP32, LCD, connector, and screws match the intended fit |
| **3 · Wire on the bench** 🔌 | [Wiring and assembly](guides/device-build.md) | Unpowered connections match the firmware pin map |
| **4 · Host the relay** 🖥️ | [Relay setup](guides/relay-setup.md) | Management auth works and TCP 8099 is reachable from your trusted LAN |
| **5 · Configure and flash** ⚡ | [Firmware setup](guides/firmware-setup.md) | Board joins Wi-Fi and receives simulated stats/cards |
| **6 · Print and close up** 🖨️ | [Printing and assembly](guides/device-build.md) | Fit, cable routing, and USB access pass a real inspection |
| **7 · Bring your channel** 🟣 | [Twitch app setup](guides/twitch-app-setup.md) | App registration, callback, and broadcaster consent succeed |
| **8 · Stream with it** 🎉 | [Everyday use](guides/device-use.md) | You understand live/offline states, cards, and recovery |

Test the electronics before closing the enclosure. CAD validation and a render cannot prove that your particular clone board fits.

## 📚 Guides by task

| Guide | What you’ll find |
| :--- | :--- |
| [First simulated stream](guides/first-simulated-stream.md) | A linear, annotated lesson using only your computer |
| [Register and authorize a Twitch app](guides/twitch-app-setup.md) | Developer Console, client credentials, exact redirect URL, scopes, consent, and optional webhook setup |
| [Configure and run the relay](guides/relay-setup.md) | Native and Docker startup, authentication, network exposure, storage, and operations |
| [Build the device](guides/device-build.md) | Bill of materials, wiring, measurements, printing, assembly, and fit checks |
| [Configure and flash firmware](guides/firmware-setup.md) | PlatformIO, Wi-Fi credentials, device identity, upload, serial monitor, and display checks |
| [Use your screen](guides/device-use.md) | What appears on screen, notification behavior, restarts, and everyday care |
| [Develop the project](guides/development.md) | Repository layout, toolchains, tests, quality gates, and CAD regeneration |
| [Troubleshoot a problem](guides/troubleshooting.md) | Symptom → diagnostic check → next action |

## 🔎 Reference shelf

| Reference | Use it when… |
| :--- | :--- |
| [Relay configuration](reference/relay-configuration.md) | You need a setting’s exact name, default, or environment override |
| [HTTP API](reference/http-api.md) | You need endpoints, authentication, request bodies, and response behavior |
| [Architecture](reference/architecture.md) | You want to understand event flow, ownership, and system limits |
| [TSB/3 wire protocol](../twitch-screen-firmware/docs/PROTOCOL.md) | You are changing or implementing a device/relay protocol peer |
| [CAD measurement checklist](../twitch-screen-cad-design/docs/measurement_checklist.md) | You need to confirm dimensions before printing |
| [CAD completion audit](../twitch-screen-cad-design/docs/completion_audit.md) | You want the scope and limits of mechanical validation |
| [Documentation decisions](decisions/README.md) | You are changing how this guidebook is organized |

## 🧑‍💻 Component workbenches

**[Relay README](../twitch-screen-relay/README.md)** · **[Firmware README](../twitch-screen-firmware/README.md)** · **[CAD README](../twitch-screen-cad-design/README.md)**

The root README is the tour. This guidebook is the route map. Component READMEs are the workbench notes you want beside a terminal or soldering station.

## 🧾 About this documentation

The guides describe the checked-in implementation. Historical plans and review reports remain available, but current source and configuration win when they disagree. Configuration defaults and third-party setup instructions were reviewed on **2026-09-25**; official Twitch links are included where the provider’s requirements matter.

The [documentation maintenance guide](maintaining-documentation.md) explains source checking, visuals, tone, and review. [Documentation validation notes](../tasks/docs-quality-review.md) record what was verified and what still requires a real device or Twitch account.
