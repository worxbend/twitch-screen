package twitchscreen.relay.bus

import java.time.{Clock, Instant}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.{Channel, ChannelClosed, Source}
import sttp.tapir.Schema
import scala.util.control.NonFatal

/** A [[RelayEvent]] together with the moment the bus accepted it. */
private[relay] final case class BusEvent(at: Instant, event: RelayEvent)

/** What one subscriber has seen. Exposed by the status endpoint so a lagging consumer is visible before it matters. */
final case class SubscriberStats(name: String, delivered: Long, dropped: Long) derives Schema

/** Fan-out of [[RelayEvent]]s to independent subscribers.
  *
  * Each subscriber gets its own bounded channel and its own fork, so a slow consumer cannot stall a producer or any other consumer: once
  * its queue is full it loses events and says so through [[subscriberStats]]. That trade is deliberate — a relay that blocks its Twitch
  * reader because the activity log is busy would drop the events that matter (device pushes) in order to preserve the ones that do not.
  *
  * Ordering is per subscriber, not global: two threads publishing at the same instant may be seen in either order. Nothing downstream
  * depends on cross-producer ordering, because the one place order is observable — the sequence number on the wire — is assigned inside
  * [[twitchscreen.relay.device.DeviceHub]]'s actor.
  */
private[relay] final class EventBus(clock: Clock, queueCapacity: Int):
  private val logger = LoggerFactory.getLogger(getClass)
  private val subscriptions = AtomicReference(Vector.empty[Subscription])

  def publish(event: RelayEvent): Unit =
    val message = BusEvent(clock.instant(), event)
    subscriptions.get().foreach(_.offer(message))

  /** Registers a subscriber and hands back its channel, for callers that want to drive it themselves (e.g. as a Flow). */
  def subscribe(name: String)(using ResourceScope): Source[BusEvent] = register(name).channel

  /** Registers a subscriber and forks the loop that drives it. A failing handler is logged; the subscription survives. */
  def consume(name: String)(handle: BusEvent => Unit)(using Ox): Unit =
    val events = subscribe(name)
    forkDiscard:
      repeatWhile:
        events.receiveOrClosed() match
          case message: BusEvent =>
            try handle(message)
            catch case NonFatal(e) => logger.error(s"Subscriber '$name' failed to handle ${message.event.getClass.getSimpleName}", e)
            true
          case ChannelClosed.Done     => false
          case ChannelClosed.Error(t) => throw t

  def subscriberStats: List[SubscriberStats] = subscriptions.get().map(_.stats).toList

  private def register(name: String)(using ResourceScope): Subscription =
    useInScope(Subscription(name, Channel.buffered[BusEvent](queueCapacity)).tap(add)): subscription =>
      remove(subscription)
      subscription.channel.doneOrClosed().discard

  private def add(subscription: Subscription): Unit = subscriptions.updateAndGet(_ :+ subscription).discard

  private def remove(subscription: Subscription): Unit =
    subscriptions.updateAndGet(_.filterNot(_ eq subscription)).discard

private final class Subscription(val name: String, val channel: Channel[BusEvent]):
  private val deliveredCount = AtomicLong(0)
  private val droppedCount = AtomicLong(0)

  /** Never blocks: a full queue costs this subscriber an event, not the publisher its thread. */
  def offer(message: BusEvent): Unit =
    channel.trySendOrClosed(message) match
      case accepted: Boolean => (if accepted then deliveredCount else droppedCount).incrementAndGet().discard
      case _: ChannelClosed  => () // the subscriber's scope is ending; its remaining events are of no interest

  def stats: SubscriberStats = SubscriberStats(name, deliveredCount.get(), droppedCount.get())
