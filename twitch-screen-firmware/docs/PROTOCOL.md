# TSB/3 — Twitch Screen Binary Protocol, version 3

**Status: normative.** This document is the complete and only specification of the wire
protocol spoken between the relay (`twitch-screen-relay`, Scala 3) and the device
(`twitch-screen-firmware`, ESP32) over the persistent TCP link on port **8099**.

The two implementations are written independently from this document. Anything this
document does not pin down is a defect in this document, not a licence to choose.
MUST / MUST NOT / SHOULD / MAY carry RFC 2119 force.

## 0. What this replaces

* **This supersedes NDJSON protocol v2** in every respect. v2 and v3 peers cannot
  interoperate and MUST NOT try: a v2 peer's first byte is `{` (`0x7b`), which fails this
  protocol's frame magic immediately, and a v3 peer's first byte is `0xa7`, which is not
  legal UTF-8 start-of-text. The v2→v3 cutover is a flag day: flash the device and restart
  the relay together. It is the last one (§16).
* `demo-server/twitch_server.py` speaks NDJSON v2 and is therefore **obsolete**. Do not
  update it, do not test against it, and do not treat its behaviour as normative for
  anything. The relay is the only server implementation of v3.
* The wire version number is **3**. It appears at a fixed offset in every frame header
  (§3) and is frozen there for all future versions.

## 1. Decisions taken from the design review

Three designs were proposed and reviewed through an embedded-correctness lens, a
semantic-completeness lens and an operability lens. Where the reviews disagreed, this
document decides, once, for the following reasons.

* **Fixed-offset, fixed-width payload records, not a tag-length-value field list.** Two
  independent implementations cannot disagree about a field that has exactly one offset and
  exactly one width, and the decoder becomes a bounds check plus one `memcpy` with no skip
  machinery to get wrong. The cost — every `EVENT` is 176 bytes whether it carries a follow
  or a chat line — is about 100 B/s on a LAN at the busiest realistic chat rate, which buys
  nothing worth the ambiguity.
* **An 8-byte header with magic, version, type, length, flags and a positionally weighted
  header check, not a bare type+length.** The magic makes a v2 peer or a desync fail on the
  first byte instead of by timeout; the version byte at a fixed offset is what lets a peer
  of any version read a `BYE` from a peer whose version it does not speak.
* **Resynchronise by one-byte window shift; do not tear the link down on the first bad
  byte.** A deterministic encoder bug on either side must not produce an unbreakable
  reconnect loop with the screen stuck on "connecting"; the resync budget (§4.5) bounds how
  long the receiver tries before giving up.
* **Absolute, self-contained telemetry; no delta or patch encoding for `STATS`.** One
  captured frame must fully explain the idle screen. Delta encoding saved about twenty
  bytes on a frame sent every five seconds and made a correct capture coexist with a wrong
  screen, which is the worst possible debugging property.
* **Every `EVENT` is sequenced and every `EVENT` is replayable, including chat.** An
  unsequenced or unreplayed event kind punches a hole in the sequence space that silently
  defeats replay for the durable events around it; chat volume is bounded instead by a
  dedicated replay sub-budget (§10.3) and by the device's capability bits.
* **No donations, and therefore no money on this wire at all.** Twitch has no native
  donation event and the relay has no StreamElements, StreamLabs or Ko-fi integration, so a
  donation kind would be a field nothing could ever fill. Kind `0x18` and the two payload
  slots that a monetary amount would have used are **reserved and zero** rather than
  repacked, so a future version can reintroduce an amount without moving a single existing
  field (§6.4, §8).
* **Display names only; no logins and no user ids.** The relay carries a Twitch display name
  and nothing else, so bot filtering matches on the lowercased display name. This is weaker
  than matching on the login and §13 says so plainly.
* **Audience events arrive over IRC only.** Subscriptions, gifted subs, cheers and raids come
  from the chat connection and nowhere else; EventSub stays scoped to stream lifecycle,
  channel update and follow. §13.2 documents exactly what is lost on the webhook transport.
* **Exact version equality, with a machine-readable refusal.** v2's real failure was
  silence, not strictness: a mismatched device reconnect-looped forever with no diagnosis
  on either side. `BYE` (§6.7) fixes that for thirty-two bytes, once, at teardown.

---

## 2. Transport and endianness

One long-lived TCP connection per device, always initiated by the device, to the relay on
**TCP port 8099**. `TCP_NODELAY` MUST be set on both ends. `SO_KEEPALIVE` SHOULD be set as
a second net; the application heartbeat (§12) is authoritative. No TLS, no authentication
beyond the device id.

Frames are written back to back on the byte stream with no separator, no padding and no
alignment requirement between frames.

Neither side may block on a partial frame. The device's reader is a resumable state machine
driven from `linkLoop()` (called every ~5 ms); the relay uses one virtual thread per
connection and may block only that thread. **A socket read returns a short count whenever
fewer bytes are buffered than were asked for** — `WiFiClient::read(buf, n)` on the ESP32
and `InputStream.read` on the JVM both do this. Every reader MUST accumulate until it holds
the number of bytes it needs and MUST NOT assume one read call delivers them.

The device MUST NOT wrap this socket in any character-oriented API (`InputStreamReader`,
`BufferedReader`, `Reader`, `String`): a partial multi-byte sequence would be mangled before
the codec ever saw it. Bytes only, on both sides.

### 2.1 Endianness

> **Every multi-byte integer in TSB/3 is LITTLE-ENDIAN, without exception**, in frame
> headers, in payload fields and in every length field, in both directions. There is no
> big-endian field anywhere in this protocol and there is no per-field byte-order flag.

The ESP32 is little-endian and is the constrained side, so it performs zero byte swaps and
may `memcpy` a payload straight onto a struct. The JVM's `ByteBuffer` and `DataOutputStream`
default to big-endian, so the relay MUST call `ByteBuffer.order(ByteOrder.LITTLE_ENDIAN)` on
every buffer used for this protocol, or write the bytes explicitly. The side with a JIT and
no real-time constraint pays the swap.

### 2.2 Primitive types

| Name | Width | Signedness | Range |
|---|---|---|---|
| `u8` | 1 byte | unsigned | 0 … 255 |
| `u16` | 2 bytes | unsigned, LE | 0 … 65 535 |
| `u32` | 4 bytes | unsigned, LE | 0 … 4 294 967 295 |
| `char[N]` | N bytes | — | NUL-padded UTF-8, at most N−1 content bytes (§9) |

There are **no signed integers, no 64-bit integers, no floating-point values and no
booleans** on the wire. A boolean is a `u8`; a receiver MUST treat any non-zero value as
true. The relay's `SeqNo` (a `Long`) is narrowed to `u32` at the wire boundary (§10.1).

Alignment rule, which holds for every payload in this document: a field of width *W* starts
at an offset that is a multiple of *W*, and every payload length is a multiple of 4. With an
8-byte header and a 4-byte-aligned receive buffer, every payload scalar therefore lands
naturally aligned. **Decoders MUST still use `memcpy` or explicit byte shifts and MUST NOT
cast a pointer into the receive buffer** (`reinterpret_cast<uint32_t*>(rx + n)`): the ESP32
toolchain compiles at `-Os` with strict aliasing active, so type punning is undefined
behaviour regardless of alignment.

---

## 3. Frame header

Every frame is exactly `8 + length` bytes.

| Offset | Width | Type | Field | Value and meaning |
|---|---|---|---|---|
| 0 | 1 | `u8` | `magic0` | MUST be `0xa7` |
| 1 | 1 | `u8` | `magic1` | MUST be `0x53` |
| 2 | 1 | `u8` | `version` | protocol version of this frame; `0x03` for TSB/3 |
| 3 | 1 | `u8` | `type` | message type code (§6); `0x00` is illegal |
| 4 | 2 | `u16` | `length` | payload length in **bytes**, excluding this header; `0 … 248` |
| 6 | 1 | `u8` | `flags` | bit field, below |
| 7 | 1 | `u8` | `hchk` | header check, below |

`magic0`/`magic1` are `0xa7 0x53`. `0xa7` is a UTF-8 continuation byte, so it can never
begin a well-formed UTF-8 text stream, and it is not `{` (`0x7b`): a v2 NDJSON peer is
rejected on the first byte rather than misparsed.

### 3.1 `hchk`

```
sum  = (1*b[0] + 2*b[1] + 3*b[2] + 4*b[3] + 5*b[4] + 6*b[5] + 7*b[6]) mod 256
hchk = 0xFF XOR sum
```

The weights are positional so that a one-byte stream shift — which moves `type` into
`length` and vice versa — changes the result; a plain additive sum does not catch that and
is therefore forbidden. `hchk` is **not** an integrity check and makes no integrity claim:
TCP already checksums the payload and the link layer already CRCs the frame. Its only job is
to make `length` trustworthy enough to skip by during resynchronisation (§4.4), and to turn
plausible garbage into an immediate, located framing error. There is no payload checksum in
TSB/3 and there will not be one.

### 3.2 `flags`

| Bit | Mask | Name | Meaning |
|---|---|---|---|
| 0 | `0x01` | `REPLAY` | this frame is a replay of buffered state, not a live event (§10.3) |
| 1–7 | `0xfe` | reserved | MUST be sent as 0 |

**A receiver MUST ignore `flags` bits it does not recognise and MUST NOT reject a frame
because an unknown bit is set.** Setting `REPLAY` on a cached, pre-encoded frame rewrites
exactly two bytes: offset 6 and offset 7.

---

## 4. Framing, malformed input and resynchronisation

### 4.1 Reader state machine (normative shape)

Five states over one static receive buffer. No dynamic allocation anywhere on the read path.

```
buffers:  uint8_t  hdr[8]            -- candidate header window
          alignas(4) uint8_t rx[248] -- payload
          uint16_t held              -- bytes held in hdr
          uint16_t have, need        -- bytes held in / required for rx
          uint32_t skipRemaining     -- bytes still to discard
```

