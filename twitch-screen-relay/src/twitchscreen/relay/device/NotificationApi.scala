package twitchscreen.relay.device

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import org.slf4j.LoggerFactory
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.Schema.annotations.encodedName
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.config.NotificationsConfig
import twitchscreen.relay.http.{ApiJson, Fail, Http, HttpPageSize, ServerEndpoints, StrictRequest}
import twitchscreen.relay.protocol.{EventRequest, Notification, NotificationKind, NotificationRequest, SeqNo}

private[device] object NotificationKindJson:
  val codec: JsonValueCodec[NotificationKind] = new JsonValueCodec[NotificationKind]:
    def nullValue: NotificationKind = NotificationKind.Info
    def decodeValue(in: com.github.plokhotnyuk.jsoniter_scala.core.JsonReader, default: NotificationKind): NotificationKind =
      val name = in.readString(null)
      NotificationKind.parse(name).fold(_ => in.decodeError("invalid notification type"), identity)
    def encodeValue(value: NotificationKind, out: com.github.plokhotnyuk.jsoniter_scala.core.JsonWriter): Unit =
      out.writeVal(value.wire)

final case class Deadline_IN(relayEpoch: String, monotonicMs: Long) derives Schema:
  require(relayEpoch != null && relayEpoch.matches("(?!0000000000000000$)[0-9a-f]{16}"), "invalid relay epoch")
  require(monotonicMs >= 0 && monotonicMs <= 9007199254740991L, "invalid monotonic deadline")
  private[device] def clock: RelayClock = RelayClock(java.lang.Long.parseUnsignedLong(relayEpoch, 16), monotonicMs)

object Deadline_IN:
  private given JsonValueCodec[Long] = StrictRequest.integralLong
  given JsonValueCodec[Deadline_IN] = JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false).withCheckFieldDuplication(true))

final case class Notification_IN(
    @encodedName("type") kind: NotificationKind,
    title: String,
    body: String,
    ttlMs: Option[Long],
    expiresInMs: Option[Long] = None,
    deadline: Option[Deadline_IN] = None
) derives Schema

object Notification_IN:
  private given JsonValueCodec[NotificationKind] = NotificationKindJson.codec
  private given optionalLong: JsonValueCodec[Option[Long]] = StrictRequest.optional(StrictRequest.integralLong)
  private given optionalDeadline: JsonValueCodec[Option[Deadline_IN]] = StrictRequest.optional(summon[JsonValueCodec[Deadline_IN]])
  given JsonValueCodec[Notification_IN] =
    JsonCodecMaker.make(ApiJson.deviceVocabulary.withSkipUnexpectedFields(false).withCheckFieldDuplication(true))

final case class Notification_OUT(
    seq: SeqNo,
    id: String,
    @encodedName("type") kind: NotificationKind,
    title: String,
    body: String,
    at: Option[Instant],
    ttlMs: Long,
    relayEpoch: String,
    origin: String,
    sourceGeneration: Long,
    twitchEpisode: Long,
    expiresInMs: Long,
    remainingExpiryMs: Long
) derives Schema

object Notification_OUT:
  private given JsonValueCodec[NotificationKind] = NotificationKindJson.codec
  private val timestamp = DateTimeFormatterBuilder().appendInstant(3).toFormatter
  private given JsonValueCodec[Instant] with
    def nullValue: Instant = null
    def decodeValue(in: com.github.plokhotnyuk.jsoniter_scala.core.JsonReader, default: Instant): Instant =
      Instant.parse(in.readString(null))
    def encodeValue(value: Instant, out: com.github.plokhotnyuk.jsoniter_scala.core.JsonWriter): Unit =
      out.writeVal(timestamp.format(value))
  given JsonValueCodec[Notification_OUT] = JsonCodecMaker.make(ApiJson.deviceVocabulary.withTransientNone(false))

  private[device] def from(value: RetainedEvent, now: RelayClock): Notification_OUT =
    val notification = Notification.from(value.record)
    Notification_OUT(
      seq = notification.seq,
      id = notification.id.value,
      kind = notification.kind,
      title = notification.title,
      body = notification.body,
      at = notification.at,
      ttlMs = notification.ttl.toMillis,
      relayEpoch = f"${value.relayEpoch}%016x",
      origin = value.origin.toString.toLowerCase(java.util.Locale.ROOT),
      sourceGeneration = value.sourceGeneration,
      twitchEpisode = value.twitchEpisode,
      expiresInMs = value.expiresInMs,
      remainingExpiryMs = if now.relayEpoch != value.relayEpoch then 0L else math.max(0L, value.deadlineRelayMs - now.monotonicMs)
    )

final case class Notifications_OUT(notifications: List[Notification_OUT]) derives Schema

