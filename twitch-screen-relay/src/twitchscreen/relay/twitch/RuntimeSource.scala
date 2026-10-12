package twitchscreen.relay.twitch

import java.time.Clock
import java.util.concurrent.{CompletableFuture, CountDownLatch, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.AtomicReference
import ox.*
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.control.NonFatal
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.{EventSubTransport, StatsConfig, TwitchConfig, TwitchMode}
import twitchscreen.relay.device.{DeviceHub, SourceFence, SourceIngestion}
import twitchscreen.relay.http.Fail

/** Stable application-facing source. Each admitted producer, timer and provider belongs to one replaceable structured scope. A failed
  * teardown keeps its owner reachable and prevents a competing source, even if cancellation was requested successfully.
  */
private[relay] final class RuntimeSource private (
    hub: DeviceHub,
    create: (TwitchConfig, SourceFence) => Ox ?=> TwitchSource,
    deployment: TwitchConfig,
    teardownBudget: FiniteDuration,
    applicationBudget: FiniteDuration,
    activateSource: (SourceFence, TwitchMode) => Unit
)(using application: Ox)
    extends TwitchSource:
  private final case class Owned(cancel: () => Unit, done: CountDownLatch, source: CompletableFuture[TwitchSource], start: CountDownLatch)
  private final case class Current(source: TwitchSource, fence: SourceFence, config: TwitchConfig)
  private val deduplication = WebhookDeduplication()
  private val active = AtomicReference[Option[Current]](None)
  private def currentSource: Option[TwitchSource] =
    active.get().filter(_.fence == hub.sourceFence).map(_.source)
  private var owned = Option.empty[Owned]
  private val selected = AtomicReference(deployment)
  private var ingestionStarted = false
  private val failure = AtomicReference[Option[String]](None)
  private val recoverySignal = AtomicReference[() => Unit](() => ())

  private def activate(mode: TwitchMode): Unit = activateSource(hub.sourceFence, mode)

  /** One application recovery worker observes failures; notification never waits for provider cleanup. */
  private[relay] def onRecoveryNeeded(signal: () => Unit): Unit =
    recoverySignal.set(signal)
    if recoveryNeeded then signal()

  private[relay] def recoveryNeeded: Boolean =
    failure.get().isDefined || active.get().forall(_.fence != hub.sourceFence)

  private def failed(code: String): Unit =
    failure.set(Some(code))
    recoverySignal.get()()

  override def status: TwitchStatus = currentSource.fold(
    TwitchStatus(TwitchMode.Disabled, TwitchHealth.Disconnected, "", failure.get().getOrElse("source applying"))
  )(_.status)

  // Mounted once, including when ingestion is disabled. Dispatch captures the current source; its admitted bus retains the original fence.
  override val endpoints: List[ServerEndpoint[Any, Identity]] =
    if deployment.eventSub.transport == EventSubTransport.Webhook then
      List(EventSubWebhookApi.callbackEndpoint.handle((id, at, signature, kind, body) => dispatchWebhook(id, at, signature, kind, body)))
    else Nil

  private[twitch] def dispatchWebhook(id: String, at: String, signature: String, kind: String, body: String): Either[Fail, String] =
    currentSource
      .flatMap(_.webhookCallback)
      .fold[Either[Fail, String]](Left(Fail.Unavailable("EventSub source unavailable")))(
        _(id, at, signature, kind, body)
      )

  override def startIngestion()(using Ox): Unit = synchronized:
    if !ingestionStarted then
      ingestionStarted = true
      owned.foreach(_.start.countDown())

  /** Detection can retire current dispatch/alerts without waiting for a source's uncooperative cleanup. */
  private[relay] def invalidate(): Unit =
    active.set(None)
    activate(TwitchMode.Disabled)

  private[relay] def recover(): Either[String, Unit] = synchronized(replaceSelected())

  private[relay] def applicationFailure: Option[String] = failure.get()
  private[relay] def applied(fence: SourceFence): Option[TwitchConfig] =
    active.get().filter(value => value.fence == fence && value.config == selected.get()).map(_.config)

  /** Accepted intent is delivered reliably without taking the provider lifecycle lock. HTTP never waits for cleanup or construction. */
  private[relay] def request(config: TwitchConfig): Unit =
    selected.set(config)
    active.set(None)
    failure.set(None)
    activate(TwitchMode.Disabled)
    recoverySignal.get()()

  /** The caller installs a committed hub fence before replacement. */
  private[relay] def replace(config: TwitchConfig): Either[String, Unit] = synchronized:
    selected.set(config)
    replaceSelected()

  private def replaceSelected(): Either[String, Unit] =
    active.set(None)
    activate(TwitchMode.Disabled)
    owned.foreach(_.cancel())
    val stopped = owned.forall(_.done.await(teardownBudget.toMillis, TimeUnit.MILLISECONDS))
    if !stopped then
      failed("source_teardown_timeout")
      Left("source_teardown_timeout")
    else
      owned = None
      launch(selected.get())

  /** Hub authority changes before cancellation, so queued callbacks cannot reuse the new epoch's numeric generation. */
  private[relay] def reset(expectedEpoch: Long, replacementEpoch: Long): Either[String, Boolean] = synchronized:
    if !hub.resetEpoch(expectedEpoch, replacementEpoch) then Right(false)
    else replaceSelected().map(_ => true)

  private def launch(config: TwitchConfig): Either[String, Unit] =
    val captured = hub.publicationSnapshot
    val fence = SourceFence(captured.relayEpoch, captured.control.fold(1L)(_.sourceGeneration))
    val authoritative =
      captured.settings.forall(value => twitchscreen.relay.settings.RuntimeSettingsStartup.select(deployment, value).config == config)
    if !authoritative || selected.get() != config then
      failed("source_superseded")
      Left("source_superseded")
    else launchAt(config, fence)

  private def launchAt(config: TwitchConfig, fence: SourceFence): Either[String, Unit] =
    activateSource(fence, config.mode)
    val ready = CompletableFuture[TwitchSource]()
    val done = CountDownLatch(1)
    val begin = CountDownLatch(if ingestionStarted then 0 else 1)
    val task = forkCancellable:
      try
        supervised:
          val source = create(config, fence)
          source.webhookOwner.foreach(_.useDeduplication(deduplication))
          ready.complete(source).discard
          begin.await()
          source.startIngestion()
          never
      catch case NonFatal(_) => ready.completeExceptionally(IllegalStateException("source application failed")).discard
      finally
        val source = if ready.isCompletedExceptionally then null else ready.getNow(null)
        val current = active.get()
        if source != null && current.exists(_.source eq source) && active.compareAndSet(current, None) then
          failed("source_application_failed")
          activate(TwitchMode.Disabled)
        done.countDown()
    val current = Owned(() => task.cancelNow(), done, ready, begin)
    owned = Some(current)
    try
      val source = ready.get(applicationBudget.toMillis, TimeUnit.MILLISECONDS)
      if done.getCount == 0 then
        activate(TwitchMode.Disabled)
        failed("source_application_failed")
        Left("source_application_failed")
      else
        failure.set(None)
        active.set(Some(Current(source, fence, config)))
        if done.getCount == 0 || hub.sourceFence != fence || selected.get() != config then
          active.set(None)
          activate(TwitchMode.Disabled)
          failed("source_application_failed")
          Left("source_application_failed")
        else Right(())
    catch
      case _: TimeoutException =>
        current.cancel()
        activate(TwitchMode.Disabled)
        failed("source_application_timeout")
        Left("source_application_timeout")
      case NonFatal(_) =>
        current.cancel()
        activate(TwitchMode.Disabled)
        failed("source_application_failed")
        Left("source_application_failed")

private[relay] object RuntimeSource:
  def start(
      config: TwitchConfig,
      bus: EventBus,
      hub: DeviceHub,
      stats: StatsConfig,
      filter: BotFilter,
      clock: Clock,
      oauth: TwitchOAuthOwner,
      activate: TwitchMode => Unit = _ => (),
      activateFenced: Option[(SourceFence, TwitchMode) => Unit] = None
  )(using Ox): RuntimeSource =
    startFencedWith(
      config,
      hub,
      (selected, fence) => TwitchSource.create(selected, SourceIngestion.startAtFence(bus, hub, stats, fence), filter, clock, oauth),
      activate = activate,
      activateFenced = activateFenced
    )

  private[relay] def startWith(
      config: TwitchConfig,
      hub: DeviceHub,
      create: TwitchConfig => Ox ?=> TwitchSource,
      teardownBudget: FiniteDuration = 5.seconds,
      applicationBudget: FiniteDuration = 15.seconds,
      activate: TwitchMode => Unit = _ => ()
  )(using Ox): RuntimeSource =
    startFencedWith(config, hub, (selected, _) => create(selected), teardownBudget, applicationBudget, activate)

  private[relay] def startFencedWith(
      config: TwitchConfig,
      hub: DeviceHub,
      create: (TwitchConfig, SourceFence) => Ox ?=> TwitchSource,
      teardownBudget: FiniteDuration = 5.seconds,
      applicationBudget: FiniteDuration = 15.seconds,
      activate: TwitchMode => Unit = _ => (),
      activateFenced: Option[(SourceFence, TwitchMode) => Unit] = None
  )(using Ox): RuntimeSource =
    val callback = activateFenced.getOrElse((_, mode) => activate(mode))
    val owner = new RuntimeSource(hub, create, config, teardownBudget, applicationBudget, callback)
    owner.replace(config).fold(code => throw IllegalStateException(code), identity)
    owner
