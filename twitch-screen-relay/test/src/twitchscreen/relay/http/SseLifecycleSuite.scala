package twitchscreen.relay.http

import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.*
import ox.discard
import scala.concurrent.duration.DurationInt

class SseLifecycleSuite extends munit.FunSuite:
  private def channel(): EmbeddedChannel =
    val result = EmbeddedChannel()
    result.pipeline().addLast("requestDeadline", RequestDeadline(30.seconds)).discard
    result.pipeline().addLast("requestReadTimeout", RequestReadTimeout(30.seconds)).discard
    result.pipeline().addLast("sseLifecycle", SseLifecycle(30.seconds)).discard
    result

  private def response(status: HttpResponseStatus = HttpResponseStatus.OK): HttpResponse =
    val value = DefaultHttpResponse(HttpVersion.HTTP_1_1, status)
    value.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8").discard
    value

  test("only a successful canonical GET promotes inbound idle handling; request deadlines remain installed"):
    for
      method <- List(HttpMethod.GET, HttpMethod.POST)
      path <- List("/api/v1/admin/events", "/other")
      status <- List(HttpResponseStatus.OK, HttpResponseStatus.TOO_MANY_REQUESTS)
    do
      val value = channel()
      try
        value.writeInbound(DefaultHttpRequest(HttpVersion.HTTP_1_1, method, path)).discard
        value.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT).discard
        value.writeOutbound(response(status)).discard
        val promoted = method == HttpMethod.GET && path == "/api/v1/admin/events" && status == HttpResponseStatus.OK
        assertEquals(value.pipeline().get("requestReadTimeout") == null, promoted)
        assertEquals(value.pipeline().get("streamWriteTimeout") != null, promoted)
        assert(value.pipeline().get("requestDeadline") != null)
      finally value.finishAndReleaseAll().discard

  test("stream completion restores ordinary idle protection and removes its write handler"):
    val value = channel()
    try
      value.writeInbound(DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/v1/admin/events")).discard
      value.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT).discard
      value.writeOutbound(response()).discard
      assert(value.pipeline().get("requestReadTimeout") == null)
      value.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT).discard
      assert(value.pipeline().get("requestReadTimeout") != null)
      assert(value.pipeline().get("streamWriteTimeout") == null)
    finally value.finishAndReleaseAll().discard

  test("a streamed connection cannot pipeline another management exchange after idle promotion"):
    val value = channel()
    try
      value.writeInbound(DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/v1/admin/events")).discard
      value.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT).discard
      value.writeOutbound(response()).discard
      value.writeInbound(DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/v1/admin/snapshot")).discard
      assert(!value.isActive)
    finally value.finishAndReleaseAll().discard
