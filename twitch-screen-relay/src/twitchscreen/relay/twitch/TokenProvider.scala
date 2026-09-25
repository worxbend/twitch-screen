package twitchscreen.relay.twitch

/** Only broadcaster-owned, usable grants with the requested scope may be used for user-token Helix endpoints. */
private[twitch] trait TokenProvider:
  def tokenFor(scope: String): Option[String]
  def reject(token: String): Unit

private[twitch] object TokenProvider:
  def broadcaster(auth: TwitchAuth, channel: String): TokenProvider = new TokenProvider:
    override def tokenFor(scope: String): Option[String] = auth.view.tokenFor(channel, scope)
    override def reject(token: String): Unit = auth.rejectAccessToken(token)
