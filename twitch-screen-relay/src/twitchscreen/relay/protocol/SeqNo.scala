package twitchscreen.relay.protocol

import sttp.tapir.{Codec, CodecFormat, Schema}

/** Position of a notification in the relay's stream. Strictly increasing while the relay runs; the device drops any frame whose seq it has
  * already seen, and asks for everything above its own seq when it reconnects.
  */
opaque type SeqNo = Long

object SeqNo:
  /** What a device reports after a fresh boot: "I have seen nothing, do not replay". */
  val Zero: SeqNo = 0L

  def apply(value: Long): Either[String, SeqNo] =
    if value >= 0 then Right(value) else Left(s"Sequence number must not be negative: $value")

  extension (seq: SeqNo)
    def value: Long = seq
    def next: SeqNo = seq + 1
    def isAfter(other: SeqNo): Boolean = seq > other

  given Ordering[SeqNo] = Ordering.Long
  given Schema[SeqNo] = Schema.schemaForLong.as[SeqNo]
  given Codec[String, SeqNo, CodecFormat.TextPlain] =
    Codec.long.mapDecode(raw => sttp.tapir.DecodeResult.fromEitherString(raw.toString, SeqNo(raw)))(_.value)