| State | Action |
|---|---|
| `HUNT` | consume one byte; if it is `0xa7`, store it at `hdr[0]`, set `held = 1`, go to `HEADER`; otherwise discard it and count it (§4.5) |
| `HEADER` | accumulate into `hdr` until `held == 8`, then validate (§4.2). Valid → **set `have = 0`**, `need = length`; if `need == 0` dispatch immediately, else go to `BODY`. Valid but `need > sizeof(rx)` → `skipRemaining = need`, go to `SKIP`. Invalid → §4.4 |
| `BODY` | accumulate into `rx` until `have == need`, then dispatch (§4.3) and go to `HUNT` with `held = 0` |
| `SKIP` | read and discard `min(skipRemaining, available())` bytes; when it reaches 0, count one oversized-skip and go to `HUNT` |

`have` MUST be reset to 0 on entry to `BODY` and `held` MUST be reset to 0 after a dispatch.
Sharing one accumulator between the header and the payload without resetting it is the
single easiest way to implement this wrongly: the symptom is a frame decoded from
`length − 8` fresh bytes plus eight stale ones, which renders leftover text from an earlier
frame rather than crashing.

Each state consumes at most the bytes already available from the socket and returns with its
state intact, so a frame that straddles several TCP segments — or several `linkLoop()`
iterations — is resumed exactly where it stopped.

### 4.2 Header validation

A candidate 8-byte header is **valid** if and only if all of:

* `hdr[0] == 0xa7` and `hdr[1] == 0x53`
* `hdr[7] == hchk(hdr[0..6])`
* `type != 0x00`
* `length <= 248`

`version` is deliberately **not** part of header validation. A header carrying an unknown
version is a valid header with a version problem, handled at the session layer (§11.2). That
is exactly the mechanism that lets two peers of different versions exchange a `BYE`.

### 4.3 Payload-level problems — skip the frame, keep the link

The frame is well-framed but not usable. The receiver MUST discard the payload, increment
the matching counter, log at debug level, and continue reading at the next frame. It MUST
NOT close the connection. These counters reset the resync budget (§4.5), because a frame
that was skipped was nonetheless correctly framed.

| Condition | Counter |
|---|---|
| `type` is not implemented by this receiver, including a type added by a newer peer | `framesUnknownType` |
| `type` belongs to the receiver's own outbound range (§6) — a confused peer, not a corrupt stream | `framesWrongDirection` |
| `length` is smaller than the base length of a known `type` (§6) | `framesShortPayload` |
| `length == 0` for a type whose base length is non-zero | `framesShortPayload` |
| the payload decodes but a field is out of range in a way §6 marks as fatal-to-the-frame | `framesInvalidField` |
| `length` exceeds this receiver's payload buffer (never happens between two v3 peers, §7) | `framesOversizeSkipped` |

The one exception is the handshake (§11.2): before `WELCOME` has been exchanged, a frame
that is not the expected one is fatal to the session.

### 4.4 Framing violations — resynchronise, then give up

If the candidate header is invalid, the receiver MUST NOT trust `length` and MUST NOT
consume all eight bytes. It MUST shift the window by exactly one byte and re-examine:

```
memmove(hdr, hdr + 1, 7);  held = 7;   /* then keep filling from the stream */
/* re-validate as soon as held == 8; hdr[0] == 0xa7 is the first condition */
```

This guarantees that a frame boundary lying inside a discarded region is still found, so a
single spurious or missing byte costs a few bytes of garbage rather than the connection.

### 4.5 Resync budget — stated as numbers, because it must not be guessed

Each side maintains two counters, both reset to zero **on every successfully decoded frame
and on every frame skipped under §4.3**:

* `rejectedCandidates` — incremented once per invalid 8-byte window **whose first byte is
  `0xa7`**. A window that does not even start with the magic byte does not count here.
* `discardedBytes` — incremented once per byte consumed in `HUNT` without producing a valid
  header, and once per byte shifted out of an invalid candidate window. Bytes consumed in
  `SKIP` (a well-framed frame the receiver chose to discard) do **not** count.

If `rejectedCandidates` reaches **16**, or `discardedBytes` reaches **4096**, the stream is
declared untrustworthy:

* the relay sends `BYE(5 FRAMING_VIOLATION)` on a best-effort basis, then closes;
* the device closes, logs, and enters backoff (§12.1). It does not attempt to send anything
  on a stream it cannot read.

### 4.6 The two-tier rule, stated once

**A malformed frame must not kill the link; a malformed stream must.** A binary stream has
no newline to resynchronise on, so "malformed frame" here means *well-framed but not
understood* (§4.3) and "malformed stream" means *the framing invariant itself is broken*
(§4.4, past the budget).

---

## 5. Limits — every one of them is a number

| Constant | Value | Meaning |
|---|---|---|
| `TSB3_MAX_PAYLOAD` | **248** bytes | largest legal `length` |
| `TSB3_MAX_FRAME` | **256** bytes | `8 + TSB3_MAX_PAYLOAD`; the receive buffer size on both sides |
| `TSB3_MIN_RX_MAX` | **256** bytes | every v3 peer MUST be able to accept a 256-byte frame |
| largest defined v3 frame | **176** bytes | `EVENT` (§6.4): 8 header + 168 payload |
| `device_id` | **31** content bytes + NUL, field width 32 | `HELLO` |
| `fw_version` | **15** content bytes + NUL, field width 16 | `HELLO` |
| `actor` | **47** content bytes + NUL, field width 48 | `EVENT`; matches the firmware's `char title[48]` |
| `text` | **95** content bytes + NUL, field width 96 | `EVENT`; matches the firmware's `char body[96]` |
| `reason` | **23** content bytes + NUL, field width 24 | `BYE` |
| relay outbound queue, per device | **128** frames | drop-on-full, never block (§10.4) |
| device notification queue | **at least 8** entries | refuse-newest on full (§10.5) |
| replay buffer, durable events | **64** events | §10.3 |
| replay buffer, chat events | **16** events | separate ring, §10.3 |
| resync budget | **16** candidates / **4096** bytes | §4.5 |

256 is chosen because the largest v3 frame is 176 bytes, 256 is a power of two, it halves
the firmware's current 512-byte line buffer, and it is far below the ESP32's 1436-byte
`WiFiClientRxBuffer`, so a frame can always be assembled.

A sender MUST NOT emit a frame larger than `min(TSB3_MAX_FRAME, peer_rx_max)`, where
`peer_rx_max` is the value the peer declared (`HELLO.rx_max`, `WELCOME.max_frame`). A sender
that cannot fit a message MUST truncate its string fields per §9.2 and, if it still does not
fit, MUST drop the message and count it. It MUST NOT fragment and MUST NOT emit a
partial frame.

The relay's `device-link.max-frame-length` becomes **256 and is counted in bytes**, not in
UTF-16 characters. `LinkCounters.recordSent` / `recordReceived` MUST be fed `8 + length`,
which is now the true byte count.

---

## 6. Message types and payloads

`type` is a `u8`, partitioned by direction so that a misrouted frame is detectable.

| Range | Direction |
|---|---|
| `0x01` … `0x1f` | device → relay |
| `0x20` … `0x3f` | relay → device |
| `0x00`, `0x40` … `0xff` | reserved; never sent in v3 |

### The single type table

| Code | Name | Direction | Payload length | Section |
|---|---|---|---|---|
| `0x01` | `HELLO` | device → relay | 60 | §6.1 |
| `0x02` | `PING` | device → relay | 4 | §6.3 |
| `0x03` | `PONG` | device → relay | 4 | §6.3 |
| `0x04` | `ACK` | device → relay | 4 | §6.6 |
| `0x20` | `WELCOME` | relay → device | 24 | §6.2 |
| `0x21` | `EVENT` | relay → device | 168 | §6.4 |
| `0x22` | `STATS` | relay → device | 32 | §6.5 |
| `0x23` | `PING` | relay → device | 4 | §6.3 |
| `0x24` | `PONG` | relay → device | 4 | §6.3 |
| `0x25` | `BYE` | relay → device | 32 | §6.7 |

The listed payload length is the **base length**. A receiver MUST reject `length < base` as
a frame error (§4.3) and MUST accept `length > base`, decoding the first `base` bytes and
ignoring the tail (§16 rule 2). All offsets below are relative to the first payload byte,
which is frame offset 8.

### 6.1 `HELLO` — `0x01`, device → relay, 60 bytes

| Off | W | Type | Field | Meaning, and what a receiver does with an out-of-range value |
|---|---|---|---|---|
| 0 | 4 | `u32` | `last_seq` | highest seq the device has **successfully enqueued for display**; 0 = fresh boot. Any value is legal; `last_seq > latest_seq` is handled by re-baselining (§10.2), not by an error |
| 4 | 4 | `u32` | `caps` | capability bitmap, below. Unknown bits MUST be ignored, never rejected |
| 8 | 2 | `u16` | `rx_max` | largest frame this device accepts, in bytes, including the header. MUST be ≥ 256. A smaller value is `BYE(11 INVALID_PARAMETER)` and close |
| 10 | 2 | `u16` | `reserved0` | MUST be sent as 0; receiver MUST ignore its content |
| 12 | 32 | `char[32]` | `device_id` | 1…31 content bytes, NUL-padded. Blank after trimming ASCII whitespace, no NUL in the field, or any byte `< 0x20` or `> 0x7e` before the first NUL → `BYE(3 INVALID_DEVICE_ID)` and close |
| 44 | 16 | `char[16]` | `fw_version` | free-form ASCII, 0…15 content bytes, NUL-padded; diagnostics only, never parsed. An unterminated or non-ASCII value is logged and otherwise ignored |

`caps` bits:

| Bit | Mask | Name | Meaning |
|---|---|---|---|
| 0 | `0x01` | `CAP_ACK` | the device will send `ACK` frames (§6.6) |
| 1 | `0x02` | `CAP_CHAT` | the device renders chat events and wants them |
| 2 | `0x04` | `CAP_GENERIC` | the device renders the generic kinds `0x00`…`0x03` |
| 3 | `0x08` | `CAP_UTF8_TEXT` | the device has a font beyond US-ASCII; strings are sent verbatim (§9.3) |
| 4–31 | | reserved | senders MUST set 0; receivers MUST ignore unknown bits |

A capability is in effect only if the relay also supports it; `WELCOME.caps` states the
intersection and is authoritative for the connection. A missing capability MUST cause
degradation, never a refusal.

`CAP_CHAT` and the relay's own `notifications.chat` setting are **ANDed**: the relay sends
`CHAT` events only when the device asked for them *and* its own policy allows them. The
device's bit can only narrow, never widen. Neither switch affects `STATS.msg_total` or
`STATS.chat_rate`, which always count every non-bot chat message (§13).

