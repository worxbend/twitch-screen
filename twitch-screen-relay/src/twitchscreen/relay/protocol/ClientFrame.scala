package twitchscreen.relay.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

/** A frame arriving from a device, in its on-the-wire shape. Nothing outside [[DeviceCommand.fromFrame]] should read these: the fields are
  * unvalidated primitives straight off the socket.
  */
private[relay] enum ClientFrame:
  case Hello(device: String, proto: Int, lastSeq: Long)
  case Ping(t: Long)
  case Pong(t: Long)

private[relay] object ClientFrame:
  given JsonValueCodec[ClientFrame] = JsonCodecMaker.make(WireJson.config)
