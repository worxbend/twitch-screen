package twitchscreen.relay.bus

import java.time.{Clock, Instant}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.{Channel, ChannelClosed, Source}
import ox.flow.Flow
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description
import scala.util.control.NonFatal
import scala.concurrent.duration.FiniteDuration

/** A [[RelayEvent]] together with the moment the bus accepted it. */
private[relay] final case class BusEvent(at: Instant, event: RelayEvent)

/** Per-subscriber bus counters. `enqueued + dropped` is every event offered to the subscriber, and `delivered <= enqueued`, so
  * `enqueued - delivered` is the backlog, including an event whose handler is still running.
  */
final case class SubscriberStats(
    name: String,
    @description("events accepted into the subscriber queue")
    enqueued: Long,
    @description(
      "events whose handler call has finished (normally or with a logged failure); always 0 for raw-channel subscribers"
    )
    delivered: Long,
    @description("events rejected because the subscriber queue was full")
    dropped: Long
) derives Schema

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
    val subscription = register(name)
    forkDiscard:
      repeatWhile:
        subscription.channel.receiveOrClosed() match
          case message: BusEvent =>
            try handle(message)
            catch case NonFatal(e) => logger.error(s"Subscriber '$name' failed to handle ${message.event.getClass.getSimpleName}", e)
            subscription.markDelivered()
            true
          case ChannelClosed.Done     => false
          case ChannelClosed.Error(t) => throw t

  def subscriberStats: List[SubscriberStats] = subscriptions.get().map(_.stats).toList

  /** A serialized event/timer fold. A recoverable bad step retains the prior state and the worker continues. */
  def foldTimed[S](name: String, initial: S, interval: FiniteDuration)(step: (S, Option[BusEvent]) => S)(using Ox): Unit =
    val subscription = register(name)
    forkDiscard:
      Flow
        .fromSource(subscription.channel)
        .map(message => Option(message))
        .merge(Flow.tick[Option[BusEvent]](interval, None))
        .mapStateful(initial): (state, input) =>
          val next =
            try step(state, input)
            catch
              case NonFatal(error) =>
                logger.error(s"Subscriber '$name' failed a fold step", error)
                state
          if input.isDefined then subscription.markDelivered() // timer ticks are not deliveries
          (next, ())
        .runDrain()

  private def register(name: String)(using ResourceScope): Subscription =
    useInScope(Subscription(name, Channel.buffered[BusEvent](queueCapacity)).tap(add)): subscription =>
      remove(subscription)
      subscription.channel.doneOrClosed().discard

  private def add(subscription: Subscription): Unit =
    subscriptions
      .updateAndGet: current =>
        require(!current.exists(_.name == subscription.name), s"Duplicate bus subscriber: ${subscription.name}")
        current :+ subscription
      .discard

  private def remove(subscription: Subscription): Unit =
    subscriptions.updateAndGet(_.filterNot(_ eq subscription)).discard

private final class Subscription(val name: String, val channel: Channel[BusEvent]):
  private val logger = LoggerFactory.getLogger(getClass)
  private val enqueuedCount = AtomicLong(0)
  private val deliveredCount = AtomicLong(0)
  private val droppedCount = AtomicLong(0)

  /** Never blocks: a full queue costs this subscriber an event, not the publisher its thread. */
  def offer(message: BusEvent): Unit =
    channel.trySendOrClosed(message) match
      case true => enqueuedCount.incrementAndGet().discard
      case false =>
        val dropped = droppedCount.incrementAndGet()
        // Log at powers of two: overload stays visible without turning it into a log storm.
        if (dropped & (dropped - 1)) == 0 then logger.warn(s"Subscriber '$name' queue overflow: $dropped events dropped")
      case _: ChannelClosed => () // the subscriber's scope is ending; its remaining events are of no interest

  /** Called by the bus's own driving loops once a handler or fold step has finished with an event. */
  private[bus] def markDelivered(): Unit = deliveredCount.incrementAndGet().discard

  def stats: SubscriberStats = SubscriberStats(name, enqueuedCount.get(), deliveredCount.get(), droppedCount.get())
