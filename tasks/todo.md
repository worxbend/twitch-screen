# Review remediation tasks

- [x] Groom every consolidated finding and record its disposition by ID.
- [x] Implement and validate relay management access and Twitch fixes.
- [x] Implement and validate relay session/API/alert/observability fixes.
- [x] Implement and validate compatible firmware/protocol fixes.
- [x] Repair onboarding, retire the old demo and configure repeatable gates.
- [x] Address CAD maintenance findings and run available geometry checks.
- [x] Independent review/judge for each implementation stream; resolve required findings.
- [x] Integrate and run combined relay, firmware, protocol and deployment checks.
- [x] Record remaining policy/environment/hardware limits accurately.
- [x] Commit and push the reviewed branch; verify remote commit.

All 121 findings are accounted for in [review-status.md](review-status.md).
Independent verdicts and integrated verification are in [review-judge.md](review-judge.md).
The push and remote commit are verified by the orchestrator; GitHub Actions
results are available from the branch run linked in the judge report.

## Round 2

Round 2 work was committed directly to `main`. Evidence is in
[review-round2-relay.md](review-round2-relay.md) and
[review-round2-firmware.md](review-round2-firmware.md), and each finding's
disposition is in [review-status.md](review-status.md).

- [x] Relay: RLY-03, RLY-11, RLY-08, RLY-43, PROTO-03, PROTO-14/16, PROTO-18,
      RLY-25/16, RLY-21, RLY-41, RLY-12/37, RLY-55. Each was implemented and independently reviewed.
- [x] Firmware/protocol: FW-01, FW-09, PROTO-09, FW-08/24. Each was implemented and independently reviewed.
- [x] Integrated validation on `origin/main` f9132c8:
  - Relay compile with `-Werror` passed. Two full test runs: 33 suites and 371 tests passed on the rerun. The first run had one failure, in the known load-sensitive LifecycleOrderingSuite flake (RLY-50). scalafmt check passed.
  - Firmware native and ASan/UBSan suites passed 10/10. The ESP32 build passed (67,248 B RAM, 1,151,877 B flash).
  - All 20 golden vectors matched. Tool tests passed 7/7.
  - Dependency audit: 181 coordinates, 2 advisories, both covered by exceptions, 0 unexcepted.
  - Docker build and the 512 MiB container smoke passed.
  - actionlint was not run because it is not installed on this host, and there were no workflow changes in this round. CAD checks were not run because FreeCAD is not available and there were no CAD changes.
- [ ] User decision, left unchanged: RLY-42, RLY-58, FW-10, FW-18, PROTO-02,
      PROTO-10, PROTO-11, REPO-06.
- [ ] Follow-ups: the RLY-50 LifecycleOrderingSuite/port flake; the RLY-25 idle-timeout
      15 s limit; the RLY-12 JSON 400 body check through the real server; the
      possible parallel-suite flake in the ApiSuite pageSize test; RLY-08 does not clear
      pushedEndStartedAt.
- [x] Agent instruction to always commit and push to `main` is in AGENTS.md.

## Kimi round 3 follow-ups

Round 3 dispositions and integrated validation are in
[review-kimi-summary.md](review-kimi-summary.md#round-3-ultracode-swarm).

- [ ] Flakes seen in lane runs, none in the integrated run: the K-017
      trickle-body test in `ManagementAuthSuite`; the `DeviceLinkSuite`
      `bytesSent` read-after-write race; `SequenceExhaustionSuite:134`; one
      firmware `test_presentation` error that passed on rerun.
- [ ] Hardware: K-109 needs a stack high-water measurement on a real board
      under worst-case load. K-118 needs an on-device check that a routine
      card's hold no longer invalidates the full panel.
- [ ] Weak regression tests: K-011 is proven only by a pure test; K-086 shows
      only as a timing change, because a 2 s backstop still closes the socket;
      K-025/K-107/K-118 display glue is not built on the host.
- [x] Owner decision K-002 (H2): keep the trusted-home-LAN model; no
      device-link authentication or TLS (decided 2026-09-25).
- [ ] User decision, left unchanged: K-177 (FW-10 BYE retry cap) and K-180
      (FW-18 OTA). See
      [ADR-0002](../docs/decisions/0002-device-link-trust-boundary.md).
- [ ] Owner action: K-169 is a stale comment in the owner's untracked
      `src/credentials.h`.
