package twitchscreen.relay.http

import sttp.tapir.*

/** One bounded list contract for retained diagnostics. */
private[relay] object HttpPageSize:
  val Maximum: Int = 500

  def input(default: Int): EndpointInput.Query[Int] =
    query[Int]("pageSize")
      .default(default)
      .validate(Validator.min(1).and(Validator.max(Maximum)))
      .description(s"Most recent first; between 1 and $Maximum entries")
