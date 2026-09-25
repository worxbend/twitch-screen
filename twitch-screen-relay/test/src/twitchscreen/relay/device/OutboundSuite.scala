package twitchscreen.relay.device

import java.time.Clock
import ox.*
import ox.channels.Channel
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.ChatNotifications
import twitchscreen.relay.protocol.*

/** K-138: a frame fanned out to several devices is encoded once per text policy, not once per session.
  *
  * The hub hands every attached device the same [[Outbound]], and that `Outbound` caches one [[EncodedFrame]] per [[TextPolicy]]. These
  * tests pin both halves, so a refactor that builds an `Outbound` per device or turns the caches back into per-call encodes fails here.
  */
class OutboundSuite extends munit.FunSuite:
  private val clock = Clock.systemUTC()

  test("K-138: an Outbound encodes once per text policy and returns the same bytes on every call"):
    val frame = Outbound(RelayMessage.Pong(Token.fromWire(1)))
    assert(frame.encoded(TextPolicy.Verbatim) eq frame.encoded(TextPolicy.Verbatim))
    assert(frame.encoded(TextPolicy.AsciiFolded) eq frame.encoded(TextPolicy.AsciiFolded))
    // Per-policy dispatch: one policy is never served the other policy's cache.
    assert(frame.encoded(TextPolicy.Verbatim) ne frame.encoded(TextPolicy.AsciiFolded))

  test("K-138: a broadcast hands every same-policy device the same Outbound and the same encoded bytes"):
    supervised:
      val hub = DeviceHub.start(TestRelay.config, ChatNotifications.Show, clock, EventBus(clock, 32))

      def attachDirect(name: String): Channel[Outbound] =
        val outbound = Channel.buffered[Outbound](16)
        hub
          .attach(
            AttachRequest(
              DeviceId(name).toOption.get,
              "test",
              Tsb3.Version,
              SeqNo.Zero,
              TestDevice.FullCaps,
              outbound,
              LinkCounters(clock),
              () => (),
              Channel.buffered[Unit](1)
            )
          )
          .discard
        outbound

      val alpha = attachDirect("alpha")
      val beta = attachDirect("beta")
      // The greeting burst is built per device and is not expected to be shared.
      List(alpha, beta).foreach: device =>
        assertEquals(List.fill(2)(device.receive().message.messageType), List(MessageType.Welcome, MessageType.Stats))

      // `encoded` runs on the test thread rather than a session writer fork; EncodedFrame is immutable, so that is equivalent.
      def assertShared(first: Outbound, second: Outbound): Unit =
        assert(first eq second, s"each device received its own Outbound: $first vs $second")
        assert(first.encoded(TextPolicy.Verbatim) eq second.encoded(TextPolicy.Verbatim))
        assert(first.encoded(TextPolicy.AsciiFolded) eq second.encoded(TextPolicy.AsciiFolded))

      hub.broadcastStats(StreamStats.Unknown)
      val (statsA, statsB) = (alpha.receive(), beta.receive())
      assertEquals(List(statsA, statsB).map(_.message.messageType), List(MessageType.Stats, MessageType.Stats))
      assertShared(statsA, statsB)

      assert(hub.publish(EventRequest.of(NotificationKind.Info, "shäred", "")).isRight)
      val (eventA, eventB) = (alpha.receive(), beta.receive())
      assertEquals(List(eventA, eventB).map(_.message.messageType), List(MessageType.Event, MessageType.Event))
      assertShared(eventA, eventB)
