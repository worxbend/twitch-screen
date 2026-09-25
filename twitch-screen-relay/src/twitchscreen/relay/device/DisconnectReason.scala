package twitchscreen.relay.device

/** Why a device link ended. Recorded on the bus and in the activity log so a flapping board is diagnosable. */
private[relay] enum DisconnectReason:
  case PeerClosed
  case IdleTimeout
  case HandshakeTimeout
  case HandshakeRejected(detail: String)
  case ProtocolViolation(detail: String)
  case ReadFailed(detail: String)
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
      case ReadFailed(detail)        => s"read failed: $detail"
      case RequestedByOperator       => "disconnected through the management API"
      case ListenerStopped           => "the relay is shutting down"
