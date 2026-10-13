# Agent instructions

## Scope and ownership

Inspect the current Git status and relevant source before editing. Preserve unrelated in-flight changes, private configuration and local evidence. This is a monorepo with separate builds, not a root Mill project. Keep changes focused; update behavior tests and public instructions together.

Never read or publish local `.env` files, firmware `src/credentials.h`, provider tokens or credential-bearing logs. Safe example files may be inspected. Do not commit build outputs, `.agent-workflow/`, configured firmware images or downloaded media.

## Verification

The source of truth for automated gates is `.github/workflows/ci.yml`; product-site checks are in `.github/workflows/pages-build.yml`. The commands and prerequisites are documented in `docs/guides/development.md`.

- Relay: run Mill inside `twitch-screen-relay/`. Use `./mill --no-server -j 1 test.testForked` for a fresh full test run, plus compilation and Scalafmt checks. Preserve pinned toolchain and dependency versions.
- Firmware: run PlatformIO inside `twitch-screen-firmware/`, sequentially across native, native-sanitized, native-lvgl, native-lvgl-base and native-generic. Build the native runtime peer for application harnesses and compile esp32dev. Do not overwrite an existing credentials header.
- Shared contracts: run root Python unit tests, `tools/check_protocol_vectors.py` and `tools/check_desk_contract.py`, plus relay-tool tests and panel Node tests.
- Companion: use a hash-locked isolated environment; test source and installed wheel, package membership and configuration CLI.
- Site: run site unit tests, static build and artifact verification; browser checks require the separately pinned test-only browser.
- Containers/integration: use test-owned processes, ports, state and synthetic inputs, never an existing user deployment. Keep failure evidence private and bounded.

Report actual results and missing gates. Host rendering tests and ESP32 compilation do not verify physical pixels, real Twitch permissions, printed fit or complete product acceptance. Flashing a board, deploying services or publishing Pages requires separate authorization.

## Git and release safety

Commit and push only when requested. Stage intended paths explicitly, inspect the staged diff and scan for secrets without printing detected values. Fetch and check upstream before publication; do not force-push, rewrite unrelated commits or bypass branch protection. Do not rebase a dirty working tree or hide changes in an implicit stash.

Follow `CHANGELOG.md`, `LICENSE.md` and `THIRD_PARTY_NOTICES.md` for release scope. Source-only previews are distinct from binary/product acceptance. Do not bundle firmware binaries, historical media or CAD deliverables while redistribution-rights gates remain unresolved.

Create an annotated new version tag only after the exact commit passes release gates. Never move a published tag. Push the intended branch and exact tag without force (atomically when both are new), and read back the remote branch/tag identities and CI status before claiming success. A GitHub prerelease must explicitly be a prerelease, not latest, and verify the existing remote tag. Tagging does not authorize Pages deployment.
