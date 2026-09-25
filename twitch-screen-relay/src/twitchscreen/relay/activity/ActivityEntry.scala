package twitchscreen.relay.activity

import java.time.Instant
import sttp.tapir.Schema
import twitchscreen.relay.bus.EventCategory

/** One line of the relay's history, as served by `GET /api/v1/activity`. */
final case class ActivityEntry(id: Long, at: Instant, category: EventCategory, summary: String) derives Schema
