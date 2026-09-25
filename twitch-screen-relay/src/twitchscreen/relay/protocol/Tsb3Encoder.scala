package twitchscreen.relay.protocol

import scala.concurrent.duration.FiniteDuration

/** Turns a message into the bytes of one complete TSB/3 frame: the eight header bytes of §3 followed by a fixed-offset, fixed-width payload
  * from §6. Every multi-byte integer is little-endian, without exception (§2.1).
  *
  * Encoding is total — there is no failure mode and therefore no `Either`. A value too large for its field is clamped by the opaque type
  * that carries it, a string too long for its field is truncated by §9.2, and a frame that still does not fit is a caller's problem, not
  * the codec's: §5 obliges the sender to drop and count it. Nothing here throws.
  *
  * Every call allocates a fresh array and shares nothing, which is what §10.1 requires of a hub that broadcasts a pre-encoded frame to
  * several sessions: a shared mutable buffer inside the hub breaks the ordering guarantee that makes sequence numbers mean anything.
  */
private[relay] object Tsb3Encoder:
  /** Encodes a relay → device message (§6, `0x20`…`0x3f`).
    *
    * `flags` carries `REPLAY` for a frame served from the replay ring (§10.3). `text` decides whether strings are sent verbatim or folded
    * to US-ASCII, and follows the connection's effective capabilities (§9.3). `version` is the version byte at header offset 2: it is the
    * negotiated version for everything except a `BYE`, which §6.7 requires be encoded with the version of the frame that provoked it, since
    * that is what lets a device read a refusal from a relay whose version it does not speak.
    */
  def toDevice(
      message: RelayMessage,
      flags: FrameFlags = FrameFlags.Empty,
      text: TextPolicy = TextPolicy.Verbatim,
      version: ProtocolVersion = Tsb3.Version
  ): Array[Byte] = message match
    case m: RelayMessage.Welcome => frame(MessageType.Welcome, flags, version, welcome(m))
    case m: RelayMessage.Event   => frame(MessageType.Event, flags, version, event(m.record, text))
    case m: RelayMessage.Stats   => frame(MessageType.Stats, flags, version, stats(m))
    case m: RelayMessage.Ping    => frame(MessageType.RelayPing, flags, version, heartbeat(m.token))
    case m: RelayMessage.Pong    => frame(MessageType.RelayPong, flags, version, heartbeat(m.token))
    case m: RelayMessage.Bye     => frame(MessageType.Bye, flags, version, bye(m))

  /** Encodes a device → relay message (§6, `0x01`…`0x1f`).
    *
    * The relay never sends one of these. It exists because the golden vectors of §18 pin both directions and because the end-to-end suite
    * has to speak as a device; without it every device-side test would hand-code bytes, which is exactly how two implementations drift.
    */
  def toRelay(message: DeviceMessage, version: ProtocolVersion = Tsb3.Version): Array[Byte] = message match
    case m: DeviceMessage.Hello => frame(MessageType.Hello, FrameFlags.Empty, version, hello(m))
    case m: DeviceMessage.Ping  => frame(MessageType.DevicePing, FrameFlags.Empty, version, heartbeat(m.token))
    case m: DeviceMessage.Pong  => frame(MessageType.DevicePong, FrameFlags.Empty, version, heartbeat(m.token))
    case m: DeviceMessage.Ack   => frame(MessageType.Ack, FrameFlags.Empty, version, u32Payload(Tsb3.Ack.Seq, 4, m.seq.value))

  /** §3.2: setting `REPLAY` on a frame that was encoded once and cached rewrites exactly two bytes — the flags byte at offset 6 and the
    * header check at offset 7. Returns a copy, because the cached frame is shared read-only by every session replaying it.
    */
  def withFlags(encoded: Array[Byte], flags: FrameFlags): Array[Byte] =
    val copy = encoded.clone()
    copy(6) = flags.toByte
    copy(7) = Tsb3.headerCheck(copy)
    copy

  private def welcome(message: RelayMessage.Welcome): Array[Byte] =
    val payload = new Array[Byte](MessageType.Welcome.baseLength)
    putU32(payload, Tsb3.Welcome.LatestSeq, message.latestSeq.value)
    putU32(payload, Tsb3.Welcome.ServerTime, epochSeconds(message.serverTime))
    putU32(payload, Tsb3.Welcome.SessionId, message.sessionId.value)
    putU16(payload, Tsb3.Welcome.MaxFrame, message.maxFrame.value)
    putU16(payload, Tsb3.Welcome.PingInterval, seconds(message.pingInterval))
    putU16(payload, Tsb3.Welcome.IdleTimeout, seconds(message.idleTimeout))
    putU16(payload, Tsb3.Welcome.ReplayWindow, message.replayWindow.value)
    putU32(payload, Tsb3.Welcome.Caps, message.caps.unsigned)
    payload

  /** The record's `actor` and `text` are the values before truncation, so the `eflags` bits are set here, from what was actually written,
    * and can never disagree with the bytes (§9.2 step 4). `reserved1` at +12 and `reserved2` at +23 are left as the zeroes the array was
    * allocated with: §8.1 holds those five bytes open for a future monetary amount, so that reintroducing one moves no existing field.
    */
  private def event(record: EventRecord, policy: TextPolicy): Array[Byte] =
    val actor = WireStrings.field(record.actor, Tsb3.ActorWidth, policy, placeholder = Some(WireStrings.FoldedPlaceholder))
    val text = WireStrings.field(record.text, Tsb3.TextWidth, policy)
    val flags = record.flags
      .withFlag(if actor.truncated then EventFlags.ActorTruncated else EventFlags.Empty)
      .withFlag(if text.truncated then EventFlags.TextTruncated else EventFlags.Empty)

    val payload = new Array[Byte](MessageType.Event.baseLength)
    putU32(payload, Tsb3.Event.Seq, record.seq.value)
    putU32(payload, Tsb3.Event.Ts, epochSeconds(record.at))
    putU32(payload, Tsb3.Event.Value, record.value.value)
    putU16(payload, Tsb3.Event.Months, record.months.value)
    putU16(payload, Tsb3.Event.TtlDs, record.ttl.deciseconds)
    putU8(payload, Tsb3.Event.Kind, record.kind.code)
    putU8(payload, Tsb3.Event.Tier, record.tier.code)
    putU8(payload, Tsb3.Event.EFlags, flags.value)
    WireStrings.write(payload, Tsb3.Event.Actor, Tsb3.ActorWidth, actor)
    WireStrings.write(payload, Tsb3.Event.Text, Tsb3.TextWidth, text)
    payload

  private def stats(message: RelayMessage.Stats): Array[Byte] =
    val figures = message.stats
    val payload = new Array[Byte](MessageType.Stats.baseLength)
    putU32(payload, Tsb3.Stats.Viewers, figures.viewers.value.toLong)
    putU32(payload, Tsb3.Stats.MsgTotal, figures.messagesTotal.value.toLong)
    putU32(payload, Tsb3.Stats.UptimeS, math.max(0L, figures.uptime.toSeconds))
    putU32(payload, Tsb3.Stats.Followers, figures.followers.value.toLong)
    putU32(payload, Tsb3.Stats.Subs, figures.subscribers.value.toLong)
    putU32(payload, Tsb3.Stats.ServerTime, epochSeconds(message.serverTime))
    putU32(payload, Tsb3.Stats.StreamStartedAt, epochSeconds(figures.streamStartedAt))
    putU16(payload, Tsb3.Stats.ChatRate, figures.chatRate.value)
    putU8(payload, Tsb3.Stats.Live, if figures.state.isLive then 1 else 0)
    payload

  /** `reason` is ASCII and for logs only, so it is folded whatever the device's capabilities say: a refusal the operator cannot read in a
    * log line is worse than a transliterated one.
    */
  private def bye(message: RelayMessage.Bye): Array[Byte] =
    val payload = new Array[Byte](MessageType.Bye.baseLength)
    putU16(payload, Tsb3.Bye.Code, message.code.value)
    putU16(payload, Tsb3.Bye.Detail, message.detail.value)
    putU16(payload, Tsb3.Bye.RetryAfter, seconds(message.retryAfter))
    val reason = WireStrings.field(message.reason, Tsb3.ReasonWidth, TextPolicy.AsciiFolded)
    WireStrings.write(payload, Tsb3.Bye.Reason, Tsb3.ReasonWidth, reason)
    payload

  private def hello(message: DeviceMessage.Hello): Array[Byte] =
    val payload = new Array[Byte](MessageType.Hello.baseLength)
    putU32(payload, Tsb3.Hello.LastSeq, message.lastSeq.value)
    putU32(payload, Tsb3.Hello.Caps, message.caps.unsigned)
    putU16(payload, Tsb3.Hello.RxMax, message.rxMax.value)
    val deviceId = WireStrings.field(message.deviceId.value, Tsb3.DeviceIdWidth, TextPolicy.AsciiFolded)
    val fwVersion = WireStrings.field(message.fwVersion.value, Tsb3.FwVersionWidth, TextPolicy.AsciiFolded)
    WireStrings.write(payload, Tsb3.Hello.DeviceId, Tsb3.DeviceIdWidth, deviceId)
    WireStrings.write(payload, Tsb3.Hello.FwVersion, Tsb3.FwVersionWidth, fwVersion)
    payload

  private def heartbeat(token: Token): Array[Byte] = u32Payload(Tsb3.Heartbeat.Token, 4, token.value)

  private def u32Payload(offset: Int, size: Int, value: Long): Array[Byte] =
    val payload = new Array[Byte](size)
    putU32(payload, offset, value)
    payload

  private def frame(messageType: MessageType, flags: FrameFlags, version: ProtocolVersion, payload: Array[Byte]): Array[Byte] =
    val bytes = new Array[Byte](Tsb3.HeaderSize + payload.length)
    System.arraycopy(payload, 0, bytes, Tsb3.HeaderSize, payload.length)
    FrameHeader.encodeInto(bytes, version, messageType, flags, payload.length)
    bytes

  private def putU8(target: Array[Byte], offset: Int, value: Int): Unit =
    target(offset) = (value & 0xff).toByte

  private def putU16(target: Array[Byte], offset: Int, value: Int): Unit =
    target(offset) = (value & 0xff).toByte
    target(offset + 1) = ((value >>> 8) & 0xff).toByte

  private def putU32(target: Array[Byte], offset: Int, value: Long): Unit =
    target(offset) = (value & 0xff).toByte
    target(offset + 1) = ((value >>> 8) & 0xff).toByte
    target(offset + 2) = ((value >>> 16) & 0xff).toByte
    target(offset + 3) = ((value >>> 24) & 0xff).toByte

  /** 0 means "unknown" in every timestamp field of this protocol, which is what an absent instant encodes to. */
  private def epochSeconds(at: Option[java.time.Instant]): Long =
    at.fold(0L)(instant => math.max(0L, math.min(0xffffffffL, instant.getEpochSecond)))

  private def seconds(duration: FiniteDuration): Int =
    val value = duration.toSeconds
    if value <= 0 then 0 else if value > 0xffff then 0xffff else value.toInt
