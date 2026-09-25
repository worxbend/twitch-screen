package twitchscreen.relay.config

import com.typesafe.config.ConfigFactory
import pureconfig.ConfigSource
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class ConfigSuite extends munit.FunSuite:
  test("the configuration shipped in resources loads"):
    val config = ConfigSource.default.loadOrThrow[Config]
    assertEquals(config.deviceLink.port.value, 8099)

  test("the shipped configuration speaks TSB/3 and counts its frame limit in bytes"):
    val config = ConfigSource.default.loadOrThrow[Config]
    // §7 makes the version check exact equality, and §5 fixes the frame at 256 BYTES, header included —
    // not the 512 UTF-16 characters v2's line reader counted.
    assertEquals(config.deviceLink.protocolVersion, 3)
    assertEquals(config.deviceLink.maxFrameLength, 256)

  test("the shipped configuration carries §13.1's bot list, matched on the display name"):
    val config = ConfigSource.default.loadOrThrow[Config]
    assertEquals(
      config.notifications.ignoredDisplayNames,
      List("streamelements", "nightbot", "moobot", "streamlabs", "fossabot", "sery_bot")
    )

  test("§13.1: the bot list overridden from a comma-separated environment variable is split, trimmed and loaded"):
    val env = ConfigFactory.parseString("notifications.ignored-display-names = \"nightbot, foo,,\"")
    val config = ConfigSource.fromConfig(env.withFallback(ConfigFactory.load())).loadOrThrow[Config]
    assertEquals(config.notifications.ignoredDisplayNames, List("nightbot", "foo"))

  test("a configuration claiming a protocol version this build cannot encode is rejected at load"):
    val failure = intercept[IllegalArgumentException](deviceLinkConfig(protocolVersion = 2))
    assert(failure.getMessage.contains("protocol-version"), failure.getMessage)

  test("a frame limit below the 256 bytes every v3 peer must accept is rejected at load"):
    val failure = intercept[IllegalArgumentException](deviceLinkConfig(maxFrameLength = 128))
    assert(failure.getMessage.contains("max-frame-length"), failure.getMessage)

  test("the shipped configuration obtains the Twitch user token through the consent flow, never from the environment"):
    val config = ConfigSource.default.loadOrThrow[Config]
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

  private def deviceLinkConfig(
      pingInterval: FiniteDuration = 4.seconds,
      maxFrameLength: Int = 256,
      protocolVersion: Int = 3
  ): DeviceLinkConfig =
    DeviceLinkConfig(
      host = Hostname("0.0.0.0").toOption.get,
      port = Port(8099).toOption.get,
      protocolVersion = protocolVersion,
      acceptBacklog = 8,
      handshakeTimeout = 5.seconds,
      idleTimeout = 10.seconds,
      pingInterval = pingInterval,
      outboundQueueCapacity = 8,
      replayBufferSize = 8,
      maxFrameLength = maxFrameLength
    )

  test("the rendered configuration masks every secret"):
    val rendered = ConfigApi.flatten(ConfigFactory.load())
    assertEquals(rendered("twitch.client-secret"), "***")

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
