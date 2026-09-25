package twitchscreen.relay.http

import io.netty.buffer.Unpooled
import io.netty.channel.{ChannelFutureListener, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.codec.http.*
import io.netty.util.ReferenceCountUtil
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import ox.discard

/** Counts bytes before Netty's streaming adapter or Tapir can buffer a request, including chunked bodies. One instance per connection. */
private[http] final class RequestBodyLimit(maxBytes: Long) extends ChannelInboundHandlerAdapter:
  private val received = AtomicLong(0L)
  private val rejected = AtomicBoolean(false)

  override def channelRead(context: ChannelHandlerContext, message: Object): Unit =
    if rejected.get() then ReferenceCountUtil.release(message).discard
    else
      val exceeds = message match
        case request: HttpRequest =>
          received.set(0L)
          HttpUtil.getContentLength(request, 0L) > maxBytes
        case content: HttpContent => received.addAndGet(content.content().readableBytes().toLong) > maxBytes
        case _                    => false
      if exceeds then
        rejected.set(true)
        ReferenceCountUtil.release(message).discard
        val bytes = "{\"error\":\"Request body exceeds 65536 bytes\"}".getBytes(UTF_8)
        val response =
          DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, Unpooled.wrappedBuffer(bytes))
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json").discard
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length).discard
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE).discard
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE).discard
      else context.fireChannelRead(message).discard
