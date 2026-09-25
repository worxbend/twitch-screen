package twitchscreen.relay.activity

import java.time.Instant
import twitchscreen.relay.bus.{BusEvent, EventCategory, RelayEvent}

class ActivityLogSuite extends munit.FunSuite:
  private val at = Instant.ofEpochSecond(1790309000L)

  private def record(log: ActivityLog, index: Int): Unit =
    log.record(BusEvent(at.plusSeconds(index.toLong), RelayEvent.Followed(s"viewer$index")))

  test("entries are returned most recent first"):
    val log = ActivityLog(10)
    (1 to 3).foreach(record(log, _))
    assertEquals(log.recent(10, None).map(_.summary), List("viewer3 followed", "viewer2 followed", "viewer1 followed"))

  test("the buffer keeps only as many entries as it was sized for"):
    val log = ActivityLog(2)
    (1 to 5).foreach(record(log, _))
    assertEquals(log.size, 2)
    assertEquals(log.recent(10, None).map(entry => (entry.id, entry.summary)), List((5L, "viewer5 followed"), (4L, "viewer4 followed")))

  test("routine poll observations and chat cannot evict lifecycle history"):
    val log = ActivityLog(2)
    log.record(BusEvent(at, RelayEvent.TwitchLinkDown("network")))
    (1 to 20).foreach: _ =>
      log.record(BusEvent(at, RelayEvent.FollowersObserved(twitchscreen.relay.protocol.Count.Zero)))
      log.record(BusEvent(at, RelayEvent.ChatMessaged("viewer", "hi", None)))
    assertEquals(log.recent(10, None).map(_.summary), List("Twitch link down: network"))

  test("filtering by category leaves the other categories out"):
    val log = ActivityLog(10)
    record(log, 1)
    log.record(BusEvent(at, RelayEvent.TwitchLinkDown("network")))
    assertEquals(log.recent(10, Some(EventCategory.Twitch)).map(_.category), List(EventCategory.Twitch))

  test("a page size smaller than the buffer returns only that many"):
    val log = ActivityLog(10)
    (1 to 5).foreach(record(log, _))
    assertEquals(log.recent(2, None).size, 2)
