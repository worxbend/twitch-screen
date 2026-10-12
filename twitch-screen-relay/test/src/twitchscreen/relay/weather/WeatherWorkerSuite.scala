package twitchscreen.relay.weather

import com.sun.net.httpserver.HttpServer
import java.net.{InetSocketAddress, URI}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import ox.supervised
import scala.concurrent.duration.*
import twitchscreen.relay.config.{Config, WeatherConfig}
import twitchscreen.relay.device.{DeviceHub, Weather_IN}
import twitchscreen.relay.bus.EventBus
import java.time.Clock
import pureconfig.ConfigSource

class WeatherWorkerSuite extends munit.FunSuite:
  private val good =
    """{"current_units":{"time":"unixtime","temperature_2m":"°C","relative_humidity_2m":"%","weather_code":"wmo code"},"current":{"time":1791804600,"temperature_2m":11,"relative_humidity_2m":79,"weather_code":61}}"""

  private def socketTest(run: (OpenMeteo, AtomicReference[(Int, String)], AtomicInteger) => Unit): Unit =
    val response = AtomicReference((200, good))
    val requests = AtomicInteger()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val _ = server.createContext(
      "/weather",
      exchange =>
        val _ = requests.incrementAndGet()
        val (code, text) = response.get()
        val bytes = text.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.set("Location", "/escaped")
        exchange.sendResponseHeaders(code, bytes.length.toLong)
        try exchange.getResponseBody.write(bytes)
        finally exchange.close()
    )
    server.start()
    val config = WeatherConfig(enabled = true)
    val client = OpenMeteo.client(config)
    try run(new OpenMeteo(config, client, URI.create(s"http://127.0.0.1:${server.getAddress.getPort}/weather")), response, requests)
    finally
      client.close()
      server.stop(0)

  test("real HTTP failures, malformed bodies, size cap and redirect never overwrite last good hub observation"):
    socketTest: (source, response, requests) =>
      supervised:
        val clock = Clock.systemUTC()
        val config = ConfigSource.default.loadOrThrow[Config]
        val hub = DeviceHub.start(config.deviceLink, config.notifications.chat, clock, EventBus(clock, 64))
        val accept: Weather_IN => Unit = value => { val _ = hub.acceptWeather(value) }
        WeatherWorker.attempt(() => source.fetch(), accept)
        val first = hub.publicationSnapshot
        assertEquals(first.weather.flatMap(_.data).map(_.sourceId), Some("open-meteo"))
        List((503, "outage"), (200, "{}"), (302, good), (200, "x" * 65537)).foreach: failed =>
          response.set(failed)
          WeatherWorker.attempt(() => source.fetch(), accept)
          val current = hub.publicationSnapshot
          assertEquals(current.weather.map(_.revision), first.weather.map(_.revision))
          assertEquals(current.weather.flatMap(_.data), first.weather.flatMap(_.data))
          assertEquals(current.weatherDeadlineRelayMs, first.weatherDeadlineRelayMs)
        response.set((200, good.replace("11", "12")))
        WeatherWorker.attempt(() => source.fetch(), accept)
        assertEquals(hub.weatherSnapshot.data.map(_.temperature), Some(12.0))
        assertEquals(requests.get(), 6)

  test("disabled source is not acquired and does not call provider"):
    supervised:
      WeatherWorker.startWith(WeatherConfig(), () => { fail("disabled client acquired") }, _ => fail("disabled ingress"))

  test("enabled source fetches immediately and its scope cancels the cadence without another request"):
    val calls = AtomicInteger()
    val accepted = CountDownLatch(1)
    supervised:
      WeatherWorker.startWith(
        WeatherConfig(enabled = true),
        () =>
          () =>
            val _ = calls.incrementAndGet()
            Right(Weather_IN("open-meteo", "Kyiv", 10, "clear"))
        ,
        _ => accepted.countDown()
      )
      assert(accepted.await(3, TimeUnit.SECONDS))
    assertEquals(calls.get(), 1)

  private def stalled(run: (OpenMeteo, CountDownLatch) => Unit): Unit =
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val _ = server.createContext(
      "/weather",
      exchange =>
        try
          exchange.sendResponseHeaders(200, 0L)
          exchange.getResponseBody.write('{'.toInt)
          exchange.getResponseBody.flush()
          entered.countDown()
          val _ = release.await(5, TimeUnit.SECONDS)
        finally exchange.close()
    )
    server.start()
    val config = WeatherConfig(enabled = true, requestTimeout = 500.millis)
    val client = OpenMeteo.client(config)
    try run(new OpenMeteo(config, client, URI.create(s"http://127.0.0.1:${server.getAddress.getPort}/weather")), entered)
    finally
      release.countDown()
      client.close()
      server.stop(0)

  test("request budget includes a stalled response body"):
    stalled: (source, entered) =>
      assertEquals(source.fetch(), Left("weather_provider_unavailable"))
      assert(entered.await(2, TimeUnit.SECONDS))

  test("scope cancellation interrupts an in-flight local HTTP request without admission"):
    stalled: (source, entered) =>
      val returned = CountDownLatch(1)
      supervised:
        WeatherWorker.startWith(
          WeatherConfig(enabled = true),
          () =>
            () =>
              try source.fetch()
              finally returned.countDown(),
          _ => fail("stalled source published")
        )
        assert(entered.await(2, TimeUnit.SECONDS))
      assert(returned.await(2, TimeUnit.SECONDS))

  test("provider exceptions are isolated and do not publish an observation"):
    WeatherWorker.attempt(() => throw java.io.IOException("synthetic"), _ => fail("failed source published"))
