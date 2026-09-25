package twitchscreen.relay.twitch

private[twitch] enum AuthorizationFailure:
  case InvalidState
  case Provider(error: TwitchCallFailure)

  def message: String = this match
    case InvalidState    => s"unknown or expired authorization request; start again at ${TwitchAuth.AuthorizePath}"
    case Provider(error) => error.message
