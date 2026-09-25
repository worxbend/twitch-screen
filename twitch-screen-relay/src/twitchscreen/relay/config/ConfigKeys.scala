package twitchscreen.relay.config

import pureconfig.{CamelCase, ConfigFieldMapping, KebabCase}
import scala.compiletime.constValueTuple
import scala.deriving.Mirror

/** The HOCON keys a case class is read from, spelled the way `ConfigReader.derived` looks them up (camelCase fields, kebab-case keys). */
private[config] object ConfigKeys:
  private val mapping = ConfigFieldMapping(CamelCase, KebabCase)

  inline def of[A](using m: Mirror.ProductOf[A]): List[String] =
    constValueTuple[m.MirroredElemLabels].toList.map(label => mapping(label.toString))
