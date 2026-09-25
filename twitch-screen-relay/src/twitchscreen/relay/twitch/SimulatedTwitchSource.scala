package twitchscreen.relay.twitch

import java.time.Clock
import org.slf4j.LoggerFactory
import ox.*
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.TwitchConfig
import twitchscreen.relay.protocol.{ChatColour, Count, SubTier}

/** A deterministic stand-in for Twitch, so the firmware, the enclosure and the dashboard can be worked on without credentials — the job the
  * Python `demo-server/` used to do, now speaking through the same bus as the real source.
  *
  * Deliberately not random: it walks fixed scripts in order, so a given number of ticks always produces the same events and a UI regression
  * is reproducible.
  */
private[twitch] object SimulatedTwitchSource:
  private val logger = LoggerFactory.getLogger(getClass)

  /** One of each notification kind the firmware renders, so a full cycle exercises every icon and accent colour. */
  private val audience: Vector[RelayEvent] = Vector(
    RelayEvent.Followed("pixelpainter"),
    RelayEvent.Subscribed("nightowl", tier = SubTier.Tier1, cumulativeMonths = 3, message = "three months!"),
    RelayEvent.BitsCheered("bitbaron", bits = 500, message = "take my bits"),
    RelayEvent.SubscriptionGifted("generouspanda", count = 5, tier = SubTier.Tier1, anonymous = false),
    RelayEvent.Raided("streamfriend", viewers = 128),
    RelayEvent.Followed("quietlurker"),
    // One bot-authored line per cycle, so that a simulated run exercises §13.1's filtering rather than only the happy path.
    RelayEvent.ChatMessaged("Nightbot", "!commands", None)
  )

  private val chatter: Vector[String] =
    Vector("that solder joint is clean", "o7", "how is the enclosure going?", "PogChamp", "round display gang", "gg")

  private val chatters: Vector[String] = Vector("sparkplug", "vectorvic", "lathe_and_order", "kerf")

  def start(config: TwitchConfig, bus: EventBus, filter: BotFilter, clock: Clock)(using Ox): TwitchSource =
    val channel = if config.channel.isBlank then "simulated-channel" else config.channel
    logger.info(s"Simulating Twitch for '$channel': an audience event every ${config.simulation.interval}")

    bus.publish(RelayEvent.TwitchLinkUp("simulated"))
    bus.publish(
      RelayEvent.StreamStarted(channel, "Building a round-display notifier", "Science & Technology", Some(clock.instant()))
    )

    // Through the filter, exactly as the live source does: a simulated run that bypassed it would hide the one
    // behaviour §13.1 asks for, which is that a bot costs no sequence number and no chat count.
    cycle(config.simulation.interval, audience)(filter.publish(bus, _))
    cycle(config.simulation.chatInterval, chatter)(line =>
      filter.publish(bus, RelayEvent.ChatMessaged(chatters(line.length % chatters.size), line, Some(ChatColour(0x00ff7f50))))
    )
    cycle(config.pollInterval, telemetry)(_.foreach(bus.publish))

    new TwitchSource:
      override val status: TwitchStatus =
        TwitchStatus(config.mode, TwitchHealth.Connected, channel, "synthetic events, no Twitch connection")
      override val endpoints: List[ServerEndpoint[Any, Identity]] = Nil

  /** Viewer and follower figures that drift in a fixed pattern, so the idle dashboard visibly changes. */
  private val telemetry: Vector[List[RelayEvent]] =
    (0 until 8).toVector.map: step =>
      List(
        RelayEvent.ViewersObserved(Count.clamp(120 + step * 37), (12 + step * 5).minutes),
        RelayEvent.FollowersObserved(Count.clamp(12_400 + step)),
        RelayEvent.SubscribersObserved(Count.clamp(318 + step / 4))
      )

  /** Emits `script` in order, forever, one entry per `interval`. */
  private def cycle[T](interval: FiniteDuration, script: Vector[T])(emit: T => Unit)(using Ox): Unit =
    forkDiscard:
      var index = 0
      forever:
        sleep(interval)
        emit(script(index % script.size))
        index += 1
