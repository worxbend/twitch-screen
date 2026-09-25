package twitchscreen.relay.twitch

/** OAuth names shared by subscription planning, polling and configuration, and the reference for the shipped config default. */
private[relay] object TwitchScopes:
  val Followers: String = "moderator:read:followers"
  val Subscriptions: String = "channel:read:subscriptions"

  /** Default of `twitch.oauth.scopes` in application.conf. HOCON cannot reference it, so ConfigSuite pins the two as equal. */
  val Default: List[String] = List(Followers, Subscriptions)
