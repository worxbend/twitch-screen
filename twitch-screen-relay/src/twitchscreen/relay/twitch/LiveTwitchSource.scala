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
  */
private[twitch] object LiveTwitchSource:
  private val logger = LoggerFactory.getLogger(getClass)

  def start(config: TwitchConfig, bus: EventBus, filter: BotFilter, clock: Clock)(using Ox): TwitchSource =
    val health = AtomicReference(TwitchHealth.Connecting)
    val detail = AtomicReference("connecting")
    val client = useInScope(build(config))(_.close())
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
        subscribeToEvents(client, config, broadcasterId)
        HelixPoller.start(client.getHelix, config, broadcasterId, tracker, bus, clock)
        health.set(TwitchHealth.Connected)
        detail.set(s"broadcaster $broadcasterId, EventSub over ${config.eventSub.transport}")
        logger.info(s"Twitch integration up for '${config.channel}' (broadcaster $broadcasterId)")
        bus.publish(RelayEvent.TwitchLinkUp(s"channel ${config.channel}"))

    new TwitchSource:
      override def status: TwitchStatus = TwitchStatus(config.mode, health.get(), config.channel, detail.get())
      override def endpoints: List[ServerEndpoint[Any, Identity]] =
        if config.eventSub.transport == EventSubTransport.Webhook then webhook.endpoints else Nil

  private def build(config: TwitchConfig): TwitchClient =
    val builder = TwitchClientBuilder
      .builder()
      .withClientId(config.clientId)
      .withClientSecret(config.clientSecret.value)
      .withEnableHelix(true)
      .withEnableChat(true)
      // The socket is only opened for the WebSocket transport; with a webhook, Twitch calls us instead.
      .withEnableEventSocket(config.eventSub.transport == EventSubTransport.WebSocket)
    // An anonymous chat connection can read any public channel, which is all the relay needs. A token is only
    // required to see subscriber-only chat or to send.
    if config.chatAccessToken.isSet then builder.withChatAccount(OAuth2Credential("twitch", config.chatAccessToken.value)).discard
    if config.userAccessToken.isSet then builder.withDefaultAuthToken(OAuth2Credential("twitch", config.userAccessToken.value)).discard
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

  private def subscribeToEvents(client: TwitchClient, config: TwitchConfig, broadcasterId: String): Unit =
    config.eventSub.transport match
      case EventSubTransport.WebSocket => subscribeOverWebSocket(client.getEventSocket, broadcasterId)
      case EventSubTransport.Webhook   => EventSubWebhookApi.createSubscriptions(client.getHelix, config, broadcasterId)

  /** Only the events chat and the Helix poll cannot provide. Subscriptions requiring a broadcaster token are skipped when none is
    * configured, so an app-token-only relay still gets stream start and stop.
    */
  private def subscribeOverWebSocket(socket: IEventSubSocket, broadcasterId: String): Unit =
    List(
      EventSubFactory.webSocketSubscription(SubscriptionTypes.STREAM_ONLINE, EventSubFactory.streamOnline(broadcasterId)),
      EventSubFactory.webSocketSubscription(SubscriptionTypes.STREAM_OFFLINE, EventSubFactory.streamOffline(broadcasterId)),
      EventSubFactory.webSocketSubscription(SubscriptionTypes.CHANNEL_UPDATE_V2, EventSubFactory.channelUpdate(broadcasterId)),
      EventSubFactory.webSocketSubscription(SubscriptionTypes.CHANNEL_FOLLOW_V2, EventSubFactory.follow(broadcasterId, broadcasterId))
    ).foreach(subscription => socket.register(subscription).discard)
    socket.connect()
    logger.info("EventSub WebSocket connected")
