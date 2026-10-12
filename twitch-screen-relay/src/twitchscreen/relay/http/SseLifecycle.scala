package twitchscreen.relay.http

import io.netty.channel.{ChannelDuplexHandler, ChannelHandlerContext, ChannelPromise}
import io.netty.handler.codec.http.{HttpMethod, HttpRequest, HttpResponse, LastHttpContent}
import io.netty.handler.timeout.WriteTimeoutHandler
import ox.discard
import scala.concurrent.duration.FiniteDuration

/** Promote only an accepted canonical SSE response. Header/body deadlines and browser validation remain installed on every connection. */
private[http] final class SseLifecycle(readTimeout: FiniteDuration) extends ChannelDuplexHandler:
  private var candidate = false
  private var streaming = false

  override def channelRead(context: ChannelHandlerContext, message: Object): Unit =
    message match
      case request: HttpRequest =>
        if streaming then
          io.netty.util.ReferenceCountUtil.release(message).discard
          context.close().discard
          return
        candidate = request.method() == HttpMethod.GET && request.uri().takeWhile(_ != '?') == "/api/v1/admin/events"
      case _ => ()
    context.fireChannelRead(message).discard

  override def write(context: ChannelHandlerContext, message: Object, promise: ChannelPromise): Unit =
    message match
      case response: HttpResponse
          if candidate && response.status().code() == 200 &&
            Option(response.headers().get("Content-Type")).exists(_.startsWith("text/event-stream")) =>
        streaming = true
        if context.pipeline().get("requestReadTimeout") != null then context.pipeline().remove("requestReadTimeout").discard
        if context.pipeline().get("streamWriteTimeout") == null then
          context.pipeline().addBefore("sseLifecycle", "streamWriteTimeout", WriteTimeoutHandler(10)).discard
      case _: LastHttpContent if streaming =>
        streaming = false
        candidate = false
        if context.pipeline().get("streamWriteTimeout") != null then context.pipeline().remove("streamWriteTimeout").discard
        if context.pipeline().get("requestReadTimeout") == null then
          context.pipeline().addAfter("requestDeadline", "requestReadTimeout", RequestReadTimeout(readTimeout)).discard
      case _ => ()
    context.write(message, promise).discard
