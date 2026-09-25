# Consolidated review remediation

Baseline: `a66a052`, including the existing Twitch OAuth work. Input:
[`REVIEW_CONSOLIDATED.md`](../REVIEW_CONSOLIDATED.md). Original source reports
referenced by the input are not present in this checkout.

## Workflow

1. **GROOM:** independently reassess every register item against current code.
   Distinguish confirmed defects, existing fixes, optional enhancements and
   changes that require a new protocol policy. Record evidence by finding ID.
2. **IMPLEMENT:** use isolated worktrees for relay security/Twitch, relay
   runtime and firmware. Root owns repository automation, onboarding and CAD.
   Add regression coverage for behavior changes and keep commits focused.
3. **REVIEW/JUDGE:** rotate reviewers away from their own implementations;
   require concrete findings and a verdict. Fix required findings and rerun
   affected checks before integration.
4. Integrate, run combined checks, record unresolved validation limits, commit
   and push `fix/consolidated-review-20260925` without rewriting existing history.

## Scope and decisions

- Apply the review's explicit Basic-or-Bearer management authorization policy,
  including public callback exceptions and fail-closed configuration.
- Prioritize deployment, liveness, recovery and reproduced API failures.
- Preserve TSB/3 frozen fields and informational ACK semantics unless the user
  selects a coordinated protocol change. Compatible recovery fixes may proceed.
- Optional tooling, OTA, DMA and API enhancements need an actual benefit to
  justify adoption; document disposition instead of treating them as defects.
- Preserve committed CAD deliverables. Geometry changes require geometry
  validation; syntax checks alone do not establish physical fit.

## Workstreams and acceptance

| Workstream | Acceptance | Verification | Dependencies |
|---|---|---|---|
| Relay access/Twitch | Protected operations require either credential; public callbacks retain their own verification; initialization and redelivery recover safely | HTTP, config, Twitch regression suites; full relay suite; container smoke | Current OAuth baseline |
| Relay runtime | Bounded socket lifecycle; typed input errors; correct alert/metrics/store transitions; protocol encoders respect bounds | Focused MUnit and real socket regressions, full suite, compile/format | Coordinate shared config/wiring with access stream |
| Firmware/protocol | Responsive UI ownership and bounded networking; compatible overflow handling; clear receiver rules | Native suites, sanitizers, ESP32 compilation, golden-vector drift; hardware limits recorded | Cross-component protocol agreement |
| Repository/tooling | TSB/3 relay simulation is the supported setup; repeatable automated gates; retired demo cannot mislead users | Document/command checks, CI-equivalent local runs | New auth configuration and firmware toolchain |
| CAD | Shared geometry remains equivalent; validation reports useful failures and explicit sampling limits | Python syntax; installed FreeCAD geometry/assembly/wall checks where available | Preserve artifact policy |

## Risks

- Wire-compatible TSB/3 has finite replay and no durable exactly-once delivery.
- Real Twitch, physical ESP32 behavior and physical CAD fit require external
  validation; host success must not be reported as hardware verification.
- PlatformIO's existing local environment is broken and must be recreated in
  isolation. Never run concurrent PlatformIO installs/builds.
- Agents must coordinate shared files and rotate review ownership; root alone
  integrates and pushes.

Task state and evidence are maintained in [todo.md](todo.md) and per-stream
review reports in this directory.
