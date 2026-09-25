# 💜 Contributing to Twitch Screen

Thanks for helping make this little screen easier to build, use, and understand. A clear bug report, a measured hardware fit, or a corrected setup instruction is useful work.

## Choose a workbench

| Area | Start with | Main tools |
| :--- | :--- | :--- |
| Relay, Twitch integration, HTTP API | [Developer guide](docs/guides/development.md), [relay README](twitch-screen-relay/README.md) | Mill, Scala 3, JDK 25 |
| Firmware, display, device protocol | [Firmware README](twitch-screen-firmware/README.md), [TSB/3 specification](twitch-screen-firmware/docs/PROTOCOL.md) | PlatformIO, C++ |
| Enclosure and print fit | [CAD README](twitch-screen-cad-design/README.md), [measurement checklist](twitch-screen-cad-design/docs/measurement_checklist.md) | FreeCAD, Blender |
| Tutorials and documentation | [Guidebook](docs/README.md), [documentation maintenance](docs/maintaining-documentation.md) | Markdown and the source being documented |

## Before changing things

1. Read the relevant guide and source. Historical plans can describe behavior that has since changed.
2. For a large change, open an [issue](https://github.com/worxbend/twitch-screen/issues) describing the user problem and intended behavior.
3. Create a short-lived branch from the branch your change targets.
4. Keep unrelated fixes and formatting in separate changes.

No `LICENSE` file is currently checked in. This guide documents the contribution workflow; it does not select a project license.

## Make the change reviewable

Use one clear purpose per pull request. Explain the problem, resulting behavior, and how you checked it. For a bug, include the smallest reproduction you can provide. For a behavior change, add a meaningful regression test at the layer that owns it.

For a UI change, include photos or captures and identify the actual board/display. For CAD, include parameter changes, regenerated deliverables, verification results, and any measured print-fit observations. Label renders as renders.

Update user-facing instructions alongside a behavior or configuration change. Keep the wire specification and shared vectors synchronized for protocol changes. Describe consequential design decisions using the existing [ADR convention](docs/decisions/README.md).

## Run the relevant checks

The [developer guide](docs/guides/development.md) has prerequisites and complete commands. The [CI workflow](.github/workflows/ci.yml) is the source of truth for automated gates.

| Change | Verification to include |
| :--- | :--- |
| Relay | Compilation, MUnit tests, Scalafmt check; dependency audit when dependency resolution changes |
| Firmware | Native tests, Linux sanitizer tests, ESP32 build; real display checks for rendering changes |
| Protocol | Shared vector drift check plus both protocol implementations’ tests |
| Container | Build check, image build, existing smoke test |
| CAD | Script syntax plus geometry/export/assembly/wall checks when geometry changes; physical measurements when available |
| Documentation | Relative links and anchors, image alt text, command syntax, source-backed defaults, and a focused runnable walkthrough where possible |

Say exactly what you ran and what remains untested. A compiled firmware image does not prove a display looks correct. Geometry checks do not prove printed fit. A simulator does not prove real Twitch permissions.

## Keep local and private files local

Never commit Wi-Fi credentials, Twitch tokens, client secrets, password hashes used by a deployment, or management tokens. Use placeholders in examples. Redact logs and screenshots before sharing them.

The repository ignores local `.env` files, firmware `src/credentials.h`, build caches, virtual environments, and logs. CAD deliverables under `output/` are intentionally versioned; the generated gallery, manifest, and delivery ZIP are excluded. See [.gitignore](.gitignore) and each component’s ignore rules.

## Report a problem with evidence

Include the commit, operating system, component, command, expected result, and actual result. Firmware reports should include board/display identification, wiring, and a redacted serial log. Relay reports should name the mode (`disabled`, `simulated`, or `live`) and redact all authorization headers and tokens.

For a security-sensitive report, avoid posting credentials in a public issue. Use GitHub’s private vulnerability reporting control when it is available on the repository, or first ask the maintainer for a private reporting channel without including sensitive material.

## Agent-assisted contributions

Project skills live in [`.agents/skills/`](.agents/skills). They cover Scala quality, architecture and tooling, firmware workflow, protocol testing, and display debugging. Compatibility symlinks are provided under `.claude/skills/`, `.kimi/skills/`, `.kimi-code/skills/`, and `.codex/skills/`; edit the canonical source once.

Use the relevant skills, inspect the current source, and respect the requested scope. Documentation work should not silently alter firmware, runtime configuration, or deployment state. Verify generated text and commands just as carefully as code.