### 6.2 `WELCOME` — `0x20`, relay → device, 24 bytes

| Off | W | Type | Field | Meaning, and out-of-range behaviour |
|---|---|---|---|---|
| 0 | 4 | `u32` | `latest_seq` | highest seq the relay has ever assigned; 0 = nothing yet |
| 4 | 4 | `u32` | `server_time` | unix epoch seconds, UTC. 0 = unknown; the device then keeps using `millis()` alone |
| 8 | 4 | `u32` | `session_id` | opaque; changes whenever the relay's sequence space resets, i.e. once per relay process start (§10.2) |
| 12 | 2 | `u16` | `max_frame` | largest frame the relay accepts, bytes, including header; MUST be ≥ 256. A device seeing a smaller value logs it and continues using 256 |
| 14 | 2 | `u16` | `ping_interval_s` | how often the relay will ping. Informational (§12) |
| 16 | 2 | `u16` | `idle_timeout_s` | how long the relay tolerates inbound silence. Informational (§12) |
| 18 | 2 | `u16` | `replay_window` | number of durable events the relay retains (§10.3) |
| 20 | 4 | `u32` | `caps` | the **effective intersection** of the two capability sets; authoritative |

`ping_interval_s` and `idle_timeout_s` are informational: the device keeps its own timers
(§12) and SHOULD log both advertised values on every connection. This is what kills the class
of incident where an operator changes `application.conf` and believes devices followed.

**The comparison is against the relay's own §12 values, never against the device's**, and an
earlier draft of this document, read the other way, produced two false log lines on every
single connection. §12's table *mandates* that the two sides differ — device 15 s / 45 s
against relay 20 s / 90 s — so a device that compares `ping_interval_s` with its own 15 s and
`idle_timeout_s` with its own 45 s reports the specification as a fault, forever, which is
precisely the noise that buries the one line that matters. Two rules, and they are different
things:

* **Note, at information level:** `ping_interval_s != 20` or `idle_timeout_s != 90`, i.e. the
  relay is not running §12's own numbers. That is the incident this field exists for —
  somebody edited `application.conf` and believed devices followed.
* **Warn:** `idle_timeout_s * 1000 <=` the device's **ping interval**. That is the one setting
  that actually breaks a healthy link: the relay's idle timer is reset by any inbound frame,
  so a relay patient enough to outlast the device's ping interval never drops a working
  device, and one that is not drops it every time.

A warning that fires on every healthy connection is how the one that matters gets missed.

### 6.3 `PING` / `PONG` — `0x02`, `0x03` (device → relay), `0x23`, `0x24` (relay → device), 4 bytes

| Off | W | Type | Field | Meaning |
|---|---|---|---|---|
| 0 | 4 | `u32` | `token` | opaque to the responder |

A `PONG` MUST echo the `token` of the `PING` it answers, byte for byte, and MUST be sent as
soon as the reader returns — it MUST NOT be coalesced or deferred behind other work. A peer
receiving a `PONG` whose token it never sent MUST ignore the token; the frame still counts as
inbound traffic for liveness. The device SHOULD use `millis() / 1000` as its token.

### 6.4 `EVENT` — `0x21`, relay → device, 168 bytes

One record for every notification and every stream event. One layout, one decode path, one
struct.

| Off | W | Type | Field | Meaning, and what a receiver does with an out-of-range value |
|---|---|---|---|---|
| 0 | 4 | `u32` | `seq` | sequence number, strictly increasing from 1 (§10.1). `seq == 0` is illegal: the receiver MUST treat the frame as a frame error (§4.3) and skip it |
| 4 | 4 | `u32` | `ts` | unix epoch seconds, UTC, when the event happened at the relay. 0 = unknown |
| 8 | 4 | `u32` | `value` | primary number; meaning is fixed per `kind` (§6.4.1). Unused for a kind ⇒ sender MUST write 0 and receiver MUST ignore |
| 12 | 4 | `u8[4]` | `reserved1` | **Reserved. Senders MUST write four zero bytes; receivers MUST ignore the content entirely.** These four bytes are held open for a future monetary amount (§8) and are deliberately not repacked, so reintroducing one moves no existing field |
| 16 | 2 | `u16` | `months` | cumulative subscription months; 0 when not applicable |
| 18 | 2 | `u16` | `ttl_ds` | display time in units of 100 ms. 0 = use the receiver's per-kind default. Values above 6000 (10 min) SHOULD be clamped by the receiver |
| 20 | 1 | `u8` | `kind` | event kind (§6.4.1). Unknown ⇒ render as `INFO` from `actor`/`text`, never drop, never close |
| 21 | 1 | `u8` | `tier` | 0 = not applicable, 1 = Prime, 2 = Tier 1, 3 = Tier 2, 4 = Tier 3. Any value > 4 ⇒ receiver MUST treat as 0 and render no tier |
| 22 | 1 | `u8` | `eflags` | bit field, below. Unknown bits MUST be ignored |
| 23 | 1 | `u8` | `reserved2` | **Reserved. Senders MUST write 0; receivers MUST ignore.** Held open as the decimal exponent of a future monetary amount (§8) |
| 24 | 48 | `char[48]` | `actor` | who did it — display name, channel, or the title for generic kinds. ≤ 47 content bytes + NUL (§9) |
| 72 | 96 | `char[96]` | `text` | what they said — message, stream title, or the body for generic kinds. ≤ 95 content bytes + NUL (§9) |

`eflags` bits:

| Bit | Mask | Name | Meaning |
|---|---|---|---|
| 0 | `0x01` | `TEXT_TRUNCATED` | `text` was shortened by the sender (§9.2) |
| 1 | `0x02` | `ACTOR_TRUNCATED` | `actor` was shortened by the sender (§9.2) |
| 2 | `0x04` | reserved | senders MUST set 0; receivers MUST ignore |
| 3 | `0x08` | `ANONYMOUS` | anonymous gifter; `actor` MAY be empty |
| 4 | `0x10` | `CHAT_COLOUR_PRESENT` | `value` carries a real chat name colour, including `0x00000000` for black |
| 5–7 | `0xe0` | reserved | senders MUST set 0; receivers MUST ignore |

Whether a frame was replayed is carried by `flags.REPLAY` in the **header** (§3.2), not in
`eflags`. A receiver SHOULD render a replayed card without the entrance animation, so that a
fifteen-event replay burst after a reconnect is not fifteen full-screen takeovers.

#### 6.4.1 Field meanings per kind

| Code | Kind | `value` | `months` | `tier` | `actor` | `text` |
|---|---|---|---|---|---|---|
| `0x00` | `INFO` | 0 | 0 | 0 | title | body |
| `0x01` | `MESSAGE` | 0 | 0 | 0 | title | body |
| `0x02` | `WARNING` | 0 | 0 | 0 | title | body |
| `0x03` | `ALERT` | 0 | 0 | 0 | title | body |
| `0x10` | `STREAM_START` | stream start, unix seconds | 0 | 0 | channel | stream title |
| `0x11` | `STREAM_END` | stream duration, seconds | 0 | 0 | channel | empty |
| `0x12` | `FOLLOW` | 0 (reserved) | 0 | 0 | follower | empty |
| `0x13` | `SUB` | 0 | cumulative months | sub tier | subscriber | resub message or empty |
| `0x14` | `GIFT` | number of subs gifted | 0 | sub tier | gifter | empty |
| `0x15` | `RAID` | raider's viewer count | 0 | 0 | raider | empty |
| `0x16` | `CHAT` | name colour `0x00rrggbb` | 0 | 0 | chatter | message |
| `0x17` | `BITS` | bits count | 0 | 0 | sender | cheer message or empty |
| `0x18` | *reserved — `DONATION`* | — | — | — | — | — |

**`0x18` is permanently reserved and MUST NOT be sent by a v3 relay.** It is the code a
future version would use to reintroduce donations (§8); a v3 receiver that somehow sees it
applies the unknown-kind rule of §6.4.2 like any other unknown code.

Ranges: `0x00`–`0x0f` generic severities, `0x10`–`0x3f` stream and audience events,
`0x40`–`0xff` reserved for future versions.

**`ttl_ds` per kind — what a v3 relay actually sends.** `ttl_ds = 0` is legal and means "use
the receiver's per-kind default", and a relay that sends one uniform value for every kind is
also legal — and wrong. It gives a follow the same share of a 240×240 display as a stream
transition, and if that uniform value is large it turns the reconnect burst of §10.3 into a
slideshow that keeps the idle dashboard off the screen for minutes. No relay described by the
vectors of §18 behaves that way. A v3 relay MUST send the values below, which are exactly what
V6–V15 pin, frame by frame:

| `ttl_ds` | seconds | kinds |
|---|---|---|
| 60 | 6.0 | `FOLLOW` (V7), `CHAT` (V12, V13) |
| 80 | 8.0 | `SUB` (V8), `GIFT` (V9), `BITS` (V11), and the generic kinds `0x00`–`0x03` (V14) |
| 100 | 10.0 | `RAID` (V10), `STREAM_START` (V6), `STREAM_END` (V15) |

A card posted to `POST /api/v1/notifications` carries whatever `ttl` the caller asked for; a
post that names none takes the relay's configured card default, which is a separate knob from
this table and exists for exactly that one path.

`FOLLOW.value` is fixed at 0 rather than carrying a follower total, because a total of 0
would be indistinguishable from "not reported"; follower counts live in `STATS` (§6.5).
`CHAT.value` is only meaningful when `eflags.CHAT_COLOUR_PRESENT` is set, for the same
reason: black is a legal colour.

#### 6.4.2 Unknown kinds — the rule, and the obligation that makes it work

A receiver that does not know a `kind` MUST render it as `INFO`, using `actor` as the title
and `text` as the body. It MUST NOT drop the event, MUST NOT close the link, and MUST still
apply the sequence accounting of §10.

Correspondingly, **a sender emitting any kind not listed in the table above MUST populate `actor`
and `text` with human-readable fallback text** that stands on its own, because `value`,
`months` and `tier` are uninterpretable without knowing the kind. A relay that adds
`0x19 HYPE_TRAIN` in a later release therefore draws a real card on firmware flashed today.

#### 6.4.3 The generic kinds and the management API

