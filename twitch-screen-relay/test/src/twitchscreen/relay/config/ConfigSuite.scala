package twitchscreen.relay.config

import com.typesafe.config.ConfigFactory
import pureconfig.ConfigSource
import scala.concurrent.duration.DurationInt

class ConfigSuite extends munit.FunSuite:
  test("the configuration shipped in resources loads"):
    val config = ConfigSource.default.loadOrThrow[Config]
    assertEquals(config.deviceLink.port.value, 8099)

  test("a sensitive value does not render itself"):
    assertEquals(Sensitive("hunter2").toString, "***")

  test("live mode without a client id is rejected at load, not at the first Helix call"):
    val failure = intercept[IllegalArgumentException](liveTwitchConfig(clientId = ""))
    assert(failure.getMessage.contains("twitch.client-id"), failure.getMessage)

  test("live mode with credentials is accepted"):
    assertEquals(liveTwitchConfig(clientId = "abc").mode, TwitchMode.Live)

  test("a ping interval longer than the idle timeout is rejected"):
    val failure = intercept[IllegalArgumentException](
      DeviceLinkConfig(
        host = Hostname("0.0.0.0").toOption.get,
        port = Port(8099).toOption.get,
        protocolVersion = 2,
        acceptBacklog = 8,
        handshakeTimeout = 5.seconds,
        idleTimeout = 10.seconds,
        pingInterval = 30.seconds,
        outboundQueueCapacity = 8,
        replayBufferSize = 8,
        maxFrameLength = 512
      )
    )
    assert(failure.getMessage.contains("ping-interval"), failure.getMessage)

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
      userAccessToken = Sensitive.Empty,
      chatAccessToken = Sensitive.Empty,
      eventSub = EventSubConfig(EventSubTransport.WebSocket, "", Sensitive.Empty),
      pollInterval = 30.seconds,
      simulation = SimulationConfig(10.seconds, 2.seconds)
    )
