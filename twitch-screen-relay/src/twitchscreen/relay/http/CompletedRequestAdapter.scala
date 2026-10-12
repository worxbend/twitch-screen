package twitchscreen.relay.http

import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.{MessageToMessageDecoder, PrematureChannelClosureException}
import io.netty.handler.codec.http.{DefaultHttpRequest, DefaultLastHttpContent, FullHttpRequest}
import ox.discard

/** Adapts a bounded, fully decoded request back to Tapir's streaming input representation. Tapir 1.13.31 accepts nonempty bodies only as
  * StreamedHttpRequest, not FullHttpRequest. Both messages are delivered in the same event-loop pass, after HTTP framing has completed. The
  * decoder releases the full request; its retained content transfers to the final chunk. No partial request reaches Tapir.
  */
private[http] final class CompletedRequestAdapter extends MessageToMessageDecoder[FullHttpRequest]:
  override def exceptionCaught(context: ChannelHandlerContext, cause: Throwable): Unit = cause match
    // The aggregator already released the partial body. Expected timeout/EOF closes quietly and never dispatches it.
    case _: PrematureChannelClosureException => context.close().discard
    case _                                   => context.fireExceptionCaught(cause).discard

  override protected def decode(context: ChannelHandlerContext, request: FullHttpRequest, output: java.util.List[Object]): Unit =
    if !request.decoderResult().isSuccess then context.close().discard
    else
      val head = DefaultHttpRequest(request.protocolVersion(), request.method(), request.uri(), request.headers().copy())
      val last = DefaultLastHttpContent(request.content().retainedDuplicate())
      last.trailingHeaders().set(request.trailingHeaders()).discard
      output.add(head).discard
      output.add(last).discard
