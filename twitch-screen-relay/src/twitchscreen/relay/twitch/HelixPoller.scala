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

  /** `userToken` is read on every poll, so totals appear from the first poll after consent and follow each refresh.
    *
    * The two rejection callbacks are deliberately separate. A 401 on a user-token call (followers, subscribers) calls `unauthorized`, which
    * withholds the broadcaster grant until it is refreshed. A 401 on the application-token call (streams) calls `appTokenRejected`, which
    * asks the owning session to rebuild the client and so fetch a fresh application token. Neither ever triggers the other.
    */
  def start(
      helix: TwitchHelix,
      config: TwitchConfig,
      broadcasterId: String,
      userToken: String => Option[String],
      tracker: ChannelStateTracker,
      bus: EventBus,
      clock: Clock,
      health: TwitchRuntimeHealth,
      unauthorized: () => Unit,
      appTokenRejected: () => Unit
  )(using Ox): Unit =
    logger.info(s"Polling Helix for '${config.channel}' every ${config.pollInterval}")
    forkDiscard:
      forever:
        // Polled before the first sleep, so a freshly started relay has real numbers to show immediately.
        try poll(helix, config, broadcasterId, userToken, tracker, bus, clock, health, unauthorized, appTokenRejected)
        catch
          case NonFatal(error) =>
            health.observe("poll", Some(error.getClass.getSimpleName))
        sleep(config.pollInterval)

  private[twitch] def poll(
      helix: TwitchHelix,
      config: TwitchConfig,
      broadcasterId: String,
      userToken: String => Option[String],
      tracker: ChannelStateTracker,
      bus: EventBus,
      clock: Clock,
      health: TwitchRuntimeHealth,
      unauthorized: () => Unit,
      appTokenRejected: () => Unit
  ): Unit =
    pollStream(helix, config, tracker, bus, clock, health, appTokenRejected)
    userToken("moderator:read:followers").foreach(token => pollFollowers(helix, token, broadcasterId, bus, health, unauthorized))
    userToken("channel:read:subscriptions").foreach(token => pollSubscribers(helix, token, broadcasterId, bus, health, unauthorized))
    health.observe("poll", None)

  /** Streams are read with the application token (`null` credential), so a 401 here means that token is no longer valid. */
  private[twitch] def pollStream(
      helix: TwitchHelix,
      config: TwitchConfig,
      tracker: ChannelStateTracker,
      bus: EventBus,
      clock: Clock,
      health: TwitchRuntimeHealth,
      appTokenRejected: () => Unit
  ): Unit =
    attempt("streams", health, appTokenRejected):
      helix.getStreams(null, null, null, 1, null, null, null, List(config.channel).asJava).execute()
    .foreach: streams =>
      streams.getStreams.asScala.headOption match
        case Some(stream) =>
          // Helix's own `started_at` is what §6.4.1 puts in `STREAM_START.value`; the moment this poll happened to
          // run is not it, and would move the stream's start time on every relay restart.
          tracker.channelInfo(stream.getTitle, stream.getGameName)
          tracker
            .observedLive(
              Option(stream.getTitle).getOrElse(""),
              Option(stream.getGameName).getOrElse(""),
              Option(stream.getStartedAtInstant),
              publish = bus.publish
            )
            .discard
          bus.publish(RelayEvent.ViewersObserved(Count.clamp(intOr(stream.getViewerCount)), uptimeOf(stream.getStartedAtInstant, clock)))
        case None => tracker.observedOffline(bus.publish).discard

  private def pollFollowers(
      helix: TwitchHelix,
      userToken: String,
      broadcasterId: String,
      bus: EventBus,
      health: TwitchRuntimeHealth,
      unauthorized: () => Unit
  ): Unit =
    attempt("followers", health, unauthorized):
      helix.getChannelFollowers(userToken, broadcasterId, null, 1, null).execute()
    .foreach(followers => bus.publish(RelayEvent.FollowersObserved(Count.clamp(intOr(followers.getTotal)))))

  private def pollSubscribers(
      helix: TwitchHelix,
      userToken: String,
      broadcasterId: String,
      bus: EventBus,
      health: TwitchRuntimeHealth,
      unauthorized: () => Unit
  ): Unit =
    attempt("subscribers", health, unauthorized):
      helix.getSubscriptions(userToken, broadcasterId, null, null, 1).execute()
    .foreach(subscriptions => bus.publish(RelayEvent.SubscribersObserved(Count.clamp(intOr(subscriptions.getTotal)))))

  /** One Helix endpoint failing must not cost the others their poll — a missing `moderator:read:followers` scope should not hide the viewer
    * count. This is the boundary where twitch4j's exceptions become values.
    */
  private def attempt[T](source: String, health: TwitchRuntimeHealth, unauthorized: () => Unit)(call: => T): Option[T] =
    try
      val result = call
      health.observe(source, None)
      Some(result)
    catch
      case NonFatal(error) =>
        if isUnauthorized(error) then unauthorized()
        health.observe(source, Some(error.getClass.getSimpleName))
        None

  private def uptimeOf(startedAt: java.time.Instant, clock: Clock): FiniteDuration =
    if startedAt == null then FiniteDuration(0, SECONDS)
    else FiniteDuration(math.max(0L, JDuration.between(startedAt, clock.instant()).toSeconds), SECONDS)

  private def intOr(value: Integer): Int = if value == null then 0 else value.intValue

  private[twitch] def isUnauthorized(error: Throwable): Boolean =
    Iterator
      .iterate(Option(error))(_.flatMap(cause => Option(cause.getCause)))
      .take(10)
      .takeWhile(_.isDefined)
      .flatten
      .exists:
        case _: com.github.twitch4j.common.exception.UnauthorizedException => true
        case failure: feign.FeignException                                 => failure.status() == 401
        case _                                                             => false
