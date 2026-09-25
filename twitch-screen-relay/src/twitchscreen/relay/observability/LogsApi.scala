package twitchscreen.relay.observability

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.http.{ApiJson, Fail, Http, HttpPageSize, ServerEndpoints}

final case class Logs_OUT(records: List[LogRecord]) derives Schema

object Logs_OUT:
  given JsonValueCodec[Logs_OUT] = JsonCodecMaker.make(ApiJson.config)

/** The relay's own log tail, for when it is running headless and `journalctl` is not to hand. */
final class LogsApi extends ServerEndpoints:
  override val endpoints: List[ServerEndpoint[Any, Identity]] =
    List(LogsApi.listEndpoint.handleSuccess((pageSize, minLevel) => Logs_OUT(LogBuffer.recent(pageSize, minLevel))))

object LogsApi:
  private val DefaultPageSize = 100

  val listEndpoint: PublicEndpoint[(Int, LogLevel), Fail, Logs_OUT, Any] =
    Http.baseEndpoint.get
      .in("logs")
      .in(HttpPageSize.input(DefaultPageSize))
      .in(query[LogLevel]("minLevel").default(LogLevel.Info))
      .out(jsonBody[Logs_OUT])
      .summary("Read the relay's recent log lines")
      .tag("observability")