`POST /api/v1/notifications` keeps working unchanged. A plain `{type, title, body, ttl}` post
becomes an `EVENT` with `kind` in `0x00`–`0x03`, `actor` = title, `text` = body,
`ttl_ds = min(ttl_ms / 100, 65535)`, `value = 0`. When the posted `type` names a Twitch kind
(`raid`, `sub`, `bits`, …) the relay MUST still emit that kind, MUST put the posted title in
`actor` and the posted body in `text`, and MUST leave the numeric fields at 0. **A receiver
that knows the kind and finds a non-empty `text` MUST render `actor`/`text` as given rather
than composing its own sentence from the numeric fields.** Otherwise a hand-written test card
is silently replaced by "raided with 0 viewers".

### 6.5 `STATS` — `0x22`, relay → device, 32 bytes

Absolute and self-contained. There is no patch or delta encoding: one captured frame fully
explains the idle screen.

| Off | W | Type | Field | Meaning, and out-of-range behaviour |
|---|---|---|---|---|
| 0 | 4 | `u32` | `viewers` | current viewer count |
| 4 | 4 | `u32` | `msg_total` | **cumulative chat messages since the current stream started**, bot-filtered (§13) |
| 8 | 4 | `u32` | `uptime_s` | seconds since stream start; 0 when offline |
| 12 | 4 | `u32` | `followers` | channel follower total |
| 16 | 4 | `u32` | `subs` | channel subscriber total |
| 20 | 4 | `u32` | `server_time` | unix epoch seconds, UTC, at the moment of encoding; 0 = unknown |
| 24 | 4 | `u32` | `stream_started_at` | unix epoch seconds of the stream start; 0 = offline or unknown |
| 28 | 2 | `u16` | `chat_rate` | messages per minute, bot-filtered. The UI clamps its ring gauge at 100; that is a display decision, not a protocol limit |
| 30 | 1 | `u8` | `live` | 0 = offline, 1 = live. Any non-zero value MUST be treated as live |
| 31 | 1 | `u8` | `sflags` | reserved; senders MUST write 0, receivers MUST ignore |

`msg_total` resets to 0 when a stream starts. It does **not** reset when a stream ends: the
last value stays on screen until the next stream begins. `msg_total` and `chat_rate` count
exactly the same population of messages — every non-bot chat message — regardless of whether
chat cards are enabled, or both numbers lie.

Broadcast cadence: every **5 s**, once immediately after `WELCOME` and the replay burst, and
once immediately after any `STREAM_START` or `STREAM_END` event so that the `live` flag, the
uptime reset and the `msg_total` reset land with the card the device just showed.

`STATS` is idempotent and always re-sent on greet, so it is never replayed.

**Local uptime ticking.** `uptime_s` is a snapshot taken at `server_time`. The device SHOULD
record `millis()` on receipt and display `uptime_s + (millis() − rxMillis) / 1000`,
re-anchoring on every `STATS`, so the label ticks every second instead of jumping in 5 s
steps. `stream_started_at` is carried as well for a device that prefers an absolute anchor;
it is redundant by design and costs four bytes on a frame sent every five seconds.

### 6.6 `ACK` — `0x04`, device → relay, 4 bytes

| Off | W | Type | Field | Meaning |
|---|---|---|---|---|
| 0 | 4 | `u32` | `seq` | highest seq the device has successfully enqueued for display |

Sent only when `CAP_ACK` is in effect, at most once per 200 ms, coalesced to the highest seq.
It is informational: it feeds `GET /api/v1/devices` and lets an operator see a device falling
behind. **The relay MUST NOT make delivery conditional on it**, MUST NOT block waiting for
it, and MUST NOT withhold events because of it.

### 6.7 `BYE` — `0x25`, relay → device, 32 bytes — FROZEN SHAPE

The offsets, widths and semantics of this payload, and the type code `0x25`, are **frozen
across every future version of this protocol**. Nothing may be removed, re-typed or
repurposed; only appended (§16). This is what makes cross-version error reporting possible.

| Off | W | Type | Field | Meaning |
|---|---|---|---|---|
| 0 | 2 | `u16` | `code` | reason code, below |
| 2 | 2 | `u16` | `detail` | code-specific detail; 0 when unused |
| 4 | 2 | `u16` | `retry_after_s` | requested minimum backoff before reconnecting; 0 = receiver's choice |
| 6 | 2 | `u16` | `reserved0` | MUST be 0 |
| 8 | 24 | `char[24]` | `reason` | ASCII, NUL-padded, ≤ 23 content bytes; for logs only, never parsed |

| `code` | Name | `detail` |
|---|---|---|
| 1 | `UNSUPPORTED_VERSION` | the version this relay speaks (3) |
| 2 | `BAD_HANDSHAKE` | the type code actually received |
| 3 | `INVALID_DEVICE_ID` | 0 |
| 4 | `INVALID_SEQUENCE` | 0 |
| 5 | `FRAMING_VIOLATION` | 0 |
| 6 | `FRAME_TOO_LARGE` | the relay's `max_frame` |
| 7 | `DUPLICATE_HELLO` | 0 |
| 8 | `SERVER_SHUTDOWN` | 0 |
| 9 | `REPLACED` | 0 — another connection claimed this device id |
| 10 | `RATE_LIMIT` | 0 |
| 11 | `INVALID_PARAMETER` | the payload offset of the offending field |
| 12 | `HANDSHAKE_TIMEOUT` | 0 |
| 13 … 999 | reserved | |
| 1000 + | private | a receiver treats an unknown code as a generic teardown |

`BYE` is advisory and always the **last** frame on the connection: the sender closes
immediately after writing it and MUST NOT wait for a reply. A receiver MUST close, MUST log
`code`, `detail` and `reason`, and MUST NOT answer.

**A `BYE` is always encoded with the `version` byte of the frame that provoked it** (and with
the negotiated version when nothing provoked it). That is what guarantees a peer can read a
refusal from a peer whose version it does not speak.

The device never sends `BYE`; it closes the socket. Only the relay has something to explain.

---

## 7. Version negotiation

Version handling is **exact equality**, not a range, and is resolved at the header's
`version` byte.

* The device sends every frame with `version = 3` and MUST check `version == 3` on every
  inbound frame.
* The relay MUST check `version == 3` on the `HELLO`. On mismatch it MUST send
  `BYE(1 UNSUPPORTED_VERSION, detail = 3, retry_after_s = 30, "…")`, encoded with the
  version byte the device used, and close.
* A device receiving a frame whose `version` is not 3 MUST treat it as a session error: log
  it, close, and go to backoff. A device receiving `BYE(1)` MUST go straight to the **30 s**
  backoff cap rather than the exponential ramp — a version mismatch needs a reflash, not a
  retry, and hammering the relay buries the one log line that explains it.

There is exactly one relay and a handful of devices, all flashed by the same person. A
negotiated version range would mean maintaining two encoders in the firmware forever, in
flash and in test surface, to serve a case that is resolved by reflashing. The part v2 got
wrong was not the strictness — it was the silence.

---

## 8. There are no monetary amounts on this wire

**TSB/3 carries no money.** There is no donation event, no currency code, no decimal
exponent, no minor-unit amount and no floating-point value anywhere in this protocol, in
either direction, at any point.

The reason is not squeamishness about rounding — it is that nothing could fill the field.
Twitch has no native donation event, and the relay integrates with no donation provider
(StreamElements, StreamLabs, Ko-fi and Twitch Charity are all absent from its build and from
its HTTP surface). A `DONATION` kind would therefore have been a wire field that no producer
could ever populate, carried on every frame layout and asserted by every test, forever.

**Bits are not money and are unaffected.** `BITS.value` (kind `0x17`) is a plain `u32` count
of bits, which is exactly what Twitch reports. It carries no currency and needs none.

### 8.1 The space held open for a future version

Two payload slots in `EVENT` are reserved rather than reclaimed, and this is deliberate:

| Offset | Width | Name | Future use |
|---|---|---|---|
| 12 | 4 | `reserved1` | the ISO 4217 alphabetic currency code, 3 bytes + NUL |
| 23 | 1 | `reserved2` | the decimal exponent applied to `value` |

Senders MUST write zeros into both; receivers MUST ignore their content. Because they were
not repacked, a v4 that reintroduces donations moves **no existing field**: every offset in
§6.4, every `static_assert` in §15.1 and every golden vector in §18 that does not itself
carry an amount stays byte-for-byte valid. Reclaiming those five bytes now would have saved
five bytes on a 176-byte frame and cost a full re-pin of the wire later.

Should a future version reintroduce donations, the shape is already decided: `value` becomes
an unsigned count of minor units, `reserved2` becomes the decimal exponent, `reserved1`
becomes the alphabetic ISO 4217 code, kind `0x18` becomes `DONATION`, and the amount is still
never a float — the relay scales a `BigDecimal` HALF_UP and the device renders with integer
division. None of that is normative today.

---

## 9. Strings

* Encoding is **UTF-8**. There is no other string encoding and no code-page negotiation.
* String fields are **fixed-width, NUL-padded byte arrays**. There is no length prefix, so
  no length field can ever point past a buffer.
* A sender MUST write at most `N−1` content bytes into a `char[N]` field and MUST zero every
  remaining byte. **The final byte of a string field is therefore always `0x00`.**
* A sender MUST NOT emit a byte below `0x20` other than the padding NULs, and MUST NOT emit
  `0x7f`.

### 9.1 Receiver rule — one store, no trust

**A receiver never trusts the sender for termination. After copying a string field, it MUST
force the last byte of its own copy to `0x00`.** That single store makes every downstream
`strlen`, `strlcpy` and `lv_label_set_text` safe no matter what arrives. A receiver MUST NOT
reject a frame for invalid UTF-8; it MAY render replacement glyphs. Malformed text is a
cosmetic problem, never a link problem.

### 9.2 Sender truncation — the exact algorithm

When a value does not fit a field of width `N` (cap = `N − 1` content bytes), the sender MUST
produce:

1. let `b` be the UTF-8 bytes of the value; if `len(b) ≤ cap`, emit `b` unchanged;
2. otherwise let `cut = cap − 3`, then **while `cut > 0` and `(b[cut] & 0xc0) == 0x80`,
   decrement `cut`** — this walks back to the last code-point boundary;
3. emit `b[0 … cut)` followed by `2e 2e 2e` (`"..."`);
4. set the matching `eflags` bit (`TEXT_TRUNCATED` / `ACTOR_TRUNCATED`).

