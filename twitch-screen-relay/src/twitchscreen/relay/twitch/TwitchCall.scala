package twitchscreen.relay.twitch

import scala.util.control.NonFatal

/** Only stable operation/class names leave the foreign API boundary; exception text may contain credentials. */
private[twitch] final case class TwitchCallFailure(operation: String, errorType: String, status: Option[Int]):
  def message: String = s"could not $operation ($errorType)"
  def unauthorized: Boolean = status.contains(401)
  def rejectedGrant: Boolean = status.exists(code => code == 400 || code == 401)

private[twitch] object TwitchCall:
  def attempt[A](operation: String)(call: => A): Either[TwitchCallFailure, A] =
    try Right(call)
    catch
      case rejected: ApplicationTokenRejected => throw rejected
      case NonFatal(error)                    => Left(TwitchCallFailure(operation, error.getClass.getSimpleName, statusOf(error)))

  private[twitch] def statusOf(error: Throwable): Option[Int] =
    Iterator
      .iterate(Option(error))(_.flatMap(cause => Option(cause.getCause)))
      .take(10)
      .takeWhile(_.isDefined)
      .flatten
      .collectFirst:
        case _: com.github.twitch4j.common.exception.UnauthorizedException => 401
        case failure: feign.FeignException                                 => failure.status()
        case failure: TwitchOAuthClient.TokenResponseFailure               => failure.status
