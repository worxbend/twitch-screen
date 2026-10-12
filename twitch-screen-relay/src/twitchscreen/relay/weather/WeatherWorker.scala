package twitchscreen.relay.weather

import org.slf4j.LoggerFactory
import ox.{Ox, forkDiscard, sleep, useInScope}
import scala.util.control.NonFatal
import twitchscreen.relay.config.WeatherConfig
import twitchscreen.relay.device.{DeviceHub, Weather_IN}

/** One application source, not one poller per device. Failed requests never re-admit the last observation. */
private[relay] object WeatherWorker:
  private val logger = LoggerFactory.getLogger(getClass)

  def start(config: WeatherConfig, hub: DeviceHub)(using Ox): Unit =
    startWith(
      config,
      () =>
        val client = useInScope(OpenMeteo.client(config))(_.close())
        val source = OpenMeteo(config, client)
        () => source.fetch()
      ,
      value => { val _ = hub.acceptWeather(value) }
    )

  /** Lazy acquisition makes disabled mode allocate no client and issue no request. Sleep is interruptible in the application's Ox scope. */
  private[weather] def startWith(
      config: WeatherConfig,
      acquire: () => (() => Either[String, Weather_IN]),
      accept: Weather_IN => Unit
  )(using Ox): Unit =
    if config.enabled then
      val fetch = acquire()
      forkDiscard:
        while true do
          attempt(fetch, accept)
          sleep(config.refreshInterval)

  private[weather] def attempt(fetch: () => Either[String, Weather_IN], accept: Weather_IN => Unit): Unit =
    try
      fetch() match
        case Right(value) => accept(value)
        case Left(_)      => logger.warn("Weather refresh failed; preserving last observation and its original expiry")
    catch case NonFatal(_) => logger.warn("Weather refresh failed; preserving last observation and its original expiry")
