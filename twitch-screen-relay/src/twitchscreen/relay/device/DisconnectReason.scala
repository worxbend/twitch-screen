package twitchscreen.relay.device

/** Why a device link ended. Recorded on the bus and in the activity log so a flapping board is diagnosable. */
private[relay] enum DisconnectReason:
  case PeerClosed
  case IdleTimeout
  case HandshakeTimeout
  case HandshakeRejected(detail: String)
  case ProtocolViolation(detail: String)

  /** §4.4/§4.5: the framing invariant itself is broken and the resync budget ran out. Distinct from a protocol violation because the remedy
    * is different — a framing violation says the byte stream is untrustworthy, not that the peer asked for something illegal.
    */
  case FramingViolation(detail: String)
  case ReadFailed(detail: String)

  /** §6.7 code 9: a new connection claimed this device id, so this one is a corpse left by a half-open socket. */
  case Replaced
  case RequestedByOperator
  case ListenerStopped

private[relay] object DisconnectReason:
  extension (reason: DisconnectReason)
    def describe: String = reason match
      case PeerClosed                => "peer closed the connection"
      case IdleTimeout               => "no frame received within the idle timeout"
      case HandshakeTimeout          => "no hello received within the handshake timeout"
      case HandshakeRejected(detail) => s"handshake rejected: $detail"
      case ProtocolViolation(detail) => s"protocol violation: $detail"
      case FramingViolation(detail)  => detail // ProtocolError.FramingViolation already says "framing violation: …"
      case ReadFailed(detail)        => s"read failed: $detail"
      case Replaced                  => "another connection claimed this device id"
      case RequestedByOperator       => "disconnected through the management API"
      case ListenerStopped           => "the relay is shutting down"
