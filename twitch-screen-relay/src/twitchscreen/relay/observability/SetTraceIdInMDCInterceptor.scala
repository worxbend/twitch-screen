package twitchscreen.relay.observability

import io.opentelemetry.api.trace.Span
import ox.logback.InheritableMDC
import sttp.shared.Identity
import sttp.tapir.server.interceptor.{EndpointInterceptor, RequestHandler, RequestInterceptor, Responder}

/** Puts the current trace id in the MDC for the duration of a request, so every log line written while handling it — including lines
  * written from forks — carries the same id as the exported span.
  */
private[relay] object SetTraceIdInMDCInterceptor extends RequestInterceptor[Identity]:
  val MDCKey: String = "traceId"

  override def apply[R, B](
      responder: Responder[Identity, B],
      requestHandler: EndpointInterceptor[Identity] => RequestHandler[Identity, R, B]
  ): RequestHandler[Identity, R, B] =
    RequestHandler.from: (request, endpoints, monad) =>
      InheritableMDC.unsupervisedWhere(MDCKey -> Span.current().getSpanContext.getTraceId):
        requestHandler(EndpointInterceptor.noop)(request, endpoints)(using monad)
