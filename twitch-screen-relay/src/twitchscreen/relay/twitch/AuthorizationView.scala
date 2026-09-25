package twitchscreen.relay.twitch

/** One atomic authorization observation; ownership, usability and scopes always belong to the same grant. */
private[twitch] final case class AuthorizationView(held: Option[UserToken], usable: Option[UserToken], missingScopes: List[String]):
  def broadcaster(channel: String): Option[UserToken] = usable.filter(_.login.equalsIgnoreCase(channel))
  def tokenFor(channel: String, scope: String): Option[String] =
    broadcaster(channel).filter(_.scopes.contains(scope)).map(_.accessToken.value)
