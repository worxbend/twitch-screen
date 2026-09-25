package twitchscreen.relay.http

import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import io.opentelemetry.api.OpenTelemetry
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.time.{Clock, Instant, ZoneOffset}
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import ox.{discard, supervised}
import pureconfig.ConfigSource
import twitchscreen.relay.activity.{ActivityApi, ActivityLog}
import twitchscreen.relay.alerts.{AlertRule, AlertStore, AlertsApi}
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.*
import twitchscreen.relay.device.{DeviceApi, DeviceHub, NotificationApi}
import twitchscreen.relay.health.{HealthApi, StatusApi}
import twitchscreen.relay.observability.LogsApi
import twitchscreen.relay.stats.StatsApi
import twitchscreen.relay.twitch.{BotFilter, EventSubSigning, TwitchSource}

/** Real assembled API groups, including the live-mode callback/auth routes, without starting external Twitch ingestion. */
class ManagementRoutesSuite extends munit.FunSuite:
  ox.logback.InheritableMDC.init
  private val token = "route-test-api-token-with-at-least-32-bytes"
  private val password = "route-test-password"
  private val salt = "test-salt-for-route-fixture".getBytes(UTF_8)
  private val key =
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray, salt, 600000, 256)).getEncoded
  private val hash = s"pbkdf2-sha256$$600000$$${Base64.getEncoder.encodeToString(salt)}$$${Base64.getEncoder.encodeToString(key)}"
  private val basic = "Basic " + Base64.getEncoder.encodeToString(s"operator:$password".getBytes(UTF_8))
  private val secret = "test-eventsub-signing-secret"
  private val now = Instant.parse("2026-09-25T12:00:00Z")
  private val clock = Clock.fixed(now, ZoneOffset.UTC)
  private val routes = List(
    ("GET", "config", "", 200),
    ("GET", "logs", "", 200),
    ("GET", "status", "", 200),
    ("GET", "devices", "", 200),
    ("GET", "devices/123", "", 404),
    ("GET", "notifications", "", 200),
    ("GET", "activity", "", 200),
    ("POST", "activity:export", "", 200),
    ("GET", "alerts", "", 200),
    ("GET", "alertRules", "", 200),
    ("POST", "notifications", """{"type":"info","title":"test","body":"body","ttlMs":1000}""", 200),
    ("POST", "devices/123:disconnect", "", 404),
    ("POST", "alerts/123:acknowledge", "", 404),
    ("GET", "twitch/authorize", "", 302),
    ("GET", "twitch/authorization", "", 200),
    ("DELETE", "twitch/authorization", "", 404)
  )

  private def withServer(configuredToken: String = token)(test: Int => Unit): Unit = supervised:
    val directory = Files.createTempDirectory("relay-route-test")
    try
      val source = ConfigFactory
        .parseString(s"""
        http.auth.api-token = "$configuredToken"
        twitch.mode = live
        twitch.channel = channel
        twitch.client-id = client
        twitch.client-secret = fixture-client-secret
        twitch.event-sub.transport = webhook
        twitch.event-sub.callback-url = "https://relay.example/api/v1/twitch/eventsub"
        twitch.event-sub.secret = "$secret"
      """)
        .withValue("twitch.oauth.token-file", ConfigValueFactory.fromAnyRef(directory.resolve("token.json").toString))
        .withValue("http.auth.basic-username", ConfigValueFactory.fromAnyRef("operator"))
        .withValue("http.auth.basic-password-hash", ConfigValueFactory.fromAnyRef(hash))
        .withFallback(ConfigFactory.load())
      val config = ConfigSource.fromConfig(source).loadOrThrow[Config]
      Config.log(config)
      val bus = EventBus(clock, 256)
      val hub = DeviceHub.start(config.deviceLink, config.notifications.chat, clock, bus)
      val activity = ActivityLog.start(config.activity, bus)
      val alerts = AlertStore(config.alerts.bufferSize)
      val twitch = TwitchSource.start(config.twitch, bus, BotFilter.from(config.notifications), clock)
      val apis: List[ServerEndpoints] = List(
        HealthApi(),
        StatusApi(twitch, hub, bus, activity, alerts, clock, now.minusSeconds(300)),
        DeviceApi(hub),
        NotificationApi(hub, config.notifications),
        StatsApi(hub),
        ActivityApi(activity, clock),
        AlertsApi(alerts, AlertRule.from(config.alerts, config.twitch.mode), clock),
        LogsApi(),
        ConfigApi(source),
        twitch
      )
      val afterBindRan = AtomicBoolean(false)
      val binding = HttpApi(apis, config.http.copy(host = Hostname("127.0.0.1").toOption.get), OpenTelemetry.noop()).startOnPort(
        0,
        port =>
          assertEquals(send(port, "GET", "health").statusCode(), 200)
          assertEquals(
            send(port, "GET", "twitch/callback?code=unissued&state=unissued").statusCode(),
            400,
            "callback handler is reachable when ingestion is allowed to start"
          )
          // RLY-43: Twitch verifies a webhook subscription by calling back as soon as ingestion registers it.
          val challenge = """{"subscription":{"type":"stream.online"},"challenge":"after-bind-challenge"}"""
          val verification =
            send(
              port,
              "POST",
              "twitch/eventsub",
              body = challenge,
              headers = EventSubSigning.headers(secret, "after-bind", now.toString, challenge)
            )
          assertEquals(verification.statusCode(), 200, "EventSub webhook callback verifies when ingestion is allowed to start")
          assertEquals(verification.body(), "after-bind-challenge", "EventSub webhook callback verifies when ingestion is allowed to start")
          afterBindRan.set(true)
      )
      assert(afterBindRan.get(), "afterBind hook ran before startOnPort returned")
      test(binding.port)
    finally Files.deleteIfExists(directory.resolve("token.json")).discard
    Files.deleteIfExists(directory).discard

  private def send(
      port: Int,
      method: String,
      path: String,
      credential: Option[String] = None,
      body: String = "",
      headers: List[(String, String)] = Nil
  ): HttpResponse[String] =
    val builder = HttpRequest
      .newBuilder(URI.create(s"http://127.0.0.1:$port" + (if path.startsWith("/") then path else s"/api/v1/$path")))
      .method(method, HttpRequest.BodyPublishers.ofString(body))
      .header("Content-Type", "application/json")
    credential.foreach(builder.header("Authorization", _))
    headers.foreach((name, value) => builder.header(name, value))
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    try client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    finally client.close()

  test("every actual management operation rejects missing credentials and accepts either alternative"):
    withServer(): port =>
      routes.foreach: (method, path, body, expected) =>
        val denied = send(port, method, path, body = body)
        assertEquals(denied.statusCode(), 401, s"$method $path")
        assert(denied.body().startsWith("{\"error\":"))
        List(basic, s"Bearer $token").foreach: credential =>
          assertEquals(send(port, method, path, Some(credential), body).statusCode(), expected, s"$method $path")
      assert(send(port, "GET", "status", Some(s"Bearer $token")).body().contains("\"uptimeSeconds\":300"))
      val malformed = send(port, "POST", "notifications", Some(s"Bearer $token"), "{")
      assertEquals(malformed.statusCode(), 400)
      assert(malformed.body().startsWith("{\"error\":"))

  test("aggregate routes and signed callbacks remain public; tokens cannot bypass callback validation"):
    withServer(): port =>
      assertEquals(send(port, "GET", "health").statusCode(), 200)
      assertEquals(send(port, "GET", "stats").statusCode(), 200)
      assertEquals(send(port, "GET", "twitch/callback?code=unissued&state=unissued").statusCode(), 400)
      val body = """{"subscription":{"type":"stream.online"},"challenge":"verified"}"""
      val headers = EventSubSigning.headers(secret, "delivery", now.toString, body)
      assertEquals(send(port, "POST", "twitch/eventsub", body = body, headers = headers).body(), "verified")
      val forged = headers.filterNot(_._1.endsWith("Signature")) :+ ("Twitch-Eventsub-Message-Signature" -> "forged")
      assertEquals(send(port, "POST", "twitch/eventsub", Some(s"Bearer $token"), body, forged).statusCode(), 401)

  test("credential rotation rejects the old token and secrets are absent from configuration logs and exports"):
    val rotated = token + "-rotated"
    withServer(rotated): port =>
      assertEquals(send(port, "GET", "config", Some(s"Bearer $token")).statusCode(), 401)
      val responses = List(
        send(port, "GET", "config", Some(s"Bearer $rotated")),
        send(port, "GET", "logs", Some(s"Bearer $rotated")),
        send(port, "POST", "activity:export", Some(s"Bearer $rotated"))
      )
      responses.foreach: response =>
        assertEquals(response.statusCode(), 200)
        List(token, rotated, password, hash, basic, "fixture-client-secret", secret).foreach(value =>
          assert(!response.body().contains(value))
        )

  test("OpenAPI requires Basic OR Bearer on every management operation and leaves the public operations unsecured"):
    withServer(): port =>
      val docs = send(port, "GET", "/docs/docs.yaml")
      assertEquals(docs.statusCode(), 200)
      val yaml = docs.body()
      val alternatives = "security:\\s+- ManagementBasic: \\[\\]\\s+- ManagementToken: \\[\\]".r
      assertEquals(alternatives.findAllIn(yaml).size, routes.size)
      List("health", "stats", "twitch/callback", "twitch/eventsub").foreach: path =>
        val section = yaml.linesIterator.dropWhile(_ != s"  /api/v1/$path:").drop(1).takeWhile(!_.startsWith("  /")).mkString("\n")
        assert(section.nonEmpty, s"missing public $path")
        assert(!section.contains("security:"), section)
