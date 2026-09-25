package twitchscreen.relay.twitch

import com.github.twitch4j.{TwitchClient, TwitchClientBuilder}
import com.github.twitch4j.client.websocket.domain.WebsocketConnectionState
import com.github.twitch4j.eventsub.socket.events.{
  EventSocketConnectionStateEvent,
  EventSocketSubscriptionFailureEvent,
  EventSocketSubscriptionSuccessEvent
}
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import ox.*
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.{EventSubTransport, TwitchConfig}

/** Construction exposes callback endpoints and starts asynchronous token maintenance. Ingestion begins after both listeners bind. */
private[twitch] object LiveTwitchSource:
  private val logger = LoggerFactory.getLogger(getClass)

  /** Matches the maintenance interval, so no failure mode rebuilds the client more often than a restart request does. */
  private[twitch] val SessionFailurePause: FiniteDuration = 30.seconds

  def create(config: TwitchConfig, bus: EventBus, filter: BotFilter, clock: Clock)(using Ox): TwitchSource =
    val health = TwitchRuntimeHealth(config, bus)
    val auth = TwitchAuth.start(config, TwitchOAuthClient.live(config, clock), bus, clock)
    val authApi = TwitchAuthApi(auth)
    val tracker = ChannelStateTracker(config.channel, clock, pollInterval = config.pollInterval)
    val webhook =
      Option.when(config.eventSub.transport == EventSubTransport.Webhook)(
        EventSubWebhookApi.create(config, bus, tracker, filter, clock, health.observeSubscription)
      )

    new TwitchSource:
      override def status: TwitchStatus = health.status
      override def endpoints: List[ServerEndpoint[Any, Identity]] =
        authApi.endpoints ++ webhook.toList.flatMap(_.endpoints)
      override def startIngestion()(using Ox): Unit =
        forkDiscard:
          superviseSessions(
            () => attempt(build(config)),
            _.close(),
            reason => health.observe(HealthComponent.Startup, Some(reason)),
            sleep
          ): client =>
            health.resetSession()
            TwitchEventHandlers.registerChat(client.getEventManager, bus, filter)
            TwitchEventHandlers.registerEventSub(client.getEventManager, bus, tracker, filter, config.channel)
            val restartRequested = AtomicBoolean(false)
            val acceptingCallbacks = AtomicBoolean(true)
            val onAppTokenRejected = appTokenRejected(health, restartRequested)
            if config.eventSub.transport == EventSubTransport.WebSocket then
              observeSocket(client, health, restartRequested, acceptingCallbacks)
            val broadcasterId = TwitchRetry.untilReady(
              () => resolveBroadcasterId(client.getHelix, config),
              reason => health.observe(HealthComponent.Startup, Some(reason)),
              sleep
            )
            health.observe(HealthComponent.Startup, None)
            TwitchRetry.untilReady(
              () => attempt(client.getChat.joinChannel(config.channel)),
              reason => health.observe(HealthComponent.Chat, Some(reason)),
              sleep
            )
            // The streams poll uses the application token in both transports, so it is the WebSocket transport's rebuild trigger too.
            HelixPoller.start(
              client.getHelix,
              PollingContext(config, broadcasterId, tracker, bus, clock, health),
              TokenProvider.broadcaster(auth, config.channel),
              onAppTokenRejected
            )
            try maintainSubscriptions(client, config, broadcasterId, auth, health, restartRequested, onAppTokenRejected)
            finally acceptingCallbacks.set(false)

  /** Owns the client lifecycle: each session gets a freshly built client (and therefore a freshly fetched application token), which is
    * closed with the session's scope. A session ends normally when its maintenance loop sees a restart request; it ends abnormally when it
    * throws, e.g. [[ApplicationTokenRejected]] during broadcaster lookup. Normal ends pause one second; abnormal ends pause
    * [[SessionFailurePause]] so permanently invalid client credentials cannot spin rebuilds against Twitch. Interruption is fatal here and
    * propagates to the owning scope.
    */
  private[twitch] def superviseSessions[C](
      build: () => Either[String, C],
      close: C => Unit,
      observeStartup: String => Unit,
      pause: FiniteDuration => Unit
  )(session: C => Ox ?=> Unit): Nothing =
    forever:
      try
        supervised:
          val client = useInScope(TwitchRetry.untilReady(build, observeStartup, pause))(close)
          session(client)
        pause(1.second)
      catch
        case NonFatal(error) =>
          logger.warn(s"Twitch session failed (${error.getClass.getSimpleName}); rebuilding client")
          observeStartup(s"session failed (${error.getClass.getSimpleName}); rebuilding client")
          pause(SessionFailurePause)

  /** The application token is not something a refresh can fix in place; only a rebuilt client fetches a new one. The flag is consumed by
    * `maintainSubscriptions`, whose 30-second pass interval bounds how often a permanently rejected token can rebuild the client.
    */
  private[twitch] def appTokenRejected(health: TwitchRuntimeHealth, restart: AtomicBoolean): () => Unit =
    val reported = AtomicBoolean(false)
    () =>
      if !reported.getAndSet(true) then
        logger.warn("Twitch rejected the application token; rebuilding client")
        health.observe(HealthComponent.Startup, Some("application token rejected; rebuilding client"))
      restart.set(true)

  private def build(config: TwitchConfig): TwitchClient =
    TwitchClientBuilder
      .builder()
      .withClientId(config.clientId)
      .withClientSecret(config.clientSecret.value)
      .withEnableHelix(true)
      .withEnableChat(true)
      .withEnableEventSocket(config.eventSub.transport == EventSubTransport.WebSocket)
      // No default user credential: webhook calls use the application's token; scoped polls pass their user token explicitly.
      .build()

  private def maintainSubscriptions(
      client: TwitchClient,
      config: TwitchConfig,
      broadcasterId: String,
      auth: TwitchAuth,
      health: TwitchRuntimeHealth,
      restartRequested: AtomicBoolean,
      appTokenRejected: () => Unit
  ): Unit =
    val transport = EventSubTransportStrategy.create(client, config, health, restartRequested, appTokenRejected)
    var registeredGrant = Option.empty[String]
    while !restartRequested.get() do
      try
        val plan = SubscriptionPlan.build(config, broadcasterId, auth.view)
        health.observe(HealthComponent.Authorization, plan.authorizationFailure)
        if !config.oauth.scopes.contains(TwitchScopes.Followers) || plan.followFailure.isDefined then
          health.observe(HealthComponent.EventSubFollow, plan.followFailure)
        health.observe(
          HealthComponent.Chat,
          if client.getChat.getState == WebsocketConnectionState.CONNECTED then None else Some("disconnected; reconnecting")
        )
        registeredGrant = transport.maintain(plan, registeredGrant)
        health.observe(HealthComponent.SubscriptionMaintenance, None)
      catch
        case NonFatal(error) => health.observe(HealthComponent.SubscriptionMaintenance, Some(s"${error.getClass.getSimpleName}; retrying"))
      sleep(30.seconds)

  /** A failed subscription rebuilds the client on the next maintenance pass. Twitch4J may discard permanent failures from its pool, so
    * reconnecting that pool is insufficient. Successful unrelated subscriptions never clear this recovery request.
    */
  private def observeSocket(
      client: TwitchClient,
      health: TwitchRuntimeHealth,
      restartRequested: AtomicBoolean,
      acceptingCallbacks: AtomicBoolean
  ): Unit =
    client.getEventManager
      .onEvent(
        classOf[EventSocketConnectionStateEvent],
        event =>
          if acceptingCallbacks.get() then
            health.observe(
              HealthComponent.EventSubConnection,
              if event.getState == WebsocketConnectionState.CONNECTED then None else Some("disconnected; reconnecting")
            )
      )
      .discard
    client.getEventManager
      .onEvent(
        classOf[EventSocketSubscriptionSuccessEvent],
        event => if acceptingCallbacks.get() then subscriptionSucceeded(health, event.getSubscription.getType.getName)
      )
      .discard
    client.getEventManager
      .onEvent(
        classOf[EventSocketSubscriptionFailureEvent],
        event => if acceptingCallbacks.get() then subscriptionFailed(health, restartRequested, event.getSubscription.getType.getName)
      )
      .discard

  private[twitch] def subscriptionFailed(health: TwitchRuntimeHealth, restart: AtomicBoolean, kind: String): Unit =
    health.observeSubscription(kind, Some("subscription rejected; rebuilding connection"))
    restart.set(true)

  private[twitch] def subscriptionSucceeded(health: TwitchRuntimeHealth, kind: String): Unit =
    health.observeSubscription(kind, None)

  /** A 401 here is not retried against the same client: it throws [[ApplicationTokenRejected]] so the session ends and the next one builds
    * a client with a fresh application token.
    */
  private[twitch] def resolveBroadcasterId(helix: com.github.twitch4j.helix.TwitchHelix, config: TwitchConfig): Either[String, String] =
    attempt(
      try
        helix
          .getUsers(null, null, List(config.channel).asJava)
          .execute()
          .getUsers
          .asScala
          .headOption
          .flatMap(user => Option(user.getId))
      catch case NonFatal(error) if HelixPoller.isUnauthorized(error) => throw ApplicationTokenRejected()
    ).flatMap(_.toRight(s"Twitch has no channel named '${config.channel}'"))

  private def attempt[A](call: => A): Either[String, A] = TwitchCall.attempt("contact Twitch")(call).left.map(_.message)

/** Carries no cause or provider text, so nothing token-derived can reach logs or health. */
private[twitch] final class ApplicationTokenRejected extends RuntimeException("Twitch rejected the application token", null, false, false)
