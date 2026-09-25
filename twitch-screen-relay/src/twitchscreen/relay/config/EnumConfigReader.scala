package twitchscreen.relay.config

import pureconfig.ConfigReader

private[config] object EnumConfigReader:
  def apply[A](label: String, values: Array[A]): ConfigReader[A] = ConfigReader[String].emap: raw =>
    values
      .find(_.toString.equalsIgnoreCase(raw))
      .toRight(ConfigReaderFailures.reason(s"Unknown $label '$raw', expected one of: ${values.mkString(", ")}"))
