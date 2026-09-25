package twitchscreen.relay

import ox.*
import ox.channels.Channel
import scala.concurrent.duration.DurationInt

/** Converts application interruption into a graceful stop before interrupting the service scope. OxApp cancels every fork in its scope
  * together; cleanup in that scope's body is too late to drain device writers.
  */
private[relay] object ApplicationLifetime:
  def run(start: Ox ?=> (() => Unit)): Unit = unsupervised:
    val stopping = Channel.buffered[Unit](1)
    val services = forkUnsupervised:
      supervised:
        val shutdown = start
        try stopping.receiveOrClosed().discard
        finally timeoutOption(3.seconds)(shutdown()).discard
    try services.join()
    catch
      case interrupted: InterruptedException =>
        stopping.doneOrClosed().discard
        // The service body's cleanup has its own bounded drain. Keep its actor and socket writers alive until it finishes.
        uninterruptible(services.join())
        throw interrupted
