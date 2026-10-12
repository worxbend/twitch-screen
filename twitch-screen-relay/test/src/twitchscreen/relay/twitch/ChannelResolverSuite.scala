package twitchscreen.relay.twitch

import com.sun.net.httpserver.HttpServer
import com.typesafe.config.ConfigFactory
import java.net.{InetSocketAddress, URI}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import ox.discard
import pureconfig.ConfigSource
import twitchscreen.relay.config.{Config, Sensitive}

class ChannelResolverSuite extends munit.FunSuite:
  private val config = ConfigSource
    .fromConfig(ConfigFactory.load())
    .loadOrThrow[Config]
    .twitch
    .copy(clientId = "synthetic", clientSecret = Sensitive("synthetic"))

  private def withProvider(token: String, users: String, pauseMs: Long = 0)(test: (ChannelResolver, AtomicInteger) => Unit): Unit =
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8)
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    server.setExecutor(executor)
    val calls = AtomicInteger()
    List("/token" -> token, "/users" -> users).foreach: (path, body) =>
      server
        .createContext(
          path,
          exchange =>
            calls.incrementAndGet().discard
            try
              if pauseMs > 0 then Thread.sleep(pauseMs)
              exchange.getRequestBody.readAllBytes().discard
              val bytes = body.getBytes(StandardCharsets.UTF_8)
              exchange.sendResponseHeaders(200, bytes.length.toLong)
              exchange.getResponseBody.write(bytes)
            finally exchange.close()
        )
        .discard
    server.start()
    val client = ChannelResolver.client()
    try
      val base = s"http://127.0.0.1:${server.getAddress.getPort}"
      test(ChannelResolver(config, client, URI.create(s"$base/token"), s"$base/users"), calls)
    finally
      server.stop(0)
      executor.shutdownNow().discard
      client.close()

  test("actual app-token and Helix exchange resolves exact identity without a user grant"):
    withProvider(
      """{"access_token":"synthetic","expires_in":3600}""",
      """{"data":[{"id":"42","login":"synthetic","display_name":"Synthetic"}]}"""
    ): (resolver, calls) =>
      assertEquals(resolver.resolve("synthetic"), Right(ResolvedChannel_OUT("42", "synthetic", "Synthetic")))
      assertEquals(calls.get(), 2)

  test("not-found, oversized and malformed provider responses fail safely"):
    withProvider("""{"access_token":"synthetic"}""", """{"data":[]}"""): (resolver, _) =>
      assertEquals(resolver.resolve("synthetic"), Left("channel_not_found"))
    withProvider("x" * 65537, """{"data":[]}"""): (resolver, calls) =>
      assertEquals(resolver.resolve("synthetic"), Left("channel_lookup_failed"))
      assertEquals(calls.get(), 1)
    withProvider("""{"access_token":"synthetic"}""", """{"data":[{"id":"42","login":"other","display_name":"Other"}]}"""): (resolver, _) =>
      assertEquals(resolver.resolve("synthetic"), Left("channel_not_found"))

  test("one three-second deadline covers both provider exchanges"):
    withProvider("""{"access_token":"synthetic"}""", """{"data":[]}""", pauseMs = 1700): (resolver, calls) =>
      val at = System.nanoTime()
      assertEquals(resolver.resolve("synthetic"), Left("channel_lookup_failed"))
      val elapsed = (System.nanoTime() - at) / 1000000
      assert(elapsed >= 2900 && elapsed < 3700, s"lookup elapsed $elapsed ms")
      assertEquals(calls.get(), 2)
