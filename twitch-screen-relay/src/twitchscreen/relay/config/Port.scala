package twitchscreen.relay.config

import pureconfig.ConfigReader
import sttp.tapir.Schema

/** A TCP port the relay binds to or advertises. */
opaque type Port = Int

object Port:
  private val Lowest = 1
  private val Highest = 65535

  def apply(value: Int): Either[String, Port] =
    if value >= Lowest && value <= Highest then Right(value)
    else Left(s"Port out of range ($Lowest-$Highest): $value")

  extension (port: Port) def value: Int = port

  given ConfigReader[Port] = ConfigReader[Int].emap(raw => Port(raw).left.map(ConfigReaderFailures.reason))
  given Schema[Port] = Schema.schemaForInt.as[Port]
