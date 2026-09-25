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
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.{EventSubTransport, TwitchConfig}

/** Construction exposes callback endpoints and starts asynchronous token maintenance. Ingestion begins after both listeners bind. */
private[twitch] object LiveTwitchSource:
  private val logger = LoggerFactory.getLogger(getClass)

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
          forever:
            supervised:
              val client = useInScope(
                TwitchRetry.untilReady(() => attempt(build(config)), reason => health.observe("startup", Some(reason)), sleep)
              )(_.close())
              TwitchEventHandlers.registerChat(client.getEventManager, bus, filter)
              TwitchEventHandlers.registerEventSub(client.getEventManager, bus, tracker, filter)
              val restartRequested = AtomicBoolean(false)
              val acceptingCallbacks = AtomicBoolean(true)
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
              HelixPoller.start(
                client.getHelix,
                config,
                broadcasterId,
                scope => authorizedToken(auth, config).filter(_ => auth.current.exists(_.scopes.contains(scope))),
                tracker,
                bus,
                clock,
                health,
                () => auth.rejectAccessToken()
              )
              try maintainSubscriptions(client, config, broadcasterId, auth, health, restartRequested)
              finally acceptingCallbacks.set(false)
            sleep(1.second)

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
      restartRequested: AtomicBoolean
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
              (kind, failure) => health.observe(s"eventsub-$kind", failure)
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

  private[twitch] def resolveBroadcasterId(helix: com.github.twitch4j.helix.TwitchHelix, config: TwitchConfig): Either[String, String] =
    attempt(
      helix
        .getUsers(null, null, List(config.channel).asJava)
        .execute()
        .getUsers
        .asScala
        .headOption
        .flatMap(user => Option(user.getId))
    ).flatMap(_.toRight(s"Twitch has no channel named '${config.channel}'"))

  private def attempt[A](call: => A): Either[String, A] =
    try Right(call)
    catch
      case NonFatal(error) =>
        logger.warn(s"Twitch startup call failed: ${error.getClass.getSimpleName}")
        Left(s"Twitch unavailable (${error.getClass.getSimpleName}); retrying")
