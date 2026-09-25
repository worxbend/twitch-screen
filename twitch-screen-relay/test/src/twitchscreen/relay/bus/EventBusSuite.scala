package twitchscreen.relay.bus

import java.time.{Clock, Instant, ZoneOffset}
import ox.supervised

class EventBusSuite extends munit.FunSuite:
  private val clock = Clock.fixed(Instant.ofEpochSecond(1790309000L), ZoneOffset.UTC)

  test("every subscriber sees every event"):
    supervised:
      val bus = EventBus(clock, queueCapacity = 8)
      val first = bus.subscribe("first")
      val second = bus.subscribe("second")
      bus.publish(RelayEvent.Followed("pixelpainter"))
      assertEquals(List(first.receive().event, second.receive().event), List.fill(2)(RelayEvent.Followed("pixelpainter")))

  test("events are stamped with the bus's clock rather than each publisher's idea of now"):
    supervised:
      val bus = EventBus(clock, queueCapacity = 4)
      val events = bus.subscribe("only")
      bus.publish(RelayEvent.Followed("pixelpainter"))
      assertEquals(events.receive().at, clock.instant())

  test("a subscriber that stops reading loses events instead of blocking the publisher"):
    supervised:
      val bus = EventBus(clock, queueCapacity = 2)
      bus.subscribe("stalled").discard
      (1 to 5).foreach(index => bus.publish(RelayEvent.Followed(s"viewer$index")))
      val stats = bus.subscriberStats.head
      assertEquals(stats.delivered + stats.dropped, 5L)

  test("a stalled subscriber reports what it lost"):
    supervised:
      val bus = EventBus(clock, queueCapacity = 2)
      bus.subscribe("stalled").discard
      (1 to 5).foreach(index => bus.publish(RelayEvent.Followed(s"viewer$index")))
      assert(bus.subscriberStats.head.dropped > 0)

  test("publishing with no subscribers is not an error"):
    supervised:
      EventBus(clock, queueCapacity = 2).publish(RelayEvent.Followed("nobody is listening"))

  test("a device event is categorised as a device event"):
    assertEquals(RelayEvent.TwitchLinkDown("network").category, EventCategory.Twitch)

  extension [T](value: T) private def discard: Unit = ()
