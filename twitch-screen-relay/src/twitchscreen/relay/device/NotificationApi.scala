package twitchscreen.relay.device

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import java.time.Instant
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.Schema.annotations.encodedName
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.config.NotificationsConfig
import twitchscreen.relay.http.{ApiJson, Fail, Http, HttpPageSize, ServerEndpoints}
import twitchscreen.relay.protocol.{Notification, NotificationKind, NotificationRequest, SeqNo}

/** The JSON field is `type`, matching the device protocol; `type` is a Scala keyword, hence the rename. */
final case class Notification_IN(
    @encodedName("type") kind: NotificationKind,
    title: String,
    body: String,
    ttlMs: Option[Long]
) derives Schema

object Notification_IN:
  given JsonValueCodec[Notification_IN] = JsonCodecMaker.make(ApiJson.deviceVocabulary)

final case class Notification_OUT(
    seq: SeqNo,
    id: String,
    @encodedName("type") kind: NotificationKind,
    title: String,
    body: String,
    at: Option[Instant],
    ttlMs: Long
) derives Schema

object Notification_OUT:
  given JsonValueCodec[Notification_OUT] = JsonCodecMaker.make(ApiJson.deviceVocabulary)

  def from(notification: Notification): Notification_OUT =
    Notification_OUT(
      seq = notification.seq,
      id = notification.id.value,
      kind = notification.kind,
      title = notification.title,
      body = notification.body,
      at = notification.at,
      ttlMs = notification.ttl.toMillis
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

  override val endpoints: List[ServerEndpoint[Any, Identity]] = List(
    listEndpoint.handleSuccess(pageSize => Notifications_OUT(hub.recentNotifications(pageSize).map(Notification_OUT.from))),
    createEndpoint.handle(create)
  )

  private def create(request: Notification_IN): Either[Fail, Notification_OUT] =
    for
      title <- required("title", request.title)
      body <- bounded("body", request.body)
      ttl <- ttlOf(request.ttlMs)
      published <- hub
        .publish(NotificationRequest(request.kind, title, body, ttl))
        .left
        .map(_ => Fail.Unavailable(SequenceExhaustedMessage))
    yield Notification_OUT.from(published)

  private def required(field: String, value: String): Either[Fail, String] =
    bounded(field, value).flatMap(text => Option(text.trim).filter(_.nonEmpty).toRight(Fail.IncorrectInput(s"$field must not be blank")))

  private def bounded(field: String, value: String): Either[Fail, String] =
    Option(value).filter(_.length <= MaxTextLength).toRight(Fail.IncorrectInput(s"$field must contain at most $MaxTextLength characters"))

  private def ttlOf(millis: Option[Long]): Either[Fail, FiniteDuration] = millis match
    case None                                              => Right(config.defaultTtl)
    case Some(value) if value > 0 && value <= MaxTtlMillis => Right(value.millis)
    case Some(_)                                           => Left(Fail.IncorrectInput(s"ttlMs must be between 1 and $MaxTtlMillis"))

object NotificationApi:
  private val DefaultPageSize = 20
  private val MaxTextLength = 4096
  private val MaxTtlMillis = 6553500L // u16 deciseconds, validated before FiniteDuration's nanosecond bound
  private[device] val SequenceExhaustedMessage =
    "TSB/3 sequence space exhausted (§10.1); restart the relay to begin a new sequence space"

  val listEndpoint: PublicEndpoint[Int, Fail, Notifications_OUT, Any] =
    Http.baseEndpoint.get
      .in("notifications")
      .in(HttpPageSize.input(DefaultPageSize))
      .out(jsonBody[Notifications_OUT])
      .summary("List recently published notifications")
      .tag("notifications")

  val createEndpoint: PublicEndpoint[Notification_IN, Fail, Notification_OUT, Any] =
    Http.baseEndpoint.post
      .in("notifications")
      .in(jsonBody[Notification_IN])
      .out(jsonBody[Notification_OUT])
      .summary("Publish a notification to every attached device")
      .description(
        "Returns 200 with the assigned sequence and replay record. Every accepted request creates a new notification; retries are not deduplicated. Text is limited to 4096 characters per field and ttlMs to 1–6553500; wire text may be truncated to fit the display. Returns 503 with a JSON error when the relay's u32 sequence space is exhausted; publication resumes only after a relay restart."
      )
      .tag("notifications")
