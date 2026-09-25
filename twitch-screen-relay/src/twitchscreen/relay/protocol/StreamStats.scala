package twitchscreen.relay.protocol

import java.time.Instant
import scala.concurrent.duration.{Duration, FiniteDuration}
import sttp.tapir.Schema

/** The idle dashboard's figures. Bundled with its value types: they exist only to describe this record, and the record is the only place
  * they are constructed from raw numbers.
  */
final case class StreamStats(
    state: StreamState,
    viewers: Count,
    followers: Count,
    subscribers: Count,
    uptime: FiniteDuration,
    chatRate: MessagesPerMinute,
    /** Cumulative chat messages since the current stream started, bot-filtered (§6.5). Resets when a stream starts, not when one ends, so
      * the last figure stays on the idle screen until the next stream begins. Defaulted because the aggregator that feeds it is being
      * written separately; a relay that never sets it reports 0, which the device renders as "no messages yet".
      */
    messagesTotal: Count = Count.Zero,
    /** When the current stream started, carried absolutely alongside [[uptime]] so a device may anchor on either. Empty when offline. */
    streamStartedAt: Option[Instant] = None
)

object StreamStats:
  /** What a device sees before the relay has learned anything from Twitch. */
  val Unknown: StreamStats =
    StreamStats(StreamState.Offline, Count.Zero, Count.Zero, Count.Zero, Duration.Zero, MessagesPerMinute.Zero)

/** Whether the channel is streaming. A two-case enum rather than a `Boolean` so call sites read as intent. */
enum StreamState:
  case Live, Offline

object StreamState:
  def fromLive(live: Boolean): StreamState = if live then Live else Offline

  extension (state: StreamState) def isLive: Boolean = state == Live

  given Schema[StreamState] = Schema.derivedEnumeration[StreamState].defaultStringBased

/** A non-negative population figure: viewers, followers, subscribers, or a cumulative message count. */
opaque type Count = Int

object Count:
  val Zero: Count = 0

  def apply(value: Int): Either[String, Count] =
    if value >= 0 then Right(value) else Left(s"Count must not be negative: $value")

  /** For figures coming from Helix, where a missing or nonsensical value should read as zero rather than fail a poll. */
  def clamp(value: Int): Count = if value > 0 then value else 0

  extension (count: Count) def value: Int = count

  given Schema[Count] = Schema.schemaForInt.as[Count]

/** Chat throughput. The firmware's ring gauge clamps its own display at 100, but the relay reports the true rate. */
opaque type MessagesPerMinute = Int

object MessagesPerMinute:
  val Zero: MessagesPerMinute = 0

  def clamp(value: Int): MessagesPerMinute = if value > 0 then value else 0

  extension (rate: MessagesPerMinute) def value: Int = rate

  given Schema[MessagesPerMinute] = Schema.schemaForInt.as[MessagesPerMinute]
