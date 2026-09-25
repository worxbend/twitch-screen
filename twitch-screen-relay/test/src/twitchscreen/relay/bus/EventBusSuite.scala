package twitchscreen.relay.bus

import ch.qos.logback.classic.{Level, Logger as LogbackLogger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.time.{Clock, Instant, ZoneOffset}
import org.slf4j.LoggerFactory
import ox.{repeatUntil, sleep, supervised, timeout}
import ox.channels.Channel
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import sttp.tapir.{Schema, SchemaType}

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
      assertEquals(stats.enqueued + stats.dropped, 5L)
      assertEquals((stats.enqueued, stats.delivered, stats.dropped), (2L, 0L, 3L))

  test("K-153: delivered counts finished handler calls, not queue acceptance"):
    supervised:
      val bus = EventBus(clock, queueCapacity = 2)
      val entered = Channel.buffered[Unit](1)
      val release = Channel.buffered[Unit](8)
      bus.consume("blocked"): _ =>
        entered.send(())
        release.receive()
      bus.publish(RelayEvent.Followed("viewer1"))
      timeout(3.seconds)(entered.receive())
      // The handler now holds event 1: two more fill the queue and the last two are dropped.
      (2 to 5).foreach(index => bus.publish(RelayEvent.Followed(s"viewer$index")))
      assertEquals(countersOf(bus, "blocked"), (3L, 0L, 2L))
      (1 to 3).foreach(_ => release.send(()))
      timeout(3.seconds):
        entered.receive()
        entered.receive()
        repeatUntil:
          countersOf(bus, "blocked")._2 == 3L || { sleep(10.millis); false }
      assertEquals(countersOf(bus, "blocked"), (3L, 3L, 2L))

  test("foldTimed counts a delivery per finished event step, not per tick, including a failed step"):
    supervised:
      val bus = EventBus(clock, 4)
      val observed = Channel.buffered[String](4)
      bus.foldTimed("fold", 0, 1.hour): (state, input) =>
        input match
          case Some(BusEvent(_, RelayEvent.Followed("bad"))) => throw IllegalStateException("synthetic failure")
          case Some(BusEvent(_, RelayEvent.Followed(name))) =>
            observed.send(name)
            state + 1
          case _ => state
      List("first", "bad", "last").foreach(name => bus.publish(RelayEvent.Followed(name)))
      timeout(3.seconds):
        assertEquals(List(observed.receive(), observed.receive()), List("first", "last"))
        repeatUntil:
          countersOf(bus, "fold")._2 == 3L || { sleep(10.millis); false }
      assertEquals(countersOf(bus, "fold"), (3L, 3L, 0L))

  test("K-073: queue overflow is logged at WARN when the dropped count reaches 1, 2 and 4, not 3"):
    val logger = LoggerFactory.getLogger("twitchscreen.relay.bus.Subscription").asInstanceOf[LogbackLogger]
    val appender = ListAppender[ILoggingEvent]()
    appender.start()
    logger.addAppender(appender)
    def overflowWarnings: List[String] =
      appender.list.asScala
        .filter(_.getLevel == Level.WARN)
        .map(_.getFormattedMessage)
        .filter(_.contains("'stalled' queue overflow"))
        .toList
    try
      supervised:
        val bus = EventBus(clock, queueCapacity = 2)
        bus.subscribe("stalled").discard
        (1 to 5).foreach(index => bus.publish(RelayEvent.Followed(s"viewer$index")))
        val expected = List(1, 2).map(dropped => s"Subscriber 'stalled' queue overflow: $dropped events dropped")
        assertEquals(overflowWarnings, expected)
        bus.publish(RelayEvent.Followed("viewer6"))
        assertEquals(overflowWarnings, expected :+ "Subscriber 'stalled' queue overflow: 4 events dropped")
    finally
      logger.detachAppender(appender).discard
      appender.stop()

  test("SubscriberStats counters carry OpenAPI descriptions"):
    summon[Schema[SubscriberStats]].schemaType match
      case SchemaType.SProduct(fields) =>
        val descriptions = fields.map(field => field.name.name -> field.schema.description).toMap
        assert(descriptions("enqueued").exists(_.contains("queue")), descriptions)
        assert(descriptions("delivered").exists(_.contains("handler")), descriptions)
        assert(descriptions("dropped").exists(_.contains("full")), descriptions)
      case other => fail(s"SubscriberStats is not a product schema: $other")

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
      val observed = Channel.buffered[Int](2)
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
      timeout(3.seconds):
        assertEquals(observed.receive(), 1)
        assertEquals(observed.receive(), 2)

  test("a device event is categorised as a device event"):
    val device = twitchscreen.relay.protocol.DeviceId("device").toOption.get
    summon[sttp.tapir.Codec[String, twitchscreen.relay.device.ConnectionId, sttp.tapir.CodecFormat.TextPlain]].decode("1") match
      case sttp.tapir.DecodeResult.Value(connection) =>
        assertEquals(RelayEvent.DeviceDisconnected(device, connection, "closed").category, EventCategory.Device)
      case other => fail(s"could not decode connection: $other")

  /** `(enqueued, delivered, dropped)` for one subscriber. */
  private def countersOf(bus: EventBus, name: String): (Long, Long, Long) =
    bus.subscriberStats.find(_.name == name).map(stats => (stats.enqueued, stats.delivered, stats.dropped)).getOrElse(fail(s"no $name"))

  extension [T](value: T) private def discard: Unit = ()
