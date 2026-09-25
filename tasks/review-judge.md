# Independent review and judge record

Reviewers inspect another author's changes. Local commits are checkpoints;
required findings must be resolved before the final push.

| Review | Required finding | Resolution |
|---|---|---|
| Root on deployment | Syntax validation missed missing `curl` in the clean JDK builder. | Added builder dependency; full image builds pass. |
| Root on relay auth | Synthetic routes did not establish complete real-route classification, OpenAPI OR semantics or credential rotation. | Security owner adding the real-route matrix and rotation coverage. |
| Root on firmware | Capacity pause preceded mandatory version check, delaying wrong-version EVENT teardown while full. | Reproduced with a failing regression, fixed guard ordering; root native/sanitized suites pass. |
| Root on firmware transport | Nonblocking socket mode did not guarantee nonblocking `close`; pinned lwIP can wait up to 20 seconds. | Single persistent close worker owns one descriptor; UI loop cannot allocate another socket until it finishes. ESP32 build and source review pass. |
| Root on webhook dedup | Arrival-time expiry could forget a delivery while its future signed timestamp remained fresh. | Security owner added timestamp-aware retention and inclusive replay-window endpoint regression. |
| Root on health transitions | CAS followed by a separate read could publish nonadjacent or reordered health transitions. | Security owner moved state/publication to an actor with atomic read snapshot. |
| Root on actual shutdown | Container SIGTERM interrupted writers before the Main finally block could drain BYE. | ApplicationLifetime keeps the nested service scope alive for bounded cleanup. Cancellation socket and hung-cleanup tests pass; actual Docker SIGTERM now produces BYE8 then EOF. |
| Firmware reviewer on OAuth | Refresh side effects could reinstall a credential after sign-out or new consent. | Security owner serializes token state, shared library handle and persistence; concurrency regression added. |
| Firmware reviewer on tracker | Concurrent transitions could publish END then START while the state was Offline. | Security owner serializes state transition and publication; concurrency regression added. |
| Firmware reviewer on subscriptions | Success of one subscription could clear another failure, and reconnect did not recreate dropped registrations. | Security owner implementing per-kind reconciliation and focused recovery tests. |
| Firmware reviewer on credentials | Header-invalid static bearer values could pass startup validation. | Security owner adding header-safe validation and regression. |
| Root on dependencies | The resolved Maven audit found Netty/Jawn advisories and two unpatched legacy dependencies. | Netty/Jawn patched; root independently reviewed scanner and exception boundaries, ran seven offline tests and the live 182-coordinate audit. Two exact legacy exceptions expire 2026-10-25. |

## Verdicts

- Firmware, repository automation, onboarding and CAD: independently approved
  in [judge-runtime-agent.md](judge-runtime-agent.md). Minor trailing whitespace
  was corrected during integration.
- Relay runtime: root reviewed atomic alert transitions, input bounds, socket
  lifecycle, EVENT-gap teardown, coherent lifecycle stats, diagnostics and
  scoped metrics. Required real-shutdown finding was corrected. Final combined
  tests and container verification remain the integration gate.
- Relay auth/Twitch: firmware agent's final verdict pending review corrections.
- Dependency follow-up: root approves the focused version fixes and audit implementation.
  Strict coordinate parsing, complete result pairing, pagination bounds and exact
  expiring exceptions were reviewed and independently tested. The legacy
  packages remain affected; [assessment and follow-up](review-dependencies.md)
  define the narrow exposure assumptions.

## Integrated evidence

- All five firmware suites pass in native and ASan/UBSan environments; 9,316
  assertions per environment. Isolated PlatformIO 6.1.18 recreated locally.
- ESP32 application build passes with `-Wall -Wextra -Werror`: 67,248 bytes
  static RAM and 1,151,877 bytes flash with local configuration. No flash.
- All 20 golden vectors agree across normative text, relay and firmware;
  213 prose field assertions checked. Deliberate vector drift was rejected.
- FreeCAD geometry measurements unchanged against the baseline; assembly and
  695-sample wall checks pass. Packaging verifies all previews and an 81-file
  archive. Discrete extraction sampling does not prove continuous clearance.
- CI workflow passes actionlint; skill validators and discovery links pass.
- Dependency audit passes with 182 coordinates, two explicit legacy exceptions
  and zero unexcepted matches. Seven offline scanner tests and updated actionlint pass.
- Final relay suite, final image smoke and remote CI pending.

See [review-status.md](review-status.md) for every finding's disposition.
No physical display, WiFi outage, watchdog reset, real Twitch subscription or
physical enclosure fit is claimed from host tests.
