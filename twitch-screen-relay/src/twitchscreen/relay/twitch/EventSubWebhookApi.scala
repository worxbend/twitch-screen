package twitchscreen.relay.twitch

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReaderException, readFromString}
import com.github.twitch4j.eventsub.condition.EventSubCondition
import com.github.twitch4j.eventsub.EventSubSubscriptionStatus
import scala.jdk.CollectionConverters.*
import com.github.twitch4j.eventsub.subscriptions.{SubscriptionType, SubscriptionTypes}
import com.github.twitch4j.helix.TwitchHelix
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.{Clock, Instant}
import java.time.format.DateTimeParseException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.slf4j.LoggerFactory
import ox.discard
import ox.either.catching
import scala.util.control.NonFatal
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.TwitchConfig
import twitchscreen.relay.http.{Fail, Http, ServerEndpoints}

/** The EventSub webhook transport: Twitch posts here instead of the relay holding a WebSocket open.
  *
  * The endpoint is unauthenticated by design — Twitch cannot present a credential — so the HMAC signature over (message id + timestamp +
  * body) *is* the authentication, and a body that fails it never reaches the bus. Authenticated IDs are retained through their entire
  * allowed timestamp window, so concurrent delivery and clock skew cannot publish the same event twice.
  */
private[twitch] final class EventSubWebhookApi(
    config: TwitchConfig,
    bus: EventBus,
    tracker: ChannelStateTracker,
    filter: BotFilter,
    clock: Clock,
    observeSubscription: (String, Option[String]) => Unit
) extends ServerEndpoints:
  import EventSubWebhookApi.*

  private val logger = LoggerFactory.getLogger(getClass)
  private val deduplication = WebhookDeduplication()

  private val callbackServerEndpoint: ServerEndpoint[Any, Identity] =
    EventSubWebhookApi.callbackEndpoint.handle(handleCallback)

  override val endpoints: List[ServerEndpoint[Any, Identity]] = List(callbackServerEndpoint)

  private def handleCallback(
      messageId: String,
      timestamp: String,
      signature: String,
      messageType: String,
      body: String
  ): Either[Fail, String] =
    for
      sent <- verifyFreshness(timestamp)
      _ <- verifySignature(messageId, timestamp, signature, body)
      envelope <- parse(body)
      fresh <-
        if messageType == "notification" || messageType == "revocation" then
          deduplication.claim(messageId, clock.instant(), sent.plus(ReplayWindow).plusNanos(1))
        else Right(true)
      response <- if fresh then dispatch(messageType, envelope) else Right("")
    yield response

  /** Twitch replays are valid signatures on stale bodies; ten minutes is the window Twitch documents. */
  private def verifyFreshness(timestamp: String): Either[Fail, Instant] =
    Instant
      .parse(timestamp)
      .catching[DateTimeParseException]
      .left
      .map(_ => Fail.IncorrectInput("unparseable Twitch-Eventsub-Message-Timestamp"))
      .filterOrElse(
        sent => java.time.Duration.between(sent, clock.instant()).abs().compareTo(ReplayWindow) <= 0,
        Fail.Unauthorized("Twitch-Eventsub-Message-Timestamp is outside the replay window")
      )

  private def verifySignature(messageId: String, timestamp: String, signature: String, body: String): Either[Fail, Unit] =
    val expected = "sha256=" + hmacSha256(config.eventSub.secret.value, messageId + timestamp + body)
    // Constant-time comparison: a length-sensitive `==` would leak the signature one byte at a time.
    if MessageDigest.isEqual(expected.getBytes(UTF_8), signature.getBytes(UTF_8)) then Right(())
    else
      logger.warn("Rejected an EventSub callback with a bad signature")
      Left(Fail.Unauthorized("bad Twitch-Eventsub-Message-Signature"))

  private def parse(body: String): Either[Fail, EventSubEnvelope] =
    readFromString[EventSubEnvelope](body)
      .catching[JsonReaderException]
      .left
      .map(error => Fail.IncorrectInput(s"unreadable EventSub payload: ${error.getMessage}"))

  private def dispatch(messageType: String, envelope: EventSubEnvelope): Either[Fail, String] = messageType match
    case "webhook_callback_verification" =>
      envelope.challenge
        .toRight(Fail.IncorrectInput("verification payload carried no challenge"))
        .tapRight: _ =>
          observeSubscription(envelope.subscription.kind, None)
          logger.info(s"Twitch verified the EventSub callback for ${envelope.subscription.kind}")

    case "notification" =>
      // Through the filter, not straight to the bus: §13.1 requires bot-authored events to be dropped at the
      // source on *both* EventSub transports, not only on the one that happens to be wired to twitch4j.
      toRelayEvents(envelope).foreach(filter.publish(bus, _))
      Right("")

    case "revocation" =>
      val reason = envelope.subscription.status.getOrElse("revoked")
      logger.warn(s"Twitch revoked the ${envelope.subscription.kind} subscription: $reason")
      observeSubscription(envelope.subscription.kind, Some(s"subscription $reason"))
      Right("")

    case unknown =>
      logger.debug(s"Ignoring EventSub message type '$unknown'")
      Right("")

  /** Mirrors [[TwitchEventHandlers.registerEventSub]]: the same four subscriptions, arriving by a different road. */
  private def toRelayEvents(envelope: EventSubEnvelope): List[RelayEvent] =
    val payload = envelope.event.getOrElse(EventSubPayload(None, None, None, None, None, None, None, None, None, None, None))
    envelope.subscription.kind match
      case "stream.online" =>
        tracker
          .wentLive(
            payload.title.getOrElse(""),
            payload.categoryName.getOrElse(""),
            payload.startedAt.flatMap(parseInstant),
            publish = event => filter.publish(bus, event)
          )
          .discard
        Nil
      case "stream.offline" =>
        tracker.wentOffline(event => filter.publish(bus, event)).discard
        Nil
      case "channel.follow" => payload.userName.map(RelayEvent.Followed.apply).toList
      case "channel.update" =>
        tracker.channelInfo(payload.title.getOrElse(""), payload.categoryName.getOrElse(""))
        List(
          RelayEvent.ChannelUpdated(
            payload.broadcasterUserName.getOrElse(config.channel),
            payload.title.getOrElse(""),
            payload.categoryName.getOrElse("")
          )
        )
      case other =>
        logger.debug(s"No mapping for EventSub type '$other'")
        Nil

