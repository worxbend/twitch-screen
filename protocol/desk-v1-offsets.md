# DESK/1 normative payload offsets

All offsets are relative to payload byte zero (frame byte 8). Scalars are little-endian.
These records are packed byte sequences, not naturally aligned C++ structs. Use explicit byte operations or memcpy.
This table is normative alongside PROTOCOL.md section 20. desk-v1-layouts.json is its machine-readable equivalent.

## HELLO: 0x01, 92 payload bytes, 100 frame bytes

| Offset | Width | Type | Field |
|---:|---:|---|---|
| 0 | 4 | u32 | lastSeq |
| 4 | 4 | u32 | caps |
| 8 | 2 | u16 | rxMax |
| 10 | 2 | u16 | reserved0 |
| 12 | 32 | bytes32 | deviceId |
| 44 | 16 | bytes16 | fwVersion |
| 60 | 2 | u16 | profile |
| 62 | 2 | u16 | reserved |
| 64 | 8 | u64 | previousRelayEpoch |
| 72 | 4 | u32 | processedSeq |
| 76 | 8 | u64 | deviceBootId |
| 84 | 8 | u64 | probeDeviceMs |

## WELCOME: 0x20, 60 payload bytes, 68 frame bytes

| Offset | Width | Type | Field |
|---:|---:|---|---|
| 0 | 4 | u32 | latestSeq |
| 4 | 4 | u32 | serverTime |
| 8 | 4 | u32 | sessionId |
| 12 | 2 | u16 | maxFrame |
| 14 | 2 | u16 | pingIntervalS |
| 16 | 2 | u16 | idleTimeoutS |
| 18 | 2 | u16 | replayWindow |
| 20 | 4 | u32 | caps |
| 24 | 2 | u16 | profile |
| 26 | 2 | u16 | reserved |
| 28 | 8 | u64 | relayEpoch |
| 36 | 8 | u64 | connectionId |
| 44 | 8 | u64 | sampledRelayMs |
| 52 | 8 | u64 | echoedProbeDeviceMs |

## STATS: 0x22, 60 payload bytes, 68 frame bytes

| Offset | Width | Type | Field |
|---:|---:|---|---|
| 0 | 4 | u32 | viewers |
| 4 | 4 | u32 | msgTotal |
| 8 | 4 | u32 | uptimeS |
| 12 | 4 | u32 | followers |
| 16 | 4 | u32 | subs |
| 20 | 4 | u32 | serverTime |
| 24 | 4 | u32 | streamStartedAt |
| 28 | 2 | u16 | chatRate |
| 30 | 1 | u8 | live |
| 31 | 1 | u8 | sflags |
| 32 | 8 | u64 | relayEpoch |
| 40 | 8 | u64 | connectionId |
| 48 | 4 | u32 | sourceGeneration |
| 52 | 4 | u32 | statsRevision |
| 56 | 1 | u8 | availability |
| 57 | 3 | bytes3 | reserved |

## TIME_REQUEST: 0x05, 8 payload bytes, 16 frame bytes

| Offset | Width | Type | Field |
|---:|---:|---|---|
| 0 | 8 | u64 | probeDeviceMs |

## DEVICE_REPORT: 0x06, 244 payload bytes, 252 frame bytes

| Offset | Width | Type | Field |
|---:|---:|---|---|
| 0 | 8 | u64 | relayEpoch |
| 8 | 8 | u64 | connectionId |
| 16 | 8 | u64 | deviceBootId |
| 24 | 4 | u32 | reportRevision |
| 28 | 8 | u64 | observedDeviceMs |
| 36 | 8 | u64 | observedRelayLowerMs |
| 44 | 8 | u64 | grantTotal |
| 52 | 4 | u32 | processedSeq |
| 56 | 4 | u32 | enqueuedSeq |
| 60 | 4 | u32 | displayRevision |
| 64 | 4 | u32 | sourceGeneration |
| 68 | 4 | u32 | twitchEpisode |
| 72 | 4 | u32 | weatherRevision |
| 76 | 4 | u32 | cpuRevision |
| 80 | 1 | u8 | baseScreen |
| 81 | 1 | u8 | reason |
| 82 | 1 | u8 | activePhase |
| 83 | 1 | u8 | pendingCount |
| 84 | 4 | u32 | activeRemainingMs |
| 88 | 4 | u32 | expiredTotal |
| 92 | 4 | u32 | suppressedTotal |
| 96 | 4 | u32 | refusedTotal |
| 100 | 8 | u64 | entry0.originalRelayEpoch |
| 108 | 4 | u32 | entry0.seq |
| 112 | 1 | u8 | entry0.kind |
| 113 | 1 | u8 | entry0.origin |
| 114 | 2 | u16 | entry0.reserved |
| 116 | 8 | u64 | entry1.originalRelayEpoch |
| 124 | 4 | u32 | entry1.seq |
| 128 | 1 | u8 | entry1.kind |
| 129 | 1 | u8 | entry1.origin |
| 130 | 2 | u16 | entry1.reserved |
| 132 | 8 | u64 | entry2.originalRelayEpoch |
| 140 | 4 | u32 | entry2.seq |
| 144 | 1 | u8 | entry2.kind |
| 145 | 1 | u8 | entry2.origin |
| 146 | 2 | u16 | entry2.reserved |
| 148 | 8 | u64 | entry3.originalRelayEpoch |
| 156 | 4 | u32 | entry3.seq |
| 160 | 1 | u8 | entry3.kind |
| 161 | 1 | u8 | entry3.origin |
| 162 | 2 | u16 | entry3.reserved |
| 164 | 8 | u64 | entry4.originalRelayEpoch |
| 172 | 4 | u32 | entry4.seq |
| 176 | 1 | u8 | entry4.kind |
| 177 | 1 | u8 | entry4.origin |
| 178 | 2 | u16 | entry4.reserved |
| 180 | 8 | u64 | entry5.originalRelayEpoch |
| 188 | 4 | u32 | entry5.seq |
| 192 | 1 | u8 | entry5.kind |
| 193 | 1 | u8 | entry5.origin |
| 194 | 2 | u16 | entry5.reserved |
| 196 | 8 | u64 | entry6.originalRelayEpoch |
| 204 | 4 | u32 | entry6.seq |
| 208 | 1 | u8 | entry6.kind |
| 209 | 1 | u8 | entry6.origin |
| 210 | 2 | u16 | entry6.reserved |
| 212 | 8 | u64 | entry7.originalRelayEpoch |
| 220 | 4 | u32 | entry7.seq |
| 224 | 1 | u8 | entry7.kind |
| 225 | 1 | u8 | entry7.origin |
| 226 | 2 | u16 | entry7.reserved |
| 228 | 8 | u64 | entry8.originalRelayEpoch |
| 236 | 4 | u32 | entry8.seq |
| 240 | 1 | u8 | entry8.kind |
| 241 | 1 | u8 | entry8.origin |
| 242 | 2 | u16 | entry8.reserved |

