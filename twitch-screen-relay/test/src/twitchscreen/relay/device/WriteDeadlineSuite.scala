package twitchscreen.relay.device

import java.io.ByteArrayOutputStream
import java.time.Clock
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import ox.*
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.jdk.CollectionConverters.*
import twitchscreen.relay.protocol.*

/** K-140: the write deadline is armed by a write and observed by a watcher that blocks while no write is in flight. These tests drive
  * [[WriteDeadline]] with an injected sleeper and clock, so they pin the number and length of the watcher's waits, not just the outcome.
  */
class WriteDeadlineSuite extends munit.FunSuite:
  /** A clock the tests move by hand, and a sleeper that records each requested wait and advances that clock by it instead of sleeping. */
  private final class FakeTime:
    val now = AtomicLong(0L)
    val requested = ConcurrentLinkedQueue[FiniteDuration]()
    @volatile var onSleep: FiniteDuration => Unit = _ => ()
    def sleeper(duration: FiniteDuration): Unit =
      // A real sleep is an interruption point; keep that, so a regressed polling watcher cannot spin past scope cancellation.
      if Thread.interrupted() then throw InterruptedException()
      requested.add(duration).discard
      now.addAndGet(duration.toNanos).discard
      onSleep(duration)

    /** The first few requested waits: bounded, so a regressed watcher that keeps waiting fails the comparison instead of stalling it. */
    def waits(): List[FiniteDuration] = requested.asScala.take(4).toList
    def deadline(): WriteDeadline = WriteDeadline(sleeper, () => now.get())

  /** Counts each wait, then really takes it: interruptible, so a regressed polling watcher fails the count instead of spinning. */
  private def countingSleeper(count: AtomicInteger)(duration: FiniteDuration): Unit =
    count.incrementAndGet().discard
    sleep(duration)

  test("an idle sink arms no deadline and the watcher never wakes"):
    supervised:
      val sleeps = AtomicInteger(0)
      val overdue = AtomicInteger(0)
      val deadline = WriteDeadline(countingSleeper(sleeps), () => System.nanoTime())
      forkDiscard(deadline.watch(() => 50.millis)(overdue.incrementAndGet().discard))
      // Six budgets of real time: any polling watcher, however it is tuned, would have slept at least once.
      Thread.sleep(300)
      assertEquals(sleeps.get(), 0)
      assertEquals(overdue.get(), 0)

  test("a write that completes before its budget never trips"):
    supervised:
      val time = FakeTime()
      val deadline = time.deadline()
      val overdue = AtomicInteger(0)
      val slept = CountDownLatch(1)
      time.onSleep = _ =>
        deadline.end() // the write finishes while the watcher waits on it
        slept.countDown()
      deadline.begin()
      forkDiscard(deadline.watch(() => 100.millis)(overdue.incrementAndGet().discard))
      assert(slept.await(2, TimeUnit.SECONDS))
      Thread.sleep(50)
      assertEquals(time.waits(), List(100.millis))
      assertEquals(overdue.get(), 0)

  test("a stalled write trips exactly at start + budget"):
    supervised:
      val time = FakeTime()
      val deadline = time.deadline()
      val tripped = CountDownLatch(1)
      deadline.begin()
      forkDiscard(deadline.watch(() => 100.millis)(tripped.countDown()))
      assert(tripped.await(2, TimeUnit.SECONDS))
      assertEquals(time.waits(), List(100.millis))

  test("a write started during the previous write's wait is armed from its own start"):
    supervised:
      val time = FakeTime()
      val deadline = time.deadline()
      val tripped = CountDownLatch(1)
      val first = AtomicInteger(0)
      time.onSleep = _ =>
        if first.getAndIncrement() == 0 then
          // The first write finishes, and a second starts 60 ms later — 40 ms before the first write's deadline.
          deadline.end()
          time.now.set(60.millis.toNanos)
          deadline.begin()
          time.now.set(100.millis.toNanos)
      deadline.begin()
      forkDiscard(deadline.watch(() => 100.millis)(tripped.countDown()))
      assert(tripped.await(2, TimeUnit.SECONDS))
      // The second wait runs from the watcher's wake (t=100) to the second write's own deadline (t=60+100).
      assertEquals(time.waits(), List(100.millis, 60.millis))

  test("a completed FrameSink write leaves no armed deadline"):
    supervised:
      val sleeps = AtomicInteger(0)
      val overdue = AtomicInteger(0)
      val deadline = WriteDeadline(countingSleeper(sleeps), () => System.nanoTime())
      val sink = FrameSink(ByteArrayOutputStream(), LinkCounters(Clock.systemUTC()), deadline)
      assertEquals(sink.write(EncodedFrame(RelayMessage.Pong(Token.fromWire(1)))), WriteResult.Written)
      forkDiscard(sink.watchDeadline(() => 50.millis)(overdue.incrementAndGet().discard))
      Thread.sleep(200)
      // The write raised one signal, so the watcher may wake once for it — but the write is over, so nothing trips.
      assert(sleeps.get() <= 1, s"watcher slept ${sleeps.get()} times")
      assertEquals(overdue.get(), 0)

  test("the watcher reads the budget current when a write is armed"):
    supervised:
      val time = FakeTime()
      val deadline = time.deadline()
      val tripped = CountDownLatch(1)
      val budget = AtomicLong(100.millis.toNanos)
      forkDiscard(deadline.watch(() => budget.get().nanos)(tripped.countDown()))
      budget.set(250.millis.toNanos)
      deadline.begin()
      assert(tripped.await(2, TimeUnit.SECONDS))
      assertEquals(time.waits(), List(250.millis))
