package twitchscreen.relay.twitch

import com.typesafe.config.ConfigFactory
import java.nio.file.{Files, Path}
import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import java.util.concurrent.atomic.AtomicInteger
import ox.*
import ox.channels.Channel
import pureconfig.ConfigSource
import scala.concurrent.duration.DurationInt
import sttp.model.Uri
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.*

class TwitchOAuthOwnerSuite extends munit.FunSuite:
  private final class TestClock(var now: Instant = Instant.parse("2026-10-12T00:00:00Z")) extends Clock:
    override def getZone: ZoneId = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant = now

  private final class Provider(clock: Clock) extends TwitchOAuthClient:
    val exchanges = AtomicInteger()
    val refreshes = AtomicInteger()
    val revokes = AtomicInteger()
    private def token =
      UserToken(Sensitive("synthetic-access"), Sensitive("synthetic-refresh"), clock.instant().plusSeconds(3600), Nil, "1", "owner")
    override def exchange(code: String): Either[TwitchCallFailure, UserToken] =
      exchanges.incrementAndGet().discard
      Right(token)
    override def refresh(value: UserToken): Either[TwitchCallFailure, UserToken] =
      refreshes.incrementAndGet().discard
      Right(token)
    override def isValid(value: UserToken): Option[Boolean] = Some(true)
    override def revoke(value: UserToken): Boolean =
      revokes.incrementAndGet().discard
      true

  private def withOwner(body: (TwitchConfig, TestClock, Provider, EventBus, TwitchOAuthOwner) => Ox ?=> Unit): Unit =
    val directory =
      Files.createTempDirectory(Path.of(sys.env.getOrElse("TMPDIR", System.getProperty("java.io.tmpdir"))), "relay-oauth-owner-")
    try
      supervised:
        val clock = TestClock()
        val provider = Provider(clock)
        val bus = EventBus(clock, 32)
        val defaults = ConfigSource.fromConfig(ConfigFactory.load()).loadOrThrow[Config].twitch
        val config = defaults.copy(
          mode = TwitchMode.Disabled,
          channel = "",
          clientId = "synthetic-client",
          clientSecret = Sensitive("synthetic-secret"),
          oauth = defaults.oauth.copy(tokenFile = directory.resolve("token.json").toString)
        )
        val auth = TwitchAuth.create(config, provider, TokenFile(Path.of(config.oauth.tokenFile)), bus, clock, None)
        val owner = TwitchOAuthOwner.create(config, () => auth)
        body(config, clock, provider, bus, owner)
    finally
      val paths = Files.walk(directory)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
      finally paths.close()

  test("OAuth admission is independent of channel, ingestion mode and EventSub configuration"):
    withOwner: (config, _, _, _, owner) =>
      assertEquals(owner.endpoints.size, 4)
      assert(owner.auth.isDefined)
      val invalidTransport = config.copy(eventSub = EventSubConfig(EventSubTransport.Webhook, "", Sensitive("")))
      invalidTransport.validateOAuth()
      intercept[IllegalArgumentException](invalidTransport.copy(mode = TwitchMode.Live, channel = "channel").validateLive()).discard

  test("missing or invalid OAuth configuration never starts a grant owner"):
    val defaults = ConfigSource.fromConfig(ConfigFactory.load()).loadOrThrow[Config].twitch
    val starts = AtomicInteger()
    def unexpected(): TwitchAuth =
      starts.incrementAndGet().discard
      throw IllegalStateException("must not create provider")
    val missing = TwitchOAuthOwner.create(defaults.copy(clientId = "", clientSecret = Sensitive("")), unexpected)
    val invalid = TwitchOAuthOwner.create(
      defaults.copy(
        clientId = "synthetic",
        clientSecret = Sensitive("synthetic"),
        oauth = defaults.oauth.copy(redirectUrl = "http://unsafe/callback")
      ),
      unexpected
    )
    assertEquals(starts.get(), 0)
    assertEquals(missing.endpoints, Nil)
    assertEquals(invalid.endpoints, Nil)

  test("pending consent, grant and refresh survive cancellation of replaced ingestion scopes"):
    withOwner: (config, clock, provider, bus, owner) =>
      val auth = owner.auth.get
      val state = Uri.unsafeParse(auth.beginAuthorization()).params.get("state").get
      val endpoints = owner.endpoints
      val ready = Channel.buffered[Unit](1)
      val source = forkCancellable:
        supervised:
          val live = TwitchSource.create(config.copy(mode = TwitchMode.Live, channel = "first"), bus, BotFilter(Set.empty), clock, owner)
          assertEquals(live.endpoints.size, 0)
          ready.send(())
          never
      ready.receive()
      source.cancelNow()
      assert(timeoutOption(5.seconds)(source.joinEither()).exists(_.isLeft))
      assert(auth.completeAuthorization("synthetic-code", state).isRight)
      assertEquals(provider.exchanges.get(), 1)
      val disabled = TwitchSource.create(config, bus, BotFilter(Set.empty), clock, owner)
      val liveAgain = TwitchSource.create(config.copy(mode = TwitchMode.Live, channel = "second"), bus, BotFilter(Set.empty), clock, owner)
      assertEquals(disabled.endpoints, Nil)
      assertEquals(liveAgain.endpoints, Nil)
      assertEquals(owner.endpoints, endpoints)
      assertEquals(auth.view.held.map(_.login), Some("owner"))
      clock.now = clock.now.plusSeconds(3500)
      auth.maintain()
      assertEquals(provider.refreshes.get(), 1)
      assertEquals(provider.revokes.get(), 0)
      assert(TokenFile(Path.of(config.oauth.tokenFile)).load().toOption.flatten.isDefined)
      assert(auth.completeAuthorization("synthetic-code", state).isLeft)
