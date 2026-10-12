package twitchscreen.relay.config

import com.typesafe.config.ConfigFactory
import pureconfig.ConfigSource
import scala.concurrent.duration.*

class WeatherConfigSuite extends munit.FunSuite:
  test("shipped optional weather defaults are disabled and Kyiv with bounded current conditions cadence"):
    val config = ConfigSource.default.loadOrThrow[Config]
    assertEquals(config.weather, WeatherConfig())
    assert(!config.weather.enabled)
    assertEquals(config.weather.refreshInterval, 5.minutes)
    assertEquals(config.weather.freshness, 15.minutes)
    assertEquals(config.weather.requestTimeout, 10.seconds)
    assert(Config.SchemaPaths.contains("weather.enabled"))
    assert(Config.SchemaPaths.contains("weather.request-timeout"))

  test("HOCON configuration fails strictly for invalid coordinates, booleans, units and durations even when disabled"):
    List(
      "latitude = NaN",
      "latitude = 91",
      "longitude = -181",
      "enabled = sometimes",
      "location = \"\"",
      "unit = kelvin",
      "refresh-interval = 0 seconds",
      "freshness = 31 minutes",
      "freshness = 1001 microseconds",
      "request-timeout = 0 seconds",
      "request-timeout = 61 seconds"
    )
      .foreach: invalid =>
        val source = ConfigFactory.parseString(s"weather { $invalid }").withFallback(ConfigFactory.load())
        assert(ConfigSource.fromConfig(source).load[Config].isLeft, invalid)

  test("fahrenheit and independent enabled weather load without Twitch live credentials"):
    val source = ConfigFactory.parseString("""weather { enabled = true, unit = fahrenheit }""").withFallback(ConfigFactory.load())
    val config = ConfigSource.fromConfig(source).loadOrThrow[Config]
    assert(config.weather.enabled)
    assertEquals(config.weather.unit, "fahrenheit")
