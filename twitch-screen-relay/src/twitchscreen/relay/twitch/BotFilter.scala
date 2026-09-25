package twitchscreen.relay.twitch

import java.util.Locale
import org.slf4j.LoggerFactory
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.NotificationsConfig

/** §13.1. The one place that decides whether an account's activity reaches the relay at all.
  *
  * Filtering happens at the **source**, before anything is published to the event bus, so a bot costs no bus capacity, no sequence number
  * and no replay slot — and, because `STATS.msg_total` and `STATS.chat_rate` are folded from the same bus, both numbers exclude bot traffic
  * without the statistics layer knowing this type exists. Filtering further downstream would leave exactly those two figures inflated, and
  * they are what the idle screen shows.
  *
  * Both chat messages and non-chat events from a matched account are suppressed, which is why every Twitch source publishes through
  * [[publish]] rather than calling the bus directly: the decision lives here and cannot be reimplemented differently per transport.
  *
  * **Matching is on the lowercased, trimmed display name, and that is weaker than matching on a login.** A Twitch login is lowercase,
  * unique and stable; a display name is user-settable. The relay carries a display name and nothing else, by decision (§13.1), so a bot
  * that renames itself stops being matched until the list is updated, and a human who sets their display name to `Nightbot` is silently
  * suppressed. The shipped defaults work only because each of those services happens to ship with its display name equal to its login apart
  * from capitalisation — a property of those accounts, not of Twitch.
  */
private[relay] final class BotFilter(ignored: Set[String]):
  private val logger = LoggerFactory.getLogger(classOf[BotFilter])

  /** The names actually being matched, lowercased and trimmed, for the startup log line and for tests. */
  def ignoredDisplayNames: Set[String] = ignored

  def isIgnored(displayName: String): Boolean = ignored.contains(BotFilter.normalise(displayName))

  /** Who an event is attributed to, or `None` for an event no account authored — stream lifecycle, channel telemetry, relay internals. An
    * event with no actor is never filtered: there is nobody to match.
    */
  def actorOf(event: RelayEvent): Option[String] = event match
    case RelayEvent.Followed(user)                            => Some(user)
    case RelayEvent.Subscribed(user, _, _, _)                 => Some(user)
    case RelayEvent.SubscriptionGifted(user, _, _, anonymous) => Option.unless(anonymous)(user)
    case RelayEvent.Raided(from, _)                           => Some(from)
    case RelayEvent.BitsCheered(user, _, _)                   => Some(user)
    case RelayEvent.ChatMessaged(user, _, _)                  => Some(user)
    case _                                                    => None

  def allows(event: RelayEvent): Boolean = !actorOf(event).exists(isIgnored)

  /** Publishes unless the actor is on the list. Every Twitch source publishes audience events through this, so there is exactly one place
    * where the decision is made and exactly one place to look when an operator asks why a bot's message appeared.
    */
  def publish(bus: EventBus, event: RelayEvent): Unit =
    if allows(event) then bus.publish(event)
    else logger.debug(s"Suppressed an event from an ignored display name: ${event.summary}")

private[relay] object BotFilter:
  /** Matches nothing. Used by the disabled source and by tests that are not about filtering. */
  val Empty: BotFilter = BotFilter(Set.empty)

  def from(config: NotificationsConfig): BotFilter =
    BotFilter(config.ignoredDisplayNames.map(normalise).filter(_.nonEmpty).toSet)

  /** `Locale.ROOT` on purpose: under a Turkish default locale `"NIGHTBOT".toLowerCase` yields a dotless `ı` and stops matching. */
  private def normalise(displayName: String): String = displayName.trim.toLowerCase(Locale.ROOT)
