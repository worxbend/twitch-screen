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
    val tracker = ChannelStateTracker(config.channel, clock)
    val webhook =
      EventSubWebhookApi.create(config, bus, tracker, filter, clock, (kind, failure) => health.observe(s"eventsub-$kind", failure))

    new TwitchSource:
      override def status: TwitchStatus = health.status
      override def endpoints: List[ServerEndpoint[Any, Identity]] =
        authApi.endpoints ++ (if config.eventSub.transport == EventSubTransport.Webhook then webhook.endpoints else Nil)
      override def startIngestion()(using Ox): Unit =
        forkDiscard:
          superviseSessions(() => attempt(build(config)), _.close(), reason => health.observe("startup", Some(reason)), sleep): client =>
            TwitchEventHandlers.registerChat(client.getEventManager, bus, filter)
            TwitchEventHandlers.registerEventSub(client.getEventManager, bus, tracker, filter)
            val restartRequested = AtomicBoolean(false)
            val acceptingCallbacks = AtomicBoolean(true)
            val onAppTokenRejected = appTokenRejected(health, restartRequested)
            if config.eventSub.transport == EventSubTransport.WebSocket then
              observeSocket(client, health, restartRequested, acceptingCallbacks)
            val broadcasterId = TwitchRetry.untilReady(
              () => resolveBroadcasterId(client.getHelix, config),
              reason => health.observe("startup", Some(reason)),
              sleep
            )
            health.observe("authorization", Some("broadcaster authorization required"))
            health.observe("chat", Some("connecting"))
            health.observe("streams", Some("awaiting first poll"))
            health.observe("startup", None)
            TwitchRetry.untilReady(
              () => attempt(client.getChat.joinChannel(config.channel)),
              reason => health.observe("chat", Some(reason)),
              sleep
            )
            // The streams poll uses the application token in both transports, so it is the WebSocket transport's rebuild trigger too.
            HelixPoller.start(
              client.getHelix,
              config,
              broadcasterId,
              scope => authorizedToken(auth, config).filter(_ => auth.current.exists(_.scopes.contains(scope))),
              tracker,
              bus,
              clock,
              health,
              () => auth.rejectAccessToken(),
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
        health.observe("startup", Some("application token rejected; rebuilding client"))
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

  private def authorizedToken(auth: TwitchAuth, config: TwitchConfig): Option[String] =
    auth.current.filter(_.login.equalsIgnoreCase(config.channel)).flatMap(_ => auth.accessToken)

  private def maintainSubscriptions(
      client: TwitchClient,
      config: TwitchConfig,
      broadcasterId: String,
      auth: TwitchAuth,
      health: TwitchRuntimeHealth,
      restartRequested: AtomicBoolean,
      appTokenRejected: () => Unit
  ): Unit =
    var registeredGrant = Option.empty[String]
    while !restartRequested.get() do
      try
        val token = authorizedToken(auth, config)
        val missing = (auth.missingScopes ++ List("moderator:read:followers", "channel:read:subscriptions").diff(
          auth.current.toList.flatMap(_.scopes)
        )).distinct
        val authorized = token.isDefined
        health.observe(
          "authorization",
          if !authorized then Some("authorize the configured broadcaster or refresh the expired grant")
          else if missing.nonEmpty then Some(s"missing scopes: ${missing.mkString(", ")}")
          else None
        )
        health.observe(
          "chat",
          if client.getChat.getState == WebsocketConnectionState.CONNECTED then None else Some("disconnected; reconnecting")
        )
        config.eventSub.transport match
          case EventSubTransport.Webhook =>
            val subscriptions = EventSubWebhookApi.unscopedSubscriptions(broadcasterId) ++
              (if authorized && !missing.contains("moderator:read:followers") then EventSubWebhookApi.scopedSubscriptions(broadcasterId)
               else Nil)
            if !authorized || missing.contains("moderator:read:followers") then
              health.observe("eventsub-channel.follow", Some("awaiting broadcaster follow scope"))
            EventSubWebhookApi.reconcileSubscriptions(
              client.getHelix,
              config,
              subscriptions,
              (kind, failure) => health.observe(s"eventsub-$kind", failure),
              appTokenRejected
            )
          case EventSubTransport.WebSocket =>
            val grant = token.filter(_ => !missing.contains("moderator:read:followers"))
            if registeredGrant.isDefined && grant != registeredGrant then restartRequested.set(true)
            else if grant.isDefined && registeredGrant.isEmpty then
              auth.credential.foreach: credential =>
                val subscriptions =
                  EventSubWebhookApi.unscopedSubscriptions(broadcasterId) ++ EventSubWebhookApi.scopedSubscriptions(broadcasterId)
                val accepted = subscriptions.map: (kind, condition) =>
                  health.observe(s"eventsub-${kind.getName}", Some("awaiting subscription confirmation"))
                  val success = client.getEventSocket.register(credential, EventSubFactory.webSocketSubscription(kind, condition))
                  if !success then health.observe(s"eventsub-${kind.getName}", Some("registration rejected; rebuilding connection"))
                  success
                if accepted.forall(identity) then
                  registeredGrant = grant
                  client.getEventSocket.connect()
                else restartRequested.set(true)
            if grant.isEmpty then health.observe("eventsub-connection", Some("awaiting broadcaster authorization and follow scope"))
        health.observe("subscription-maintenance", None)
      catch case NonFatal(error) => health.observe("subscription-maintenance", Some(s"${error.getClass.getSimpleName}; retrying"))
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
              "eventsub-connection",
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
    health.observe(s"eventsub-$kind", Some("subscription rejected; rebuilding connection"))
    restart.set(true)

  private[twitch] def subscriptionSucceeded(health: TwitchRuntimeHealth, kind: String): Unit =
    health.observe(s"eventsub-$kind", None)

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

  private def attempt[A](call: => A): Either[String, A] =
    try Right(call)
    catch
      case rejected: ApplicationTokenRejected => throw rejected
      case NonFatal(error) =>
        logger.warn(s"Twitch startup call failed: ${error.getClass.getSimpleName}")
        Left(s"Twitch unavailable (${error.getClass.getSimpleName}); retrying")

/** Carries no cause or provider text, so nothing token-derived can reach logs or health. */
private[twitch] final class ApplicationTokenRejected extends RuntimeException("Twitch rejected the application token", null, false, false)
