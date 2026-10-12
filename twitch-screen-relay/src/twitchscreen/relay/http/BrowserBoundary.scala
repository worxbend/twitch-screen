package twitchscreen.relay.http

import io.netty.buffer.Unpooled
import io.netty.channel.{ChannelFutureListener, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.codec.http.*
import io.netty.util.ReferenceCountUtil
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Locale
import ox.discard
import scala.jdk.CollectionConverters.*
import twitchscreen.relay.config.{HttpBoundaryConfig, HttpConfig}

/** Rejects hostile browser/Host input before buffering a body, including docs and unmatched routes. A callback exemption applies only to
  * browser-origin checks; provider checks remain in its handler.
  */
private[http] final class BrowserBoundary(config: HttpConfig) extends ChannelInboundHandlerAdapter:
  private val proxy = ProxyBoundary(config)
  private val origins = config.allowedOrigins.flatMap(HttpBoundaryConfig.origin).toSet
  private val callbacks = Set("/api/v1/twitch/callback", "/api/v1/twitch/eventsub")
  private var rejected = false

  private def failure(request: HttpRequest, context: ChannelHandlerContext): Option[(HttpResponseStatus, String)] =
    def values(name: String) = request.headers().getAll(name).asScala.toList
    val remote = Option(context.channel().remoteAddress()).collect { case address: java.net.InetSocketAddress => address }
    val effective = proxy
      .resolve(request, remote)
      .filter: (scheme, authority) =>
        config.allowedHosts.flatMap(HttpBoundaryConfig.authority(_, scheme)).contains(authority)
    val path = scala.util.Try(java.net.URI(request.uri())).toOption.filter(uri => !uri.isAbsolute).map(_.getRawPath)
    val callback = path.exists(callbacks)
    val originValues = values("Origin")
    val fetchValues = values("Sec-Fetch-Site")
    val validOrigin = originValues match
      case Nil => true
      case value :: Nil =>
        HttpBoundaryConfig
          .origin(value)
          .exists: normalized =>
            origins.contains(normalized) && effective.contains(normalized)
      case _ => false
    val validFetch = fetchValues match
      case Nil          => true
      case value :: Nil => Set("same-origin", "none").contains(value)
      case _            => false
    if effective.isEmpty || path.isEmpty || (!callback && (!validOrigin || !validFetch)) then
      Some(HttpResponseStatus.FORBIDDEN -> "browser_boundary_rejected")
    else if !callback && Set(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH).contains(request.method()) &&
      (values("Content-Type").size != 1 ||
        !values("Content-Type").exists(_.takeWhile(_ != ';').trim.toLowerCase(Locale.ROOT) == "application/json"))
    then Some(HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE -> "unsupported_media_type")
    else None

  override def channelRead(context: ChannelHandlerContext, message: Object): Unit =
    if rejected then ReferenceCountUtil.release(message).discard
    else
      val result = message match
        case request: HttpRequest => failure(request, context)
        case _                    => None
      result match
        case None => context.fireChannelRead(message).discard
        case Some((status, code)) =>
          rejected = true
          ReferenceCountUtil.release(message).discard
          val bytes = s"{\"error\":\"Private HTTP boundary rejected request\",\"code\":\"$code\"}".getBytes(UTF_8)
          val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes))
          response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json").discard
          response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length).discard
          response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE).discard
          response.headers().set("X-Content-Type-Options", "nosniff").discard
          response.headers().set("Referrer-Policy", "no-referrer").discard
          response.headers().set("Content-Security-Policy", "frame-ancestors 'none'; base-uri 'none'").discard
          context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE).discard
