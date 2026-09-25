# twitch-screen

Monorepo for the Twitch screen project: a round ESP32-driven desk display that
shows live Twitch stream stats and pops up follow / sub / raid / bits events.
Each top-level folder is an isolated project with its own tooling.

| Folder | What | Tooling |
|---|---|---|
| [`twitch-screen-relay/`](twitch-screen-relay) | Backend server — Twitch API ingest, pushes to the device over TCP | Scala 3 + Mill |
| [`twitch-screen-firmware/`](twitch-screen-firmware) | ESP32 firmware for the 240×240 round LCD | PlatformIO |
| [`twitch-screen-cad-design/`](twitch-screen-cad-design) | Parametric enclosure, print files and renders | FreeCAD + Blender |
| [`demo-server/`](demo-server) | Python stand-in from before the relay existed; superseded by `twitch.mode = simulated` | python3 (stdlib only) |

## How the pieces talk

The device holds one long-lived TCP connection and the server pushes frames to
it as newline-delimited JSON. The wire format is specified in
[`twitch-screen-firmware/docs/PROTOCOL.md`](twitch-screen-firmware/docs/PROTOCOL.md).

The relay implements the protocol, and also serves an HTTP management API on
port 8080 with Swagger UI at `/docs`. Its `twitch.mode = simulated` generates a
fixed script of follows, subs, raids and chat, so the firmware can be worked on
without Twitch credentials:

```sh
cd twitch-screen-relay && RELAY_TWITCH_MODE=simulated ./mill run
```

`demo-server/twitch_server.py` predates the relay and does the same job in
Python. It still works, but the relay's simulated mode exercises the real code
paths, so prefer it for firmware work.

See each folder's README for build instructions.

## Development setup

Run each project's commands from its own directory; there is no shared root
build. The firmware and simulator can run together without the Scala relay.

- **Simulator:** the relay in `simulated` mode, as above. The older Python
  stand-in (`python3 demo-server/twitch_server.py`) needs no third-party
  packages. Either way the ESP32 must be able to reach TCP port 8099 on this
  machine.
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

## Scala agent skills

Shared project skills live in [`.agents/skills/`](.agents/skills) and apply to
`twitch-screen-relay/`:

| Skill | Use |
|---|---|
| [`scala-code-quality`](.agents/skills/scala-code-quality/SKILL.md) | Scala 3 style, domain types, total functions, FP/OOP, and clean code |
| [`scala-fowler-refactoring`](.agents/skills/scala-fowler-refactoring/SKILL.md) | Fowler's refactorings, code smells, and behavior preservation |
| [`scala-vss-backend`](.agents/skills/scala-vss-backend/SKILL.md) | [VirtusLab Scala Stack](https://vss.virtuslab.com/), Ox, Tapir, and project integrations |
| [`scala-quality-tooling`](.agents/skills/scala-quality-tooling/SKILL.md) | Mill, Scalafmt, tests, and Scalafix/WartRemover integration policy |

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
