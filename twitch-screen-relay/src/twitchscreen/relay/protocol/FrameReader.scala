package twitchscreen.relay.protocol

import java.io.InputStream
import scala.concurrent.duration.FiniteDuration

/** §14's per-cause frame counters, as the reader sees them. The decoder's four causes are counted by the session; these two are the ones
  * only the reader can see, because the frames they describe never become messages.
  *
  * A trait rather than a direct dependency on the session's counters: the reader lives in the protocol layer and has no business knowing
  * what a device link is, and [[FrameReaderSuite]] needs a reader with nowhere to report to.
  */
private[relay] trait FrameCounters:
  /** §4.3: a well-framed frame whose `length` is past this receiver's payload buffer. The payload is discarded and the link is kept. */
  def recordOversizeSkipped(): Unit

  /** §4.5: one candidate window shifted by a byte. A version skew shows up here long before it shows up on the screen. */
  def recordResync(): Unit

private[relay] object FrameCounters:
  /** For a reader with nobody to report to. */
  val Ignore: FrameCounters = new FrameCounters:
    def recordOversizeSkipped(): Unit = ()
    def recordResync(): Unit = ()

/** §12: how long the reader may keep waiting for one **complete** frame, and how to tell the socket about it.
  *
  * The distinction matters. `SO_TIMEOUT` bounds a single read, so a peer that emits one byte every 89 seconds restarts a 90 second timer
  * forever while never completing a frame — it holds a session, a row in the device table and a 128-frame outbound queue open indefinitely,
  * and §4.5's budget of 4096 discarded bytes would take four days to fire at that rate. §12 says "90 s with no inbound frame of any type",
  * so the budget is spent across every read that goes into one frame, and `setReadTimeout` narrows the socket's own timeout to whatever is
  * left of it before each blocking read. That keeps the timeout exact rather than "somewhere between one budget and two".
  */
private[relay] final case class FrameBudget(timeout: FiniteDuration, setReadTimeout: Int => Unit)

/** Lifts a byte stream into whole TSB/3 frames (§4): find an eight-byte header that validates, then read exactly `length` payload bytes.
  *
  * Four properties this reader exists to guarantee, each of which the v2 line reader got wrong or could not have:
  *
  *   - **A socket read returns what it has, not what was asked for.** `InputStream.read` short-counts whenever fewer bytes are buffered, so
  *     every fill accumulates until it holds what it needs. A frame straddling three TCP segments is assembled, not truncated.
  *   - **A bad header shifts the window by exactly one byte, never by eight** (§4.4). Consuming all eight would step over a frame boundary
  *     lying inside the discarded region, turning one spurious byte into a permanently desynchronised stream. A stray `0xa7` inside a
  *     payload therefore costs a few bytes of garbage rather than the connection.
  *   - **A malformed frame must not kill the link; a malformed stream must** (§4.6). The resync budget of §4.5 — 16 rejected candidate
  *     headers or 4096 discarded bytes — is what separates the two, and it is a number rather than a judgement call. A frame that is
  *     well-framed but past this receiver's buffer is §4.1's `SKIP` state: discard exactly `length` bytes, count it, keep reading.
  *   - **The idle timeout is measured in whole frames** (§12), through [[FrameBudget]], not in single socket reads.
  *
  * The reader holds no state between calls and therefore no `var` outside a method body: when [[read]] returns a frame the stream is
  * positioned exactly on the next frame boundary, and §4.5's counters reset on every frame decoded or deliberately skipped, so a call that
  * returns starts the next one from zero. Whether a returned frame is then skipped under §4.3 is the caller's decision, not the reader's.
  *
  * [[read]] blocks. §2 permits that: the relay runs one virtual thread per connection and may block only that thread.
  */
