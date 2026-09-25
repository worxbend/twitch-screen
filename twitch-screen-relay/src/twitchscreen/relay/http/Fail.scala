package twitchscreen.relay.http

/** The application-wide recoverable-failure type. Deliberately not sealed: each feature package adds the failures it needs, and
  * [[Http.failOutput]] maps anything it does not recognise to a 500.
  */
abstract class Fail

object Fail:
  final case class NotFound(what: String) extends Fail
  final case class Conflict(msg: String) extends Fail
  final case class IncorrectInput(msg: String) extends Fail
  final case class Unauthorized(msg: String) extends Fail
  case object Forbidden extends Fail

  /** A dependency the relay needs is not currently usable — Twitch is disconnected, a device has gone away. */
  final case class Unavailable(msg: String) extends Fail
