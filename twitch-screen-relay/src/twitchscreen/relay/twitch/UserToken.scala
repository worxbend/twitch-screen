package twitchscreen.relay.twitch

import java.time.Instant
import twitchscreen.relay.config.Sensitive

/** The broadcaster's user access token, obtained through the consent flow of [[TwitchAuth]].
  *
  * `login` and `userId` name the account that granted it — normally the broadcaster, though a moderator's token is enough for follows.
  * `scopes` is what Twitch actually granted, which can be less than what was asked for.
  */
private[twitch] final case class UserToken(
    accessToken: Sensitive,
    refreshToken: Sensitive,
    expiresAt: Instant,
    scopes: List[String],
    userId: String,
    login: String
)
