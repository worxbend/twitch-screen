package twitchscreen.relay.twitch

import java.time.{Clock, Instant, ZoneOffset}
import ox.supervised
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.{ChatNotifications, NotificationsConfig}
import twitchscreen.relay.protocol.SubTier

/** §13.1. Bot-authored events never reach the wire, and — because the statistics fold from the same bus this filter guards — never reach
  * `STATS.msg_total` or `STATS.chat_rate` either. Those two numbers are what the idle screen shows, which is why the filter sits at the
  * source rather than one step downstream. That half of the rule is pinned in `StatsStateSuite`, through the fold the device reads.
  */
class BotFilterSuite extends munit.FunSuite:
  private val clock = Clock.fixed(Instant.ofEpochSecond(1790309000L), ZoneOffset.UTC)
  private val filter = BotFilter.from(NotificationsConfig(30.seconds, ChatNotifications.Show))

  test("the shipped defaults are the six names §13.1 lists"):
    assertEquals(filter.ignoredDisplayNames, Set("streamelements", "nightbot", "moobot", "streamlabs", "fossabot", "sery_bot"))

  test("matching is on the lowercased, trimmed display name, because the relay has no login"):
    assert(filter.isIgnored("Nightbot"))
    assert(filter.isIgnored("  NIGHTBOT  "))
    assert(filter.isIgnored("StreamElements"))
    assert(!filter.isIgnored("nightbot_fan"))
    assert(!filter.isIgnored("sparkplug"))

  test("a bot's chat message never reaches the bus"):
    assertEquals(delivered(RelayEvent.ChatMessaged("Nightbot", "!commands", None)), 0L)

  test("a bot's non-chat event is dropped too, not only its chat"):
    // §13.1 suppresses both. A bot that follows or raids would otherwise take the screen over with a full card.
    assertEquals(delivered(RelayEvent.Followed("StreamElements")), 0L)
    assertEquals(delivered(RelayEvent.Raided("Moobot", 12)), 0L)
    assertEquals(delivered(RelayEvent.Subscribed("Fossabot", SubTier.Tier1, 2, "")), 0L)

  test("a human's chat message and a human's event both get through"):
    assertEquals(delivered(RelayEvent.ChatMessaged("sparkplug", "o7", None)), 1L)
    assertEquals(delivered(RelayEvent.Followed("newfriend")), 1L)

  test("an event nobody authored is never filtered, because there is nobody to match"):
    assertEquals(delivered(RelayEvent.StreamEnded("w0rxbend", 3760.seconds)), 1L)
    assertEquals(delivered(RelayEvent.TwitchLinkDown("socket closed")), 1L)

  test("an anonymous gift is not matched against the list, because its actor is not a display name"):
    val gift = RelayEvent.SubscriptionGifted("", 3, SubTier.Tier1, anonymous = true)
    assertEquals(filter.actorOf(gift), None)
    assertEquals(delivered(gift), 1L)

  test("a blank configured name is dropped rather than matching every empty actor"):
    val sloppy = BotFilter.from(NotificationsConfig(30.seconds, ChatNotifications.Show, List("nightbot", "", "   ")))
    assertEquals(sloppy.ignoredDisplayNames, Set("nightbot"))
    assert(!sloppy.isIgnored(""))

  test("an empty list matches nothing, so the filter can be turned off without touching any call site"):
    assert(!BotFilter.Empty.isIgnored("Nightbot"))
    assert(BotFilter.Empty.allows(RelayEvent.ChatMessaged("Nightbot", "!commands", None)))

  /** How many events the bus actually accepted. Non-blocking, so a dropped event is a count of zero rather than a hung test. */
  private def delivered(event: RelayEvent): Long =
    supervised:
      val bus = EventBus(clock, queueCapacity = 8)
      bus.subscribe("test").discard
      filter.publish(bus, event)
      bus.subscriberStats.map(stats => stats.delivered + stats.dropped).sum

  extension [T](value: T) private def discard: Unit = ()
