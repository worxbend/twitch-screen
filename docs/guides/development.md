# Development and verification

Each project has its own build directory. There is no root build that compiles the relay, firmware, and CAD together. Use the root-level Python checks to verify shared contracts, and use the component tools for compilation and tests.

For a first run, follow [your first simulated stream](first-simulated-stream.md). For the system model and ownership boundaries, read [architecture](../reference/architecture.md).

## Checkout and prerequisites

```sh
git clone https://github.com/worxbend/twitch-screen.git
cd twitch-screen
```

The first command creates the checkout; the second enters the repository root used by the root-level commands below. Existing checkouts can start there directly.

| Work | Tools | Version source |
|---|---|---|
| Relay | Bundled `./mill` launcher; internet for initial toolchain and dependency downloads | [build.mill](../../twitch-screen-relay/build.mill): Mill 1.1.9, Temurin JDK 25, Scala 3.9.0. |
| Firmware and native suites | Python 3, PlatformIO 6.1.18; a host C++ compiler for native tests | [platformio.ini](../../twitch-screen-firmware/platformio.ini) pins platform and library versions. CI uses Python 3.13 on Ubuntu 24.04. |
| Root checks | Python 3 standard library; dependency audit also uses Mill and reaches OSV | [tools/](../../tools). |
| Container checks | Docker Engine and Docker CLI; Compose for the supplied deployment file | [Dockerfile](../../twitch-screen-relay/Dockerfile), [compose.yaml](../../twitch-screen-relay/compose.yaml). |
| CAD regeneration | FreeCAD 1.1.3, Blender 5.2, `uv` for mesh-check dependencies | [CAD README](../../twitch-screen-cad-design/README.md) and scripts. |
| Firmware image asset regeneration | Python with Pillow | [make_glitch.py](../../twitch-screen-firmware/tools/make_glitch.py). |

These are the repository's configured or recorded tool versions, not a recommendation to substitute the newest available release. The first Mill or PlatformIO run downloads dependencies. Native checks can run without a connected board; ESP32 compilation needs a local credentials header, but it does not contact Wi-Fi or flash hardware.

Create the isolated PlatformIO environment from the repository root:

```sh
python3 -m venv .venv-pio
.venv-pio/bin/python -m pip install platformio==6.1.18
```

The first line creates an ignored environment; the second installs the same PlatformIO version used in CI. The firmware commands below use its explicit path so they do not depend on an unrelated global installation.

## Choose the check that proves the change

| Change | First checks | Additional evidence when relevant |
|---|---|---|
| Relay logic or HTTP behavior | Relevant MUnit suite, relay compilation, formatting check | Full relay suite before integration; simulated runtime for visible behavior. |
| TSB/3 bytes or semantics | Golden-vector drift check, relay protocol suites, firmware native suites | Real-socket relay suites, native sanitizer suite, ESP32 compilation. |
| Firmware parser, queue, or session | Native suite and native sanitizer suite | ESP32 compilation; authorized device observations for Wi-Fi and timing. |
| Display layout, colors, or assets | Presentation tests and ESP32 compilation | Physical GC9A01 rendering; host tests do not prove it. |
| Dependency or container change | Dependency audit, relay tests, Docker build and smoke | Real workload sizing beyond the smoke's 512 MiB limit. |
| Enclosure geometry or parameters | Export and all CAD geometry/mesh/assembly/wall checks | Inspect previews and slicer output, then measure the actual parts. |
| Documentation | Verify commands against their implementation and check relative links | Run the demonstrated local path when practical; distinguish observed results from examples. |

Run PlatformIO commands sequentially because they share package and build state. Prefer a focused suite while developing, then the relevant integration gates once the change is ready.

## Relay commands

Run these in `twitch-screen-relay/`:

| Command | Purpose |
|---|---|
| `./mill compile` | Compile application code with warnings treated as errors. |
| `./mill test` | Run the full MUnit suite, including real-socket device tests. |
| `./mill test.testOnly twitchscreen.relay.http.ApiSuite` | Run one named suite; replace the class with the relevant test. |
| `./mill mill.scalalib.scalafmt/checkFormatAll` | Check Scala formatting without changing files. |
| `./mill mill.scalalib.scalafmt/` | Format Scala sources; review the resulting diff. |
| `./mill run` | Run using the current environment and HOCON defaults. Management credentials are required. |
| `./mill --no-server run` | Run without a persistent Mill server, used by the local tutorial. |
| `./mill assembly` | Produce the application assembly used by the container build. |
| `./mill show resolvedRunMvnDeps` | Print resolved runtime artifacts for dependency inspection or auditing. |
| `./mill resolve _` | List available build targets when discovering a task. |
| `./mill inspect compile` | Inspect a particular build target. |
| `python3 tools/hash_management_password.py` | Prompt for a management password and emit its PBKDF2 verifier. |