A multi-byte sequence is never split. The result is at most `cap` bytes and may be shorter
when the walk-back dropped a multi-byte character (vector V13 pins this: 46 and 94 bytes
against caps of 47 and 95).

### 9.3 ASCII folding and `CAP_UTF8_TEXT`

The device's fonts (Montserrat 14/20/28/48 as built) cover `0x20`–`0x7e` plus `0xb0` and
`0x2022`; anything else renders as a missing glyph.

* If the device did **not** set `CAP_UTF8_TEXT`, the relay MUST reduce every outbound string
  to printable US-ASCII (`0x20`–`0x7e`) before encoding: transliterate where it has a
  mapping (`ł`→`l`, `ä`→`a`, `Ж`→`Zh`), drop what it cannot map, collapse the resulting runs
  of spaces. **The relay has no login and no user id to fall back on — it carries the display
  name and nothing else (§13)** — so for a display name that folds away to nothing it MUST
  substitute the fixed ASCII placeholder `viewer` rather than emitting an empty `actor`.
* If the device set `CAP_UTF8_TEXT`, the relay sends the string verbatim.

**Folding happens before truncation**, so the cap is a true byte budget in both modes. The
wire is specified as UTF-8 regardless, so that regenerating a font later is a firmware change
alone with no protocol bump.

---

## 10. Sequence numbers, replay, backpressure

### 10.1 Assignment

Every `EVENT` carries a `u32 seq`, strictly increasing, starting at 1. `seq = 0` is reserved
and never assigned. No other frame type is sequenced. The device keeps `seq` as a `uint32_t`
and MUST NOT narrow it.

Monotonicity on the wire depends on the relay's hub actor assigning numbers on a single
thread and never blocking. **Frame encoding MUST NOT happen inside the hub**: either keep
queueing domain values and encode in each session's writer fork, or encode once into an
*immutable* `Array[Byte]` before broadcast and share it read-only. A per-frame
`ByteArrayOutputStream` or a shared mutable buffer inside the hub breaks the ordering
guarantee.

The counter is not persisted across relay restarts; it restarts at 0 and `session_id` covers
the consequence. The relay MUST NOT let it wrap past `0xffffffff`.

### 10.2 Baseline and re-baseline

On each `WELCOME`, the device applies exactly one test:

1. fresh boot (`last_seq == 0`) **or** `latest_seq < last_seq` ⇒ **re-baseline**:
   `last_seq = latest_seq`, keep whatever is already queued for display;
2. otherwise keep `last_seq` and expect the replay burst.

**This test and the relay's replay test of §10.3 are the same predicate, computed from the
same two numbers, and that is the point of stating it this way.** The device re-baselines on
exactly the greets for which the relay sends no replay, and expects the burst on exactly the
greets for which the relay sends one. A condition that only one side can evaluate is not a
protocol rule, it is two behaviours that agree until they do not.

`session_id` is **not** part of this test, and this is a correction to an earlier draft of
this document. That draft made a differing `session_id` a third re-baseline trigger, on the
reasoning that a restarted relay which happened to reach a *higher* seq than the device had
seen would otherwise silently skip the gap. The relay cannot implement the matching rule:
`HELLO` (§6.1) carries no `session_id` echo, so the relay has no way to know whether the
`last_seq` it is being shown belongs to its own sequence space or to a dead one. The two
sides therefore used different tests for the same condition, and after a relay restart the
relay pushed a replay burst that the device — having just re-baselined to `latest_seq` —
discarded frame by frame as duplicates. Nothing was drawn and the outbound queue was spent.

Dropping the clause also loses nothing. When a restarted relay is at `latest_seq = 130` and
the device holds 117, the events the relay still retains above 117 are events this device has
genuinely never seen, so replaying them is right; the events *below* 117 in the new sequence
space are past the end of a 64-entry ring and are unrecoverable either way. Re-baselining
did not close that hole, it only hid it. The number-based test delivers strictly more, and
both sides can compute it.

`session_id` stays on the wire and keeps its §6.2 meaning — it changes once per relay process
start. It is diagnostic: a device SHOULD log it, because "the relay restarted under me" is
the first thing an operator wants to know from a serial log, and a future version that adds a
`session_id` echo to `HELLO` can make the §10.3 precondition exact without moving a field.

`last_seq` lives in RAM only; there is no NVS persistence, so every power loss re-baselines.

### 10.3 Replay

The relay retains, per the `replay_window` it advertised:

* a **durable ring of 64** events of every kind except `CHAT`, and
* a **separate ring of 16** `CHAT` events.

Every `EVENT` is sequenced and every `EVENT` is replayable. The split exists so that a busy
chat cannot evict follows, raids and subs from the buffer; it does **not** create an
unsequenced or unreplayable kind, because a kind that is skipped by replay lets a later event
hold the high-water mark past a lost durable one, which silently defeats replay exactly when
it matters.

On greet, and only when `0 < last_seq <= latest_seq`, the relay sends every retained event
with `seq > last_seq` from both rings, **merged into one ascending `seq` order**, each with
`flags.REPLAY` set. That condition is the exact complement of §10.2's re-baseline test, which
is what makes the two sides agree: the relay replays on precisely the greets for which the
device kept its mark. It then sends exactly one `STATS`. Replayed events are
filtered by the effective capabilities exactly as live events are: a device without
`CAP_CHAT` is not buried in replayed chat on reconnect.

A device that sent `last_seq = 0` receives exactly two frames on greet: `WELCOME` and
`STATS`. The replayed sequence may contain gaps (a capability-filtered kind, an evicted chat
line); the device tracks a high-water mark, not a set.

### 10.4 Backpressure at the relay

Backpressure is **drop, not block**. A device whose outbound queue (128 frames) is full loses
the frame the relay was about to enqueue, and the loss is counted. Only `EVENT` frames are
recoverable, via replay. Every type is assigned deliberately:

| Type | Recoverable | How |
|---|---|---|
| `EVENT` | yes | replay buffer, §10.3 |
| `STATS` | no | next 5 s tick, and one on every greet |
| `PING` / `PONG` | no | next heartbeat |
| `WELCOME` / `BYE` | n/a | handshake / teardown |

Any type added in a future version MUST state which bucket it is in.

### 10.5 The device's queue — two normative requirements

These two rules cost nothing on the wire and are the difference between replay working and
replay being decorative.

1. **The device MUST advance `last_seq` only after an event has been successfully enqueued
   for display**, never at the moment of decode.
2. **When its queue is full the device MUST refuse the newest event and MUST NOT advance
   `last_seq` past it** — it MUST NOT drop the oldest queued entry to make room. Dropping the
   oldest advances the high-water mark past an event that was never shown, and the reconnect
   replay can then never bring it back.

Today's firmware does the opposite of both (`main.cpp` advances `lastSeq` before `enqueue`,
and `enqueue` drops the oldest on overflow), which is why a raid burst silently loses cards.

---

## 11. Handshake

### 11.1 Order

```
device                                    relay
  |---- TCP connect ---------------------->|
  |---- HELLO ---------------------------->|   first frame, immediately
  |                                        |   validate version, device_id, rx_max
  |<--- WELCOME ---------------------------|   within 5 s
  |<--- EVENT (replayed, 0…80, REPLAY set)-|   only if last_seq > 0 and no re-baseline
  |<--- STATS -----------------------------|   exactly one
  |            ... steady state ...        |
```

1. The device MUST send `HELLO` immediately after connect, before reading anything, and MUST
   receive `WELCOME` within **5 s** or tear down.
2. The relay MUST require the first frame to be `HELLO`; it MUST receive one within **5 s**
   of accept or send `BYE(12 HANDSHAKE_TIMEOUT)` and close. Any other first frame is
   `BYE(2 BAD_HANDSHAKE, detail = the type received)` and close.
3. A second `HELLO` on an established session is `BYE(7 DUPLICATE_HELLO)` and close.
4. The device MUST NOT act on any `EVENT` or `STATS` received before `WELCOME`.
5. The greeting burst order is fixed: `WELCOME` → replayed `EVENT`s in ascending `seq` →
   exactly one `STATS`.

### 11.2 During the handshake, tolerance is suspended

Before `WELCOME` has been exchanged, §4.3's "skip the frame and continue" does not apply. The
relay's first inbound frame MUST be a valid `HELLO`; anything else — wrong type, short
payload, wrong version, invalid device id — is fatal and answered with `BYE` and a close. The
device's first inbound frame MUST be `WELCOME` or `BYE`; anything else is a teardown. After
`WELCOME`, §4.3 applies normally in both directions.

---

## 12. Heartbeat, timers, reconnect

Every value is unchanged from v2, which the firmware already implements correctly. Only the
encoding changes.

| Timer | Owner | Value |
|---|---|---|
| connect timeout | device | 3 s |
| welcome timeout | device | 5 s |
| device ping interval | device | every 15 s |
| device idle timeout | device | 45 s with no inbound frame of any type |
| relay handshake timeout | relay | 5 s |
| relay ping interval | relay | 20 s |
| relay idle timeout | relay | 90 s with no inbound frame of any type |
| stats broadcast | relay | every 5 s, plus one on greet, plus one after a stream transition |
| reconnect backoff | device | 1 s doubling to a 30 s cap, +0…25 % jitter, forever; reset on `WELCOME` |

The relay is deliberately more patient than the device so that the **device**, not the relay,
decides when to reconnect. `ping_interval < idle_timeout` MUST hold on each side and the
relay MUST enforce it at startup. The asymmetry between the two columns is mandated, not
tolerated: §6.2 says what the device does with the relay's advertised values, and it is not to
warn that they differ.

The device's idle timeout is measured in **whole frames**, and so is the relay's. A read
timeout on a single socket read is not the same thing: a peer that emits one byte every
89 seconds and never completes a frame restarts a per-read timer forever while satisfying
nothing. Both sides MUST time the interval since the last *complete inbound frame*.

**Any inbound frame of any type resets the idle timer** — including a frame that was skipped
under §4.3 or discarded under §4.5. A `PING` MUST be answered with a `PONG` echoing `token`
as soon as the reader returns.

### 12.1 Backoff

