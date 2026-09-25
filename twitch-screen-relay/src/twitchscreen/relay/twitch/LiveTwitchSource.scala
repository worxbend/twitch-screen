package twitchscreen.relay.twitch

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential
import com.github.twitch4j.{TwitchClient, TwitchClientBuilder}
import com.github.twitch4j.eventsub.socket.IEventSubSocket
import com.github.twitch4j.eventsub.subscriptions.SubscriptionTypes
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import ox.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.{EventSubTransport, TwitchConfig}

/** The real integration: Helix, chat and EventSub through twitch4j.
  *
  * Startup is deliberately forgiving. If the channel cannot be resolved — bad credentials, Twitch down, no network at boot — the relay logs
  * it, reports itself disconnected and carries on serving devices; a notification relay that refuses to start because Twitch is unreachable
  * is worse than one that says so on its status endpoint.
  *
  * No user token is configured. The client starts with the application's credentials alone — enough to resolve the channel, poll viewers
  * and read chat anonymously — and everything that needs the broadcaster's consent (follows, follower and subscriber totals, every EventSub
  * WebSocket subscription) starts when [[TwitchAuth]] first holds a token, whether loaded from disk at startup or granted in a browser
  * later.
  */
private[twitch] object LiveTwitchSource:
  private val logger = LoggerFactory.getLogger(getClass)

  def start(config: TwitchConfig, bus: EventBus, filter: BotFilter, clock: Clock)(using Ox): TwitchSource =
    val health = AtomicReference(TwitchHealth.Connecting)
    val detail = AtomicReference("connecting")
    val client = useInScope(build(config))(_.close())
    val auth = TwitchAuth.start(config, TwitchOAuthClient.live(config, clock), bus, clock)
    val authApi = TwitchAuthApi(auth)
    val tracker = ChannelStateTracker(config.channel, clock)
    val webhook = EventSubWebhookApi.create(config, bus, tracker, filter, clock)

    logger.info(s"Ignoring events from ${filter.ignoredDisplayNames.toList.sorted.mkString(", ")} (matched on the display name)")
    TwitchEventHandlers.registerChat(client.getEventManager, bus, filter)
    TwitchEventHandlers.registerEventSub(client.getEventManager, bus, tracker, filter)

    resolveBroadcasterId(client, config) match
      case Left(failure) =>
        health.set(TwitchHealth.Disconnected)
        detail.set(failure)
        logger.error(s"Twitch integration is degraded: $failure")
        bus.publish(RelayEvent.TwitchLinkDown(failure))
      case Right(broadcasterId) =>
        joinChat(client, config)
        subscribeToEvents(client, config, broadcasterId, auth)
        HelixPoller.start(client.getHelix, config, broadcasterId, () => auth.accessToken, tracker, bus, clock)
        health.set(TwitchHealth.Connected)
        detail.set(s"broadcaster $broadcasterId, EventSub over ${config.eventSub.transport}")
        logger.info(s"Twitch integration up for '${config.channel}' (broadcaster $broadcasterId)")
        bus.publish(RelayEvent.TwitchLinkUp(s"channel ${config.channel}"))

    new TwitchSource:
      override def status: TwitchStatus =
        val authorization = auth.current.fold(s"not authorized, open ${TwitchAuth.AuthorizePath}")(held => s"authorized as ${held.login}")
        TwitchStatus(config.mode, health.get(), config.channel, s"${detail.get()}; $authorization")
      override def endpoints: List[ServerEndpoint[Any, Identity]] =
        authApi.endpoints ++ (if config.eventSub.transport == EventSubTransport.Webhook then webhook.endpoints else Nil)

  private def build(config: TwitchConfig): TwitchClient =
    val builder = TwitchClientBuilder
      .builder()
      .withClientId(config.clientId)
      .withClientSecret(config.clientSecret.value)
      .withEnableHelix(true)
      .withEnableChat(true)
      // The socket is only opened for the WebSocket transport; with a webhook, Twitch calls us instead.
      .withEnableEventSocket(config.eventSub.transport == EventSubTransport.WebSocket)
    // No default auth token: Helix then authenticates with an application token minted from the client secret, which is what
    // webhook subscriptions require, and calls that need the broadcaster pass the user token explicitly. Chat is anonymous,
    // which reads any public channel — everything the relay needs short of subscriber-only chat.
    builder.build()

  private def joinChat(client: TwitchClient, config: TwitchConfig): Unit =
    client.getChat.joinChannel(config.channel)
    logger.info(s"Joined chat for '${config.channel}'")

  /** Every EventSub condition is keyed by the broadcaster's numeric id, so nothing can be registered without it. */
  private def resolveBroadcasterId(client: TwitchClient, config: TwitchConfig): Either[String, String] =
    try
      client.getHelix
        .getUsers(null, null, List(config.channel).asJava)
        .execute()
        .getUsers
        .asScala
        .headOption
        .map(_.getId)
        .toRight(s"Twitch has no channel named '${config.channel}'")
    catch case NonFatal(error) => Left(s"could not resolve '${config.channel}': ${error.getMessage}")

  /** Registers what can be registered now, and forks the rest to wait for the broadcaster's consent. The fork lives in the application
    * scope, so a relay that is never authorized simply keeps it parked until shutdown.
    */
  private def subscribeToEvents(client: TwitchClient, config: TwitchConfig, broadcasterId: String, auth: TwitchAuth)(using Ox): Unit =
    config.eventSub.transport match
      case EventSubTransport.WebSocket =>
        // Twitch accepts no WebSocket subscription at all without a user token, not even stream start and stop.
        if auth.current.isEmpty then logger.info("EventSub waits for Twitch authorization")
        forkDiscard(subscribeOverWebSocket(client.getEventSocket, auth.awaitCredential(), broadcasterId))
      case EventSubTransport.Webhook =>
        val unscoped = EventSubWebhookApi.unscopedSubscriptions(broadcasterId)
        val scoped = EventSubWebhookApi.scopedSubscriptions(broadcasterId)
        if auth.current.isDefined then EventSubWebhookApi.createSubscriptions(client.getHelix, config, unscoped ++ scoped)
        else
          EventSubWebhookApi.createSubscriptions(client.getHelix, config, unscoped)
          logger.info("The follow subscription waits for Twitch authorization")
          forkDiscard:
            auth.awaitCredential().discard
            EventSubWebhookApi.createSubscriptions(client.getHelix, config, scoped)

  /** Only the events chat and the Helix poll cannot provide. `credential` is [[TwitchAuth]]'s long-lived handle, so the socket's
    * resubscriptions after a reconnect carry whichever access token is current by then.
    */
  private def subscribeOverWebSocket(socket: IEventSubSocket, credential: OAuth2Credential, broadcasterId: String): Unit =
    try
      List(
        EventSubFactory.webSocketSubscription(SubscriptionTypes.STREAM_ONLINE, EventSubFactory.streamOnline(broadcasterId)),
        EventSubFactory.webSocketSubscription(SubscriptionTypes.STREAM_OFFLINE, EventSubFactory.streamOffline(broadcasterId)),
        EventSubFactory.webSocketSubscription(SubscriptionTypes.CHANNEL_UPDATE_V2, EventSubFactory.channelUpdate(broadcasterId)),
        EventSubFactory.webSocketSubscription(SubscriptionTypes.CHANNEL_FOLLOW_V2, EventSubFactory.follow(broadcasterId, broadcasterId))
      ).foreach(subscription => socket.register(credential, subscription).discard)
      socket.connect()
      logger.info("EventSub WebSocket connected")
    catch case NonFatal(error) => logger.error(s"EventSub WebSocket subscription failed: ${error.getMessage}")
