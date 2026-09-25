# Consolidated finding status

All 121 register items were reassessed against application baseline `a66a052`.
`Addressed` means the scoped implementation or clarification has evidence in the linked report;
it does not imply physical hardware or live-provider acceptance. Independent judge and
combined validation are recorded in [review-judge.md](review-judge.md).

| Finding | Disposition | Evidence / limitation |
|---|---|---|
| RLY-01 | Addressed | [review-security](review-security.md) |
| RLY-02 | Addressed | [review-security](review-security.md) |
| RLY-03 | Addressed | [review-security](review-security.md) |
| RLY-04 | Addressed | [review-repository](review-repository.md) |
| RLY-05 | Addressed | [review-security](review-security.md) |
| RLY-06 | Addressed | [review-security](review-security.md) |
| RLY-07 | Addressed | [review-runtime](review-runtime.md) |
| RLY-08 | Addressed | [review-security](review-security.md) |
| RLY-09 | Addressed | [review-runtime](review-runtime.md) |
| RLY-10 | Addressed | [review-security](review-security.md) |
| RLY-11 | Addressed | [review-security](review-security.md) |
| RLY-12 | Addressed | [review-runtime](review-runtime.md); [HTTP/security regressions](review-security.md). Focused behavior coverage. |
| RLY-13 | Addressed | [review-runtime](review-runtime.md) |
| RLY-14 | Addressed | [review-runtime](review-runtime.md) |
| RLY-15 | Addressed | [review-runtime](review-runtime.md) |
| RLY-16 | Addressed | [review-runtime](review-runtime.md) |
| RLY-17 | Addressed | [review-runtime](review-runtime.md) |
| RLY-18 | Addressed | [review-runtime](review-runtime.md) |
| RLY-19 | Addressed | [review-security](review-security.md) |
| RLY-20 | Addressed | [review-security](review-security.md) |
| RLY-21 | Addressed | [review-security](review-security.md) |
| RLY-22 | Addressed | [review-runtime](review-runtime.md) |
| RLY-23 | Addressed | [review-security](review-security.md) |
| RLY-24 | Addressed | [review-security](review-security.md) |
| RLY-25 | Addressed | [review-security](review-security.md) |
| RLY-26 | Addressed | [review-security](review-security.md) |
| RLY-27 | Addressed | [review-runtime](review-runtime.md) |
| RLY-28 | Addressed | [review-runtime](review-runtime.md) |
| RLY-29 | Addressed | [review-runtime](review-runtime.md) |
| RLY-30 | Addressed | [review-runtime](review-runtime.md) |
| RLY-31 | Addressed | [review-runtime](review-runtime.md) |
| RLY-32 | Addressed | [review-runtime](review-runtime.md) |
| RLY-33 | Addressed | [review-runtime](review-runtime.md) |
| RLY-34 | Addressed | [review-runtime](review-runtime.md) |
| RLY-35 | Addressed | [review-security](review-security.md) |
| RLY-36 | Addressed | [review-runtime](review-runtime.md) |
| RLY-37 | Addressed | [review-runtime](review-runtime.md); [HTTP/security regressions](review-security.md). Focused behavior coverage. |
| RLY-38 | Addressed | [review-repository](review-repository.md) |
| RLY-39 | Deferred optional | Optional lint/coverage adoption has no required migration. [review-repository](review-repository.md) |
| RLY-40 | Addressed | [review-repository](review-repository.md) |
| RLY-41 | Audit remediation in progress | Resolved-dependency advisories are being remediated and assessed. [review-repository](review-repository.md) |
| RLY-42 | Retained behavior | Initial live announcement initializes stats and is explicitly tested. [review-security](review-security.md) |
| RLY-43 | Addressed | [review-security](review-security.md) |
| RLY-44 | Addressed | [review-repository](review-repository.md) |
| RLY-45 | Addressed | [review-runtime](review-runtime.md) |
| RLY-46 | Addressed | [review-runtime](review-runtime.md) |
| RLY-47 | Addressed | [review-runtime](review-runtime.md) |
| RLY-48 | Addressed | [review-security](review-security.md) |
| RLY-49 | Addressed | [review-security](review-security.md) |
| RLY-50 | Addressed | [review-runtime](review-runtime.md) |
| RLY-51 | Addressed | [review-runtime](review-runtime.md) |
| RLY-52 | Addressed | [review-runtime](review-runtime.md) |
| RLY-53 | Refuted on current dependency | Real HTTP regression confirms the pinned handler already emits safe JSON 500. [review-security](review-security.md) |
| RLY-54 | Addressed | [review-security](review-security.md) |
| RLY-55 | Deferred optional | Existing isolated session randomness needs no deterministic override in current tests. [review-runtime](review-runtime.md) |
| RLY-56 | Addressed | [review-security](review-security.md) |
| RLY-57 | Addressed | [review-security](review-security.md) |
| RLY-58 | Deferred optional | Machine-readable API codes remain an optional compatible enhancement. [review-security](review-security.md) |
| RLY-59 | Addressed | [review-runtime](review-runtime.md) |
| RLY-60 | Addressed | [review-runtime](review-runtime.md) |
| FW-01 | Addressed | [review-firmware](review-firmware.md) Physical-device acceptance remains outstanding. |
| FW-02 | Addressed | [review-firmware](review-firmware.md) Physical-device acceptance remains outstanding. |
| FW-03 | Addressed | [review-firmware](review-firmware.md) Physical-device acceptance remains outstanding. |
| FW-04 | Addressed | [review-firmware](review-firmware.md) Physical-device acceptance remains outstanding. |
| FW-05 | Addressed | [review-firmware](review-firmware.md) Physical-device acceptance remains outstanding. |
| FW-06 | Addressed | [review-firmware](review-firmware.md) |
| FW-07 | Addressed | [review-firmware](review-firmware.md) |
| FW-08 | Addressed | [review-firmware](review-firmware.md) |
| FW-09 | Addressed | [review-firmware](review-firmware.md) |
| FW-10 | Deferred policy/design | Preserve the frozen retry floor; a cap needs protocol policy. [review-firmware](review-firmware.md) |
| FW-11 | Addressed | [review-firmware](review-firmware.md) |
| FW-12 | Addressed | [review-firmware](review-firmware.md) |
| FW-13 | Addressed | [review-firmware](review-firmware.md) |
| FW-14 | Addressed | [review-firmware](review-firmware.md) Physical-device acceptance remains outstanding. |
| FW-15 | Addressed | [review-firmware](review-firmware.md) Physical-device acceptance remains outstanding. |
| FW-16 | Addressed | [review-firmware](review-firmware.md) Physical-device acceptance remains outstanding. |
| FW-17 | Addressed | [review-firmware](review-firmware.md) |
| FW-18 | Deferred optional | OTA requires separate partition, authentication and rollback requirements. [review-firmware](review-firmware.md) |
| FW-19 | Addressed | [review-firmware](review-firmware.md) |
| FW-20 | Addressed | [review-firmware](review-firmware.md) |
| FW-21 | Addressed | [review-firmware](review-firmware.md) |
| FW-22 | Addressed | [review-firmware](review-firmware.md) |
| FW-23 | Addressed | [review-firmware](review-firmware.md) |
| FW-24 | Addressed | [review-firmware](review-firmware.md) |
| FW-25 | Addressed | [review-firmware](review-firmware.md) |
| FW-26 | Addressed | [review-firmware](review-firmware.md) |
| FW-27 | Addressed | [review-firmware](review-firmware.md) |
| FW-28 | Addressed | [review-firmware](review-firmware.md) |
| PROTO-01 | Addressed | [review-firmware](review-firmware.md) |
| PROTO-02 | Deferred policy/design | Restart sequence ownership needs persistence or a new epoch contract; current limitations documented. [review-firmware](review-firmware.md) |
| PROTO-03 | Addressed | [review-firmware](review-firmware.md); [relay runtime](review-runtime.md) |
| PROTO-04 | Addressed | [review-firmware](review-firmware.md); [relay runtime](review-runtime.md) |
| PROTO-05 | Addressed | [review-firmware](review-firmware.md); [relay runtime](review-runtime.md) |
| PROTO-06 | Addressed | [review-firmware](review-firmware.md); [relay runtime](review-runtime.md) |
| PROTO-07 | Addressed | [review-firmware](review-firmware.md); [relay runtime](review-runtime.md) |
| PROTO-08 | Addressed | [repository](review-repository.md): automated 20-vector drift check. |
| PROTO-09 | Addressed | [review-firmware](review-firmware.md) |
| PROTO-10 | Deferred policy/design | Unknown startup statistics need a negotiated representation or greeting change. [review-firmware](review-firmware.md) |
| PROTO-11 | Deferred policy/design | Replay age cutoff needs an explicit delivery/presentation policy. [review-firmware](review-firmware.md) |
| PROTO-12 | Addressed | [review-firmware](review-firmware.md) |
| PROTO-13 | Addressed | [review-firmware](review-firmware.md); [relay runtime](review-runtime.md) |
| PROTO-14 | Addressed | [review-firmware](review-firmware.md) |
| PROTO-15 | Addressed | [review-firmware](review-firmware.md); [relay runtime](review-runtime.md) |
| PROTO-16 | Addressed | [review-firmware](review-firmware.md); [relay runtime](review-runtime.md) |
| PROTO-17 | Addressed | [review-firmware](review-firmware.md) |
| PROTO-18 | Addressed | [review-firmware](review-firmware.md); [relay runtime](review-runtime.md) |
| REPO-01 | Addressed | [review-repository](review-repository.md) |
| REPO-02 | Addressed | [review-repository](review-repository.md) |
| REPO-03 | Addressed | [review-repository](review-repository.md) |
| REPO-04 | Addressed | [review-repository](review-repository.md) |
| REPO-05 | Addressed | [review-repository](review-repository.md) |
| REPO-06 | Retained policy | Keep deliberately versioned CAD deliverables; no history rewrite. [review-repository](review-repository.md) |
| REPO-07 | Addressed | [review-repository](review-repository.md) |
| CAD-01 | Addressed | [review-repository](review-repository.md) |
| CAD-02 | Addressed | [review-repository](review-repository.md) |
| CAD-03 | Addressed | [review-repository](review-repository.md) |
| CAD-04 | Addressed | [review-repository](review-repository.md) |
| CAD-05 | Addressed | [review-repository](review-repository.md) |
| CAD-06 | Addressed | [review-repository](review-repository.md) |
| CAD-07 | Addressed | [review-repository](review-repository.md) |
| CAD-08 | Addressed | [review-repository](review-repository.md) |
