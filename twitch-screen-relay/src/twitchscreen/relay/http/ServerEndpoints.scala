package twitchscreen.relay.http

import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.netty.sync.OxStreams

/** Implemented by every feature's API class, so [[HttpApi]] can collect them without naming each endpoint. */
trait ServerEndpoints:
  def endpoints: List[ServerEndpoint[Any, Identity]]
  def streamingEndpoints: List[ServerEndpoint[OxStreams, Identity]] = Nil
