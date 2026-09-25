package twitchscreen.relay.config

import pureconfig.ConfigReader
import com.typesafe.config.ConfigValueType

private[config] object StringListReader:
  /** HOCON's `${?VAR}` yields a STRING, so a list that can be overridden from the environment accepts either a HOCON list or a
    * comma-separated string (split on `,`, trimmed, empties dropped).
    */
  val listOrCommaSeparated: ConfigReader[List[String]] = ConfigReader.fromCursor: cursor =>
    if cursor.valueOpt.exists(_.valueType() == ConfigValueType.STRING) then
      cursor.asString.map(_.split(',').iterator.map(_.trim).filter(_.nonEmpty).toList)
    else ConfigReader[Vector[String]].from(cursor).map(_.toList)