On any teardown the device closes the socket and retries with 1 s, 2 s, 4 s, 8 s, 16 s,
capped at 30 s, plus up to 25 % jitter, forever. The backoff resets after a successful
`WELCOME`. `BYE.retry_after_s`, when non-zero, raises the floor for the next attempt, and
`BYE(1 UNSUPPORTED_VERSION)` goes straight to the 30 s cap (§7). WiFi loss tears the socket
down immediately; reconnection follows WiFi.

---

## 13. Bot filtering, and the two limits of the event sources

### 13.1 Bot-authored events never reach the wire

**Bot events are filtered by the server, before anything is encoded. The protocol carries no
bot flag, no `is_bot` bit and no actor-kind field, and the device implements no filtering.**

* The relay MUST filter at the **source** — in the twitch4j chat handlers and the EventSub
  handlers, before publishing to the internal event bus — so that a bot costs no bus
  capacity, no sequence number and no replay slot. Filtering further downstream would leave
  `msg_total` and `chat_rate` inflated, and those two numbers are exactly what the idle
  screen shows.
* Matching is on the **lowercased, trimmed display name**. Both chat messages and non-chat
  events from a matched account are suppressed, and matched accounts MUST be excluded from
  `STATS.msg_total` and `STATS.chat_rate`.

Default ignore list, configuration key **`notifications.ignored-display-names`**, overridable
by environment variable:

```
streamelements, nightbot, moobot, streamlabs, fossabot, sery_bot
```

The key is named for what it actually matches. It is not `ignored-logins`, because the relay
does not have logins.

**This is weaker than matching on the login, and the weakness is worth stating precisely.** A
Twitch *login* is lowercase, unique and stable; a *display name* is user-settable and may be
changed at will, and two accounts may present display names that differ only in characters
the fold collapses. The relay carries a display name and nothing else, by decision, so:

* a bot that changes its display name stops being matched until the list is updated, and
* a human who sets their display name to `Nightbot` is silently suppressed.

The six names above are matched reliably in practice only because each of those services
ships with its display name equal to its login apart from capitalisation. That is a property
of those particular accounts, not a property of Twitch, and it is the whole basis on which
this matching works. Nothing on the wire changes if the relay later starts carrying logins —
this is a relay-side matter, and the protocol is indifferent to it.

The device is given no way to override any of this, and that is deliberate: it has no list,
no storage for one and no way to update one. An operator debugging "why did nightbot's
message appear" looks at relay configuration and relay logs, never at the wire.

### 13.2 SUB, GIFT, BITS and RAID arrive over IRC only

Subscriptions, gifted subs, cheers and raids reach the relay **only over the IRC chat
connection**. EventSub — on either transport — is scoped to exactly four subscription types:
`stream.online`, `stream.offline`, `channel.update` and `channel.follow`. No EventSub
subscription is registered for `channel.subscribe`, `channel.subscription.gift`,
`channel.cheer` or `channel.raid`, on the WebSocket transport or the webhook transport.

The consequence, stated plainly because it is an accepted limitation of v3 and not a bug to
be worked around:

| Event | WebSocket / IRC deployment | Webhook deployment with no IRC connection |
|---|---|---|
| `STREAM_START`, `STREAM_END` | delivered (EventSub) | delivered |
| `FOLLOW` | delivered (EventSub) | delivered |
| `CHAT` | delivered (IRC) | **not delivered** |
| `SUB`, `GIFT`, `BITS`, `RAID` | delivered (IRC) | **not delivered** |

A relay running the webhook transport without a chat connection will therefore emit stream
lifecycle and follow events and nothing else. This is a property of which subscriptions the
relay registers, not of this protocol: the wire can carry all seven kinds, and the day those
subscriptions are added the wire format needs no change and no version bump.

---

## 14. Counters

With a binary wire the true byte count is finally available. `bytesSent` and `bytesReceived`
MUST count `8 + length` per frame; v2 counted UTF-16 characters plus one, which was wrong on
two axes. Both sides SHOULD additionally expose: `framesUnknownType`, `framesWrongDirection`,
`framesShortPayload`, `framesInvalidField`, `framesOversizeSkipped`, `resyncEvents` and
`framesDropped`. Those are how a version skew or an encoder bug is diagnosed in the field,
and they cost nothing.

---

## 15. Implementation requirements

### 15.1 Firmware (C++11, `-fno-exceptions`, `-fno-rtti`, `-Os`)

The wire records are the structs. Both are declared in wire order and asserted at build time.

```c++
struct TsbEvent {              // == EVENT payload, 168 bytes
  uint32_t seq;                // +0
  uint32_t ts;                 // +4
  uint32_t value;              // +8
  uint8_t  reserved1[4];       // +12  MUST be zero on the wire (§8.1)
  uint16_t months;             // +16
  uint16_t ttl_ds;             // +18
  uint8_t  kind;               // +20
  uint8_t  tier;               // +21
  uint8_t  eflags;             // +22
  uint8_t  reserved2;          // +23  MUST be zero on the wire (§8.1)
  char     actor[48];          // +24
  char     text[96];           // +72
};

struct TsbStats {              // == STATS payload, 32 bytes
  uint32_t viewers;            // +0
  uint32_t msg_total;          // +4
  uint32_t uptime_s;           // +8
  uint32_t followers;          // +12
  uint32_t subs;               // +16
  uint32_t server_time;        // +20
  uint32_t stream_started_at;  // +24
  uint16_t chat_rate;          // +28
  uint8_t  live;               // +30
  uint8_t  sflags;             // +31
};

static_assert(sizeof(TsbEvent) == 168, "TsbEvent wire size");
static_assert(offsetof(TsbEvent, reserved1) == 12, "TsbEvent.reserved1 offset");
static_assert(offsetof(TsbEvent, kind)     == 20, "TsbEvent.kind offset");
static_assert(offsetof(TsbEvent, actor)    == 24, "TsbEvent.actor offset");
static_assert(offsetof(TsbEvent, text)     == 72, "TsbEvent.text offset");
static_assert(sizeof(TsbStats) == 32, "TsbStats wire size");
static_assert(offsetof(TsbStats, stream_started_at) == 24, "TsbStats.stream_started_at offset");
static_assert(offsetof(TsbStats, chat_rate)         == 28, "TsbStats.chat_rate offset");
```

(`static_assert` requires the message argument at `-std=gnu++11`.) The existing `StreamStats`
and `Notification` structs must be reordered into wire order: today `StreamStats` begins with
a `bool` and `Notification` has `title` at offset 5, so neither can be copied as-is.

Decoding is: `if (length < sizeof(T)) { skip; }` then `memcpy(&dst, payload, sizeof(T))`,
then force the last byte of each string field to `0`. No casts through `uint32_t*`.

Other consequences: the receive buffer becomes `alignas(4) uint8_t rx[248]` plus
`uint8_t hdr[8]`, replacing `char lineBuf[512]`; `ArduinoJson` leaves `lib_deps`, since
`link_client.cpp` is its only consumer firmware-wide and removing it takes the per-frame
heap-allocating `JsonDocument` off the RX path; `NotifyKind` gains `StreamStart` and
`StreamEnd` (but **not** `Donation`) and a `kindFromCode(uint8_t)` validator replacing
`kindFromString`; `Notification` carries `ts`, `value`, `months`, `ttl_ds`, `tier`, `eflags`,
`actor[48]`, `text[96]`. No allocation of any kind occurs on the read path.

### 15.2 Relay (Scala 3)

`WireJson.scala` is deleted. `FrameCodec.encode` becomes a write into an `OutputStream` (or a
`ServerFrame => Array[Byte]`), `decode` takes an `Array[Byte]` plus a length; `DeviceSession`
replaces `BufferedReader`/`BufferedWriter` with `InputStream`/`OutputStream`. `ProtocolError`
gains `BadMagic`, `HeaderCheckFailed`, `LengthOutOfRange`, `UnknownType`, `WrongDirection`,
`ShortPayload` and `InvalidField`, each mapped to §4.3 (skip) or §4.4/§4.5 (close).
`StreamStats` gains `messagesTotal` and `streamStartedAt`; `StatsState` gains the cumulative
counter, reset on `StreamStarted`; `RelayEvent`'s audience cases gain the structured numeric
fields the router currently flattens into English (`viewers`, `bits`, `tier`,
`cumulativeMonths`, `giftCount`) but **no** `Donated` case and **no** login or user-id field;
`NotificationKind` gains a `code: Byte` alongside its `wire: String`,
which the HTTP API keeps using. `application.conf`: `protocol-version = 3`,
`max-frame-length = 256` (bytes, with a comment saying so), and a new
`notifications.ignored-display-names` list under the existing `notifications` section — not a
new top-level section, which would break the config section-name test.

No new dependency is needed or justified: `java.nio.ByteBuffer` with `LITTLE_ENDIAN` order is
the entire codec. Scalac's `-Wvalue-discard` and `-Wnonunit-statement` make byte-writing
chains noisy; `.discard` or an explicit `()` is expected.

---

## 16. Forward compatibility — and its exact boundaries

Three mechanisms, all cheap enough for the device:

1. **A new message type.** The receiver does not know the code, skips the payload by
   `length`, counts it, and keeps the link (§4.3). Old peers survive new types
   unconditionally. This is why the length precedes everything except the magic.
2. **A new field appended to an existing type.** Appended at the tail, never inserted, never
   reordered, never resized, never removed — an obsolete field becomes `reserved` and keeps
   its bytes. A receiver MUST accept `length > base`, decode the first `base` bytes and
   ignore the tail. Every appended field MUST have a meaningful zero value.
3. **A new event kind.** Unknown kinds render as `INFO` from `actor`/`text` (§6.4.2), which is
   renderable rather than merely skippable because the sender is obliged to fill those two
   fields for any kind outside the registry.

**Frozen for all versions ≥ 3, and never changeable:** the 8-byte header layout, the magic
`0xa7 0x53`, the `version` byte at offset 2, `type = 0x00` being illegal, little-endian, and
the `BYE` payload (§6.7) together with its type code `0x25`.

**The honest boundaries.** A payload cannot grow past 248 bytes without a version bump, and
`EVENT` is already at 168, leaving 80 bytes of tail. A field cannot become optional or
variable-length: every field costs its width on every frame of that type, forever. Changing
the meaning, width, offset or unit of an existing field is a **version bump**, not a
compatible change — there is no field-name metadata on the wire to protect anyone from
getting this wrong, only the golden vectors of §18. And because the handshake is
exact-match (§7), rules 1–3 buy a *staged* rollout only if a future relay deliberately keeps
3 in its supported set and encodes v3-shaped frames; out of the box, a version bump means
reflashing. For one relay and a handful of devices that is the correct severity.