private[twitch] object EventSubWebhookApi:
  /** Twitch sends RFC 3339; an unparseable value degrades to "not reported" rather than failing a notification. */
  private def parseInstant(raw: String): Option[Instant] = Instant.parse(raw).catching[DateTimeParseException].toOption
  private val ReplayWindow = java.time.Duration.ofMinutes(10)

  val callbackEndpoint: PublicEndpoint[(String, String, String, String, String), Fail, String, Any] =
    Http.callbackEndpoint.post
      .in("twitch" / "eventsub")
      .in(header[String]("Twitch-Eventsub-Message-Id"))
      .in(header[String]("Twitch-Eventsub-Message-Timestamp"))
      .in(header[String]("Twitch-Eventsub-Message-Signature"))
      .in(header[String]("Twitch-Eventsub-Message-Type"))
      .in(stringBody)
      .out(stringBody)
      .summary("Twitch EventSub callback")
      .description(
        "Called by Twitch, not by operators. Authenticated by the HMAC signature over the message id, timestamp and body."
      )

  def create(
      config: TwitchConfig,
      bus: EventBus,
      tracker: ChannelStateTracker,
      filter: BotFilter,
      clock: Clock,
      observeSubscription: (String, Option[String]) => Unit = (_, _) => ()
  ): EventSubWebhookApi =
    EventSubWebhookApi(config, bus, tracker, filter, clock, observeSubscription)

  /** The subscriptions Twitch grants any application, with no user's consent behind them. */
  def unscopedSubscriptions(broadcasterId: String): List[(SubscriptionType[?, ?, ?], EventSubCondition)] = List(
    SubscriptionTypes.STREAM_ONLINE -> EventSubFactory.streamOnline(broadcasterId),
    SubscriptionTypes.STREAM_OFFLINE -> EventSubFactory.streamOffline(broadcasterId),
    SubscriptionTypes.CHANNEL_UPDATE_V2 -> EventSubFactory.channelUpdate(broadcasterId)
  )

  /** Follows need `moderator:read:followers` granted to this application, so they can only be registered after consent. */
  def scopedSubscriptions(broadcasterId: String): List[(SubscriptionType[?, ?, ?], EventSubCondition)] = List(
    SubscriptionTypes.CHANNEL_FOLLOW_V2 -> EventSubFactory.follow(broadcasterId, broadcasterId)
  )

  /** Reconcile only this callback and broadcaster's subscriptions. Application credentials are selected by the client with no default user
    * token.
    *
    * Every call here uses the application token, so a 401 from any of them calls `appTokenRejected` to have the session rebuild the client
    * with a fresh token. Iteration deliberately continues: each remaining kind is still observed as failed on this pass, so no component
    * keeps a stale healthy status, and the rebuild itself is paced by the maintenance loop rather than by this method.
    */
  def reconcileSubscriptions(
      helix: TwitchHelix,
      config: TwitchConfig,
      subscriptions: List[(SubscriptionType[?, ?, ?], EventSubCondition)],
      observe: (String, Option[String]) => Unit,
      appTokenRejected: () => Unit
  ): Unit =
    subscriptions.foreach: (kind, condition) =>
      try
        val existing = helix
          .getEventSubSubscriptions(null, null, kind, null, null, 100)
          .execute()
          .getSubscriptions
          .asScala
          .find(sub => sub.getCondition == condition && sub.getTransport.getCallback == config.eventSub.callbackUrl)
        val usable = existing.filter(sub =>
          sub.getStatus == EventSubSubscriptionStatus.ENABLED ||
            sub.getStatus == EventSubSubscriptionStatus.WEBHOOK_CALLBACK_VERIFICATION_PENDING
        )
        if usable.isEmpty then
          existing.foreach(sub => helix.deleteEventSubSubscription(null, sub.getId).execute().discard)
          val created = helix
            .createEventSubSubscription(
              null,
              EventSubFactory.webhookSubscription(kind, condition, config.eventSub.callbackUrl, config.eventSub.secret.value)
            )
            .execute()
          val enabled = created.getSubscriptions.asScala.exists(_.getStatus == EventSubSubscriptionStatus.ENABLED)
          observe(kind.getName, if enabled then None else Some("awaiting callback verification"))
        else
          observe(
            kind.getName,
            if usable.exists(_.getStatus == EventSubSubscriptionStatus.ENABLED) then None else Some("awaiting callback verification")
          )
      catch
        case NonFatal(error) =>
          if HelixPoller.isUnauthorized(error) then appTokenRejected()
          observe(kind.getName, Some(s"registration failed (${error.getClass.getSimpleName}); retrying"))

  extension [E, T](either: Either[E, T])
    private def tapRight(effect: T => Unit): Either[E, T] =
      either.foreach(effect)
      either

private def hmacSha256(secret: String, message: String): String =
  val mac = Mac.getInstance("HmacSHA256")
  mac.init(SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
  mac.doFinal(message.getBytes(UTF_8)).map(byte => f"$byte%02x").mkString
