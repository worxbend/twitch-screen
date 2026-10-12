package twitchscreen.relay.twitch

/** One atomic authorization observation; ownership, usability and scopes always belong to the same grant. */
private[twitch] final case class AuthorizationView(held: Option[UserToken], usable: Option[UserToken], missingScopes: List[String]):
  def broadcaster(broadcasterId: String): Option[UserToken] = usable.filter(_.userId == broadcasterId)
  def tokenFor(broadcasterId: String, scope: String): Option[String] =
    broadcaster(broadcasterId).filter(_.scopes.contains(scope)).map(_.accessToken.value)
