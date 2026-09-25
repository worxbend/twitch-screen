# Consolidated finding status

All 121 register items were reassessed against application baseline `a66a052`.
`Addressed` means the scoped implementation or clarification has evidence in the linked report;
it does not imply physical hardware or live-provider acceptance. Independent judge and
combined validation are recorded in [review-judge.md](review-judge.md). Round 2 changes are recorded in
[review-round2-relay.md](review-round2-relay.md) and [review-round2-firmware.md](review-round2-firmware.md);
`User decision` marks items left unchanged until the owner decides.

| Finding | Disposition | Evidence / limitation |
|---|---|---|
| RLY-01 | Addressed | [review-security](review-security.md) |
| RLY-02 | Addressed | [review-security](review-security.md) |
| RLY-03 | Addressed | [review-security](review-security.md); round 2: app-token 401 rebuilds the Twitch client, [review-round2-relay](review-round2-relay.md) |
| RLY-04 | Addressed | [review-repository](review-repository.md) |
| RLY-05 | Addressed | [review-security](review-security.md) |
| RLY-06 | Addressed | [review-security](review-security.md) |
| RLY-07 | Addressed | [review-runtime](review-runtime.md) |
| RLY-08 | Addressed | [review-security](review-security.md); round 2: grace = max(push grace, 3 x poll interval) and a pushed-end started_at guard, [review-round2-relay](review-round2-relay.md) (34893f6). Optional two-consecutive-live-polls rule not implemented. |
| RLY-09 | Addressed | [review-runtime](review-runtime.md) |
| RLY-10 | Addressed | [review-security](review-security.md) |
| RLY-11 | Addressed | [review-security](review-security.md); round 2: WebSocket registration decision extracted and tested, [review-round2-relay](review-round2-relay.md) (2092d11) |
| RLY-12 | Addressed | [review-runtime](review-runtime.md); round 2: logs/activity HTTP and MDC trace-id tests, [review-round2-relay](review-round2-relay.md) (af1155b). The JSON body for an unknown minLevel 400 is not asserted through the real server. |
| RLY-13 | Addressed | [review-runtime](review-runtime.md) |
| RLY-14 | Addressed | [review-runtime](review-runtime.md) |
| RLY-15 | Addressed | [review-runtime](review-runtime.md) |
| RLY-16 | Addressed | [review-runtime](review-runtime.md); round 2: greeting capacity uses GreetingFrames = ChatReplaySize + 2, [review-round2-relay](review-round2-relay.md) (f30d1f0) |
| RLY-17 | Addressed | [review-runtime](review-runtime.md) |
| RLY-18 | Addressed | [review-runtime](review-runtime.md) |
| RLY-19 | Addressed | [review-security](review-security.md) |
| RLY-20 | Addressed | [review-security](review-security.md) |
| RLY-21 | Addressed | [review-security](review-security.md); round 2: nullable ChannelUpdateV2Event fields normalized on the WebSocket path, [review-round2-relay](review-round2-relay.md) (142be2f) |
| RLY-22 | Addressed | [review-runtime](review-runtime.md) |
| RLY-23 | Addressed | [review-security](review-security.md) |
| RLY-24 | Addressed | [review-security](review-security.md) |
| RLY-25 | Addressed | [review-security](review-security.md); round 2: timers and default TTL validated in wire units, [review-round2-relay](review-round2-relay.md) (f30d1f0). The idle-timeout > 15 s limit (§12) is still not enforced. |
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
| RLY-37 | Addressed | [review-runtime](review-runtime.md); round 2: ApiSuite names now match what the tests do, [review-round2-relay](review-round2-relay.md) (af1155b) |
| RLY-38 | Addressed | [review-repository](review-repository.md) |
| RLY-39 | Deferred optional | Optional lint/coverage adoption has no required migration. [review-repository](review-repository.md) |
| RLY-40 | Addressed | [review-repository](review-repository.md) |
| RLY-41 | Addressed; legacy follow-up | Round 2: OTel instrumentation 2.31.1-alpha aligned to SDK 1.66.0 BOM, with an OtelLinkageSuite, [review-round2-relay](review-round2-relay.md) (3cff2e4, 50fb341, d7863f1). Audit: 181 coordinates, two legacy exceptions expire 2026-10-25. Re-check the BOM precedence when instrumentation targets SDK 1.66. [Dependency assessment](review-dependencies.md) |
| RLY-42 | User decision | Retained behavior pending owner decision: the initial live announcement initializes stats and is tested. Not changed in round 2. [review-security](review-security.md) |
| RLY-43 | Addressed | [review-security](review-security.md); round 2: webhook callback reachable in afterBind, plus StartupOrderSuite, [review-round2-relay](review-round2-relay.md) (c6cec28). Ingestion retry belongs to RLY-03. Token maintenance can still start before bind. |
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
| RLY-55 | Addressed | Round 2: injectable DeviceHub session-ID source with WELCOME.session_id tests, [review-round2-relay](review-round2-relay.md) (1e8e673, f9132c8) |
| RLY-56 | Addressed | [review-security](review-security.md) |
| RLY-57 | Addressed | [review-security](review-security.md) |
| RLY-58 | User decision | Machine-readable API error codes wait for an owner decision. Not changed in round 2. [review-security](review-security.md) |
| RLY-59 | Addressed | [review-runtime](review-runtime.md) |
| RLY-60 | Addressed | [review-runtime](review-runtime.md) |
| FW-01 | Addressed | [review-firmware](review-firmware.md); round 2: prolonged WiFi outage session test, [review-round2-firmware](review-round2-firmware.md). Physical-device acceptance remains outstanding. |
| FW-02 | Addressed | [review-firmware](review-firmware.md) Physical-device acceptance remains outstanding. |
| FW-03 | Addressed | [review-firmware](review-firmware.md) Physical-device acceptance remains outstanding. |
| FW-04 | Addressed | [review-firmware](review-firmware.md) Physical-device acceptance remains outstanding. |
| FW-05 | Addressed | [review-firmware](review-firmware.md) Physical-device acceptance remains outstanding. |
| FW-06 | Addressed | [review-firmware](review-firmware.md) |
| FW-07 | Addressed | [review-firmware](review-firmware.md) |
| FW-08 | Addressed | [review-firmware](review-firmware.md); round 2: deprecated LVGL aliases removed, and esp32dev builds with LV_DISABLE_API_MAPPING, [review-round2-firmware](review-round2-firmware.md) (c6765f5) |
| FW-09 | Addressed | [review-firmware](review-firmware.md); round 2: stable-interval backoff reset documented and REPLACED cap tested, [review-round2-firmware](review-round2-firmware.md) |
| FW-10 | User decision | Frozen retry floor kept; a cap needs an owner or protocol policy decision. Not changed in round 2. [review-firmware](review-firmware.md) |
| FW-11 | Addressed | [review-firmware](review-firmware.md) |
| FW-12 | Addressed | [review-firmware](review-firmware.md) |
| FW-13 | Addressed | [review-firmware](review-firmware.md) |
| FW-14 | Addressed | [review-firmware](review-firmware.md) Physical-device acceptance remains outstanding. |
| FW-15 | Addressed | [review-firmware](review-firmware.md) Physical-device acceptance remains outstanding. |
| FW-16 | Addressed | [review-firmware](review-firmware.md) Physical-device acceptance remains outstanding. |
| FW-17 | Addressed | [review-firmware](review-firmware.md) |
| FW-18 | User decision | OTA needs owner requirements for partitions, authentication and rollback. Not changed in round 2. [review-firmware](review-firmware.md) |
| FW-19 | Addressed | [review-firmware](review-firmware.md) |
| FW-20 | Addressed | [review-firmware](review-firmware.md) |
| FW-21 | Addressed | [review-firmware](review-firmware.md) |
| FW-22 | Addressed | [review-firmware](review-firmware.md) |
| FW-23 | Addressed | [review-firmware](review-firmware.md) |
| FW-24 | Addressed | [review-firmware](review-firmware.md); round 2: stale penv pio paths replaced, [review-round2-firmware](review-round2-firmware.md) (c6765f5) |
| FW-25 | Addressed | [review-firmware](review-firmware.md) |
| FW-26 | Addressed | [review-firmware](review-firmware.md) |
| FW-27 | Addressed | [review-firmware](review-firmware.md) |
| FW-28 | Addressed | [review-firmware](review-firmware.md) |
| PROTO-01 | Addressed | [review-firmware](review-firmware.md) |
| PROTO-02 | User decision | Restart sequence ownership needs persistence or a new epoch contract, which waits for an owner decision. Not changed in round 2. [review-firmware](review-firmware.md) |
| PROTO-03 | Addressed | [review-firmware](review-firmware.md); round 2: test proves STATS overflow is lossy but keeps the link, [review-round2-relay](review-round2-relay.md) (0d3071c). STATS stays lossy by design. |
| PROTO-04 | Addressed | [review-firmware](review-firmware.md); [relay runtime](review-runtime.md) |
| PROTO-05 | Addressed | [review-firmware](review-firmware.md); [relay runtime](review-runtime.md) |
| PROTO-06 | Addressed | [review-firmware](review-firmware.md); [relay runtime](review-runtime.md) |
| PROTO-07 | Addressed | [review-firmware](review-firmware.md); [relay runtime](review-runtime.md) |
| PROTO-08 | Addressed | [repository](review-repository.md): automated 20-vector drift check. |
| PROTO-09 | Addressed | [review-firmware](review-firmware.md); round 2: PROTOCOL.md §19 rewritten as completed migration history, [review-round2-firmware](review-round2-firmware.md) |
| PROTO-10 | User decision | Unknown startup statistics need a negotiated representation, which waits for an owner decision. Not changed in round 2. [review-firmware](review-firmware.md) |
| PROTO-11 | User decision | Replay age cutoff needs an owner delivery/presentation policy. Not changed in round 2. [review-firmware](review-firmware.md) |
| PROTO-12 | Addressed | [review-firmware](review-firmware.md) |
| PROTO-13 | Addressed | [review-firmware](review-firmware.md); [relay runtime](review-runtime.md) |
| PROTO-14 | Addressed | [review-firmware](review-firmware.md); round 2: the relay can no longer send BYE 4/6, [review-round2-relay](review-round2-relay.md) (0bdc897). Firmware still decodes codes 4/6 for receive-only logging. |
| PROTO-15 | Addressed | [review-firmware](review-firmware.md); [relay runtime](review-runtime.md) |
| PROTO-16 | Addressed | [review-firmware](review-firmware.md); round 2: REPLAY flag on device frames is ignored, and a test covers it, [review-round2-relay](review-round2-relay.md) (0bdc897) |
| PROTO-17 | Addressed | [review-firmware](review-firmware.md) |
| PROTO-18 | Addressed | [review-firmware](review-firmware.md); round 2: sequence exhaustion is contained and reported once, and /status shows sequenceExhausted, [review-round2-relay](review-round2-relay.md) (b769484) |
| REPO-01 | Addressed | [review-repository](review-repository.md) |
| REPO-02 | Addressed | [review-repository](review-repository.md) |
| REPO-03 | Addressed | [review-repository](review-repository.md) |
| REPO-04 | Addressed | [review-repository](review-repository.md) |
| REPO-05 | Addressed | [review-repository](review-repository.md) |
| REPO-06 | User decision | Keep deliberately versioned CAD deliverables with no history rewrite, pending owner decision. Not changed in round 2. [review-repository](review-repository.md) |
| REPO-07 | Addressed | [review-repository](review-repository.md) |
| CAD-01 | Addressed | [review-repository](review-repository.md) |
| CAD-02 | Addressed | [review-repository](review-repository.md) |
| CAD-03 | Addressed | [review-repository](review-repository.md) |
| CAD-04 | Addressed | [review-repository](review-repository.md) |
| CAD-05 | Addressed | [review-repository](review-repository.md) |
| CAD-06 | Addressed | [review-repository](review-repository.md) |
| CAD-07 | Addressed | [review-repository](review-repository.md) |
| CAD-08 | Addressed | [review-repository](review-repository.md) |
