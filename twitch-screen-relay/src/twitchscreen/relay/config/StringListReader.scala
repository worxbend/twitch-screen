package twitchscreen.relay.config

import pureconfig.ConfigReader

private[config] object StringListReader:
  /** HOCON's `${?VAR}` yields a STRING, so a list that can be overridden from the environment accepts either a HOCON list or a
    * comma-separated string (split on `,`, trimmed, empties dropped).
    */
  val listOrCommaSeparated: ConfigReader[List[String]] =
    ConfigReader[Vector[String]]
      .map(_.toList)
      .orElse(ConfigReader[String].map(_.split(',').iterator.map(_.trim).filter(_.nonEmpty).toList))
