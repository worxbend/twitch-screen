package twitchscreen.relay.protocol

import java.io.{ByteArrayInputStream, InputStream}
import java.util.concurrent.atomic.AtomicInteger

/** Byte-level tools the TSB/3 suites share: hex in and out, and the frame surgery a malformed-input test needs.
  *
  * Every mutation recomputes the header check, because §3.1 makes `hchk` a function of the seven bytes before it: a test that corrupts a
  * header without recomputing it is testing header validation, not the thing it meant to test.
  */
object WireBytes:
  def hex(bytes: Array[Byte]): String = bytes.map(byte => f"${byte & 0xff}%02x").mkString

  def parse(text: String): Array[Byte] =
    text.filterNot(_.isWhitespace).grouped(2).map(pair => Integer.parseInt(pair, 16).toByte).toArray

  /** Reads one frame out of a complete byte array, as a socket would deliver it. */
  def frameOf(bytes: Array[Byte]): Either[ProtocolError, Frame] = FrameReader(ByteArrayInputStream(bytes)).read()

  /** Overwrites one payload byte — the header is untouched, because `hchk` covers the header alone. */
  def withPayloadByte(frame: Array[Byte], offset: Int, value: Int): Array[Byte] =
    val copy = frame.clone()
    copy(Tsb3.HeaderSize + offset) = (value & 0xff).toByte
    copy

  /** Rewrites header fields and restores the header check, so the frame stays well-framed and the test exercises the payload layer. */
  def withHeader(
      frame: Array[Byte],
      version: Int = -1,
      typeCode: Int = -1,
      flags: Int = -1
  ): Array[Byte] =
    val copy = frame.clone()
    if version >= 0 then copy(2) = version.toByte
    if typeCode >= 0 then copy(3) = typeCode.toByte
    if flags >= 0 then copy(6) = flags.toByte
    copy(7) = Tsb3.headerCheck(copy)
    copy

  /** Grows or shrinks the payload and restores both the length field and the header check. Shrinking by one byte is §17 case 2; growing by
    * four is §17 case 4, the tail extension a later version is entitled to append.
    */
  def resized(frame: Array[Byte], payloadLength: Int): Array[Byte] =
    val copy = new Array[Byte](Tsb3.HeaderSize + payloadLength)
    System.arraycopy(frame, 0, copy, 0, math.min(frame.length, copy.length))
    copy(4) = (payloadLength & 0xff).toByte
    copy(5) = ((payloadLength >>> 8) & 0xff).toByte
    copy(7) = Tsb3.headerCheck(copy)
    copy

  /** An eight-byte header with no frame behind it, valid check and all, for the cases where only the header is under test. */
  def header(typeCode: Int, length: Int, version: Int = 3, flags: Int = 0): Array[Byte] =
    val bytes = new Array[Byte](Tsb3.HeaderSize)
    bytes(0) = Tsb3.Magic0
    bytes(1) = Tsb3.Magic1
    bytes(2) = version.toByte
    bytes(3) = typeCode.toByte
    bytes(4) = (length & 0xff).toByte
    bytes(5) = ((length >>> 8) & 0xff).toByte
    bytes(6) = flags.toByte
    bytes(7) = Tsb3.headerCheck(bytes)
    bytes

/** An `InputStream` that short-counts on purpose.
  *
  * `caps` gives the maximum each successive `read` may return, so a test can put a frame boundary in the middle of a TCP segment and prove
  * the reader accumulates instead of assuming one read delivers what it asked for (§2). Once the caps run out it behaves normally.
  *
  * [[reads]] counts the calls, so a test can assert the split really happened rather than trusting the harness.
  */
final class ChunkedInputStream(data: Array[Byte], caps: Seq[Int]) extends InputStream:
  private val source = ByteArrayInputStream(data)
  private val call = AtomicInteger(0)

  val reads: AtomicInteger = AtomicInteger(0)

  override def read(): Int =
    reads.incrementAndGet()
    source.read()

  override def read(target: Array[Byte], offset: Int, length: Int): Int =
    reads.incrementAndGet()
    val index = call.getAndIncrement()
    val cap = if index < caps.length then math.max(1, caps(index)) else length
    source.read(target, offset, math.min(length, cap))

/** An `InputStream` that hands over one byte at a time, slowly, and never gets to the end of a frame.
  *
  * This is the shape of the peer §12's frame budget exists for: every read succeeds, so a per-read `SO_TIMEOUT` is restarted forever, and
  * the bytes are legal header bytes, so §4.5's resync budget never fires either. Only a budget measured across the whole frame drops it.
  */
final class DribblingInputStream(data: Array[Byte], perByte: scala.concurrent.duration.FiniteDuration) extends InputStream:
  private val source = ByteArrayInputStream(data)

  override def read(): Int =
    Thread.sleep(perByte.toMillis)
    source.read()

  override def read(target: Array[Byte], offset: Int, length: Int): Int =
    Thread.sleep(perByte.toMillis)
    source.read(target, offset, 1)
