package twitchscreen.relay.protocol

import scala.concurrent.duration.FiniteDuration

/** Why bytes arriving from a device could not be turned into a message.
  *
  * Every one of these is a value, never an exception: a malformed frame is ordinary input on a socket exposed to a device that may be
  * running any firmware. [[ProtocolError.disposition]] carries the protocol's two-tier rule (§4.6) — a malformed *frame* must not kill the
  * link, a malformed *stream* must — so that no call site has to remember which is which.
  */
private[relay] enum ProtocolError:
  // TSB/3 framing (§4.2). These never reach a caller from the frame reader, which resynchronises past them until its budget runs out; they
  // are what header validation reports and what the resync loop counts.
  case BadMagic(byte0: Int, byte1: Int)
  case HeaderCheckFailed(expected: Int, received: Int)
  case IllegalTypeCode
  case LengthOutOfRange(length: Int, limit: Int)
  case FramingViolation(discardedBytes: Int, rejectedCandidates: Int)
  case EndOfStream
  case TruncatedFrame(expected: Int, received: Int)

  /** §12: the budget for one **complete** frame ran out. Distinct from a socket read timeout, which a peer dribbling one byte per timeout
    * window can restart forever without ever completing a frame.
    */
  case FrameTimeout(after: FiniteDuration)

  // TSB/3 payload problems (§4.3): well-framed but unusable, so skipped and counted.
  case UnknownType(code: Int)
  case WrongDirection(code: Int)
  case ShortPayload(typeName: String, expected: Int, received: Int)
  case InvalidField(field: String, offset: Int)

  // Session-level refusals (§7, §6.1, §11.2): fatal to the session even though the frame itself was well formed.
  case UnsupportedVersion(received: Int, expected: Int)
  case InvalidDeviceId(detail: String)

/** What a receiver does about a [[ProtocolError]] (§4.3 versus §4.4/§4.5). */
private[relay] enum ErrorDisposition:
  /** A candidate header cannot be trusted; shift one octet and search again within the framing budget. */
  case Resynchronize

  /** Discard the payload, count it, keep reading. The resync budget resets, because the frame was correctly framed. */
  case SkipFrame

  /** The framing invariant itself is broken, or the session cannot continue: send `BYE` where possible and close. */
  case CloseLink

private[relay] object ProtocolError:
  extension (error: ProtocolError)
    def describe: String = error match
      case BadMagic(byte0, byte1)          => f"bad frame magic 0x$byte0%02x 0x$byte1%02x, expected 0xa7 0x53"
      case HeaderCheckFailed(exp, got)     => f"header check 0x$got%02x, expected 0x$exp%02x"
      case IllegalTypeCode                 => "frame type 0x00 is illegal"
      case LengthOutOfRange(length, limit) => s"payload length $length above the $limit byte framing limit"
      case FramingViolation(bytes, cands)  => s"framing violation: $bytes bytes discarded, $cands candidate headers rejected"
      case EndOfStream                     => "stream closed"
      case TruncatedFrame(exp, got)        => s"stream closed $got bytes into a $exp byte frame"
      case FrameTimeout(after)             => s"no complete frame within $after"
      case UnknownType(code)               => f"unknown message type 0x$code%02x"
      case WrongDirection(code)            => f"message type 0x$code%02x travels the other way"
      case ShortPayload(name, exp, got)    => s"$name payload of $got bytes, expected at least $exp"
      case InvalidField(field, offset)     => s"invalid field '$field' at payload offset $offset"
      case UnsupportedVersion(got, exp)    => s"unsupported protocol version $got, this relay speaks $exp"
      case InvalidDeviceId(detail)         => s"invalid device id: $detail"

    def disposition: ErrorDisposition = error match
      case _: (BadMagic | HeaderCheckFailed | LengthOutOfRange) | IllegalTypeCode => ErrorDisposition.Resynchronize
      case _: (UnknownType | WrongDirection | ShortPayload | InvalidField)        => ErrorDisposition.SkipFrame
      case _                                                                      => ErrorDisposition.CloseLink

    /** The `BYE` the relay owes the device when this error ends the session (§6.7). Errors that are skipped rather than fatal have none,
      * and neither does a stream that has already closed under the relay's feet.
      *
      * Codes 4 (`INVALID_SEQUENCE`) and 6 (`FRAME_TOO_LARGE`) are reserved in TSB/3 and must not be sent (§6.7), so no variant maps to
      * them. The match is exhaustive on purpose: a new variant does not compile until someone decides which `BYE`, if any, it owes.
      */
    def byeAdvice: Option[(ByeCode, ByeDetail)] = error match
      case UnsupportedVersion(_, expected) => Some((ByeCode.UnsupportedVersion, ByeDetail.of(expected)))
      case InvalidDeviceId(_)              => Some((ByeCode.InvalidDeviceId, ByeDetail.Zero))
      case FramingViolation(_, _)          => Some((ByeCode.FramingViolation, ByeDetail.Zero))
      case BadMagic(_, _)                  => Some((ByeCode.FramingViolation, ByeDetail.Zero))
      case HeaderCheckFailed(_, _)         => Some((ByeCode.FramingViolation, ByeDetail.Zero))
      case IllegalTypeCode                 => Some((ByeCode.FramingViolation, ByeDetail.Zero))
      case InvalidField(_, offset)         => Some((ByeCode.InvalidParameter, ByeDetail.of(offset)))
      case EndOfStream                     => None
      case TruncatedFrame(_, _)            => None
      case FrameTimeout(_)                 => None
      case UnknownType(_)                  => None
      case WrongDirection(_)               => None
      case ShortPayload(_, _, _)           => None
      case LengthOutOfRange(_, _)          => None
