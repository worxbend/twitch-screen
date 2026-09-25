package twitchscreen.relay.http

import sttp.tapir.{Codec, CodecFormat, DecodeResult}

/** AIP-136 custom methods, e.g. `POST /api/v1/devices/7:disconnect`.
  *
  * The verb shares its path segment with the resource id, and Tapir matches whole segments, so the two are decoded together by one codec
  * rather than as separate inputs.
  */
private[relay] object CustomMethod:
  def on[T](verb: String, decode: String => Either[String, T], encode: T => String): Codec[String, T, CodecFormat.TextPlain] =
    val suffix = s":$verb"
    Codec.string.mapDecode { segment =>
      if segment.endsWith(suffix) then DecodeResult.fromEitherString(segment, decode(segment.dropRight(suffix.length)))
      else DecodeResult.Mismatch(s"<id>$suffix", segment)
    }(value => s"${encode(value)}$suffix")
