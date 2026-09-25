package twitchscreen.relay.protocol

import sttp.tapir.{Codec, CodecFormat, Schema}

/** Position of a notification in the relay's stream. Strictly increasing while the relay runs; the device drops any frame whose seq it has
  * already seen, and asks for everything above its own seq when it reconnects.
  */
opaque type SeqNo = Long

object SeqNo:
  /** What a device reports after a fresh boot: "I have seen nothing, do not replay". */
  val Zero: SeqNo = 0L

  /** §10.1: the largest seq the `u32` wire field can carry. The relay never assigns one past it. */
  val Max: SeqNo = 0xffffffffL

  def apply(value: Long): Either[String, SeqNo] =
    if value >= 0 && value <= Max then Right(value) else Left(s"Sequence number must fit in a u32: $value")

  /** A `u32` read off the TSB/3 wire (§10.1). Every value in that range is a legal sequence number, so this cannot fail: `seq == 0` is
    * meaningful on a `HELLO` ("I have seen nothing") and illegal only on an `EVENT`, where the decoder rejects it as a field error.
    */
  def fromWire(raw: Long): SeqNo = raw & 0xffffffffL

  extension (seq: SeqNo)
    def value: Long = seq
    def next: Option[SeqNo] = Option.when(seq < Max)(seq + 1)
    def isAfter(other: SeqNo): Boolean = seq > other

  given Ordering[SeqNo] = Ordering.Long
  given Schema[SeqNo] = Schema.schemaForLong.as[SeqNo]
  given Codec[String, SeqNo, CodecFormat.TextPlain] =
    Codec.long.mapDecode(raw => sttp.tapir.DecodeResult.fromEitherString(raw.toString, SeqNo(raw)))(_.value)
