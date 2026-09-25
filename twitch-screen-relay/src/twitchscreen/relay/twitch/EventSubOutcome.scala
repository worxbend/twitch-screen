package twitchscreen.relay.twitch

/** What an EventSub registration step learned about one health component. `Awaiting` is neither healthy nor failed: Twitch has not yet
  * confirmed the subscription or verified the callback. [[TwitchRuntimeHealth.record]] is the only place these become health observations.
  */
private[twitch] enum EventSubOutcome:
  case Healthy
  case Awaiting
  case Failed(reason: String)
