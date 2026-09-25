package twitchscreen.relay.protocol

import sttp.tapir.{Codec, CodecFormat, DecodeResult, Schema}

/** Identifies one board. Comes from the firmware's `DEVICE_ID` build flag and is echoed in every `hello`. */
opaque type DeviceId = String

object DeviceId:
  private val MaxLength = 64

  def apply(raw: String): Either[String, DeviceId] =
    val trimmed = raw.trim
    if trimmed.isEmpty then Left("Device id must not be blank")
    else if trimmed.length > MaxLength then Left(s"Device id must be at most $MaxLength characters: ${trimmed.length}")
    else Right(trimmed)

  extension (id: DeviceId) def value: String = id

  given Schema[DeviceId] = Schema.schemaForString.as[DeviceId]
  given Codec[String, DeviceId, CodecFormat.TextPlain] =
    Codec.string.mapDecode(raw => DecodeResult.fromEitherString(raw, DeviceId(raw)))(_.value)