---

## 17. Conformance

Both implementations MUST pin the byte vectors of §18 as tests — the relay in
`FrameCodecSuite` (today a set of JSON string literals; it becomes byte-array assertions,
frame for frame), the firmware in a host-compiled unit test over the decoder plus the
`static_assert`s of §15.1. Pinning the wire against this document on both sides is the
mechanism by which two independent implementations stay aligned, and it is the single most
valuable test in the repository.

**Both suites MUST run under a command the repository configures** — `./mill test` on the
relay and `pio test` on the firmware — and not only under a compiler line typed by hand out of
a comment. A golden-vector suite that nothing runs pins nothing: the drift it exists to catch
lands in a build that is still green, and the first symptom is a card on the display whose
title is four bytes of the previous field.

The behaviours the existing end-to-end suite covers remain the acceptance criteria and MUST
all still hold: handshake; a fresh device receives exactly two frames; push; replay after
reconnect; no replay when `last_seq == 0`; device ping answered with pong; relay ping
answered by device pong; version mismatch rejected — now with a `BYE` to assert on; a
malformed-but-well-framed frame does not kill the link.

Additional v3 cases that MUST be covered:

1. an unknown `type` is skipped and the next frame decodes correctly;
2. a `length` one byte short of a known base is skipped, counted, and the link survives;
3. `length = 249` is a framing violation;
4. a `STATS` frame with four extra trailing bytes decodes correctly (tail extension);
5. an `EVENT` with `kind = 0x7f` renders as `INFO` from `actor`/`text`;
6. an `EVENT` with `seq = 0` is skipped and does not disturb the high-water mark;
7. a string field arriving with no NUL in its last byte is terminated by the receiver;
8. a stray `0xa7` inside a payload does not cause a false lock (the one-byte-shift rescan);
9. injecting 4096 random bytes mid-stream causes exactly one teardown and one clean
   reconnect;
10. `viewers = 0x12345678` encodes as `78 56 34 12` (endianness);
11. an `EVENT` whose `reserved1` or `reserved2` bytes are non-zero is decoded normally and
    those bytes are ignored, not rejected;
12. `msg_total` and `chat_rate` exclude a bot-authored message;
13. a chat message whose truncation point falls inside a multi-byte sequence truncates to the
    code-point boundary, not the byte (vector V13).

---

## 18. Golden test vectors

**This is the most important section of the document.** It is the mechanism by which two
independently written implementations stay aligned: both MUST assert these exact bytes, and
any disagreement with them is a bug in the implementation, never in the vector.

Each vector gives the complete frame — header and payload — as lowercase hex, whitespace
separated, sixteen bytes per row, in transmission order. Every length field, every `hchk` and
every string padding run below was computed mechanically from the field tables in §6, and
each frame was re-validated against §3.1 and §4.2 after generation.

There are twenty vectors: the handshake in both directions, telemetry live and offline, every
one of the ten event kinds a v3 relay can emit, both heartbeat directions, the acknowledgement
and the refusal. There is no donation vector, because there is no donation event (§8).

The vectors form one coherent scenario. The relay is at `server_time = 1790309000`; the
stream started at `1790305340`, i.e. 3660 seconds (1 h 01 m) earlier. The device is
`roundlcd-01` running firmware `1.0.0`. Sequence numbers run 118 → 127, and V2's
`last_seq = 117` with V3's `latest_seq = 127` means the replay burst is exactly the ten event
vectors below, in order.

Reading the hex: bytes 0–7 are always the header — `a7 53` magic, `03` version, the type
code, the two little-endian length bytes, the flags byte, the header check. Multi-byte
integers are little-endian everywhere, so `4a 03 00 00` is 842 and `88 f2 b5 6a` is
1 790 309 000.

### V1. `hello_fresh_boot` — 68 bytes

```
a7 53 03 01 3c 00 00 79 00 00 00 00 07 00 00 00
00 01 00 00 72 6f 75 6e 64 6c 63 64 2d 30 31 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 31 2e 30 2e 30 00 00 00 00 00 00 00
00 00 00 00
```

- `header.magic` = a7 53
- `header.version` = 3
- `header.type` = 0x01 HELLO
- `header.length` = 60
- `header.flags` = 0x00
- `last_seq` = 0 (fresh boot: no replay requested)
- `caps` = 0x00000007 = CAP_ACK | CAP_CHAT | CAP_GENERIC
- `rx_max` = 256
- `reserved0` = 0
- `device_id` = "roundlcd-01" — 11 content bytes, NUL-padded to 32
- `fw_version` = "1.0.0" — NUL-padded to 16

### V2. `hello_resume_utf8` — 68 bytes

```
a7 53 03 01 3c 00 00 79 75 00 00 00 0f 00 00 00
00 01 00 00 72 6f 75 6e 64 6c 63 64 2d 30 31 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 31 2e 30 2e 30 00 00 00 00 00 00 00
00 00 00 00
```

- `header.type` = 0x01 HELLO
- `header.length` = 60
- `header.flags` = 0x00
- `last_seq` = 117 — replay everything with seq > 117
- `caps` = 0x0000000f = CAP_ACK | CAP_CHAT | CAP_GENERIC | CAP_UTF8_TEXT
- `rx_max` = 256
- `reserved0` = 0
- `device_id` = "roundlcd-01"
- `fw_version` = "1.0.0"

### V3. `welcome` — 32 bytes

```
a7 53 03 20 18 00 00 b1 7f 00 00 00 88 f2 b5 6a
a0 17 4e 9c 00 01 14 00 5a 00 40 00 0f 00 00 00
```

- `header.type` = 0x20 WELCOME
- `header.length` = 24
- `header.flags` = 0x00
- `latest_seq` = 127
- `server_time` = 1790309000
- `session_id` = 0x9c4e17a0
- `max_frame` = 256
- `ping_interval_s` = 20
- `idle_timeout_s` = 90
- `replay_window` = 64
- `caps` = 0x0000000f — the effective intersection, authoritative

### V4. `stats_live` — 40 bytes

```
a7 53 03 22 20 00 00 81 4a 03 00 00 57 08 00 00
4c 0e 00 00 70 30 00 00 3e 01 00 00 88 f2 b5 6a
3c e4 b5 6a 23 00 01 00
```

- `header.type` = 0x22 STATS
- `header.length` = 32
- `header.flags` = 0x00
- `viewers` = 842
- `msg_total` = 2135 — bot-filtered, since stream start
- `uptime_s` = 3660
- `followers` = 12400
- `subs` = 318
- `server_time` = 1790309000
- `stream_started_at` = 1790305340
- `chat_rate` = 35
- `live` = 1
- `sflags` = 0

### V5. `stats_offline` — 40 bytes

```
a7 53 03 22 20 00 00 81 00 00 00 00 57 08 00 00
00 00 00 00 70 30 00 00 3e 01 00 00 e0 f4 b5 6a
00 00 00 00 00 00 00 00
```

- `header.type` = 0x22 STATS
- `header.length` = 32
- `viewers` = 0
- `msg_total` = 2135 — retained until the next stream starts
- `uptime_s` = 0
- `followers` = 12400
- `subs` = 318
- `server_time` = 1790309600
- `stream_started_at` = 0 (offline)
- `chat_rate` = 0
- `live` = 0
- `sflags` = 0

### V6. `event_stream_start` — 176 bytes

```
a7 53 03 21 a8 00 00 dd 76 00 00 00 3c e4 b5 6a
3c e4 b5 6a 00 00 00 00 00 00 64 00 10 00 00 00
77 30 72 78 62 65 6e 64 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
52 6f 75 6e 64 20 4c 43 44 20 62 75 69 6c 64 20
6e 69 67 68 74 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

- `header.type` = 0x21 EVENT
- `header.length` = 168
- `header.flags` = 0x00
- `seq` = 118
- `ts` = 1790305340
- `value` = 1790305340 — stream start, unix seconds
- `reserved1` = 00 00 00 00
- `months` = 0
- `ttl_ds` = 100 (10.0 s)
- `kind` = 0x10 STREAM_START
- `tier` = 0
- `eflags` = 0x00
- `reserved2` = 0
- `actor` = "w0rxbend" — the channel
- `text` = "Round LCD build night" — the stream title

### V7. `event_follow` — 176 bytes

```
a7 53 03 21 a8 00 00 dd 77 00 00 00 7e f2 b5 6a
00 00 00 00 00 00 00 00 00 00 3c 00 12 00 00 00
6e 65 77 66 72 69 65 6e 64 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

- `header.type` = 0x21 EVENT
- `header.length` = 168
- `header.flags` = 0x00
- `seq` = 119
- `ts` = 1790308990
- `value` = 0 — fixed at 0 for FOLLOW
- `reserved1` = 00 00 00 00
- `months` = 0
- `ttl_ds` = 60 (6.0 s)
- `kind` = 0x12 FOLLOW
- `tier` = 0
- `eflags` = 0x00
- `reserved2` = 0
- `actor` = "newfriend" — the follower
- `text` = empty (all 96 bytes zero)

### V8. `event_sub` — 176 bytes

```
a7 53 03 21 a8 00 00 dd 78 00 00 00 83 f2 b5 6a
00 00 00 00 00 00 00 00 0e 00 50 00 13 03 00 00
6c 6f 79 61 6c 76 69 65 77 65 72 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
66 6f 75 72 74 65 65 6e 20 6d 6f 6e 74 68 73 21
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

- `header.type` = 0x21 EVENT
- `header.length` = 168
- `header.flags` = 0x00
- `seq` = 120
- `ts` = 1790308995
- `value` = 0
- `reserved1` = 00 00 00 00
- `months` = 14 — cumulative subscription months
- `ttl_ds` = 80 (8.0 s)
- `kind` = 0x13 SUB
- `tier` = 3 = Tier 2
- `eflags` = 0x00
- `reserved2` = 0
- `actor` = "loyalviewer" — the subscriber
- `text` = "fourteen months!" — the resub message

### V9. `event_gift` — 176 bytes

```
a7 53 03 21 a8 00 00 dd 79 00 00 00 86 f2 b5 6a
05 00 00 00 00 00 00 00 00 00 50 00 14 02 00 00
67 65 6e 65 72 6f 75 73 70 61 6c 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