object Notifications_OUT:
  given JsonValueCodec[Notifications_OUT] = JsonCodecMaker.make(ApiJson.deviceVocabulary)

/** Pushing notifications to every attached device, and reading back what was pushed.
  *
  * This is also what makes the relay useful with `twitch.mode = disabled`: anything that can POST JSON can put a message on the screen,
  * which is how the firmware was developed before the Twitch integration existed.
  */
final class NotificationApi(hub: DeviceHub, config: NotificationsConfig) extends ServerEndpoints:
  import NotificationApi.*

  if config.defaultTtl.toMillis != DefaultTtlMillis then
    LoggerFactory
      .getLogger(getClass)
      .warn("Notification API uses the DESK/1 8000 ms default; notifications.default-ttl is legacy configuration")

  override val endpoints: List[ServerEndpoint[Any, Identity]] = List(
    listEndpoint.handleSuccess { pageSize =>
      val (now, values) = hub.recentPublications(pageSize)
      Notifications_OUT(values.map(value => Notification_OUT.from(value, now)).toList)
    },
    createEndpoint.handle(create)
  )

  private def create(request: Notification_IN): Either[Fail, (Notification_OUT, Option[String], Option[String])] =
    for
      title <- required("title", request.title)
      body <- bounded("body", request.body)
      ttl <- ttlOf(request.ttlMs)
      expiry <- request.expiresInMs
        .filter(value => value >= 1 && value <= 600000L)
        .orElse(Option.when(request.expiresInMs.isEmpty)(60000L))
        .toRight(Fail.IncorrectInput("expiresInMs must be between 1 and 600000"))
      published <- hub
        .acceptExternal(
          EventRequest.card(NotificationRequest(request.kind, title, body, ttl)).copy(expiresInMs = expiry),
          request.deadline.map(_.clock)
        )
        .left
        .map:
          case IngressFailure.EpochChanged  => Fail.ClockEpochChanged
          case IngressFailure.Expired       => Fail.Expired
          case IngressFailure.RateLimited   => Fail.RateLimited
          case IngressFailure.SequenceSpent => Fail.Unavailable(SequenceExhaustedMessage)
          case IngressFailure.RevisionSpent => Fail.Unavailable("publication revision exhausted")
    yield published

  private def required(field: String, value: String): Either[Fail, String] =
    bounded(field, value).flatMap(text => Option(text.trim).filter(_.nonEmpty).toRight(Fail.IncorrectInput(s"$field must not be blank")))

  private def bounded(field: String, value: String): Either[Fail, String] =
    Option(value)
      .filter(text => text.length <= MaxTextLength && StrictRequest.validUnicode(text))
      .toRight(Fail.IncorrectInput(s"$field must contain valid Unicode and at most $MaxTextLength characters"))

  private def ttlOf(millis: Option[Long]): Either[Fail, FiniteDuration] = millis match
    case None                                                 => Right(DefaultTtlMillis.millis)
    case Some(value) if value >= 100 && value <= MaxTtlMillis => Right((((value + 99L) / 100L) * 100L).millis)
    case Some(_)                                              => Left(Fail.IncorrectInput(s"ttlMs must be between 100 and $MaxTtlMillis"))

object NotificationApi:
  private val DefaultPageSize = 20
  private val MaxTextLength = 4096
  private val DefaultTtlMillis = 8000L
  private val MaxTtlMillis = 30000L
  private[device] val SequenceExhaustedMessage =
    "TSB/3 sequence space exhausted (§10.1); restart the relay to begin a new sequence space"

  val listEndpoint: PublicEndpoint[Int, Fail, Notifications_OUT, Any] =
    Http.baseEndpoint.get
      .in("notifications")
      .in(HttpPageSize.input(DefaultPageSize))
      .out(jsonBody[Notifications_OUT])
      .summary("List recently published notifications")
      .tag("notifications")

  val createEndpoint: PublicEndpoint[Notification_IN, Fail, (Notification_OUT, Option[String], Option[String]), Any] =
    Http.baseEndpoint.post
      .in("notifications")
      .in(jsonBody[Notification_IN])
      .out(jsonBody[Notification_OUT])
      .out(header[Option[String]]("X-Relay-Epoch"))
      .out(header[Option[String]]("X-Relay-Monotonic-Ms"))
      .summary("Publish a notification to every attached device")
      .description(
        "Returns 200 with the assigned sequence, original provenance and remaining expiry. Each accepted retry creates a new card. Hold is 100–30000 ms, rounded up to 100 ms; expiry is independently 1–600000 ms. Optional calibrated deadline never extends useful life. Wrong epoch is 409, expired is 410, global 10/s burst-20 overflow is 429 with Retry-After: 1, and sequence exhaustion is 503. Legacy devices cannot enforce waiting expiry after transmission."
      )
      .tag("notifications")
