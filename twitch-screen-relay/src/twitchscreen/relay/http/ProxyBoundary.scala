package twitchscreen.relay.http

import java.net.InetSocketAddress
import io.netty.handler.codec.http.HttpRequest
import scala.jdk.CollectionConverters.*
import twitchscreen.relay.config.{HttpBoundaryConfig, HttpConfig}

/** Resolves an effective origin only from an explicitly trusted immediate hop. No DNS, subnet trust or forwarded chains. */
private[http] final class ProxyBoundary(config: HttpConfig):
  private val proxies = config.trustedProxies.flatMap(HttpBoundaryConfig.ip).toSet
  private val publicOrigin = HttpBoundaryConfig.origin(config.publicOrigin)
  private val forwardedNames = List("Forwarded", "X-Forwarded-Proto", "X-Forwarded-Host", "X-Forwarded-For", "X-Forwarded-Port")

  def resolve(request: HttpRequest, remote: Option[InetSocketAddress]): Option[(String, String)] =
    def values(name: String) = request.headers().getAll(name).asScala.toList
    val forwarding = forwardedNames.exists(name => values(name).nonEmpty)
    val trusted = remote
      .flatMap(address => Option(address.getAddress))
      .flatMap(address => HttpBoundaryConfig.ip(address.getHostAddress))
      .exists(proxies)
    if !forwarding then
      values("Host") match
        case host :: Nil => HttpBoundaryConfig.authority(host).map("http" -> _)
        case _           => None
    else if !trusted || values("Forwarded").nonEmpty || values("X-Forwarded-Host").nonEmpty || values("X-Forwarded-Port").nonEmpty then None
    else
      val clientValid = values("X-Forwarded-For") match
        case Nil           => true
        case client :: Nil => HttpBoundaryConfig.ip(client).isDefined
        case _             => false
      (values("Host"), values("X-Forwarded-Proto"), publicOrigin) match
        case (host :: Nil, "https" :: Nil, Some(expected)) if clientValid =>
          HttpBoundaryConfig.authority(host, "https").filter(_ == expected._2).map("https" -> _)
        case _ => None
