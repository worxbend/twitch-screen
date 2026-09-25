package twitchscreen.relay.config

import pureconfig.ConfigReader

/** A configuration value that must never appear verbatim in a log line or an HTTP response. Only `value` exposes it. */
final case class Sensitive(value: String):
  override def toString: String = "***"

  def isSet: Boolean = value.trim.nonEmpty

object Sensitive:
  val Empty: Sensitive = Sensitive("")

  given ConfigReader[Sensitive] = ConfigReader[String].map(Sensitive(_))
