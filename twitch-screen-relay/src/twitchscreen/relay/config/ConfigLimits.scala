package twitchscreen.relay.config

private[config] object ConfigLimits:
  private val MaxBufferEntries = 65536

  def buffer(value: Int, path: String): Unit =
    require(value >= 1 && value <= MaxBufferEntries, s"$path must be 1..$MaxBufferEntries")