private[relay] final class FrameReader(
    source: InputStream,
    maxPayload: Int = Tsb3.MaxPayload,
    counters: FrameCounters = FrameCounters.Ignore
):
  /** Reads the next well-framed frame, resynchronising past garbage until §4.5's budget runs out.
    *
    * A `Right` is well-framed, never necessarily meaningful: the type may be unknown, the payload may be short for that type, a field may
    * be out of range. That is [[Tsb3Decoder]]'s business, and §4.3 requires those be skipped rather than treated as corruption.
    *
    * `budget` bounds the whole call, which is what makes §12's timeout frame-based; without one the call is bounded only by the socket's
    * own `SO_TIMEOUT`.
    */
  def read(budget: Option[FrameBudget] = None): Either[ProtocolError, Frame] =
    val window = new Array[Byte](Tsb3.HeaderSize)
    val deadline = budget.map(Deadline(_))
    var held = 0
    var rejectedCandidates = 0
    var discardedBytes = 0
    var outcome: Option[Either[ProtocolError, Frame]] = None

    while outcome.isEmpty do
      fill(window, held, Tsb3.HeaderSize - held, deadline) match
        case Left(error) => outcome = Some(Left(error))
        case Right(_) =>
          held = Tsb3.HeaderSize
          FrameHeader.decode(window) match
            case Right(header) if header.length > maxPayload =>
              // §4.1's SKIP state, which §4.3 files under "payload-level problems — skip the frame, keep the link". It is
              // unreachable between two v3 peers, because §4.2 already caps `length` at 248 and §5 makes 256 the smallest
              // frame any peer may accept; it is implemented rather than asserted because the ceiling is a parameter, and
              // the day it moves the screen must show the next card rather than "connecting".
              discardPayload(header.length, deadline) match
                case Left(error) => outcome = Some(Left(error))
                case Right(_) =>
                  counters.recordOversizeSkipped()
                  // §4.5: both budget counters reset, because a frame that was skipped was nonetheless correctly framed.
                  held = 0
                  rejectedCandidates = 0
                  discardedBytes = 0
            case Right(header) => outcome = Some(payload(header, deadline))
            case Left(_)       =>
              // §4.4: the candidate is not trustworthy, so neither is its `length`. Shift by one byte and re-examine.
              if (window(0) & 0xff) == (Tsb3.Magic0 & 0xff) then rejectedCandidates += 1
              counters.recordResync()
              System.arraycopy(window, 1, window, 0, Tsb3.HeaderSize - 1)
              held = Tsb3.HeaderSize - 1
              discardedBytes += 1
              if rejectedCandidates >= Tsb3.MaxRejectedCandidates || discardedBytes >= Tsb3.MaxDiscardedBytes then
                outcome = Some(Left(ProtocolError.FramingViolation(discardedBytes, rejectedCandidates)))

    outcome.getOrElse(Left(ProtocolError.EndOfStream))

  /** §4.1: `have` is reset to 0 on entry to `BODY`. A fresh array per frame is that rule made structural — sharing one accumulator between
    * the header and the payload without resetting it decodes a frame from `length − 8` fresh bytes plus eight stale ones, which renders
    * leftover text from an earlier frame rather than crashing.
    */
  private def payload(header: FrameHeader, deadline: Option[Deadline]): Either[ProtocolError, Frame] =
    if header.length == 0 then Right(Frame(header, Array.emptyByteArray))
    else
      val bytes = new Array[Byte](header.length)
      fill(bytes, 0, header.length, deadline).map(_ => Frame(header, bytes))

  /** §4.1's `SKIP`: read and throw away exactly `count` bytes, in buffer-sized bites so that an oversized `length` cannot be turned into an
    * oversized allocation by the peer that sent it.
    */
  private def discardPayload(count: Int, deadline: Option[Deadline]): Either[ProtocolError, Unit] =
    val scratch = new Array[Byte](math.min(count, Tsb3.MaxFrame))
    var left = count
    var outcome: Either[ProtocolError, Unit] = Right(())
    while left > 0 && outcome.isRight do
      val take = math.min(left, scratch.length)
      outcome = fill(scratch, 0, take, deadline)
      if outcome.isRight then left -= take
    outcome

  /** Accumulates exactly `count` bytes at `offset`, however many reads that takes, within whatever is left of the frame budget. */
  private def fill(target: Array[Byte], offset: Int, count: Int, deadline: Option[Deadline]): Either[ProtocolError, Unit] =
    var read = 0
    var closed = false
    var expired: Option[ProtocolError] = None
    while read < count && !closed && expired.isEmpty do
      deadline.flatMap(_.arm()) match
        case Some(error) => expired = Some(error)
        case None =>
          val got = source.read(target, offset + read, count - read)
          if got < 0 then closed = true else read += got

    expired match
      case Some(error) => Left(error)
      case None =>
        if read == count then Right(())
        else if offset + read == 0 then Left(ProtocolError.EndOfStream)
        else Left(ProtocolError.TruncatedFrame(offset + count, offset + read))

/** One frame's worth of [[FrameBudget]], counted from the moment [[FrameReader.read]] was entered — which is the moment the previous frame
  * completed, and therefore exactly what §12 measures.
  */
private final class Deadline(budget: FrameBudget):
  private val expiresAt: Long = System.nanoTime() + budget.timeout.toNanos

  /** Narrows the socket's read timeout to what is left, and reports expiry rather than arming a blocking read that cannot help. */
  def arm(): Option[ProtocolError] =
    val remainingMs = (expiresAt - System.nanoTime()) / 1000000L
    if remainingMs <= 0 then Some(ProtocolError.FrameTimeout(budget.timeout))
    else
      budget.setReadTimeout(math.min(remainingMs, Int.MaxValue.toLong).toInt)
      None
