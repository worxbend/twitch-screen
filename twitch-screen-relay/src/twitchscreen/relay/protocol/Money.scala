package twitchscreen.relay.protocol

import java.math.RoundingMode
import scala.util.control.NonFatal

/** An ISO 4217 **alphabetic** code: exactly three uppercase ASCII letters (§8). The alphabetic code travels rather than the numeric one so
  * that a device with no lookup table can print the three bytes it already has.
  */
private[relay] opaque type Currency = String

private[relay] object Currency:
  def apply(raw: String): Either[String, Currency] =
    val code = raw.trim.toUpperCase
    if code.length == 3 && code.forall(c => c >= 'A' && c <= 'Z') then Right(code)
    else Left(s"Currency must be a three-letter ISO 4217 code: '$raw'")

  /** Lenient, for a field arriving off the wire: anything that is not three uppercase letters means "not monetary" (§6.4). */
  def fromWire(raw: String): Option[Currency] = apply(raw).toOption

  extension (currency: Currency)
    def code: String = currency

    /** Minor units per major unit, as the JDK's currency table knows them: 2 for USD and EUR, 0 for JPY, 3 for BHD. */
    def defaultExponent: DecimalExponent =
      try DecimalExponent.fromWire(java.util.Currency.getInstance(currency).getDefaultFractionDigits).getOrElse(DecimalExponent.Two)
      catch case NonFatal(_) => DecimalExponent.Two

/** How many decimal places of `value` are minor units (§8). Senders use 0…4; a receiver seeing more renders the bare integer. */
private[relay] opaque type DecimalExponent = Int

private[relay] object DecimalExponent:
  val Zero: DecimalExponent = 0
  val Two: DecimalExponent = 2

  val Max: Int = 4

  def apply(value: Int): Either[String, DecimalExponent] =
    if value >= 0 && value <= Max then Right(value) else Left(s"Decimal exponent must be 0…$Max: $value")

  /** Lenient: an out-of-range exponent means the amount is not renderable as a decimal, which §6.4 calls for rendering bare. */
  def fromWire(raw: Int): Option[DecimalExponent] = apply(raw).toOption

  extension (exponent: DecimalExponent) def value: Int = exponent

/** What turns an `EVENT`'s `value` into a decimal amount. Absent means the event carries a plain number — a bits count, a raider's viewer
  * count, a colour — and never a price.
  */
private[relay] final case class MonetaryScale(currency: Currency, exponent: DecimalExponent)

/** An exact decimal amount, as §8 requires: unsigned minor units and a decimal exponent, with no floating-point value anywhere on the wire
  * or in either implementation.
  */
private[relay] final case class Money(units: EventValue, scale: MonetaryScale, clamped: Boolean):
  /** Integer arithmetic only, mirroring what the device does with the same two fields: no float takes part in a TSB/3 amount. */
  def render: String =
    val divisor = Money.PowersOfTen(scale.exponent.value)
    val major = units.value / divisor
    if scale.exponent.value == 0 then s"$major ${scale.currency.code}"
    else
      val minor = units.value % divisor
      val padded = String.format(s"%0${scale.exponent.value}d", java.lang.Long.valueOf(minor))
      s"$major.$padded ${scale.currency.code}"

private[relay] object Money:
  /** 10^0 … 10^4, so that rendering never reaches for `Math.pow`. */
  private val PowersOfTen: Array[Long] = Array(1L, 10L, 100L, 1000L, 10000L)

  /** §8: parse the source's decimal representation, scale to `exponent` digits with HALF_UP, take the unscaled value. `12.345 USD` becomes
    * `value = 1235, exp = 2`. A scaled value beyond `u32` clamps and sets `eflags.AMOUNT_CLAMPED`, and a negative amount reads as zero.
    */
  def fromDecimal(amount: BigDecimal, currency: Currency, exponent: DecimalExponent): Money =
    val scaled = amount.bigDecimal.setScale(exponent.value, RoundingMode.HALF_UP).unscaledValue
    val clamped = scaled.compareTo(java.math.BigInteger.valueOf(0xffffffffL)) > 0
    val units =
      if clamped then EventValue.Max
      else if scaled.signum < 0 then EventValue.Zero
      else EventValue.fromWire(scaled.longValue)
    Money(units, MonetaryScale(currency, exponent), clamped)

  /** The common case: scale by whatever the currency's own minor unit is. */
  def fromDecimal(amount: BigDecimal, currency: Currency): Money =
    fromDecimal(amount, currency, currency.defaultExponent)
