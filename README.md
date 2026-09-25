# twitch-screen

Monorepo for the Twitch screen project: a round ESP32-driven desk display that
shows live Twitch stream stats and pops up follow / sub / raid / bits events.
Each top-level folder is an isolated project with its own tooling.

| Folder | What | Tooling |
|---|---|---|
| [`twitch-screen-relay/`](twitch-screen-relay) | Backend server — Twitch API ingest, pushes to the device over TCP | Scala 3 + Mill |
| [`twitch-screen-firmware/`](twitch-screen-firmware) | ESP32 firmware for the 240×240 round LCD | PlatformIO |
| [`twitch-screen-cad-design/`](twitch-screen-cad-design) | Parametric enclosure, print files and renders | FreeCAD + Blender |
| [`demo-server/`](demo-server) | Retirement notice for the incompatible NDJSON v2 simulator | python3 (stdlib only) |

## How the pieces talk

The device holds one long-lived TCP connection and the server pushes frames to
it using binary TSB/3 frames. The wire format is specified in
[`twitch-screen-firmware/docs/PROTOCOL.md`](twitch-screen-firmware/docs/PROTOCOL.md).

The relay implements the protocol, and also serves an HTTP management API on
port 8080 with Swagger UI at `/docs`. Its `twitch.mode = simulated` generates a
fixed script of follows, subs, raids and chat, so the firmware can be worked on
without Twitch credentials:

```sh
export RELAY_HTTP_AUTH_API_TOKEN="$(openssl rand -hex 32)"
cd twitch-screen-relay
RELAY_TWITCH_MODE=simulated ./mill run
```

Management endpoints require the configured Basic credentials or Bearer token,
including in simulated mode. Health, aggregate stats and `/docs` are public;
Twitch callbacks retain signature/state validation. See the [relay auth setup](twitch-screen-relay/README.md#management-authentication).

`demo-server/twitch_server.py` is retired: its NDJSON v2 protocol cannot talk to
TSB/3 firmware. The entry point exits with migration guidance.

See each folder's README for build instructions.

## Development setup

Run each project's commands from its own directory; there is no shared root
build. Use the Scala relay in simulated mode for firmware development.

- **Simulator:** the relay in `simulated` mode, as above. The ESP32 must be able
  to reach TCP port 8099 on this machine.
- **Firmware:** PlatformIO. Copy
  `twitch-screen-firmware/src/credentials.example.h` to `credentials.h` in the
  same directory, then set your WiFi credentials, server LAN address and device
  ID. Follow the [firmware instructions](twitch-screen-firmware/README.md) to
  build and flash.
- **Relay:** the included `./mill` launcher selects Mill 1.1.9 and Temurin JDK
  25. Initial setup requires internet access to download tooling and
  dependencies. Run `./mill test` and `./mill run` inside
  `twitch-screen-relay/`; Swagger UI is at `http://localhost:8080/docs`.
  `docker compose up --build` in that folder runs the same thing containerised.
- **CAD:** exported designs are included for inspection and printing. Rebuilding
  them requires the tools listed in the [CAD guide](twitch-screen-cad-design/README.md).

## Project agent skills

Shared project skills live in [`.agents/skills/`](.agents/skills) and cover the relay and firmware:

| Skill | Use |
|---|---|
| [`scala-code-quality`](.agents/skills/scala-code-quality/SKILL.md) | Scala 3 style, domain types, total functions, FP/OOP, and clean code |
| [`scala-fowler-refactoring`](.agents/skills/scala-fowler-refactoring/SKILL.md) | Fowler's refactorings, code smells, and behavior preservation |
| [`scala-vss-backend`](.agents/skills/scala-vss-backend/SKILL.md) | [VirtusLab Scala Stack](https://vss.virtuslab.com/), Ox, Tapir, and project integrations |
| [`scala-quality-tooling`](.agents/skills/scala-quality-tooling/SKILL.md) | Mill, Scalafmt, tests, and Scalafix/WartRemover integration policy |

Firmware skills: [`esp32-firmware-workflow`](.agents/skills/esp32-firmware-workflow/SKILL.md),
[`twitch-protocol-testing`](.agents/skills/twitch-protocol-testing/SKILL.md), and
[`gc9a01-display-debugging`](.agents/skills/gc9a01-display-debugging/SKILL.md).

Each skill has relative symlinks in `.claude/skills/`, `.kimi/skills/`,
`.kimi-code/skills/`, and `.codex/skills/`; edit the `.agents/skills/` source once.
Both Kimi directory names are provided for CLI versions using either convention.
Codex discovers the canonical `.agents/skills/` directory directly.

Invoke a skill explicitly with `$scala-code-quality` in Codex,
`/scala-code-quality` in Claude Code, or `/skill:scala-code-quality` in Kimi.
The descriptions also support automatic selection. The linting skill distinguishes
checks already configured in the build from integrations that still need adding.

## Versioned files

Source, plans, reference images, firmware assets and CAD deliverables are
versioned together. CAD `output/` is intentionally included so the design is
available without FreeCAD or Blender. Its generated release manifest, gallery
and delivery ZIP are excluded.

Build caches, IDE state, logs, local Python environments, `.env` files and the
firmware's `src/credentials.h` are ignored. Commit only placeholder credentials
in example files.

## Automated verification

[GitHub Actions](.github/workflows/ci.yml) runs relay compilation, tests and
formatting, firmware native/sanitizer tests and ESP32 compilation, protocol
vector drift checks, CAD script syntax checks and container build/smoke tests.
Physical display behavior, live Twitch credentials and printed fit require
separate validation. [Remediation evidence](tasks/todo.md) tracks review work.
