package twitchscreen.relay.device

import sttp.tapir.{Codec, CodecFormat, Schema}

/** Identifies one TCP connection. A device that reconnects gets a new id, so the management API can talk about "this socket" without
  * ambiguity when a board reconnects while its previous connection is still being torn down.
  */
opaque type ConnectionId = Long

object ConnectionId:
  /** Ids are handed out by a single counter inside [[DeviceHub]], which is why there is no public constructor. */
  private[device] def apply(value: Long): ConnectionId = value

  extension (id: ConnectionId) def value: Long = id

  given Schema[ConnectionId] = Schema.schemaForLong.as[ConnectionId]
  given Codec[String, ConnectionId, CodecFormat.TextPlain] = Codec.long.map(ConnectionId(_))(_.value)
