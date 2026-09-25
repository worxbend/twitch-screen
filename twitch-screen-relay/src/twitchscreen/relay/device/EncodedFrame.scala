package twitchscreen.relay.device

import java.io.OutputStream
import twitchscreen.relay.protocol.*

/** Session-writer encoding with privately owned bytes; shared broadcasts expose no mutable buffer. */
private[device] final class EncodedFrame(
    message: RelayMessage,
    flags: FrameFlags = FrameFlags.Empty,
    text: TextPolicy = TextPolicy.Verbatim,
    version: ProtocolVersion = Tsb3.Version
):
  private val bytes = Tsb3Encoder.toDevice(message, flags, text, version)
  def length: Int = bytes.length
  def writeTo(target: OutputStream): Unit = target.write(bytes)
