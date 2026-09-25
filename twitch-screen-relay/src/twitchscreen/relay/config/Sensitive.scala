package twitchscreen.relay.config

import pureconfig.ConfigReader

/** A configuration value that must never appear verbatim in a log line or an HTTP response. Only `value` exposes it. */
final case class Sensitive(value: String):
  override def toString: String = "***"

  def isSet: Boolean = value.trim.nonEmpty

object Sensitive:
  given ConfigReader[Sensitive] = ConfigReader[String].map(Sensitive(_))

  /** Reads an optional secret: only the exact empty string (HOCON's `= ""` default) is absent. Any other string, including a
    * whitespace-only one, is present, so the owner's validation still sees and rejects it.
    */
  val optionalReader: ConfigReader[Option[Sensitive]] = ConfigReader[String].map(raw => Option.when(raw.nonEmpty)(Sensitive(raw)))
