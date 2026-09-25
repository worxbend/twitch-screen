package twitchscreen.relay.twitch

import java.time.{Clock, Instant, ZoneOffset}
import ox.supervised
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.{ChatNotifications, NotificationsConfig}

/** RLY-21 / K-064: twitch4j leaves `ChannelUpdateV2Event` fields null when Twitch omits them. The WebSocket adapters must turn those nulls
  * into `None` before [[EventSubMapping]] sees them, so a missing title never reaches the screen as the word "null".
  */
class TwitchEventHandlersSuite extends munit.FunSuite:
  import TwitchEventSubFixtures.*

  private val now = Instant.ofEpochSecond(1790309000L)
  private val clock = Clock.fixed(now, ZoneOffset.UTC)
  private val notifications = NotificationsConfig(30.seconds, ChatNotifications.Hide)

  test("fixtures: Twitch's snake_case JSON populates the twitch4j getters"):
    val update = channelUpdate(Some("Streamer"), Some("Soldering"), Some("Science"))
    assertEquals(
      (update.getBroadcasterUserName, update.getTitle, update.getCategoryName),
      ("Streamer", "Soldering", "Science")
    )
    assertEquals(follow(Some("pixelpainter")).getUserName, "pixelpainter")
    assertEquals(streamOnline(Some("2026-09-25T09:02:20Z")).getStartedAt, Instant.parse("2026-09-25T09:02:20Z"))
    val empty = channelUpdate(None, None, None)
    assertEquals((empty.getBroadcasterUserName, empty.getTitle, empty.getCategoryName), (null, null, null))

  test("RLY-21: an update with every field missing names the configured channel and leaves the text empty"):
    val payload = TwitchEventHandlers.channelUpdatePayload(channelUpdate(None, None, None))
    assertEquals(payload, EventSubPayload())
    assert(!payload.productIterator.contains(Some(null)), payload.toString)
    val update = EventSubMapping.channelUpdated(payload.broadcasterUserName, payload.title, payload.categoryName, "somechannel")
    assertEquals(update, RelayEvent.ChannelUpdated("somechannel", "", ""))
    assertEquals(update.summary, "somechannel updated:  ()")
    assert(!update.summary.contains("null"), update.summary)

  test("RLY-21: fields Twitch reports pass through unchanged"):
    val payload = TwitchEventHandlers.channelUpdatePayload(channelUpdate(Some("Streamer"), Some("Soldering"), Some("Science")))
    assertEquals(
      payload,
      EventSubPayload(broadcasterUserName = Some("Streamer"), title = Some("Soldering"), categoryName = Some("Science"))
    )
    assertEquals(
      EventSubMapping.channelUpdated(payload.broadcasterUserName, payload.title, payload.categoryName, "somechannel"),
      RelayEvent.ChannelUpdated("Streamer", "Soldering", "Science")
    )

  test("RLY-21: a missing category alone becomes empty and the rest is kept"):
    val payload = TwitchEventHandlers.channelUpdatePayload(channelUpdate(Some("Streamer"), Some("Soldering"), None))
    assertEquals(
      EventSubMapping.channelUpdated(payload.broadcasterUserName, payload.title, payload.categoryName, "somechannel"),
      RelayEvent.ChannelUpdated("Streamer", "Soldering", "")
    )

  test("a follow or online event with its field missing adapts to None, never Some(null)"):
    assertEquals(TwitchEventHandlers.followPayload(follow(None)), EventSubPayload())
    assertEquals(TwitchEventHandlers.followPayload(follow(Some("pixelpainter"))), EventSubPayload(userName = Some("pixelpainter")))
    assertEquals(TwitchEventHandlers.streamOnlinePayload(streamOnline(None)), EventSubPayload())
    assertEquals(
      TwitchEventHandlers.streamOnlinePayload(streamOnline(Some("2026-09-25T09:02:20Z"))),
      EventSubPayload(startedAt = Some("2026-09-25T09:02:20Z"))
    )

  test("K-064: the handlers registerEventSub wires publish null-normalised events"):
    supervised:
      val bus = EventBus(clock, queueCapacity = 16)
      val received = bus.subscribe("test")
      val events = eventManager()
      try
        TwitchEventHandlers.registerEventSub(
          events,
          bus,
          ChannelStateTracker("somechannel", clock),
          BotFilter.from(notifications),
          "somechannel"
        )
        events.publish(channelUpdate(None, None, None))
        assertEquals(received.receive().event, RelayEvent.ChannelUpdated("somechannel", "", ""))
        events.publish(follow(None))
        events.publish(streamOnline(None))
        assertEquals(received.receive().event, RelayEvent.StreamStarted("somechannel", "", "", Some(now)))
        assert(received.tryReceive().isEmpty, "a follow without a user must publish nothing")
      finally events.close()
