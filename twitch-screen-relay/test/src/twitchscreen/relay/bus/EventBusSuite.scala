package twitchscreen.relay.bus

import java.time.{Clock, Instant, ZoneOffset}
import ox.supervised
import scala.concurrent.duration.DurationInt

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
      assertEquals((stats.delivered, stats.dropped), (2L, 3L))

  test("publishing with no subscribers is not an error"):
    supervised:
      EventBus(clock, queueCapacity = 2).publish(RelayEvent.Followed("nobody is listening"))

  test("duplicate subscription names are rejected without replacing the active consumer"):
    supervised:
      val bus = EventBus(clock, 2)
      val first = bus.subscribe("only")
      intercept[IllegalArgumentException](bus.subscribe("only")).discard
      bus.publish(RelayEvent.Followed("still registered"))
      assertEquals(first.receive().event, RelayEvent.Followed("still registered"))
      assertEquals(bus.subscriberStats.size, 1)

  test("a failed fold step retains state and the next event is processed"):
    supervised:
      val bus = EventBus(clock, 4)
      val observed = ox.channels.Channel.buffered[Int](2)
      bus.foldTimed("recoverable", 0, 1.hour): (state, input) =>
        input match
          case Some(BusEvent(_, RelayEvent.Followed("bad"))) => throw IllegalStateException("synthetic failure")
          case Some(_) =>
            observed.send(state + 1)
            state + 1
          case None => state
      bus.publish(RelayEvent.Followed("first"))
      bus.publish(RelayEvent.Followed("bad"))
      bus.publish(RelayEvent.Followed("last"))
      ox.timeout(3.seconds):
        assertEquals(observed.receive(), 1)
        assertEquals(observed.receive(), 2)

  test("a device event is categorised as a device event"):
    val device = twitchscreen.relay.protocol.DeviceId("device").toOption.get
    summon[sttp.tapir.Codec[String, twitchscreen.relay.device.ConnectionId, sttp.tapir.CodecFormat.TextPlain]].decode("1") match
      case sttp.tapir.DecodeResult.Value(connection) =>
        assertEquals(RelayEvent.DeviceDisconnected(device, connection, "closed").category, EventCategory.Device)
      case other => fail(s"could not decode connection: $other")

  extension [T](value: T) private def discard: Unit = ()
