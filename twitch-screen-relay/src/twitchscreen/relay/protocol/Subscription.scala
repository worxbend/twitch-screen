package twitchscreen.relay.protocol

/** The `tier` byte of an `EVENT` (§6.4).
  *
  * 0 means "not applicable", not Prime. Letting 0 mean Prime was rejected because a failed tier read would then encode a gifted Prime sub,
  * which cannot exist; with this encoding a failed read degrades to "no tier shown".
  */
private[relay] enum SubTier(val code: Int):
  case NotApplicable extends SubTier(0)
  case Prime extends SubTier(1)
  case Tier1 extends SubTier(2)
  case Tier2 extends SubTier(3)
  case Tier3 extends SubTier(4)

private[relay] object SubTier:
  /** §6.4: any value above 4 is treated as 0 and no tier is rendered. */
  def fromWire(raw: Int): SubTier = values.find(_.code == raw).getOrElse(NotApplicable)

  /** Twitch reports tiers as `"1000"`, `"2000"`, `"3000"` and Prime as `"Prime"`; helix and IRC disagree about the case. */
  def fromTwitch(raw: String): SubTier = raw.trim.toLowerCase match
    case "prime" => Prime
    case "1000"  => Tier1
    case "2000"  => Tier2
    case "3000"  => Tier3
    case _       => NotApplicable

/** Cumulative subscription months, as carried by `SUB` (§6.4.1); 0 when not applicable. */
private[relay] opaque type SubMonths = Int

private[relay] object SubMonths:
  val Zero: SubMonths = 0

  def clamp(value: Int): SubMonths = if value <= 0 then 0 else if value > 0xffff then 0xffff else value

  def fromWire(raw: Int): SubMonths = raw & 0xffff

  extension (months: SubMonths) def value: Int = months
