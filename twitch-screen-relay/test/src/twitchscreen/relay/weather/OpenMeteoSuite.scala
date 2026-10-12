package twitchscreen.relay.weather

import twitchscreen.relay.config.WeatherConfig

class OpenMeteoSuite extends munit.FunSuite:
  private val body =
    """{"current_units":{"temperature_2m":"°C","relative_humidity_2m":"%","weather_code":"wmo code","time":"unixtime"},"current":{"time":1791804600,"temperature_2m":11.0,"relative_humidity_2m":79,"weather_code":61}}"""

  test("current weather becomes actor ingress with producer metadata and receipt freshness"):
    val value = OpenMeteo.parse(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), WeatherConfig()).toOption.get
    assertEquals(value.sourceId, "open-meteo")
    assertEquals(value.location, "Kyiv")
    assertEquals(value.condition, "rain")
    assertEquals(value.humidityPercent, Some(79L))
    assertEquals(value.freshnessMs, 900000L)
    assertEquals(value.observedAt, Some("2026-10-12T11:30:00.000Z"))
    assertEquals(value.deadline, None)

  test("unknown codes, non-finite values, missing fields and bad units fail closed"):
    List(
      body.replace("61", "99.5"),
      body.replace("61", "100"),
      body.replace("11.0", "null"),
      body.replace("79", "101"),
      body.replace("1791804600", "-1"),
      body.replace("°C", "°F"),
      body.replace("11.0", "1e999"),
      body.replace("79", "1.5"),
      body.replace("1791804600", "1791804600.5"),
      body.replace("\"temperature_2m\":11.0,", ""),
      body.replace("\"relative_humidity_2m\":79,", ""),
      body.replace("\"weather_code\":61", "\"weather_code\":61,\"weather_code\":0"),
      "{}"
    )
      .foreach(value => assert(OpenMeteo.parse(value.getBytes(java.nio.charset.StandardCharsets.UTF_8), WeatherConfig()).isLeft))

  test("WMO codes map explicitly, never manufacture clear weather"):
    val groups = Map(
      "clear" -> List(0),
      "partly_cloudy" -> List(1, 2),
      "cloudy" -> List(3),
      "fog" -> List(45, 48),
      "drizzle" -> List(51, 53, 55, 56, 57),
      "rain" -> List(61, 63, 65, 66, 67, 80, 81, 82),
      "snow" -> List(71, 73, 75, 77, 85, 86),
      "thunderstorm" -> List(95, 96, 97, 99)
    )
    groups.foreach((condition, codes) => codes.foreach(code => assertEquals(OpenMeteo.condition(code), Some(condition))))
    assertEquals(OpenMeteo.condition(4), None)

  test("weather configuration rejects unsafe coordinates, units and cadence"):
    List(
      () => WeatherConfig(latitude = Double.NaN),
      () => WeatherConfig(longitude = 181),
      () => WeatherConfig(unit = "Celsius"),
      () => WeatherConfig(location = ""),
      () => WeatherConfig(refreshInterval = scala.concurrent.duration.DurationInt(1).second)
    )
      .foreach(make => { val _ = intercept[IllegalArgumentException](make()) })
