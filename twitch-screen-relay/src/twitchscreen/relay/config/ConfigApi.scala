package twitchscreen.relay.config

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.typesafe.config.{Config as HoconConfig, ConfigFactory}
import scala.jdk.CollectionConverters.*
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.http.{ApiJson, Fail, Http, ServerEndpoints}

/** The effective configuration, flattened to dotted keys so it reads the same as the HOCON that produced it. */
final case class Config_OUT(settings: Map[String, String]) derives Schema

object Config_OUT:
  given JsonValueCodec[Config_OUT] = JsonCodecMaker.make(ApiJson.config)

/** Shows what this relay is actually running with, after the config file, the environment overrides and the defaults have all been merged —
  * the question that takes longest to answer from the outside.
  *
  * Read-only: settings come from HOCON and the environment, so anything editable here would be lost on the next restart. Secrets are
  * replaced with `***` before they leave the process.
  */
final class ConfigApi(source: HoconConfig) extends ServerEndpoints:
  override val endpoints: List[ServerEndpoint[Any, Identity]] =
    List(ConfigApi.getEndpoint.handleSuccess(_ => Config_OUT(ConfigApi.flatten(source))))

object ConfigApi:
  /** Paths whose values must never be rendered. Kept beside the reader that uses them, not in the HOCON file. */
  private val secretPaths = Set(
    "twitch.client-secret",
    "twitch.event-sub.secret"
  )

  private val Mask = "***"

  /** Only the relay's own sections are rendered. `ConfigFactory.load()` also merges in every JVM system property, and those can carry
    * credentials passed with `-D`; rendering the whole tree would publish them.
    */
  private val relaySections =
    List("http", "device-link", "twitch", "notifications", "bus", "stats", "activity", "alerts", "observability")

  def resolved(): HoconConfig = ConfigFactory.load()

  private[config] def flatten(source: HoconConfig): Map[String, String] =
    relaySections.view
      .filter(source.hasPath)
      .flatMap: section =>
        source.getConfig(section).entrySet().asScala.map(entry => s"$section.${entry.getKey}" -> entry.getValue.unwrapped())
      .map((path, value) => path -> (if secretPaths.contains(path) then Mask else String.valueOf(value)))
      .toMap

  val getEndpoint: PublicEndpoint[Unit, Fail, Config_OUT, Any] =
    Http.baseEndpoint.get
      .in("config")
      .out(jsonBody[Config_OUT])
      .summary("The effective configuration, with secrets masked")
      .tag("config")
