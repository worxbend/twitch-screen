package twitchscreen.relay.activity

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import java.time.format.DateTimeFormatter
import java.time.{Clock, ZoneOffset}
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.bus.EventCategory
import twitchscreen.relay.http.{ApiJson, Fail, Http, HttpPageSize, ServerEndpoints}

final case class Activity_OUT(entries: List[ActivityEntry]) derives Schema

object Activity_OUT:
  given JsonValueCodec[Activity_OUT] = JsonCodecMaker.make(ApiJson.config)

/** The relay's recent history, and a plain-text report of it.
  *
  * The report is an AIP-136 custom method on the collection (`POST /api/v1/activity:export`) rather than a second representation of the
  * list, because it is a thing you produce and download, not a thing you read.
  */
final class ActivityApi(log: ActivityLog, clock: Clock) extends ServerEndpoints:
  import ActivityApi.*

  override val endpoints: List[ServerEndpoint[Any, Identity]] = List(
    listEndpoint.handleSuccess((pageSize, category) => Activity_OUT(log.recent(pageSize, category))),
    exportEndpoint.handleSuccess((pageSize, category) => report(log.recent(pageSize, category), category))
  )

  private def report(entries: List[ActivityEntry], category: Option[EventCategory]): String =
    val header = Seq(
      "twitch-screen-relay activity report",
      s"generated  ${Timestamps.format(clock.instant())}",
      s"filter     ${category.fold("all categories")(_.toString)}",
      s"entries    ${entries.size}",
      ""
    )
    (header ++ entries.map(line)).mkString("\n")

  private def line(entry: ActivityEntry): String =
    f"${Timestamps.format(entry.at)}  ${entry.category.toString}%-12s ${entry.summary}"

object ActivityApi:
  private val DefaultPageSize = 100

  private object Timestamps:
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
    def format(instant: java.time.Instant): String = formatter.format(instant)

  private val pageSize: EndpointInput.Query[Int] =
    HttpPageSize.input(DefaultPageSize)

  private val category: EndpointInput.Query[Option[EventCategory]] =
    query[Option[EventCategory]]("category").description("Restrict to one category of event")

  val listEndpoint: PublicEndpoint[(Int, Option[EventCategory]), Fail, Activity_OUT, Any] =
    Http.baseEndpoint.get
      .in("activity")
      .in(pageSize)
      .in(category)
      .out(jsonBody[Activity_OUT])
      .summary("List recent relay activity")
      .tag("activity")

  val exportEndpoint: PublicEndpoint[(Int, Option[EventCategory]), Fail, String, Any] =
    Http.baseEndpoint.post
      .in("activity:export")
      .in(pageSize)
      .in(category)
      .out(stringBody)
      .out(header("Content-Disposition", "attachment; filename=\"relay-activity.txt\""))
      .summary("Download an activity report")
      .tag("activity")
