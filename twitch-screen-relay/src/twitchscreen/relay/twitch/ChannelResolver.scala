package twitchscreen.relay.twitch

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import java.io.ByteArrayOutputStream
import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.{CompletableFuture, CompletionStage, Flow, TimeUnit}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import twitchscreen.relay.config.TwitchConfig

/** One three-second budget includes app-token acquisition, Helix lookup and bounded response decoding. No user grant is exchanged or
  * modified, redirects are disabled and each response is capped before accumulating bytes. Provider errors never expose response content.
  */
private[relay] final class ChannelResolver private[relay] (
    config: TwitchConfig,
    client: HttpClient,
    tokenEndpoint: URI = URI.create("https://id.twitch.tv/oauth2/token"),
    usersEndpoint: String = "https://api.twitch.tv/helix/users"
):
  def resolve(login: String): Either[String, ResolvedChannel_OUT] =
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    try
      val form = List("client_id" -> config.clientId, "client_secret" -> config.clientSecret.value, "grant_type" -> "client_credentials")
        .map((key, value) => s"$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}")
        .mkString("&")
      val tokenRequest = HttpRequest
        .newBuilder(tokenEndpoint)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build()
      val token = readFromArray[ChannelResolver.AppToken](exchange(tokenRequest, deadline)).accessToken
      require(token.nonEmpty, "missing application token")
      val usersRequest = HttpRequest
        .newBuilder(URI.create(s"$usersEndpoint?login=$login"))
        .header("Client-Id", config.clientId)
        .header("Authorization", s"Bearer $token")
        .GET()
        .build()
      val users = readFromArray[ChannelResolver.Users](exchange(usersRequest, deadline)).data
      users
        .find(_.login == login)
        .filter(user => user.id.matches("[0-9]{1,32}") && user.displayName.nonEmpty && user.displayName.length <= 128)
        .map(user => ResolvedChannel_OUT(user.id, user.login, user.displayName))
        .toRight("channel_not_found")
    catch case NonFatal(_) => Left("channel_lookup_failed")

  private def exchange(request: HttpRequest, deadline: Long): Array[Byte] =
    val pending = client.sendAsync(request, _ => ChannelResolver.BoundedBody())
    try
      val remaining = deadline - System.nanoTime()
      require(remaining > 0, "lookup deadline")
      val response = pending.get(remaining, TimeUnit.NANOSECONDS)
      require(response.statusCode() == 200, "provider rejected lookup")
      response.body()
    finally
      pending.cancel(true)
      ()

private[relay] object ChannelResolver:
  def client(): HttpClient = HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofSeconds(1))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()

  private[twitch] final case class AppToken(accessToken: String)
  private[twitch] object AppToken:
    given JsonValueCodec[AppToken] = JsonCodecMaker.make(CodecMakerConfig.withFieldNameMapper(JsonCodecMaker.enforce_snake_case))
  private[twitch] final case class User(id: String, login: String, displayName: String)
  private[twitch] final case class Users(data: List[User])
  private[twitch] object Users:
    given JsonValueCodec[Users] = JsonCodecMaker.make(CodecMakerConfig.withFieldNameMapper(JsonCodecMaker.enforce_snake_case))

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
        result.completeExceptionally(IllegalStateException("provider response too large"))
        ()
      else
        buffers.asScala.foreach: buffer =>
          val chunk = new Array[Byte](buffer.remaining())
          buffer.get(chunk)
          bytes.write(chunk)
        subscription.request(1)
    override def onError(error: Throwable): Unit =
      result.completeExceptionally(error)
      ()
    override def onComplete(): Unit =
      result.complete(bytes.toByteArray)
      ()
