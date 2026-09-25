package twitchscreen.relay.twitch

import ch.qos.logback.classic.{Level, Logger as LogbackLogger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.github.twitch4j.helix.TwitchHelix
import com.github.twitch4j.helix.domain.{EventSubSubscriptionList, UserList}
import com.netflix.hystrix.{HystrixCommand, HystrixCommandGroupKey, HystrixCommandProperties}
import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import ox.{discard, fork, supervised}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
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

  /** The breaker is disabled so scripted failures in one test can never short-circuit a later test's command and hide its cause. Production
    * keeps twitch4j's per-method breakers, which these call rates (one poll and one reconciliation pass per 30 seconds) cannot trip.
    */
  private val commandSetup = HystrixCommand.Setter
    .withGroupKey(HystrixCommandGroupKey.Factory.asKey("relay-test"))
    .andCommandPropertiesDefaults(HystrixCommandProperties.Setter().withCircuitBreakerEnabled(false))

  private def command[A](body: => A): HystrixCommand[A] = new HystrixCommand[A](commandSetup):
    override def run(): A = body

  /** Runs `body` and returns the INFO "recovered" lines the health actor logged. The level is forced to INFO so a WARN-only environment
    * cannot make the negative cases pass vacuously; `observe` is an actor `ask`, so every log call has finished when `body` returns.
    */
  private def recoveredLines(body: => Unit): List[String] =
    val logger = LoggerFactory.getLogger(classOf[TwitchHealthState]).asInstanceOf[LogbackLogger]
    val level = logger.getLevel
    val appender = ListAppender[ILoggingEvent]()
    logger.setLevel(Level.INFO)
    appender.start()
    logger.addAppender(appender)
    try body
    finally
      logger.detachAppender(appender).discard
      appender.stop()
      logger.setLevel(level)
    appender.list.asScala.toList
      .filter(_.getLevel == Level.INFO)
      .map(_.getFormattedMessage)
      .filter(_.endsWith("recovered"))

  private def helix(call: (String, Array[Object]) => Object): TwitchHelix =
    val handler = new InvocationHandler:
      override def invoke(proxy: Object, method: Method, arguments: Array[Object]): Object = call(method.getName, arguments)
    Proxy.newProxyInstance(classOf[TwitchHelix].getClassLoader, Array(classOf[TwitchHelix]), handler).asInstanceOf[TwitchHelix]

  /** Twitch4J's own decoder applied to a real 401 response, i.e. exactly what a rejected token raises inside a Helix command. */
  private def unauthorizedCause(path: String): Throwable =
    val request = feign.Request.create(
      feign.Request.HttpMethod.GET,
      s"https://api.twitch.tv/helix/$path",
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
    com.github.twitch4j.helix.TwitchHelixErrorDecoder(null, null).decode(path, response)

  private val emptySubscriptions = """{"data":[]}"""

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
      TwitchRetry.untilReady(
        () => LiveTwitchSource.resolveBroadcasterId(client, config),
        _ => (),
        duration => delays = delays :+ duration,
        identity
      )
    assertEquals(id, "123")
    assertEquals(calls, 3)
    assertEquals(delays, List(1.second, 2.seconds))

  test("webhook registration failure is observable and the next reconciliation recovers using app credentials"):
    val rejected = AtomicBoolean(false)
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
    EventSubWebhookApi.reconcileSubscriptions(
      client,
      config,
      subscription,
      (_, result) => results = results :+ result,
      () => rejected.set(true)
    )
    EventSubWebhookApi.reconcileSubscriptions(
      client,
      config,
      subscription,
      (_, result) => results = results :+ result,
      () => rejected.set(true)
    )
    assert(results.head.isDefined)
    assertEquals(results.last, None)
    assert(!rejected.get(), "a failure unrelated to authorization must not rebuild the client")

  test("webhook reconciliation requests a client rebuild when Twitch rejects the application token"):
    val rejected = AtomicBoolean(false)
    var results = List.empty[(String, Option[String])]
    val client = helix: (method, arguments) =>
      assertEquals(arguments(0), null, "webhook Helix calls use the app-token fallback")
      method match
        case "getEventSubSubscriptions"   => command(mapper.readValue(emptySubscriptions, classOf[EventSubSubscriptionList]))
        case "createEventSubSubscription" => command[EventSubSubscriptionList](throw unauthorizedCause("eventsub/subscriptions"))
        case other                        => fail(s"unexpected method $other")
    EventSubWebhookApi.reconcileSubscriptions(
      client,
      config,
      EventSubWebhookApi.unscopedSubscriptions("123").take(1),
      (kind, result) => results = results :+ (kind -> result),
      () => rejected.set(true)
    )
    assert(rejected.get())
    assertEquals(results.map(_._1), List("stream.online"))
    assert(results.head._2.exists(_.startsWith("registration failed")))

  test("listing subscriptions with a rejected application token requests a rebuild before any creation"):
    val rejected = AtomicBoolean(false)
    var creations = 0
    var results = List.empty[Option[String]]
    val client = helix: (method, _) =>
      method match
        case "getEventSubSubscriptions" => command[EventSubSubscriptionList](throw unauthorizedCause("eventsub/subscriptions"))
        case "createEventSubSubscription" =>
          creations += 1
          command(mapper.readValue(emptySubscriptions, classOf[EventSubSubscriptionList]))
        case other => fail(s"unexpected method $other")
    EventSubWebhookApi.reconcileSubscriptions(
      client,
      config,
      EventSubWebhookApi.unscopedSubscriptions("123"),
      (_, result) => results = results :+ result,
      () => rejected.set(true)
    )
    assert(rejected.get())
    assertEquals(creations, 0)
    assertEquals(results.size, EventSubWebhookApi.unscopedSubscriptions("123").size, "every kind is still observed as failed")
    assert(results.forall(_.isDefined))

  private def streamsFailing(error: => Throwable): TwitchHelix = helix: (method, arguments) =>
    method match
      case "getStreams" =>
        assertEquals(arguments(0), null, "streams are polled with the app token")
        command[com.github.twitch4j.helix.domain.StreamList](throw error)
      case "getChannelFollowers" =>
        command(mapper.readValue("""{"data":[],"total":7}""", classOf[com.github.twitch4j.helix.domain.InboundFollowers]))
      case "getSubscriptions" =>
        command(mapper.readValue("""{"data":[],"total":3}""", classOf[com.github.twitch4j.helix.domain.SubscriptionList]))
      case other => fail(s"unexpected method $other")

  test("a streams poll with a rejected application token requests a rebuild without rejecting the user grant"):
    supervised:
      val bus = EventBus(Clock.systemUTC(), 64)
      val health = TwitchRuntimeHealth(config, bus)
      val appRejected = AtomicBoolean(false)
      val userRejected = AtomicBoolean(false)
      HelixPoller.poll(
        streamsFailing(unauthorizedCause("streams")),
        PollingContext(config, "123", ChannelStateTracker(config.channel, Clock.systemUTC()), bus, Clock.systemUTC(), health),
        new TokenProvider:
          override def tokenFor(scope: String): Option[String] = Some("user-token")
          override def reject(token: String): Unit = userRejected.set(true)
        ,
        () => appRejected.set(true)
      )
      assert(appRejected.get())
      assert(!userRejected.get(), "an application-token 401 must never withhold the broadcaster grant")
      assert(health.failure(HealthComponent.Streams).isDefined)
      assertEquals(health.failure(HealthComponent.Followers), None)
      assertEquals(health.status.health, TwitchHealth.Degraded)

  test("a user-token rejection withholds the grant without rebuilding the client"):
    supervised:
      val bus = EventBus(Clock.systemUTC(), 64)
      val health = TwitchRuntimeHealth(config, bus)
      val appRejected = AtomicBoolean(false)
      val userRejected = AtomicBoolean(false)
      val client = helix: (method, _) =>
        method match
          case "getStreams"          => command(mapper.readValue("""{"data":[]}""", classOf[com.github.twitch4j.helix.domain.StreamList]))
          case "getChannelFollowers" => command[Unit](throw unauthorizedCause("channels/followers"))
          case "getSubscriptions"    => command[Unit](throw unauthorizedCause("subscriptions"))
          case other                 => fail(s"unexpected method $other")
      HelixPoller.poll(
        client,
        PollingContext(config, "123", ChannelStateTracker(config.channel, Clock.systemUTC()), bus, Clock.systemUTC(), health),
        new TokenProvider:
          override def tokenFor(scope: String): Option[String] = Some("user-token")
          override def reject(token: String): Unit = userRejected.set(true)
        ,
        () => appRejected.set(true)
      )
      assert(userRejected.get())
      assert(!appRejected.get(), "a user-token 401 must never rebuild the client")
      assertEquals(health.failure(HealthComponent.Streams), None)

  test("a streams failure unrelated to authorization does not request a rebuild"):
    supervised:
      val bus = EventBus(Clock.systemUTC(), 64)
      val health = TwitchRuntimeHealth(config, bus)
      val appRejected = AtomicBoolean(false)
      HelixPoller.pollStream(
        streamsFailing(IllegalStateException("network timeout")),
        config,
        ChannelStateTracker(config.channel, Clock.systemUTC()),
        bus,
        Clock.systemUTC(),
        health,
        () => appRejected.set(true)
      )
      assert(!appRejected.get())
      assert(health.failure(HealthComponent.Streams).isDefined)

  test("an application-token rejection sets the session restart flag and reports why"):
    supervised:
      val health = TwitchRuntimeHealth(config, EventBus(Clock.systemUTC(), 64))
      val restart = AtomicBoolean(false)
      val rejected = LiveTwitchSource.appTokenRejected(health, restart)
      rejected()
      rejected()
      assert(restart.get())
      assert(health.failure(HealthComponent.Startup).exists(_.contains("application token rejected")))

  test("a session ended by an application-token rejection is closed and a fresh client is built"):
    var restartRequests = List.empty[Boolean]
    var builds = 0
    var closed = List.empty[Int]
    var pauses = List.empty[FiniteDuration]
    val stopped =
      try
        supervised:
          val health = TwitchRuntimeHealth(config, EventBus(Clock.systemUTC(), 64))
          LiveTwitchSource.superviseSessions[Int](
            () => { builds += 1; Right(builds) },
            client => closed = closed :+ client,
            _ => (),
            duration => pauses = pauses :+ duration
          ): client =>
            if client == 2 then throw InterruptedException("stop after observing the rebuild")
            val restart = AtomicBoolean(false)
            HelixPoller.pollStream(
              streamsFailing(unauthorizedCause("streams")),
              config,
              ChannelStateTracker(config.channel, Clock.systemUTC()),
              EventBus(Clock.systemUTC(), 64),
              Clock.systemUTC(),
              health,
              LiveTwitchSource.appTokenRejected(health, restart)
            )
            // What maintainSubscriptions does with the flag: the session returns so its scope closes the client.
            restartRequests = restartRequests :+ restart.get()
        false
      catch case _: InterruptedException => true
    assert(stopped)
    assertEquals(restartRequests, List(true), "the rejected poll must request the rebuild")
    assertEquals(builds, 2)
    assertEquals(closed, List(1, 2))
    assertEquals(pauses, List(1.second))

  test("a rejected application token during broadcaster lookup fails the session and rebuilds after a bounded pause"):
    val rejecting = helix: (method, _) =>
      assertEquals(method, "getUsers")
      command[UserList](throw unauthorizedCause("users"))
    val rejection = intercept[ApplicationTokenRejected](LiveTwitchSource.resolveBroadcasterId(rejecting, config))
    assertEquals(rejection.getCause, null, "no provider detail travels with the rejection")
    var builds = 0
    var startup = List.empty[String]
    var pauses = List.empty[FiniteDuration]
    val stopped =
      try
        supervised:
          LiveTwitchSource.superviseSessions[Int](
            () => { builds += 1; Right(builds) },
            _ => (),
            reason => startup = startup :+ reason,
            duration => pauses = pauses :+ duration
          ): client =>
            if client == 2 then throw InterruptedException("stop after observing the rebuild")
            TwitchRetry
              .untilReady(() => LiveTwitchSource.resolveBroadcasterId(rejecting, config), _ => (), _ => fail("401 must not retry"))
              .discard
        false
      catch case _: InterruptedException => true
    assert(stopped)
    assertEquals(builds, 2)
    assertEquals(pauses, List(LiveTwitchSource.SessionFailurePause))
    assert(LiveTwitchSource.SessionFailurePause >= 30.seconds)
    assertEquals(startup, List("session failed (ApplicationTokenRejected); rebuilding client"))

  test("concurrent health changes publish ordered link transitions and repeated failures do not flood the bus"):
    supervised:
      val bus = EventBus(Clock.systemUTC(), 256)
      val events = bus.subscribe("health-test")
      val health = TwitchRuntimeHealth(config, bus)
      health.observe(HealthComponent.Startup, None)
      (1 to 20).map(index => fork(health.observe(HealthComponent.Poll, if index % 2 == 0 then None else Some("outage")))).foreach(_.join())
      health.observe(HealthComponent.Poll, None)
      val observed = Iterator.continually(events.tryReceive()).takeWhile(_.isDefined).flatten.map(_.event).toList
      assertEquals(health.status.health, TwitchHealth.Connected)
      assert(
        observed
          .collect { case e: RelayEvent.TwitchLinkUp => e; case e: RelayEvent.TwitchLinkDown => e }
          .last
          .isInstanceOf[RelayEvent.TwitchLinkUp]
      )
      health.observe(HealthComponent.Poll, Some("same failure"))
      health.observe(HealthComponent.Poll, Some("same failure"))
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
          ,
          identity
        )
        false
      catch case _: InterruptedException => true
    assert(cancelled)
    assertEquals(pauses.takeRight(3), List.fill(3)(60.seconds))

  test("Twitch4J unauthorized responses remain recognizable through Hystrix wrapping"):
    val cause = unauthorizedCause("channels/followers")
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
      assert(health.failure(HealthComponent.EventSubFollow).isDefined)
      assertEquals(health.failure(HealthComponent.EventSubOnline), None)

  test("resetting session health records unknown state without publishing synthetic failures"):
    supervised:
      val bus = EventBus(Clock.systemUTC(), 16)
      val health = TwitchRuntimeHealth(config, bus)
      health.observe(HealthComponent.Startup, None)
      val events = bus.subscribe("reset")
      health.resetSession()
      assertEquals(health.status.health, TwitchHealth.Connecting)
      assert(events.tryReceive().isEmpty)
      List(HealthComponent.Startup, HealthComponent.Authorization, HealthComponent.Chat, HealthComponent.Streams).foreach(
        health.observe(_, None)
      )
      assertEquals(health.status.health, TwitchHealth.Connecting)
      assert(events.tryReceive().isEmpty)
      List(HealthComponent.EventSubOnline, HealthComponent.EventSubOffline, HealthComponent.EventSubUpdate).foreach(health.observe(_, None))
      assertEquals(events.receive().event, RelayEvent.TwitchLinkUp("channel channel"))
      assert(events.tryReceive().isEmpty)

  test("health does not log 'recovered' for a component's first observation"):
    supervised:
      val health = TwitchRuntimeHealth(config, EventBus(Clock.systemUTC(), 64))
      assertEquals(recoveredLines(health.observe(HealthComponent.Startup, None)), Nil)

  test("health does not log 'recovered' for observations after a session reset"):
    supervised:
      val health = TwitchRuntimeHealth(config, EventBus(Clock.systemUTC(), 64))
      val lines = recoveredLines:
        health.observe(HealthComponent.Startup, None)
        health.resetSession()
        health.observe(HealthComponent.Streams, None)
        health.observe(HealthComponent.Startup, None)
      assertEquals(lines, Nil)

  test("health logs 'recovered' once when a failed component becomes healthy"):
    supervised:
      val health = TwitchRuntimeHealth(config, EventBus(Clock.systemUTC(), 64))
      val lines = recoveredLines:
        health.observe(HealthComponent.Streams, Some("x"))
        health.observe(HealthComponent.Streams, None)
        health.observe(HealthComponent.Streams, None)
      assertEquals(lines, List(s"Twitch ${HealthComponent.Streams.label} recovered"))

  test("health does not log 'recovered' when a failed component went back to awaiting first"):
    supervised:
      val health = TwitchRuntimeHealth(config, EventBus(Clock.systemUTC(), 64))
      val lines = recoveredLines:
        health.observe(HealthComponent.Streams, Some("x"))
        health.awaiting(HealthComponent.Streams)
        health.observe(HealthComponent.Streams, None)
      assertEquals(lines, Nil)

  test("link-down cards include a sanitized failure reason"):
    supervised:
      val bus = EventBus(Clock.systemUTC(), 16)
      val health = TwitchRuntimeHealth(config, bus)
      health.observe(HealthComponent.Startup, None)
      val events = bus.subscribe("reason")
      health.observe(HealthComponent.Streams, Some("HTTP\nBearer top-secret"))
      events.receive().discard
      val event = events.receive().event
      assertEquals(event, RelayEvent.TwitchLinkDown("streams: HTTP Bearer [redacted]"))

  test("reconciliation reads later pages and deletes old callbacks and duplicates while preserving unrelated broadcasters"):
    var cursors = List.empty[Option[String]]
    var deleted = List.empty[String]
    var creations = 0
    val old = "https://old-tunnel.example/api/v1/twitch/eventsub"
    def subscription(id: String, broadcaster: String, callback: String): String =
      s"""{"id":"$id","status":"enabled","type":"stream.online","version":"1","condition":{"broadcaster_user_id":"$broadcaster"},"transport":{"method":"webhook","callback":"$callback"}}"""
    val client = helix: (method, arguments) =>
      method match
        case "getEventSubSubscriptions" =>
          command:
            val cursor = Option(arguments(4)).map(_.toString)
            cursors = cursors :+ cursor
            val body =
              if cursor.isEmpty then
                s"""{"data":[${subscription("stale", "123", old)},${subscription("other", "456", old)}],"pagination":{"cursor":"page-2"}}"""
              else
                s"""{"data":[${subscription("keep", "123", config.eventSub.callbackUrl)},${subscription(
                    "duplicate",
                    "123",
                    config.eventSub.callbackUrl
                  )}]}"""
            mapper.readValue(body, classOf[EventSubSubscriptionList])
        case "deleteEventSubSubscription" =>
          command:
            deleted = deleted :+ arguments(1).toString
            null
        case "createEventSubSubscription" =>
          command:
            creations += 1
            mapper.readValue(emptySubscriptions, classOf[EventSubSubscriptionList])
        case other => fail(s"unexpected method $other")
    EventSubWebhookApi.reconcileSubscriptions(
      client,
      config,
      EventSubWebhookApi.unscopedSubscriptions("123").take(1),
      (_, failure) => assertEquals(failure, None),
      () => ()
    )
    assertEquals(cursors, List(None, Some("page-2")))
    assertEquals(deleted, List("stale", "duplicate"))
    assertEquals(creations, 0)

  test("retry jitter stays within half to full capped delay"):
    def delays(jitter: Long => Long): List[FiniteDuration] =
      var remaining = 10
      var seen = List.empty[FiniteDuration]
      TwitchRetry.untilReady(
        () =>
          remaining -= 1
          if remaining == 0 then Right(()) else Left("offline")
        ,
        _ => (),
        pause => seen = seen :+ pause,
        jitter
      )
      seen
    assertEquals(delays(_ => 0).takeRight(2), List(30.seconds, 30.seconds))
    assertEquals(delays(identity).takeRight(2), List(60.seconds, 60.seconds))

  test("provider exception text cannot cross the Twitch call boundary"):
    val result = TwitchCall.attempt("refresh token")(throw IllegalArgumentException("Bearer secret-token\nrefresh_token=secret"))
    assertEquals(result.left.map(_.message), Left("could not refresh token (IllegalArgumentException)"))

  test("OAuth HTTP rejection retains status without exposing provider response text"):
    val response = new okhttp3.Response.Builder()
      .request(new okhttp3.Request.Builder().url("https://id.twitch.tv/oauth2/token").build())
      .protocol(okhttp3.Protocol.HTTP_1_1)
      .code(400)
      .message("Bad Request")
      .body(okhttp3.ResponseBody.create("refresh_token=secret", okhttp3.MediaType.get("application/json")))
      .build()
    val result = TwitchCall.attempt("refresh token")(TwitchOAuthClient.checkTokenResponse(response))
    assert(result.left.exists(_.rejectedGrant))
    assert(result.left.exists(error => !error.message.contains("secret")))