The compiler enables `-Werror`, `-deprecation`, `-feature`, `-Wunused:all`, `-Wvalue-discard`, and `-Wnonunit-statement`. Scalafmt and MUnit are configured. Scalafix and WartRemover are not installed as build gates; skill guidance about them does not make them active checks.

Useful focused suites include:

| Area | Suite |
|---|---|
| Golden protocol bytes | `twitchscreen.relay.protocol.Tsb3GoldenVectorSuite` |
| Framing and partial input | `twitchscreen.relay.protocol.FrameReaderSuite` |
| Protocol field boundaries | `twitchscreen.relay.protocol.ProtocolBoundarySuite` |
| Real TCP sessions | `twitchscreen.relay.device.DeviceLinkSuite` |
| Slow-device behavior | `twitchscreen.relay.device.DeviceBackpressureSuite` |
| Lifecycle event/statistics ordering | `twitchscreen.relay.device.LifecycleOrderingSuite` |
| Application shutdown | `twitchscreen.relay.device.ApplicationShutdownSuite` |
| Management authentication | `twitchscreen.relay.http.ManagementAuthSuite` and `ManagementRoutesSuite` in the same package |
| Twitch recovery adapters | `twitchscreen.relay.twitch.TwitchRecoverySuite` |
| Webhook signatures and duplicate delivery | `twitchscreen.relay.twitch.EventSubWebhookSuite` and `WebhookDeduplicationSuite` in the same package |
| Configuration validation | `twitchscreen.relay.config.ConfigSuite` |

The test sources under [relay/test/src](../../twitch-screen-relay/test/src/twitchscreen/relay) are the full inventory. A scripted Twitch adapter test does not validate provider acceptance of a real account's application or grant.

For an interactive local relay, configure credentials and choose the simulated source:

```sh
export RELAY_HTTP_AUTH_API_TOKEN="$(openssl rand -hex 32)"
RELAY_TWITCH_MODE=simulated ./mill run
```

The first line creates a management token in the shell; the second selects the simulator for this run. The default listeners bind to all interfaces on ports 8080 and 8099. Use the [local tutorial](first-simulated-stream.md) for loopback-only operation or [relay setup](relay-setup.md) for LAN and persistent settings. Stop the foreground process with Ctrl+C.

New endpoint groups belong to their feature package, extend `ServerEndpoints`, use the synchronous `.handle` or `.handleSuccess` API, and are collected through [Apis.scala](../../twitch-screen-relay/src/twitchscreen/relay/Apis.scala). Select the appropriate management, public, or callback endpoint base in `Http`; unclassified endpoints fail assembly. Update documentation when an endpoint's input, output, access, or failure behavior changes.

## Firmware commands

Run these in `twitch-screen-firmware/`, after creating the root `.venv-pio` environment:

| Command | Purpose |
|---|---|
| `../.venv-pio/bin/pio test -e native` | Compile and run host tests for the codec, wire handling, link session, presentation, and deterministic parser fuzz cases. |
| `../.venv-pio/bin/pio test -e native -f test_link_session` | Run a single test directory while working on link behavior. |
| `../.venv-pio/bin/pio test -e native-sanitized` | Run native suites with AddressSanitizer and UndefinedBehaviorSanitizer; this is the Linux host gate used in CI. |
| `../.venv-pio/bin/pio run -e esp32dev` | Compile the ESP32 application. |
| `../.venv-pio/bin/pio run -e esp32dev -t upload` | Flash a connected board over USB; select the intended device before running. |
| `../.venv-pio/bin/pio device list` | List detected serial ports. |
| `../.venv-pio/bin/pio device monitor` | Open the serial monitor at the configured 115200 baud. |

Before the ESP32 build, create the local header only when absent:

```sh
test -f src/credentials.h || cp src/credentials.example.h src/credentials.h
```

