package twitchscreen.relay.weather

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.ByteBuffer
import java.time.{Duration, Instant}
import java.time.format.DateTimeFormatterBuilder
import java.util.concurrent.{CompletableFuture, CompletionStage, Flow, TimeUnit}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import twitchscreen.relay.config.WeatherConfig
import twitchscreen.relay.device.Weather_IN

/** Production always uses the fixed HTTPS origin and refuses every redirect. A package-private socket seam is for tests only. */
private[relay] final class OpenMeteo private[weather] (config: WeatherConfig, client: HttpClient, endpoint: URI):
  def fetch(): Either[String, Weather_IN] =
    val request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(config.requestTimeout.toNanos)).GET().build()
    val pending = client.sendAsync(request, _ => OpenMeteo.BoundedBody())
    try
      val response = pending.get(config.requestTimeout.toNanos, TimeUnit.NANOSECONDS)
      if response.statusCode() != 200 then Left("weather_provider_status")
      else OpenMeteo.parse(response.body(), config)
    catch case NonFatal(_) => Left("weather_provider_unavailable")
    finally
      pending.cancel(true)
      ()

private[relay] object OpenMeteo:
  def client(config: WeatherConfig): HttpClient = HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofNanos(config.requestTimeout.toNanos))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()

  def apply(config: WeatherConfig, client: HttpClient): OpenMeteo =
    require(client.followRedirects() == HttpClient.Redirect.NEVER, "weather client must refuse redirects")
    new OpenMeteo(
      config,
      client,
      URI.create(
        s"https://api.open-meteo.com/v1/forecast?latitude=${config.latitude}&longitude=${config.longitude}" +
          s"&current=temperature_2m,relative_humidity_2m,weather_code&temperature_unit=${config.unit}&timeformat=unixtime&timezone=UTC"
      )
    )

  private final case class Current(time: Long, temperature_2m: Double, relative_humidity_2m: Long, weather_code: Int)
  private final case class Units(time: String, temperature_2m: String, relative_humidity_2m: String, weather_code: String)
  private final case class Forecast(current: Current, current_units: Units)
  private given JsonValueCodec[Forecast] = JsonCodecMaker.make(CodecMakerConfig.withCheckFieldDuplication(true))
  private val timestamp = DateTimeFormatterBuilder().appendInstant(3).toFormatter

  def parse(bytes: Array[Byte], config: WeatherConfig): Either[String, Weather_IN] =
    try
      require(bytes.length <= 65536, "response too large")
      val forecast = readFromArray[Forecast](bytes)
      val units = forecast.current_units
      require(units.time == "unixtime" && units.relative_humidity_2m == "%" && units.weather_code == "wmo code")
      require(units.temperature_2m == (if config.unit == "celsius" then "°C" else "°F"))
      val current = forecast.current
      require(current.time >= 0 && current.time <= 253402300799L, "invalid observation date")
      val mapped = condition(current.weather_code).getOrElse(throw IllegalArgumentException("invalid weather code"))
      Right(
        Weather_IN(
          "open-meteo",
          config.location,
          current.temperature_2m,
          mapped,
          config.unit,
          Some(current.relative_humidity_2m),
          Some(timestamp.format(Instant.ofEpochSecond(current.time))),
          config.freshness.toMillis
        )
      )
    catch case NonFatal(_) => Left("weather_provider_invalid")

  def condition(code: Int): Option[String] = code match
    case 0                                     => Some("clear")
    case 1 | 2                                 => Some("partly_cloudy")
    case 3                                     => Some("cloudy")
    case 45 | 48                               => Some("fog")
    case 51 | 53 | 55 | 56 | 57                => Some("drizzle")
    case 61 | 63 | 65 | 66 | 67 | 80 | 81 | 82 => Some("rain")
    case 71 | 73 | 75 | 77 | 85 | 86           => Some("snow")
    case 95 | 96 | 97 | 99                     => Some("thunderstorm")
    case _                                     => None

  private final class BoundedBody extends HttpResponse.BodySubscriber[Array[Byte]]:
    private val result = CompletableFuture[Array[Byte]]()
    private val bytes = ByteArrayOutputStream()
    private var subscription: Flow.Subscription = null
    override def getBody(): CompletionStage[Array[Byte]] = result
    override def onSubscribe(value: Flow.Subscription): Unit =
      subscription = value
      value.request(1)
    override def onNext(buffers: java.util.List[ByteBuffer]): Unit =
      val incoming = buffers.asScala.foldLeft(0L)((total, buffer) => total + buffer.remaining())
      if incoming > 65536L - bytes.size() then
        subscription.cancel()
        val _ = result.completeExceptionally(IllegalStateException("weather response too large"))
      else
        buffers.asScala.foreach: buffer =>
          val chunk = new Array[Byte](buffer.remaining())
          val _ = buffer.get(chunk)
          bytes.write(chunk)
        subscription.request(1)
    override def onError(error: Throwable): Unit =
      val _ = result.completeExceptionally(error)
    override def onComplete(): Unit =
      val _ = result.complete(bytes.toByteArray)
