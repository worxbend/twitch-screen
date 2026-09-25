package twitchscreen.relay.config

import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import pureconfig.ConfigSource
import ox.discard
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class ConfigSuite extends munit.FunSuite:
  private def configured =
    ConfigFactory.parseString("http.auth.api-token = \"test-configuration-token-at-least-32-bytes\"").withFallback(ConfigFactory.load())
  private def configSource = ConfigSource.fromConfig(configured)
  test("the configuration shipped in resources loads"):
    val config = configSource.loadOrThrow[Config]
    assertEquals(config.deviceLink.port.value, 8099)

  test("the shipped configuration speaks TSB/3 and counts its frame limit in bytes"):
    val config = configSource.loadOrThrow[Config]
    // §7 makes the version check exact equality, and §5 fixes the frame at 256 BYTES, header included —
    // not the 512 UTF-16 characters v2's line reader counted.
    assertEquals(config.deviceLink.protocolVersion, 3)
    assertEquals(config.deviceLink.maxFrameLength, 256)

  test("the shipped configuration carries §13.1's bot list, matched on the display name"):
    val config = configSource.loadOrThrow[Config]
    assertEquals(
      config.notifications.ignoredDisplayNames,
      List("streamelements", "nightbot", "moobot", "streamlabs", "fossabot", "sery_bot")
    )

  test("§13.1: the bot list overridden from a comma-separated environment variable is split, trimmed and loaded"):
    val env = ConfigFactory.parseString("notifications.ignored-display-names = \"nightbot, foo,,\"")
    val config = ConfigSource.fromConfig(env.withFallback(configured)).loadOrThrow[Config]
    assertEquals(config.notifications.ignoredDisplayNames, List("nightbot", "foo"))

  test("a configuration claiming a protocol version this build cannot encode is rejected at load"):
    val failure = intercept[IllegalArgumentException](deviceLinkConfig(protocolVersion = 2))
    assert(failure.getMessage.contains("protocol-version"), failure.getMessage)

  test("a frame limit below the 256 bytes every v3 peer must accept is rejected at load"):
    val failure = intercept[IllegalArgumentException](deviceLinkConfig(maxFrameLength = 128))
    assert(failure.getMessage.contains("max-frame-length"), failure.getMessage)

  test("the shipped configuration obtains the Twitch user token through the consent flow, never from the environment"):
    val config = configSource.loadOrThrow[Config]
    assertEquals(config.twitch.oauth.redirectUrl, "http://localhost:8080/api/v1/twitch/callback")
    assertEquals(config.twitch.oauth.scopes, List("moderator:read:followers", "channel:read:subscriptions"))
    assert(!ConfigFactory.load().hasPath("twitch.user-access-token"))

  test("a live relay whose redirect URL does not land on the callback path is rejected at load"):
    val failure = intercept[IllegalArgumentException](
      liveTwitchConfig(clientId = "abc").copy(oauth = TwitchOAuthConfig("http://localhost:8080/", Nil, "t.json", 15.minutes))
    )
    assert(failure.getMessage.contains("redirect-url"), failure.getMessage)

  test("a sensitive value does not render itself"):
    assertEquals(Sensitive("hunter2").toString, "***")

  test("live mode without a client id is rejected at load, not at the first Helix call"):
    val failure = intercept[IllegalArgumentException](liveTwitchConfig(clientId = ""))
    assert(failure.getMessage.contains("twitch.client-id"), failure.getMessage)

  test("live mode with credentials is accepted"):
    assertEquals(liveTwitchConfig(clientId = "abc").mode, TwitchMode.Live)

  test("a ping interval longer than the idle timeout is rejected"):
    val failure = intercept[IllegalArgumentException](deviceLinkConfig(pingInterval = 30.seconds))
    assert(failure.getMessage.contains("ping-interval"), failure.getMessage)

  test("RLY-25: a ping interval that is zero, negative or under a second is rejected, naming the key"):
    List(0.seconds, (-1).second, 500.millis).foreach: ping =>
      val failure = intercept[IllegalArgumentException](deviceLinkConfig(pingInterval = ping))
      assert(failure.getMessage.contains("ping-interval"), s"$ping: ${failure.getMessage}")

  test("RLY-25: ping and idle are compared in the whole seconds WELCOME carries, not in nanoseconds"):
    // 1500 ms < 1900 ms, but both go on the wire as 1 s: the device would be told ping == idle.
    val fractionalPing = intercept[IllegalArgumentException](deviceLinkConfig(pingInterval = 1500.millis, idleTimeout = 1900.millis))
    assert(fractionalPing.getMessage.contains("ping-interval"), fractionalPing.getMessage)
    val fractionalIdle = intercept[IllegalArgumentException](deviceLinkConfig(pingInterval = 2.seconds, idleTimeout = 2500.millis))
    assert(fractionalIdle.getMessage.contains("idle-timeout"), fractionalIdle.getMessage)
    List(0.seconds, (-1).second).foreach: idle =>
      val failure = intercept[IllegalArgumentException](deviceLinkConfig(pingInterval = 1.second, idleTimeout = idle))
      assert(failure.getMessage.contains("idle-timeout"), s"$idle: ${failure.getMessage}")

  test("RLY-25: a handshake timeout must be positive, but is not a wire value and may be sub-second"):
    List(0.seconds, (-1).second).foreach: handshake =>
      val failure = intercept[IllegalArgumentException](deviceLinkConfig(handshakeTimeout = handshake))
      assert(failure.getMessage.contains("handshake-timeout"), s"$handshake: ${failure.getMessage}")
    assertEquals(deviceLinkConfig(handshakeTimeout = 300.millis).handshakeTimeout, 300.millis)

  test("RLY-25: the default TTL must be a whole number of the deciseconds EVENT carries, within u16"):
    List(50.millis, 0.millis, 150.millis, 6553600.millis).foreach: ttl =>
      val failure = intercept[IllegalArgumentException](NotificationsConfig(ttl, ChatNotifications.Show))
      assert(failure.getMessage.contains("default-ttl"), s"$ttl: ${failure.getMessage}")
    List(100.millis, 8.seconds, 6553500.millis).foreach: ttl =>
      assertEquals(NotificationsConfig(ttl, ChatNotifications.Show).defaultTtl, ttl)

  test("RLY-16: the outbound queue must hold the durable replay plus the named greeting frames, and no fewer"):
    assertEquals(DeviceLinkConfig.GreetingFrames, 18)
    assertEquals(DeviceLinkConfig.GreetingFrames, DeviceLinkConfig.ChatReplaySize + 2)
    val failure = intercept[IllegalArgumentException](
      deviceLinkConfig(replayBufferSize = 8, outboundQueueCapacity = 8 + DeviceLinkConfig.GreetingFrames - 1)
    )
    assert(failure.getMessage.contains("outbound-queue-capacity"), failure.getMessage)
    assertEquals(
      deviceLinkConfig(replayBufferSize = 8, outboundQueueCapacity = 8 + DeviceLinkConfig.GreetingFrames).outboundQueueCapacity,
      8 + DeviceLinkConfig.GreetingFrames
    )

  test("zero or negative simulation, bus, stats, alert and activity values are rejected"):
    val alerts = AlertsConfig(1.second, 10, None, None, None, 0, 1.minute)
    List[() => Any](
      () => SimulationConfig(0.seconds, 1.second),
      () => SimulationConfig(1.second, (-1).second),
      () => BusConfig(0),
      () => BusConfig(-1),
      () => StatsConfig(0.seconds, 1.second),
      () => alerts.copy(evaluationInterval = 0.seconds),
      () => alerts.copy(bufferSize = 0),
      () => alerts.copy(noDevicesConnectedFor = Some(0.seconds)),
      () => ActivityConfig(0)
    ).foreach(build => intercept[IllegalArgumentException](build()).discard)

  test("RLY-25: a ping interval equal to the idle timeout on the wire fails the reader, not just the constructor"):
    val source = configured
      .withValue("device-link.ping-interval", ConfigValueFactory.fromAnyRef("1500ms"))
      .withValue("device-link.idle-timeout", ConfigValueFactory.fromAnyRef("1900ms"))
    val result = ConfigSource.fromConfig(source).load[Config]
    assert(result.isLeft)
    assert(result.swap.toOption.exists(_.toString.contains("ping-interval")), result.toString)

  private def deviceLinkConfig(
      pingInterval: FiniteDuration = 4.seconds,
      maxFrameLength: Int = 256,
      protocolVersion: Int = 3,
      handshakeTimeout: FiniteDuration = 5.seconds,
      idleTimeout: FiniteDuration = 10.seconds,
      outboundQueueCapacity: Int = 26,
      replayBufferSize: Int = 8
  ): DeviceLinkConfig =
    DeviceLinkConfig(
      host = Hostname("0.0.0.0").toOption.get,
      port = Port(8099).toOption.get,
      protocolVersion = protocolVersion,
      acceptBacklog = 8,
      handshakeTimeout = handshakeTimeout,
      idleTimeout = idleTimeout,
      pingInterval = pingInterval,
      outboundQueueCapacity = outboundQueueCapacity,
      replayBufferSize = replayBufferSize,
      maxFrameLength = maxFrameLength
    )

  test("the rendered configuration masks every secret"):
    val rendered = ConfigApi.flatten(ConfigFactory.load())
    assertEquals(rendered("twitch.client-secret"), "***")
    assertEquals(rendered("twitch.event-sub.secret"), "***")
    assertEquals(rendered("http.auth.api-token"), "***")
    assertEquals(rendered("http.auth.basic-password-hash"), "***")

  test("unknown secret keys are masked independent of spelling convention"):
    val source = ConfigFactory.parseString("""twitch { clientSecret = "private", access_token = "private", passwordHash = "private" }""")
    assert(ConfigApi.flatten(source).values.forall(_ == "***"))

  test("config rejects sub-millisecond timers and unbounded queue sizes"):
    List[() => Any](
      () => SimulationConfig(1.nanos, 1.second),
      () => StatsConfig(1.nanos, 1.second),
      () => StatsConfig(1.second, 999.millis),
      () => BusConfig(Int.MaxValue),
      () => ActivityConfig(Int.MaxValue),
      () => ObservabilityConfig(Int.MaxValue)
    ).foreach(build => intercept[IllegalArgumentException](build()).discard)

  test("HTTP config cannot construct a readiness bypass with absent authentication"):
    intercept[IllegalArgumentException](HttpConfig(Hostname("localhost").toOption.get, Port(8080).toOption.get, HttpAuthConfig())).discard

  test("callback schemes and hosts are case insensitive while scopes reject malformed names"):
    val config = liveTwitchConfig("client")
    assertEquals(config.copy(oauth = config.oauth.copy(redirectUrl = "HTTP://LOCALHOST/api/v1/twitch/callback")).mode, TwitchMode.Live)
    List(List(""), List("read scope"), List("channel:read:subscriptions", "channel:read:subscriptions")).foreach: scopes =>
      intercept[IllegalArgumentException](config.copy(oauth = config.oauth.copy(scopes = scopes))).discard
    assertEquals(config.copy(oauth = config.oauth.copy(scopes = Nil)).oauth.scopes, Nil)

  test("hostnames reject embedded whitespace and list settings reject numeric scalar coercion"):
    assert(Hostname("bad host").isLeft)
    assert(Hostname("bad\nhost").isLeft)
    val source = ConfigFactory.parseString("notifications.ignored-display-names = 123").withFallback(configured)
    assert(ConfigSource.fromConfig(source).load[Config].isLeft)

  test("the rendered configuration is limited to the relay's own sections, so system properties cannot leak"):
    val sections = ConfigApi.flatten(ConfigFactory.load()).keys.map(_.takeWhile(_ != '.')).toSet
    assertEquals(
      sections,
      Set("http", "device-link", "twitch", "notifications", "bus", "stats", "activity", "alerts", "observability")
    )

  private def liveTwitchConfig(clientId: String): TwitchConfig =
    TwitchConfig(
      mode = TwitchMode.Live,
      channel = "somechannel",
      clientId = clientId,
      clientSecret = Sensitive("secret"),
      oauth = TwitchOAuthConfig("http://localhost:8080/api/v1/twitch/callback", Nil, "data/twitch-token.json", 15.minutes),
      eventSub = EventSubConfig(EventSubTransport.WebSocket, "", Sensitive.Empty),
      pollInterval = 30.seconds,
      simulation = SimulationConfig(10.seconds, 2.seconds)
    )

  test("invalid configuration returns a path-qualified reader failure rather than throwing"):
    val source = ConfigFactory.parseString("stats.broadcast-interval = 0 seconds").withFallback(configured)
    val result = ConfigSource.fromConfig(source).load[Config]
    assert(result.isLeft)
    assert(result.swap.toOption.exists(_.toString.contains("stats")))

  test("blank secrets do not count as configured"):
    assert(!Sensitive("  ").isSet)

  test("webhook provider constraints reject insecure callbacks and short secrets"):
    val base = liveTwitchConfig("abc")
    intercept[IllegalArgumentException](
      base.copy(eventSub =
        EventSubConfig(EventSubTransport.Webhook, "http://relay.example/api/v1/twitch/eventsub", Sensitive("0123456789"))
      )
    ).discard
    val failure = intercept[IllegalArgumentException](
      base.copy(eventSub = EventSubConfig(EventSubTransport.Webhook, "https://relay.example/api/v1/twitch/eventsub", Sensitive("short")))
    )
    assert(failure.getMessage.contains("secret"))

  test("missing management credentials fail configuration loading with an auth path"):
    val result =
      ConfigSource.fromConfig(ConfigFactory.parseString("http.auth.api-token = \"\"").withFallback(ConfigFactory.load())).load[Config]
    assert(result.isLeft)
    assert(result.swap.toOption.exists(_.toString.contains("http.auth")))

  test("configured credentials reject whitespace usernames and non-header-safe bearer tokens"):
    val token = Sensitive("valid-test-token-with-at-least-32-bytes")
    List(" ", "name:password").foreach: username =>
      intercept[IllegalArgumentException](HttpAuthConfig(username, apiToken = token).validate()).discard
    List("x" * 32 + "\n", "é" * 32, "x" * 32 + " space").foreach: invalid =>
      intercept[IllegalArgumentException](HttpAuthConfig(apiToken = Sensitive(invalid)).validate()).discard
