package twitchscreen.relay.config

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.typesafe.config.{Config as HoconConfig}
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
  private val Mask = "***"

  private def secret(path: String): Boolean =
    val key = path.toLowerCase(java.util.Locale.ROOT).filter(_.isLetterOrDigit)
    List("secret", "token", "password", "passwordhash", "credential").exists(key.endsWith)

  private[config] def flatten(source: HoconConfig): Map[String, String] =
    Config.Sections.view
      .filter(source.hasPath)
      .flatMap: section =>
        source.getConfig(section).entrySet().asScala.map(entry => s"$section.${entry.getKey}" -> entry.getValue.unwrapped())
      .map((path, value) => path -> (if secret(path) then Mask else String.valueOf(value)))
      .toMap

  val getEndpoint: PublicEndpoint[Unit, Fail, Config_OUT, Any] =
    Http.baseEndpoint.get
      .in("config")
      .out(jsonBody[Config_OUT])
      .summary("The effective configuration, with secrets masked")
      .tag("config")
