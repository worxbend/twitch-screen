package twitchscreen.relay.twitch

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.github.twitch4j.helix.TwitchHelix
import com.github.twitch4j.helix.domain.{EventSubSubscriptionList, UserList}
import com.netflix.hystrix.{HystrixCommand, HystrixCommandGroupKey}
import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.time.Clock
import ox.{fork, supervised}
import scala.concurrent.duration.*
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.*

class TwitchRecoverySuite extends munit.FunSuite:
  private val config = TwitchConfig(
    TwitchMode.Live,
    "channel",
    "client",
    Sensitive("secret"),
    TwitchOAuthConfig("http://localhost:8080/api/v1/twitch/callback", Nil, "unused.json", 15.minutes),
    EventSubConfig(EventSubTransport.Webhook, "https://relay.example/api/v1/twitch/eventsub", Sensitive("long-enough-secret")),
    30.seconds,
    SimulationConfig(10.seconds, 2.seconds)
  )
  private val mapper = JsonMapper.builder().enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS).build()

  private def command[A](body: => A): HystrixCommand[A] = new HystrixCommand[A](HystrixCommandGroupKey.Factory.asKey("relay-test")):
    override def run(): A = body

  private def helix(call: (String, Array[Object]) => Object): TwitchHelix =
    val handler = new InvocationHandler:
      override def invoke(proxy: Object, method: Method, arguments: Array[Object]): Object = call(method.getName, arguments)
    Proxy.newProxyInstance(classOf[TwitchHelix].getClassLoader, Array(classOf[TwitchHelix]), handler).asInstanceOf[TwitchHelix]

  test("failed broadcaster resolution retries the actual Helix adapter and recovers"):
    var calls = 0
    var delays = List.empty[FiniteDuration]
    val client = helix: (method, _) =>
      assertEquals(method, "getUsers")
      command:
        calls += 1
        if calls < 3 then throw IllegalStateException("temporarily unavailable")
        mapper.readValue("""{"data":[{"id":"123","login":"channel"}]}""", classOf[UserList])
    val id =
      TwitchRetry.untilReady(() => LiveTwitchSource.resolveBroadcasterId(client, config), _ => (), duration => delays = delays :+ duration)
    assertEquals(id, "123")
    assertEquals(calls, 3)
    assertEquals(delays, List(1.second, 2.seconds))

  test("webhook registration failure is observable and the next reconciliation recovers using app credentials"):
    var attempts = 0
    var results = List.empty[Option[String]]
    val client = helix: (method, arguments) =>
      assertEquals(arguments(0), null, "webhook Helix calls use the app-token fallback")
      method match
        case "getEventSubSubscriptions" => command(mapper.readValue("""{"data":[]}""", classOf[EventSubSubscriptionList]))
        case "createEventSubSubscription" =>
          command:
            attempts += 1
            if attempts == 1 then throw IllegalStateException("unreachable callback")
            mapper.readValue(
              """{"data":[{"id":"s1","status":"enabled","type":"stream.online","version":"1","condition":{"broadcaster_user_id":"123"},"transport":{"method":"webhook","callback":"https://relay.example/api/v1/twitch/eventsub"}}]}""",
              classOf[EventSubSubscriptionList]
            )
        case other => fail(s"unexpected method $other")
    val subscription = EventSubWebhookApi.unscopedSubscriptions("123").take(1)
    EventSubWebhookApi.reconcileSubscriptions(client, config, subscription, (_, result) => results = results :+ result)
    EventSubWebhookApi.reconcileSubscriptions(client, config, subscription, (_, result) => results = results :+ result)
    assert(results.head.isDefined)
    assertEquals(results.last, None)

  test("concurrent health changes publish ordered link transitions and repeated failures do not flood the bus"):
    supervised:
      val bus = EventBus(Clock.systemUTC(), 256)
      val events = bus.subscribe("health-test")
      val health = TwitchRuntimeHealth(config, bus)
      health.observe("startup", None)
      (1 to 20).map(index => fork(health.observe("poll", if index % 2 == 0 then None else Some("outage")))).foreach(_.join())
      health.observe("poll", None)
      val observed = Iterator.continually(events.tryReceive()).takeWhile(_.isDefined).flatten.map(_.event).toList
      assertEquals(health.status.health, TwitchHealth.Connected)
      assert(
        observed
          .collect { case e: RelayEvent.TwitchLinkUp => e; case e: RelayEvent.TwitchLinkDown => e }
          .last
          .isInstanceOf[RelayEvent.TwitchLinkUp]
      )
      health.observe("poll", Some("same failure"))
      health.observe("poll", Some("same failure"))
      val failures = Iterator.continually(events.tryReceive()).takeWhile(_.isDefined).flatten.map(_.event).toList
      assertEquals(failures.count(_.isInstanceOf[RelayEvent.RelayFailure]), 1)
      assertEquals(health.status.health, TwitchHealth.Degraded)

  test("retry delay is capped and cancellation propagates"):
    var pauses = List.empty[FiniteDuration]
    val cancelled =
      try
        TwitchRetry.untilReady(
          () => Left("unavailable"),
          _ => (),
          duration =>
            pauses = pauses :+ duration
            if pauses.size == 10 then throw InterruptedException("stop")
        )
        false
      catch case _: InterruptedException => true
    assert(cancelled)
    assertEquals(pauses.takeRight(3), List.fill(3)(60.seconds))

  test("Twitch4J unauthorized responses remain recognizable through Hystrix wrapping"):
    val request = feign.Request.create(
      feign.Request.HttpMethod.GET,
      "https://api.twitch.tv/helix/channels/followers",
      java.util.Map.of[String, java.util.Collection[String]](),
      Array.emptyByteArray,
      java.nio.charset.StandardCharsets.UTF_8,
      feign.RequestTemplate()
    )
    val response = feign.Response
      .builder()
      .status(401)
      .reason("Unauthorized")
      .request(request)
      .body("{\"error\":\"Unauthorized\",\"status\":401}", java.nio.charset.StandardCharsets.UTF_8)
      .build()
    val cause = com.github.twitch4j.helix.TwitchHelixErrorDecoder(null, null).decode("followers", response)
    val wrapped =
      try
        command[Unit](throw cause).execute()
        fail("command unexpectedly succeeded")
      catch case error: com.netflix.hystrix.exception.HystrixRuntimeException => error
    assert(HelixPoller.isUnauthorized(wrapped))
    assert(!HelixPoller.isUnauthorized(IllegalStateException("network timeout")))

  test("a partial subscription failure still rebuilds after an unrelated success"):
    supervised:
      val health = TwitchRuntimeHealth(config, EventBus(Clock.systemUTC(), 64))
      val restart = java.util.concurrent.atomic.AtomicBoolean(false)
      LiveTwitchSource.subscriptionFailed(health, restart, "channel.follow")
      LiveTwitchSource.subscriptionSucceeded(health, "stream.online")
      assert(restart.get())
      assert(health.failure("eventsub-channel.follow").isDefined)
      assertEquals(health.failure("eventsub-stream.online"), None)