`test -f` preserves an existing local configuration; `cp` creates the ignored header from the example only when needed. Set its Wi-Fi credentials and relay LAN address for a real board. CI copies the example into its fresh workspace purely to compile; its placeholder values do not connect a device.

The native build compiles the transport-independent codec and link client with a host compiler. Its custom runner treats a plain test program's exit status as the result. The ESP32 build adds Arduino, TFT_eSPI, LVGL, and the platform transport; application sources use `-Wall -Wextra -Werror`. [Firmware setup](firmware-setup.md) covers wiring configuration, device selection, and upload/monitor use.

`tools/build_version.py` and `tools/sanitizers.py` are PlatformIO build hooks, not standalone Python entry points. The first embeds a short Git revision and a dirty suffix in the firmware version; the second adds the sanitizer linker flags. Run them through their configured PlatformIO environment.

## Shared protocol vectors

The [protocol specification](../../twitch-screen-firmware/docs/PROTOCOL.md) is normative. Its 20 golden vectors are shared with the relay test literals and generated firmware header. From the repository root:

```sh
python3 tools/check_protocol_vectors.py
```

This extracts a fresh firmware header into a temporary directory, compares it with the committed header, and compares the relay's V1–V20 bytes with the specification. Success prints `All 20 TSB/3 vectors match the specification, relay and firmware.` It checks fixture agreement; run both implementations' test suites to verify codec behavior.

After an intentional reviewed change to normative vectors, regenerate the firmware header from `twitch-screen-firmware/`:

```sh
python3 test/test_proto_codec/gen_vectors.py docs/PROTOCOL.md test/test_proto_codec/vectors.h
```

The generator reads the first path and overwrites the second. Review that diff and the corresponding relay fixture changes, then rerun the root drift check and both protocol suites. Do not regenerate fixtures merely to hide an unexplained mismatch.

## Dependency audit

From the repository root:

```sh
python3 -m unittest discover -s tools/tests -v
python3 tools/audit_relay_dependencies.py
```

The first command tests the audit tool. The second resolves the relay's runtime dependencies with Mill, checks them against OSV, and applies the exact reviewed exceptions in [relay_dependency_exceptions.json](../../tools/relay_dependency_exceptions.json).

The audit returns `0` when all findings have valid exceptions, `1` for unexcepted advisories, and `2` when the scan or its inputs are incomplete or invalid. Network failures are not a clean audit. Exception records are constrained to a package, version, advisory, review date, expiry, owner, and reason; expired or stale exceptions fail validation. The currently recorded legacy exceptions expire on 2026-10-25; [the dependency assessment](../../tasks/review-dependencies.md) explains their disposition.

For a reusable input and full JSON report, first run this in the relay directory:

```sh
./mill show resolvedRunMvnDeps > /tmp/twitch-screen-runtime-deps.json
```

This writes the resolved runtime artifact list without starting the application. Then, from the repository root:

```sh
python3 tools/audit_relay_dependencies.py \
  --resolved /tmp/twitch-screen-runtime-deps.json \
  --output /tmp/twitch-screen-dependency-audit.json
```

`--resolved` supplies the saved resolution instead of invoking Mill; `--output` writes the full report. OSV is still queried. `--exceptions PATH` selects a different exception file for an explicitly reviewed audit input; it is not a general ignore switch.

## Container checks

Run from the repository root with a working Docker daemon:

```sh
docker build --check twitch-screen-relay
docker build -t twitch-screen-relay:review twitch-screen-relay
python3 tools/smoke_container.py twitch-screen-relay:review
```

The first command checks Dockerfile build configuration. The second builds the image. The third starts an isolated simulated container with a generated management token, ephemeral loopback ports, and a 512 MiB memory limit. It checks public/protected HTTP, credential redaction, a real TSB/3 `WELCOME`, and `SERVER_SHUTDOWN` BYE followed by EOF after SIGTERM. It removes its container in cleanup. Omitting the image argument uses `twitch-screen-relay:review`.

This smoke does not flash hardware, call live Twitch, or prove memory requirements for a sustained real workload. A healthy container also does not establish that an ESP32 can reach the host's LAN port.

For a persistent local Compose run, use `docker compose up --build` from `twitch-screen-relay/` with management credentials configured. The supplied Compose file selects simulated mode and publishes ports 8080 and 8099. `docker compose down` stops its services; the named token volume persists unless explicitly removed. Deployment configuration is covered in [relay setup](relay-setup.md).

