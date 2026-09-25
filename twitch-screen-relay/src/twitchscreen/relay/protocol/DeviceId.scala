package twitchscreen.relay.protocol

import sttp.tapir.{Codec, CodecFormat, DecodeResult, Schema}

/** Identifies one board. Comes from the firmware's `DEVICE_ID` build flag and is echoed in every `hello`. */
opaque type DeviceId = String

object DeviceId:
  private val MaxLength = 31

  def apply(raw: String): Either[String, DeviceId] =
    if raw == null || raw.trim.isEmpty then Left("Device id must not be blank")
    else if raw.length > MaxLength then Left(s"Device id must be at most $MaxLength ASCII bytes")
    else if raw.exists(char => char < ' ' || char > '~') then Left("Device id must contain printable ASCII only")
    else Right(raw) // Whitespace is meaningful identity; trim only determines whether the field is blank.

  extension (id: DeviceId) def value: String = id

  given Schema[DeviceId] = Schema.schemaForString.as[DeviceId]
  given Codec[String, DeviceId, CodecFormat.TextPlain] =
    Codec.string.mapDecode(raw => DecodeResult.fromEitherString(raw, DeviceId(raw)))(_.value)
