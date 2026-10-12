package twitchscreen.relay.http

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}

/** Request-only Option codec: omitted fields retain defaults, but an explicit JSON null is not a partial update or default. */
private[relay] object StrictRequest:
  /** Exact request integers accept integral decimal/exponent notation, without floating-point rounding or string coercion. Parser
    * allocation and scale are bounded by the ordinary 64 KiB request ceiling. Domain bounds remain field-specific.
    */
  val integralLong: JsonValueCodec[Long] = new JsonValueCodec[Long]:
    def nullValue: Long = 0L
    def decodeValue(in: JsonReader, default: Long): Long =
      val token = in.nextToken()
      if token != '-'.toByte && (token < '0'.toByte || token > '9'.toByte) then in.decodeError("expected an integer number")
      in.rollbackToken()
      val value = in.readBigDecimal(null, java.math.MathContext.UNLIMITED, 65536, 65536)
      try value.bigDecimal.longValueExact()
      catch case _: ArithmeticException => in.decodeError("expected an exact integer within Long range")
    def encodeValue(value: Long, out: JsonWriter): Unit = out.writeVal(value)

  val nonNullString: JsonValueCodec[String] = new JsonValueCodec[String]:
    def nullValue: String = null
    def decodeValue(in: JsonReader, default: String): String =
      if in.isNextToken('n'.toByte) then in.decodeError("explicit null is not allowed")
      else
        in.rollbackToken()
        in.readString(default)
    def encodeValue(value: String, out: JsonWriter): Unit = out.writeVal(value)

  def optional[A](codec: JsonValueCodec[A]): JsonValueCodec[Option[A]] = new JsonValueCodec[Option[A]]:
    def nullValue: Option[A] = None
    def decodeValue(in: JsonReader, default: Option[A]): Option[A] =
      if in.isNextToken('n'.toByte) then in.decodeError("explicit null is not allowed")
      else
        in.rollbackToken()
        Some(codec.decodeValue(in, codec.nullValue))
    def encodeValue(value: Option[A], out: JsonWriter): Unit = value match
      case Some(item) => codec.encodeValue(item, out)
      case None       => out.writeNull()

  def validUnicode(value: String): Boolean =
    var index = 0
    var valid = true
    while valid && index < value.length do
      val char = value.charAt(index)
      if Character.isHighSurrogate(char) then
        valid = index + 1 < value.length && Character.isLowSurrogate(value.charAt(index + 1))
        index += 2
      else
        valid = !Character.isLowSurrogate(char)
        index += 1
    valid
