package twitchscreen.relay.http

import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.codec.http.{HttpRequest, LastHttpContent}
import java.util.concurrent.TimeUnit
import ox.discard
import scala.concurrent.duration.FiniteDuration

/** One deadline for a whole request, from its decoded headers to its last body chunk. One instance per connection.
  *
  * Body chunks cannot extend it, so a slowly trickled body (fixed-length or chunked) cannot pin a connection. That includes a body still
  * being drained after an early 401 or 413. When the deadline passes, the connection closes quietly. [[RequestReadTimeout]] still closes
  * silent connections and headers that never finish arriving.
  */
private[http] final class RequestDeadline(deadline: FiniteDuration) extends ChannelInboundHandlerAdapter:
  // Read and written only on this channel's event loop, so it needs no synchronization.
  private var pending: Option[java.util.concurrent.Future[?]] = None

  override def channelRead(context: ChannelHandlerContext, message: Object): Unit =
    message match
      case _: HttpRequest if pending.isEmpty =>
        pending = Some(context.executor().schedule((() => close(context)): Runnable, deadline.toMillis, TimeUnit.MILLISECONDS))
      case _ => ()
    // Checked separately: a FullHttpRequest is both an HttpRequest and a LastHttpContent, so it starts and ends at once.
    message match
      case _: LastHttpContent => cancel()
      case _                  => ()
    context.fireChannelRead(message).discard

  override def channelInactive(context: ChannelHandlerContext): Unit =
    cancel()
    context.fireChannelInactive().discard

  override def handlerRemoved(context: ChannelHandlerContext): Unit = cancel()

  private def close(context: ChannelHandlerContext): Unit =
    pending = None
    if context.channel().isActive then context.close().discard

  private def cancel(): Unit =
    pending.foreach(_.cancel(false).discard)
    pending = None
