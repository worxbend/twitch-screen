package twitchscreen.relay.config

import pureconfig.ConfigReader
import pureconfig.error.CannotConvert

/** Constructor invariants remain useful to callers; at the HOCON boundary they become errors with the cursor's path and origin. */
private[config] object ValidatedConfigReader:
  def apply[A](reader: ConfigReader[A]): ConfigReader[A] = ConfigReader.fromCursor: cursor =>
    try reader.from(cursor)
    catch case error: IllegalArgumentException => cursor.failed(CannotConvert("configuration", "validated configuration", error.getMessage))
