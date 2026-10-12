package twitchscreen.relay.settings

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import ox.{Ox, supervised, timeoutOption}
import scala.concurrent.duration.*
import ox.channels.{Actor, ActorRef, BufferCapacity}

/** Validated partial desired-state input. Public live monitoring uses validatedPatch for resolution before storage admission. */
private[relay] final case class SettingsPatch(
    twitchDisplayEnabled: Option[Boolean] = None,
    preferredDashboard: Option[String] = None,
    mode: Option[String] = None,
    channel: Option[String] = None
):
  require(twitchDisplayEnabled.isDefined || preferredDashboard.isDefined || mode.isDefined || channel.isDefined, "empty settings update")
  preferredDashboard.foreach(value => require(Set("generic", "weather", "cpu").contains(value), "invalid preferred dashboard"))
  mode.foreach(value => require(Set("disabled", "simulated", "live").contains(value), "invalid monitoring mode"))
  channel.foreach(value => require(value != null && value.matches("[a-z0-9_]{1,25}"), "invalid monitoring channel"))

  def applyTo(current: RuntimeSettings): RuntimeSettings =
    current.copy(
      display = DisplaySettings(
        twitchDisplayEnabled.getOrElse(current.display.twitchDisplayEnabled),
        preferredDashboard.getOrElse(current.display.preferredDashboard)
      ),
      monitoring = MonitoringSettings(
        mode.getOrElse(current.monitoring.mode),
        ChannelSetting(channel.orElse(current.monitoring.channel.value))
      )
    )

/** Confirmed storage state is separate from uncertain on-disk readback. Do not project an uncertain observation as committed hub intent. */
private[relay] final case class SettingsSnapshot(
    confirmed: RuntimeSettings,
    revision: Long,
    settingsSource: String,
    storageFailure: Option[RuntimeSettingsFile.WriteFailure] = None,
    projectionFailed: Boolean = false
)

/** Bounded serialized storage owner. Atomic snapshot reads remain nonblocking during fsync; serialized captures share mutation order.
  * Production startup installs synchronous hub projection; monitoring commits enqueue provider work without waiting on its lifecycle.
  */
private[relay] final class RuntimeSettingsWorker private (
    actor: ActorRef[SettingsWorkerState],
    observed: AtomicReference[SettingsSnapshot]
):
  def snapshot: SettingsSnapshot = observed.get()

  private def bounded[A](deadlineNanos: Long)(operation: (() => Boolean) => Either[String, A]): Either[String, A] =
    val abandoned = AtomicBoolean(false)
    def expired(): Boolean = abandoned.get() || deadlineNanos - System.nanoTime() <= 0
    val remaining = deadlineNanos - System.nanoTime()
    if remaining <= 0 then Left("application_timeout")
    else
      try supervised(timeoutOption(remaining.nanos)(operation(() => expired()))).getOrElse(Left("application_timeout"))
      finally abandoned.set(true)

  /** Capture the projected response before the next serialized settings mutation can supersede it. */
  def patchAndRead[A](value: SettingsPatch, deadlineNanos: Long = RuntimeSettingsWorker.deadline())(read: => A): Either[String, A] =
    bounded(deadlineNanos)(expired => actor.ask(_.patch(value, expired).map(_ => read)))

  def patch(value: SettingsPatch): Either[String, SettingsSnapshot] =
    val deadlineNanos = RuntimeSettingsWorker.deadline()
    bounded(deadlineNanos)(expired => actor.ask(_.patch(value, expired)))

  /** Validation must be local and bounded. Provider preparation runs outside this actor and is revalidated against the merged intent. An
    * expired queued request cannot validate, enter storage, or mutate. A timed-out already admitted write needs canonical readback.
    */
  def validatedPatch[A, B](value: SettingsPatch, deadlineNanos: Long = RuntimeSettingsWorker.deadline())(
      validate: (SettingsSnapshot, RuntimeSettings) => Either[String, A]
  )(committed: (SettingsSnapshot, A) => B): Either[String, B] = bounded(deadlineNanos): expired =>
    actor.ask: current =>
      if expired() then Left("application_timeout")
      else
        val before = observed.get()
        validate(before, value.applyTo(before.confirmed))
          .flatMap(result => current.patch(value, expired).map(snapshot => committed(snapshot, result)))

  def readWithin[A](capture: SettingsSnapshot => A, deadlineNanos: Long = RuntimeSettingsWorker.deadline()): Either[String, A] =
    bounded(deadlineNanos)(expired => actor.ask(_ => if expired() then Left("application_timeout") else Right(capture(observed.get()))))

  def read[A](capture: SettingsSnapshot => A): A = actor.ask(_ => capture(observed.get()))

private[relay] object RuntimeSettingsWorker:
  def deadline(): Long = System.nanoTime() + 5.seconds.toNanos

  def start(
      file: RuntimeSettingsFile,
      startup: RuntimeSettings,
      project: SettingsSnapshot => Unit = _ => ()
  )(using Ox): Either[RuntimeSettingsFile.ReadFailure, RuntimeSettingsWorker] =
    file
      .load()
      .map: saved =>
        val initial = SettingsSnapshot(saved.getOrElse(startup), 1L, if saved.isDefined then "persisted" else "startup")
        // Fail startup before binding listeners if the initial owner transaction cannot complete.
        project(initial)
        val observed = AtomicReference(initial)
        given BufferCapacity = BufferCapacity(16)
        new RuntimeSettingsWorker(Actor.create(SettingsWorkerState(file, observed, project)), observed)

private final class SettingsWorkerState(
    file: RuntimeSettingsFile,
    observed: AtomicReference[SettingsSnapshot],
    project: SettingsSnapshot => Unit
):
  def patch(value: SettingsPatch, expired: () => Boolean): Either[String, SettingsSnapshot] =
    val current = observed.get()
    if expired() then Left("application_timeout")
    else if current.projectionFailed then Left("runtime_unavailable")
    else if current.storageFailure.exists(_.uncertain) then Left("storage_uncertain")
    else
      val next = value.applyTo(current.confirmed)
      if next == current.confirmed then Right(current)
      else if current.revision == 0xffffffffL then Left("revision_exhausted")
      else
        file.save(next) match
          case Right(()) =>
            val committed = SettingsSnapshot(next, current.revision + 1, "persisted")
            try
              project(committed)
              observed.set(committed)
              Right(committed)
            catch
              case scala.util.control.NonFatal(_) =>
                // Disk committed, but runtime application is not proven. Never acknowledge or repeat the mutation.
                observed.set(committed.copy(projectionFailed = true))
                Left("runtime_unavailable")
          case Left(failure) =>
            observed.set(current.copy(storageFailure = Some(failure)))
            Left(if failure.uncertain then "storage_uncertain" else "storage_failed")
