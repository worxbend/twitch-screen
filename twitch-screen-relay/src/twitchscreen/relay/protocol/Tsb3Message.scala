package twitchscreen.relay.protocol

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/** A validated message from a device (§6, `0x01`…`0x1f`): the wire record's primitives have become domain values. */
private[relay] enum DeviceMessage:
  case Hello(deviceId: DeviceId, lastSeq: SeqNo, caps: Capabilities, rxMax: FrameSize, fwVersion: FirmwareVersion)
  case Ping(token: Token)
  case Pong(token: Token)
  case Ack(seq: SeqNo)

private[relay] object DeviceMessage:
  extension (message: DeviceMessage)
    def messageType: MessageType = message match
      case _: Hello => MessageType.Hello
      case _: Ping  => MessageType.DevicePing
      case _: Pong  => MessageType.DevicePong
      case _: Ack   => MessageType.Ack

/** A message the relay pushes to a device (§6, `0x20`…`0x3f`). */
private[relay] enum RelayMessage:
  /** §6.2. `caps` is the effective intersection and is authoritative; `ping_interval_s` and `idle_timeout_s` are informational, because the
    * device keeps its own timers — which is what kills the class of incident where an operator edits `application.conf` and believes the
    * devices followed.
    */
  case Welcome(
      latestSeq: SeqNo,
      serverTime: Option[Instant],
      sessionId: SessionId,
      maxFrame: FrameSize,
      pingInterval: FiniteDuration,
      idleTimeout: FiniteDuration,
      replayWindow: ReplayWindow,
      caps: Capabilities
  )
  case Event(record: EventRecord)

  /** §6.5. Absolute and self-contained: one captured frame fully explains the idle screen, which no delta encoding could promise. */
  case Stats(stats: StreamStats, serverTime: Option[Instant])
  case Ping(token: Token)
  case Pong(token: Token)
  case Bye(code: ByeCode, detail: ByeDetail, retryAfter: FiniteDuration, reason: String)

private[relay] object RelayMessage:
  extension (message: RelayMessage)
    def messageType: MessageType = message match
      case _: Welcome => MessageType.Welcome
      case _: Event   => MessageType.Event
      case _: Stats   => MessageType.Stats
      case _: Ping    => MessageType.RelayPing
      case _: Pong    => MessageType.RelayPong
      case _: Bye     => MessageType.Bye

/** Why the relay is closing the connection (§6.7). The payload's shape and the type code `0x25` are frozen across every future version of
  * this protocol: that, plus the version byte at a fixed header offset, is what lets a peer read a refusal from a peer whose version it
  * does not speak.
  */
private[relay] enum ByeCode:
  case UnsupportedVersion, BadHandshake, InvalidDeviceId, InvalidSequence, FramingViolation, FrameTooLarge, DuplicateHello, ServerShutdown,
    Replaced, RateLimit, InvalidParameter, HandshakeTimeout

  /** A code from a newer peer, or a private code at 1000 and above; a receiver treats it as a generic teardown. */
  case Unknown(raw: Int)

private[relay] object ByeCode:
  extension (code: ByeCode)
    def value: Int = code match
      case UnsupportedVersion => 1
      case BadHandshake       => 2
      case InvalidDeviceId    => 3
      case InvalidSequence    => 4
      case FramingViolation   => 5
      case FrameTooLarge      => 6
      case DuplicateHello     => 7
      case ServerShutdown     => 8
      case Replaced           => 9
      case RateLimit          => 10
      case InvalidParameter   => 11
      case HandshakeTimeout   => 12
      case Unknown(raw)       => raw

  def fromWire(raw: Int): ByeCode =
    val known = values.collect { case simple if !simple.isInstanceOf[Unknown] => simple }
    known.find(_.value == raw).getOrElse(Unknown(raw & 0xffff))

/** The code-specific detail of a `BYE`: the version the relay speaks, the type code it actually received, the payload offset of an invalid
  * field, or zero when the code has nothing to add.
  */
private[relay] opaque type ByeDetail = Int

private[relay] object ByeDetail:
  val Zero: ByeDetail = 0

  def of(value: Int): ByeDetail = if value < 0 then 0 else if value > 0xffff then 0xffff else value

  def fromWire(raw: Int): ByeDetail = raw & 0xffff

  extension (detail: ByeDetail) def value: Int = detail
