package twitchscreen.relay.config

import pureconfig.ConfigReader
import scala.concurrent.duration.*
import twitchscreen.relay.device.Weather_IN

/** Optional application-owned source. No configurable URL: deployment cannot redirect provider traffic. */
final case class WeatherConfig(
    enabled: Boolean = false,
    latitude: Double = 50.45466,
    longitude: Double = 30.5238,
    location: String = "Kyiv",
    unit: String = "celsius",
    refreshInterval: FiniteDuration = 5.minutes,
    freshness: FiniteDuration = 15.minutes,
    requestTimeout: FiniteDuration = 10.seconds
):
  require(latitude.isFinite && latitude >= -90 && latitude <= 90, "weather.latitude must be finite in -90..90")
  require(longitude.isFinite && longitude >= -180 && longitude <= 180, "weather.longitude must be finite in -180..180")
  require(refreshInterval >= 30.seconds && refreshInterval <= 24.hours, "weather.refresh-interval must be 30 seconds..24 hours")
  require(freshness >= 1.second && freshness <= 30.minutes, "weather.freshness must be 1 second..30 minutes")
  require(requestTimeout >= 100.millis && requestTimeout <= 60.seconds, "weather.request-timeout must be 100 ms..60 seconds")
  require(requestTimeout < refreshInterval, "weather.request-timeout must be shorter than refresh-interval")
  require(freshness.toNanos % 1000000L == 0, "weather.freshness must be whole milliseconds")
  private val validated = Weather_IN("open-meteo", location, 0, "unknown", unit, freshnessMs = freshness.toMillis)
  require(validated.location == location)

object WeatherConfig:
  given ConfigReader[WeatherConfig] = ValidatedConfigReader.derivedValidated[WeatherConfig]
