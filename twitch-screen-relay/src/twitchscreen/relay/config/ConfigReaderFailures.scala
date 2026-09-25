package twitchscreen.relay.config

import pureconfig.error.FailureReason

/** Adapts the `Either[String, T]` smart constructors of the config primitives to PureConfig's failure vocabulary. */
private[config] object ConfigReaderFailures:
  def reason(message: String): FailureReason =
    new FailureReason:
      override def description: String = message
