package twitchscreen.relay.config

import pureconfig.ConfigReader
import pureconfig.error.CannotConvert
import scala.deriving.Mirror

/** Constructor invariants remain useful to callers; at the HOCON boundary they become errors with the cursor's path and origin. */
private[config] object ValidatedConfigReader:
  inline def derivedValidated[A](using Mirror.Of[A]): ConfigReader[A] = apply(ConfigReader.derived[A])

  def apply[A](reader: ConfigReader[A]): ConfigReader[A] = ConfigReader.fromCursor: cursor =>
    try reader.from(cursor)
    catch case error: IllegalArgumentException => cursor.failed(CannotConvert("configuration", "validated configuration", error.getMessage))
