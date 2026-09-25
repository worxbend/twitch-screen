package twitchscreen.relay.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReaderException, readFromString, writeToString}
import ox.either.catching

/** The NDJSON framing of protocol v2: one JSON object per line, UTF-8, `\n` terminated. jsoniter escapes control characters, so an encoded
  * frame never contains a raw newline and the framing cannot be broken by a chat message.
  */
private[relay] object FrameCodec:
  def encode(frame: ServerFrame): String = writeToString(frame)

  def decode(line: String, maxFrameLength: Int): Either[ProtocolError, DeviceCommand] =
    if line.length > maxFrameLength then Left(ProtocolError.FrameTooLong(maxFrameLength))
    else
      readFromString[ClientFrame](line)
        .catching[JsonReaderException]
        .left
        .map(error => ProtocolError.Malformed(error.getMessage))
        .flatMap(DeviceCommand.fromFrame)