## TIME_RESPONSE: 0x26, 32 payload bytes, 40 frame bytes

| Offset | Width | Type | Field |
|---:|---:|---|---|
| 0 | 8 | u64 | relayEpoch |
| 8 | 8 | u64 | connectionId |
| 16 | 8 | u64 | echoedProbeDeviceMs |
| 24 | 8 | u64 | sampledRelayMs |

## DISPLAY_STATE: 0x27, 32 payload bytes, 40 frame bytes

| Offset | Width | Type | Field |
|---:|---:|---|---|
| 0 | 8 | u64 | relayEpoch |
| 8 | 8 | u64 | connectionId |
| 16 | 4 | u32 | revision |
| 20 | 4 | u32 | sourceGeneration |
| 24 | 4 | u32 | twitchEpisode |
| 28 | 1 | u8 | enabled |
| 29 | 1 | u8 | preferred |
| 30 | 2 | u16 | reserved |

## WEATHER: 0x28, 68 payload bytes, 76 frame bytes

| Offset | Width | Type | Field |
|---:|---:|---|---|
| 0 | 8 | u64 | relayEpoch |
| 8 | 8 | u64 | connectionId |
| 16 | 4 | u32 | revision |
| 20 | 8 | u64 | deadlineRelayMs |
| 28 | 1 | u8 | status |
| 29 | 1 | u8 | unit |
| 30 | 1 | u8 | condition |
| 31 | 1 | u8 | presence |
| 32 | 2 | i16 | temperatureCenti |
| 34 | 1 | u8 | humidityPercent |
| 35 | 1 | u8 | reserved |
| 36 | 32 | bytes32 | location |

## CPU: 0x29, 68 payload bytes, 76 frame bytes

| Offset | Width | Type | Field |
|---:|---:|---|---|
| 0 | 8 | u64 | relayEpoch |
| 8 | 8 | u64 | connectionId |
| 16 | 4 | u32 | revision |
| 20 | 8 | u64 | deadlineRelayMs |
| 28 | 1 | u8 | status |
| 29 | 1 | u8 | presence |
| 30 | 2 | u16 | cpuBasisPoints |
| 32 | 2 | u16 | memoryBasisPoints |
| 34 | 2 | i16 | temperatureCenti |
| 36 | 32 | bytes32 | host |

## SYNC_END: 0x2a, 40 payload bytes, 48 frame bytes

| Offset | Width | Type | Field |
|---:|---:|---|---|
| 0 | 8 | u64 | relayEpoch |
| 8 | 8 | u64 | connectionId |
| 16 | 4 | u32 | displayRevision |
| 20 | 4 | u32 | weatherRevision |
| 24 | 4 | u32 | cpuRevision |
| 28 | 4 | u32 | statsRevision |
| 32 | 4 | u32 | sourceGeneration |
| 36 | 4 | u32 | twitchEpisode |

## DESK_EVENT: 0x2b, 196 payload bytes, 204 frame bytes

| Offset | Width | Type | Field |
|---:|---:|---|---|
| 0 | 4 | u32 | seq |
| 4 | 4 | u32 | ts |
| 8 | 4 | u32 | value |
| 12 | 4 | bytes4 | reserved1 |
| 16 | 2 | u16 | months |
| 18 | 2 | u16 | ttlDs |
| 20 | 1 | u8 | kind |
| 21 | 1 | u8 | tier |
| 22 | 1 | u8 | eflags |
| 23 | 1 | u8 | reserved2 |
| 24 | 48 | bytes48 | actor |
| 72 | 96 | bytes96 | text |
| 168 | 8 | u64 | relayEpoch |
| 176 | 4 | u32 | sourceGeneration |
| 180 | 4 | u32 | twitchEpisode |
| 184 | 8 | u64 | deadlineRelayMs |
| 192 | 1 | u8 | origin |
| 193 | 3 | bytes3 | reserved |

## PROGRESS_BARRIER: 0x2c, 20 payload bytes, 28 frame bytes

| Offset | Width | Type | Field |
|---:|---:|---|---|
| 0 | 8 | u64 | relayEpoch |
| 8 | 8 | u64 | connectionId |
| 16 | 4 | u32 | throughSeq |
