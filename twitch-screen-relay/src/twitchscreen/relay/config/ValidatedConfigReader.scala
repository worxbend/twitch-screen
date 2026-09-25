package twitchscreen.relay.config

import com.typesafe.config.ConfigFactory
import pureconfig.{ConfigCursor, ConfigReader}
import pureconfig.error.{CannotConvert, ConfigReaderFailures, UnknownKey}
import scala.deriving.Mirror

/** Constructor invariants remain useful to callers; at the HOCON boundary they become errors with the cursor's path and origin.
  *
  * Every relay-owned section is also read strictly: a key the case class does not declare fails loading, naming its full path. PureConfig
  * would otherwise ignore it, so `twitch.client-secert` would leave the intended secret unset without a word.
  *
  * Keys that come only from JVM system properties are exempt. `ConfigFactory.load()` layers every system property over the relay's own
  * sources, and the JDK's networking properties (`http.proxyHost`, `http.nonProxyHosts`, `http.auth.preference`, ...) share the relay's
  * `http` and `http.auth` namespaces; rejecting them would stop a relay behind an outbound proxy from starting. `/config` still masks such
  * a key, because it is outside [[Config.SchemaPaths]].
  */
private[config] object ValidatedConfigReader:
  inline def derivedValidated[A](using Mirror.ProductOf[A]): ConfigReader[A] = strict(ConfigReader.derived[A])

  inline def strict[A](reader: ConfigReader[A])(using Mirror.ProductOf[A]): ConfigReader[A] =
    rejectingUnknown(ConfigKeys.of[A].toSet, apply(reader))

  def apply[A](reader: ConfigReader[A]): ConfigReader[A] = ConfigReader.fromCursor: cursor =>
    try reader.from(cursor)
    catch case error: IllegalArgumentException => cursor.failed(CannotConvert("configuration", "validated configuration", error.getMessage))

  private def rejectingUnknown[A](known: Set[String], reader: ConfigReader[A]): ConfigReader[A] = ConfigReader.fromCursor: cursor =>
    cursor.asObjectCursor.flatMap: section =>
      val unknown = section.keys.toList.sorted
        .filterNot(known)
        .map(key => key -> section.atKeyOrUndefined(key))
        .filterNot((_, value) => fromSystemProperties(value))
        .flatMap((key, value) => unknownKey(value, key))
      unknown match
        case first :: rest => Left(rest.foldLeft(first)(_ ++ _))
        case Nil           => reader.from(cursor)

  /** The origin `ConfigFactory.load()` gives its system-property overlay, read from the library rather than restated. */
  private val SystemPropertiesOrigin: String = ConfigFactory.systemProperties().origin().description()

  private def fromSystemProperties(cursor: ConfigCursor): Boolean =
    cursor.valueOpt.exists(_.origin().description() == SystemPropertiesOrigin)

  private def unknownKey(cursor: ConfigCursor, key: String): Option[ConfigReaderFailures] =
    cursor.failed[Unit](UnknownKey(key)).left.toOption
