package twitchscreen.relay.twitch

/** OAuth names shared by subscription planning, polling and configuration. */
private[relay] object TwitchScopes:
  val Followers: String = "moderator:read:followers"
  val Subscriptions: String = "channel:read:subscriptions"