## CAD and generated assets

The exported enclosure is versioned, so inspecting the design does not require regeneration. A geometry change should be followed by the complete pipeline from `twitch-screen-cad-design/`:

```sh
flatpak run --env=QT_QPA_PLATFORM=offscreen --command=FreeCADCmd org.freecad.FreeCAD -c 'import sys; sys.path.insert(0,"scripts"); import export_project; export_project.main()'
uv run --with trimesh --with numpy --with networkx python scripts/verify_exports.py
flatpak run --env=QT_QPA_PLATFORM=offscreen --command=FreeCADCmd org.freecad.FreeCAD -c 'import sys; sys.path.insert(0,"scripts"); import verify_assembly, verify_walls; verify_assembly.main(); verify_walls.main()'
blender -b --threads 8 --python scripts/render_previews.py
python3 scripts/package_project.py
```

- The first line loads the project scripts in FreeCAD and exports the solids, meshes, native documents, and geometry report.
- The second checks the written STL/3MF files using the mesh-analysis dependencies.
- The third runs assembly-clearance/recompute and wall-thickness checks inside FreeCAD.
- The fourth renders the previews using Blender.
- The fifth packages the gallery, manifest, and delivery archive from the generated outputs.

The [CAD README](../../twitch-screen-cad-design/README.md) explains the native FreeCAD command equivalent, output semantics, provisional measurements, and why script locations and filenames must stay stable. `pod_geometry.py`, `pod_document.py`, and `render_settings.py` support the pipeline; they are not additional command-line stages. Interactive recomputation uses the project-root `TwitchScreen.FCMacro` through FreeCAD's Macro menu.

To regenerate the firmware's RGB565 logo assets, use an environment with Pillow and run from `twitch-screen-firmware/`:

```sh
python3 tools/make_glitch.py
```

This overwrites `src/assets/twitch_glitch.h` and the 48- and 84-pixel PNG previews in `assets/`. Review generated image and header changes together. It does not alter the display driver or run a firmware build.

## What CI actually runs

[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) runs on pushes and pull requests, with independent jobs on Ubuntu 24.04. A newer run for the same workflow/ref cancels the older one.

| Job | Gates |
|---|---|
| `relay` | JDK 25 setup; `./mill compile`; audit-tool unit tests; resolved runtime dependency audit; `./mill test`; Scalafmt check. |
| `protocol` | Golden-vector drift check; Python syntax compilation for CAD scripts, firmware tools, and root tools. |
| `firmware` | Python 3.13 and PlatformIO 6.1.18; native tests; native sanitizer tests; copy example credentials into the fresh checkout; ESP32 compilation. |
| `container` | Docker build configuration check; build `twitch-screen-relay:ci`; run the container smoke against that image. |

The Python syntax gate can be reproduced from the repository root:

```sh
python3 -m compileall -q twitch-screen-cad-design/scripts twitch-screen-firmware/tools tools
```

This compiles Python syntax and writes ignored bytecode caches. It does not import FreeCAD, run Blender, execute PlatformIO hooks, or validate geometry.

CI does **not** run FreeCAD/Blender regeneration, print an enclosure, inspect a physical LCD, upload firmware, or obtain live Twitch consent. There is no configured Markdown/mdoc build. These limits explain why a passing CI run can coexist with an unverified physical fit or live-account setup.

## Keep a reviewable working tree

Run `git status --short` before and after a change. Local `.env` files, Twitch token data, `src/credentials.h`, `.venv-pio`, logs, and build outputs are ignored. Example credentials must remain placeholders. Preserve an existing local credentials header when following setup instructions.

CAD `output/` is intentionally versioned: geometry changes can produce a large but necessary deliverable diff. The generated release gallery, manifest, and ZIP are excluded. Review source, validation reports, and generated outputs together when changing geometry.

Repository-specific agent skills live under [`.agents/skills/`](../../.agents/skills). They document workflows and conventions; they do not replace the configured compiler, tests, or CI gates. Record the commands actually run and distinguish them from checks that require physical hardware or a real Twitch account.

Use [troubleshooting](troubleshooting.md) to investigate a failed stage, and return to the [guidebook](../README.md) for the complete setup path.
