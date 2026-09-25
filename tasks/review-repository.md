# Repository, deployment and CAD review disposition

Baseline `a66a052`. GROOM by firmware/protocol agent and root; IMPLEMENT by root.
Independent REVIEW/JUDGE and combined validation are recorded at integration.

| IDs | Disposition and evidence |
|---|---|
| REPO-01, REPO-02 | Fixed onboarding and both firmware skills for TSB/3, current native suites, relay simulation, refuse-newest admission and management authentication. Both skill validators and all catalog symlink checks pass. |
| REPO-03, REPO-04, REPO-07 | Retired legacy server implementation; its entry point exits with migration guidance. No obsolete broadcast workers or unauthenticated trigger remain runnable. |
| REPO-05 | Added the three firmware skills to all four existing agent catalogs with relative symlinks. |
| REPO-06 | Retain deliberate CAD deliverable versioning. LFS/release migration changes the documented availability policy and is optional; no history rewriting or artifact removal. |
| RLY-04 | Dockerfile syntax check and full image build pass. Container smoke verifies public/protected HTTP, redaction, TSB/3 WELCOME and SIGTERM BYE8 then EOF under 512 MiB. |
| RLY-38, RLY-44 | Added `-Werror`, corrected JSON's build comment, removed hard-coded test counts, and configured CI compile/test/format gates. Baseline and initial integration compile cleanly with warnings fatal; actionlint 1.7.7 accepts the workflow. |
| RLY-39 | Optional linters/coverage deferred; no defect established and no migration required for these fixes. |
| RLY-40 | Pinned JDK/JRE OCI digests and all five supported Mill 1.1.9 launcher SHA-256 digests, checked both fresh/cached launchers, set JVM/container memory policy. Deliberately corrupted cached launcher is rejected. 512 MiB is a starting simulated-smoke budget, not a production load measurement. OS package repositories and transitive downloads still require ongoing supply-chain maintenance. |
| RLY-41 | Existing dependency versions retained. Full build and simulated startup test runtime linkage; they do not constitute a complete vulnerability audit. OTel instrumentation upgrade and transitive dependency audit remain follow-up work, with no specific CVE claimed by this review. |
| PROTO-08 | New root script regenerates/diffs firmware vectors and independently compares every relay vector against normative prose blocks. All 20 pass; a deliberate V16 byte mutation is rejected. CI runs it. |
| CAD-01, CAD-02 | Shared vent constructors/positions and cavity-profile formula. FreeCAD comparison confirms unchanged solid counts, volumes, surface areas and bounds for all returned geometry. Independent smaller slot probes and count assertions remain. |
| CAD-03 | Extraction checks now sample every 1 mm and report explicitly that continuous clearance is not proven. Assembly checks pass. Physical fit remains unverified. |
| CAD-04 | Editable USB test offsets the original value and restores USB/angle parameters in `finally`. Recompute/assembly checks pass. |
| CAD-05 | Empty wall sample set now raises a useful domain-specific diagnostic before `min`. Normal 695-sample wall validation passes with 1.5 mm minimum; an injected empty geometry produces the intended diagnostic. |
| CAD-06 | Explicit base/LCD hardware memberships replace loosely selected names; missing expected parts fail lookup. Assembly check passes. |
| CAD-07 | Removed confirmed unused imports from reviewed scripts; Python syntax passes. |
| CAD-08 | Shared preview size used by Blender and packaging. Existing preview dimensions retained; packaging validation passes for all previews and an 81-file delivery archive. |

## Workflow sources

CI actions use full commit pins and read-only repository permissions, following
[GitHub's secure-use guidance](https://docs.github.com/en/actions/reference/security/secure-use).
JDK setup follows the [official setup-java action](https://github.com/actions/setup-java).
Launcher digests were computed from the published Maven Central 1.1.9 artifacts;
image digests were resolved from the official `eclipse-temurin` OCI manifests.

## Validation limits

- FreeCAD 1.1.3 ran headless through the installed Flatpak. Its direct launcher
  initially terminated with the long command invocation; invoking FreeCADCmd
  through the Flatpak shell with `QT_QPA_PLATFORM=offscreen` worked.
- CAD geometry was compared before/after, and assembly/wall checks ran against
  committed STEP/FCStd artifacts. No new render or physical print was required.
- GitHub CI execution and final integration after the Twitch/dependency slices
  remain pending; local commands are not represented as remote CI success.
