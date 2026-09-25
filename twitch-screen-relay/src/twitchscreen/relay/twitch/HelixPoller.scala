package twitchscreen.relay.twitch

import com.github.twitch4j.helix.TwitchHelix
import java.time.{Clock, Duration as JDuration}
import org.slf4j.LoggerFactory
import ox.*
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.TwitchConfig
import twitchscreen.relay.protocol.Count

/** The figures on the idle dashboard. Nothing pushes viewer, follower or subscriber totals, so they are polled.
  *
  * Each of the three calls is attempted independently: a relay nobody has authorized yet still gets viewer counts, and a Helix outage costs
  * one poll rather than the loop.
  */
private[twitch] object HelixPoller:
  private val logger = LoggerFactory.getLogger(getClass)

  /** `userToken` is read on every poll, so totals appear from the first poll after consent and follow each refresh. */
  def start(
      helix: TwitchHelix,
      config: TwitchConfig,
      broadcasterId: String,
      userToken: () => Option[String],
      tracker: ChannelStateTracker,
      bus: EventBus,
      clock: Clock
  )(using Ox): Unit =
    logger.info(s"Polling Helix for '${config.channel}' every ${config.pollInterval}")
    forkDiscard:
      forever:
        // Polled before the first sleep, so a freshly started relay has real numbers to show immediately.
        try poll(helix, config, broadcasterId, userToken(), tracker, bus, clock)
        catch
          case NonFatal(error) =>
            logger.warn("Helix poll failed", error)
            bus.publish(RelayEvent.RelayFailure("twitch-poll", String.valueOf(error.getMessage)))
        sleep(config.pollInterval)

  private def poll(
      helix: TwitchHelix,
      config: TwitchConfig,
      broadcasterId: String,
      userToken: Option[String],
      tracker: ChannelStateTracker,
      bus: EventBus,
      clock: Clock
  ): Unit =
    pollStream(helix, config, tracker, bus, clock)
    userToken.foreach: token =>
      pollFollowers(helix, token, broadcasterId, bus)
      pollSubscribers(helix, token, broadcasterId, bus)

  private def pollStream(helix: TwitchHelix, config: TwitchConfig, tracker: ChannelStateTracker, bus: EventBus, clock: Clock): Unit =
    attempt("twitch-streams", bus):
      helix.getStreams(null, null, null, 1, null, null, null, List(config.channel).asJava).execute()
    .foreach: streams =>
      streams.getStreams.asScala.headOption match
        case Some(stream) =>
          // Helix's own `started_at` is what §6.4.1 puts in `STREAM_START.value`; the moment this poll happened to
          // run is not it, and would move the stream's start time on every relay restart.
          tracker.channelInfo(stream.getTitle, stream.getGameName)
          tracker
            .wentLive(String.valueOf(stream.getTitle), String.valueOf(stream.getGameName), Option(stream.getStartedAtInstant))
            .foreach(bus.publish)
          bus.publish(RelayEvent.ViewersObserved(Count.clamp(intOr(stream.getViewerCount)), uptimeOf(stream.getStartedAtInstant, clock)))
        case None => tracker.wentOffline().foreach(bus.publish)

  private def pollFollowers(helix: TwitchHelix, userToken: String, broadcasterId: String, bus: EventBus): Unit =
    attempt("twitch-followers", bus):
      helix.getChannelFollowers(userToken, broadcasterId, null, 1, null).execute()
    .foreach(followers => bus.publish(RelayEvent.FollowersObserved(Count.clamp(intOr(followers.getTotal)))))

  private def pollSubscribers(helix: TwitchHelix, userToken: String, broadcasterId: String, bus: EventBus): Unit =
    attempt("twitch-subscribers", bus):
      helix.getSubscriptions(userToken, broadcasterId, null, null, 1).execute()
    .foreach(subscriptions => bus.publish(RelayEvent.SubscribersObserved(Count.clamp(intOr(subscriptions.getTotal)))))

  /** One Helix endpoint failing must not cost the others their poll — a missing `moderator:read:followers` scope should not hide the viewer
    * count. This is the boundary where twitch4j's exceptions become values.
    */
  private def attempt[T](source: String, bus: EventBus)(call: => T): Option[T] =
    try Some(call)
    catch
      case NonFatal(error) =>
        logger.warn(s"$source failed: ${error.getMessage}")
        bus.publish(RelayEvent.RelayFailure(source, String.valueOf(error.getMessage)))
        None

  private def uptimeOf(startedAt: java.time.Instant, clock: Clock): FiniteDuration =
    if startedAt == null then FiniteDuration(0, SECONDS)
    else FiniteDuration(math.max(0L, JDuration.between(startedAt, clock.instant()).toSeconds), SECONDS)

  private def intOr(value: Integer): Int = if value == null then 0 else value.intValue