- `header.type` = 0x21 EVENT
- `header.length` = 168
- `header.flags` = 0x00
- `seq` = 121
- `ts` = 1790308998
- `value` = 5 — subs gifted
- `reserved1` = 00 00 00 00
- `months` = 0
- `ttl_ds` = 80 (8.0 s)
- `kind` = 0x14 GIFT
- `tier` = 2 = Tier 1
- `eflags` = 0x00
- `reserved2` = 0
- `actor` = "generouspal" — the gifter
- `text` = empty

### V10. `event_raid_replayed` — 176 bytes

```
a7 53 03 21 a8 00 01 d6 7a 00 00 00 88 f2 b5 6a
80 00 00 00 00 00 00 00 00 00 64 00 15 00 00 00
73 74 72 65 61 6d 66 72 69 65 6e 64 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

The only difference from a live raid is header byte 6 (`flags` = `0x01`) and the resulting header check at byte 7. A receiver SHOULD render a replayed card without the entrance animation.

- `header.type` = 0x21 EVENT
- `header.length` = 168
- `header.flags` = 0x01 REPLAY — this frame came out of the replay buffer
- `seq` = 122
- `ts` = 1790309000
- `value` = 128 — the raider's viewer count
- `reserved1` = 00 00 00 00
- `months` = 0
- `ttl_ds` = 100 (10.0 s)
- `kind` = 0x15 RAID
- `tier` = 0
- `eflags` = 0x00
- `reserved2` = 0
- `actor` = "streamfriend" — the raider
- `text` = empty

### V11. `event_bits` — 176 bytes

```
a7 53 03 21 a8 00 00 dd 7b 00 00 00 a6 f2 b5 6a
dc 05 00 00 00 00 00 00 00 00 50 00 17 00 00 00
62 69 74 73 66 61 6e 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
74 61 6b 65 20 6d 79 20 62 69 74 73 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

- `header.type` = 0x21 EVENT
- `header.length` = 168
- `header.flags` = 0x00
- `seq` = 123
- `ts` = 1790309030
- `value` = 1500 — a plain count of bits; bits are not money and carry no currency
- `reserved1` = 00 00 00 00
- `months` = 0
- `ttl_ds` = 80 (8.0 s)
- `kind` = 0x17 BITS
- `tier` = 0
- `eflags` = 0x00
- `reserved2` = 0
- `actor` = "bitsfan" — the sender
- `text` = "take my bits" — the cheer message

### V12. `event_chat_utf8` — 176 bytes

```
a7 53 03 21 a8 00 00 dd 7c 00 00 00 c5 f2 b5 6a
50 7f ff 00 00 00 00 00 00 00 3c 00 16 00 10 00
50 61 77 65 c5 82 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
c5 9b 77 69 65 74 6e 79 20 73 74 72 65 61 6d 21
20 f0 9f 8e 89 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

Sent verbatim because the device set `CAP_UTF8_TEXT`. Without that bit the relay would have ASCII-folded it to `Pawel` / `swietny stream!` before truncation (§9.3). Note that `🎉` is a four-byte sequence and `ś`/`ł` are two-byte sequences: the field widths are byte counts, never character counts.

- `header.type` = 0x21 EVENT
- `header.length` = 168
- `header.flags` = 0x00
- `seq` = 124
- `ts` = 1790309061
- `value` = 0x00ff7f50 — the chatter's name colour #ff7f50
- `reserved1` = 00 00 00 00
- `months` = 0
- `ttl_ds` = 60 (6.0 s)
- `kind` = 0x16 CHAT
- `tier` = 0
- `eflags` = 0x10 CHAT_COLOUR_PRESENT
- `reserved2` = 0
- `actor` = "Paweł" = `50 61 77 65 c5 82` — 6 UTF-8 bytes, 5 code points
- `text` = "świetny stream! 🎉" = `c5 9b 77 69 65 74 6e 79 20 73 74 72 65 61 6d 21 20 f0 9f 8e 89` — 21 UTF-8 bytes, 17 code points

### V13. `event_chat_truncated` — 176 bytes

```
a7 53 03 21 a8 00 00 dd 7d 00 00 00 c6 f2 b5 6a
00 00 00 00 00 00 00 00 00 00 3c 00 16 00 03 00
41 62 73 6f 6c 75 74 6e 69 65 20 4e 69 65 7a 77
79 63 69 65 7a 6f 6e 61 20 43 7a 61 72 6f 64 7a
69 65 6a 6b 61 20 5a 6e 61 64 20 2e 2e 2e 00 00
74 6f 20 6a 65 73 74 20 73 74 72 61 73 7a 6e 69
65 20 64 c5 82 75 67 61 20 69 6e 66 6f 72 6d 61
63 6a 61 20 6e 61 20 63 7a 61 63 69 65 20 6b 74
c3 b3 72 61 20 6e 69 65 20 7a 6d 69 65 c5 9b 63
69 20 73 69 c4 99 20 77 20 70 6f 6c 75 20 74 65
6b 73 74 6f 77 79 6d 20 75 72 7a 2e 2e 2e 00 00
```

- `header.type` = 0x21 EVENT
- `header.length` = 168
- `header.flags` = 0x00
- `seq` = 125
- `ts` = 1790309062
- `value` = 0
- `reserved1` = 00 00 00 00
- `months` = 0
- `ttl_ds` = 60 (6.0 s)
- `kind` = 0x16 CHAT
- `tier` = 0
- `eflags` = 0x03 = TEXT_TRUNCATED | ACTOR_TRUNCATED
- `reserved2` = 0
- `actor` = 46 bytes: `Absolutnie Niezwyciezona Czarodziejka Znad ...` (source was 68 bytes)
- `text` = 94 bytes: `to jest strasznie długa informacja na czacie która nie zmieści się w polu tekstowym urz...` (source was 99 bytes)

### V14. `event_info_generic` — 176 bytes

```
a7 53 03 21 a8 00 00 dd 7e 00 00 00 ce f2 b5 6a
00 00 00 00 00 00 00 00 00 00 50 00 00 00 00 00
52 65 6c 61 79 20 72 65 73 74 61 72 74 65 64 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
6d 61 6e 75 61 6c 20 63 61 72 64 20 70 6f 73 74
65 64 20 74 6f 20 2f 61 70 69 2f 76 31 2f 6e 6f
74 69 66 69 63 61 74 69 6f 6e 73 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

This is what `POST /api/v1/notifications` produces (§6.4.3): the posted title lands in `actor`, the posted body in `text`, and every numeric field stays 0.

- `header.type` = 0x21 EVENT
- `header.length` = 168
- `header.flags` = 0x00
- `seq` = 126
- `ts` = 1790309070
- `value` = 0
- `reserved1` = 00 00 00 00
- `months` = 0
- `ttl_ds` = 80 (8.0 s)
- `kind` = 0x00 INFO
- `tier` = 0
- `eflags` = 0x00
- `reserved2` = 0
- `actor` = "Relay restarted" — the posted title
- `text` = "manual card posted to /api/v1/notifications" — the posted body

### V15. `event_stream_end` — 176 bytes

```
a7 53 03 21 a8 00 00 dd 7f 00 00 00 ec f2 b5 6a
b0 0e 00 00 00 00 00 00 00 00 64 00 11 00 00 00
77 30 72 78 62 65 6e 64 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

- `header.type` = 0x21 EVENT
- `header.length` = 168
- `header.flags` = 0x00
- `seq` = 127
- `ts` = 1790309100
- `value` = 3760 — stream duration in seconds (1 h 02 m 40 s)
- `reserved1` = 00 00 00 00
- `months` = 0
- `ttl_ds` = 100 (10.0 s)
- `kind` = 0x11 STREAM_END
- `tier` = 0
- `eflags` = 0x00
- `reserved2` = 0
- `actor` = "w0rxbend" — the channel
- `text` = empty

### V16. `ping_device` — 12 bytes

```
a7 53 03 02 04 00 00 8d 72 10 00 00
```

- `header.type` = 0x02 PING (device → relay)
- `header.length` = 4
- `token` = 4210 — the device's `millis() / 1000`

### V17. `pong_relay` — 12 bytes

```
a7 53 03 24 04 00 00 05 72 10 00 00
```

- `header.type` = 0x24 PONG (relay → device)
- `header.length` = 4
- `token` = 4210 — echoed byte for byte from the PING it answers

### V18. `ping_relay` — 12 bytes

```
a7 53 03 23 04 00 00 09 88 f2 b5 6a
```

- `header.type` = 0x23 PING (relay → device)
- `header.length` = 4
- `token` = 1790309000

### V19. `ack_device` — 12 bytes

```
a7 53 03 04 04 00 00 85 7f 00 00 00
```

- `header.type` = 0x04 ACK (device → relay)
- `header.length` = 4
- `seq` = 127 — highest seq successfully enqueued for display

### V20. `bye_version_mismatch` — 40 bytes

```
a7 53 03 25 20 00 00 75 01 00 03 00 1e 00 00 00
72 65 6c 61 79 20 73 70 65 61 6b 73 20 76 33 20
6f 6e 6c 79 00 00 00 00
```

- `header.type` = 0x25 BYE
- `header.length` = 32
- `code` = 1 UNSUPPORTED_VERSION
- `detail` = 3 — the version this relay speaks
- `retry_after_s` = 30
- `reserved0` = 0
- `reason` = "relay speaks v3 only" — ASCII, NUL-padded to 24, for logs only

---

## 19. Documents and code this changes

* This file replaces the NDJSON v2 specification entirely.
* `demo-server/twitch_server.py` speaks v2 and is **obsolete**. It is not to be updated; the
  relay is the only v3 server.
* `twitch-screen-relay/README.md` must stop describing the link as "NDJSON over TCP :8099".
* `twitch-screen-firmware/PLAN.md`'s architecture diagram says "TCP v2 push"; it is stale.
* `application.conf`: `protocol-version = 3`, `max-frame-length = 256` (bytes),
  `notifications.ignored-display-names` added under `notifications`.
* The `msg_total` field that v2's specification documented and the relay never sent is
  finally satisfied by §6.5, which requires a cumulative, bot-filtered counter on the stats
  fold that does not exist today.
