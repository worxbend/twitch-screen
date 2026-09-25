package twitchscreen.relay.config

import pureconfig.ConfigReader
import sttp.tapir.Schema

/** A network interface or host name to bind to or connect to. */
opaque type Hostname = String

object Hostname:
  def apply(value: String): Either[String, Hostname] =
    val trimmed = value.trim
    if trimmed.isEmpty then Left("Hostname must not be blank") else Right(trimmed)

  extension (host: Hostname) def value: String = host

  given ConfigReader[Hostname] = ConfigReader[String].emap(raw => Hostname(raw).left.map(ConfigReaderFailures.reason))
  given Schema[Hostname] = Schema.schemaForString.as[Hostname]
