package twitchscreen.relay.twitch

import java.util.concurrent.atomic.AtomicReference
import twitchscreen.relay.bus.RelayEvent

/** The one place that decides whether the channel just changed state.
  *
  * Both EventSub (push, fast) and the Helix poll (pull, certain) observe the same thing, and either may notice first. Routing both through
  * this tracker means a stream going live produces exactly one `StreamStarted` no matter which of them saw it, without either having to
  * know the other exists.
  */
private[twitch] final class ChannelStateTracker(channel: String):
  private val live = AtomicReference[Option[Boolean]](None)

  def wentLive(title: String, game: String): Option[RelayEvent] =
    Option.when(live.getAndSet(Some(true)) != Some(true))(RelayEvent.StreamStarted(channel, title, game))

  /** Startup is not a transition: a relay that boots while the channel is offline should not announce it ended. */
  def wentOffline(): Option[RelayEvent] =
    Option.when(live.getAndSet(Some(false)) == Some(true))(RelayEvent.StreamEnded(channel))
