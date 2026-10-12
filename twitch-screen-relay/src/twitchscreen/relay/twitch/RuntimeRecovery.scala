package twitchscreen.relay.twitch

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import ox.*
import ox.channels.{Channel, ChannelClosed}
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import twitchscreen.relay.device.{ClockDiscontinuity, DeviceHub}

/** Application-owned cadence exception for clock discontinuity detection. Reset is serialized by RuntimeSource, not sent through EventBus.
  * One bounded signal keeps sampling independent of slow provider cleanup. OAuth and listeners stay outside the replaceable source scope.
  */
private[relay] final class RuntimeRecovery(hub: DeviceHub, source: RuntimeSource, detector: ClockDiscontinuity):
  private val started = AtomicBoolean(false)

  private[relay] def sample(): Either[String, Boolean] =
    if !detector.sample() || !invalidate() then Right(false)
    else source.recover().map(_ => true)

  private def invalidate(): Boolean =
    val epoch = hub.relayClock.relayEpoch
    var replacement = 0L
    while replacement == 0L || replacement == epoch do replacement = ThreadLocalRandom.current().nextLong()
    val changed = hub.resetEpoch(epoch, replacement)
    if changed then source.invalidate()
    changed

  def start()(using Ox): Unit =
    if started.compareAndSet(false, true) then
      val pending = Channel.buffered[Unit](1)
      forkDiscard:
        forever:
          sleep(1.second)
          if detector.sample() && invalidate() then pending.trySendOrClosed(()).discard
      source.onRecoveryNeeded(() => pending.trySendOrClosed(()).discard)
      forkDiscard:
        repeatWhile:
          pending.receiveOrClosed() match
            case _: Unit =>
              var delay = 1.second
              while source.recoveryNeeded do
                // A retry never rotates the epoch and always reads the latest selected source under its serialized owner.
                // Keep detector sampling separate even while teardown exceeds its budget.
                pending.tryReceive().discard
                source.recover() match
                  case Right(_) => ()
                  case Left(_) =>
                    pending.tryReceive().discard // consume this attempt's signal, not its retry budget
                    timeoutOption(jittered(delay))(pending.receiveOrClosed()).discard
                    delay = (delay * 2).min(30.seconds)
              true
            case _: ChannelClosed => false

  private def jittered(delay: FiniteDuration): FiniteDuration =
    (delay.toMillis + ThreadLocalRandom.current().nextLong(delay.toMillis / 4 + 1)).min(30.seconds.toMillis).millis
