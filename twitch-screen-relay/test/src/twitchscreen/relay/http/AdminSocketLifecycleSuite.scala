package twitchscreen.relay.http

import java.io.{BufferedReader, EOFException, InputStreamReader}
import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket, URLClassLoader}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import ox.discard
import scala.jdk.CollectionConverters.*

/** The client/test stays in a different JVM so carrier starvation cannot prevent its deadline or kill/reap cleanup. */
class AdminSocketLifecycleSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.DurationInt(60).seconds

  private def freePort(): Int =
    val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress)
    try socket.getLocalPort
    finally socket.close()

  private def classpath: String =
    val loaders = Iterator.iterate(getClass.getClassLoader)(_.getParent).takeWhile(_ != null)
    val urls = loaders.collect { case loader: URLClassLoader => loader.getURLs.toList }.flatten.map(url => Path.of(url.toURI).toString)
    (System.getProperty("java.class.path").split(java.io.File.pathSeparator).toList ++ urls).distinct.mkString(java.io.File.pathSeparator)

  private final class Feed(port: Int, address: String) extends AutoCloseable:
    private val socket = new Socket()
    socket.setSoTimeout(4000)
    private var reader: BufferedReader = null
    def open(): Int =
      socket.bind(InetSocketAddress(address, 0))
      socket.connect(InetSocketAddress("127.0.0.1", port), 1000)
      reader = BufferedReader(InputStreamReader(socket.getInputStream, UTF_8))
      socket.getOutputStream.write(s"GET /api/v1/admin/events HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".getBytes(UTF_8))
      def line(): String = Option(reader.readLine()).getOrElse(throw EOFException("SSE response ended before headers completed"))
      val status = line().split(" ")(1).toInt
      while line() != "" do ()
      status
    def snapshot(): Unit =
      // Ignore HTTP chunk-size lines; insist on actual complete SSE data, not just response headers.
      var line = reader.readLine()
      while line != null && !line.startsWith("data:") do line = reader.readLine()
      assert(line != null && line.contains("relayEpoch"), "missing initial canonical SSE data")
    override def close(): Unit = socket.close()

  private def snapshot(port: Int): Unit =
    val socket = new Socket()
    try
      socket.setSoTimeout(4000)
      socket.connect(InetSocketAddress("127.0.0.1", port), 500)
      socket.getOutputStream.write(
        s"GET /api/v1/admin/snapshot HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".getBytes(UTF_8)
      )
      val body = String(socket.getInputStream.readAllBytes(), UTF_8)
      assert(body.startsWith("HTTP/1.1 200") && body.contains("relayEpoch"))
    finally socket.close()

  for processors <- List(2, 8) do
    test(s"actual Main SSE disconnect admission and shutdown with $processors processors"):
      val directory =
        Option(System.getenv("TMPDIR")).filter(_.nonEmpty).map(Path.of(_)).getOrElse(Path.of(System.getProperty("java.io.tmpdir")))
      val evidence = Files.createTempDirectory(directory, "admin-sockets-")
      val httpPort = freePort()
      var devicePort = freePort()
      while devicePort == httpPort do devicePort = freePort()
      val javaCommand = Path.of(System.getProperty("java.home"), "bin", "java").toString
      val command = ProcessBuilder(javaCommand, s"-XX:ActiveProcessorCount=$processors", "-cp", classpath, "twitchscreen.relay.Main")
      command.directory(evidence.toFile).redirectErrorStream(true).redirectOutput(evidence.resolve("main.log").toFile).discard
      val env = command.environment()
      env
        .keySet()
        .asScala
        .filter(key => key.startsWith("RELAY_") || Set("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS").contains(key))
        .toList
        .foreach(env.remove(_).discard)
      Map(
        "RELAY_TWITCH_MODE" -> "disabled",
        "RELAY_TWITCH_CHANNEL" -> "",
        "RELAY_HTTP_HOST" -> "127.0.0.1",
        "RELAY_DEVICE_HOST" -> "127.0.0.1",
        "RELAY_HTTP_PORT" -> httpPort.toString,
        "RELAY_DEVICE_PORT" -> devicePort.toString,
        "RELAY_HTTP_ALLOWED_HOSTS" -> s"127.0.0.1:$httpPort,127.0.0.1:8080,localhost:8080",
        "RELAY_HTTP_ALLOWED_ORIGINS" -> s"http://127.0.0.1:$httpPort,http://127.0.0.1:8080,http://localhost:8080",
        "RELAY_RUNTIME_STATE_FILE" -> evidence.resolve("state/runtime-state.json").toString,
        "RELAY_TWITCH_TOKEN_FILE" -> evidence.resolve("unused-token.json").toString,
        "OTEL_TRACES_EXPORTER" -> "none",
        "OTEL_METRICS_EXPORTER" -> "none",
        "OTEL_LOGS_EXPORTER" -> "none"
      ).foreach((key, value) => env.put(key, value).discard)
      val child = command.start()
      var feeds = List.empty[Feed]
      var passed = false
      def open(address: String): (Feed, Int) =
        val feed = new Feed(httpPort, address)
        feeds = feeds :+ feed // own it before the response wait, including timeout/rejected paths
        (feed, feed.open())
      def accepted(address: String): Feed =
        val (feed, status) = open(address)
        assertEquals(status, 200)
        feed.snapshot()
        feed
      try
        val readyUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var ready = false
        while !ready && child.isAlive && System.nanoTime() < readyUntil do
          try
            snapshot(httpPort)
            ready = true
          catch case _: java.io.IOException => Thread.sleep(25)
        assert(ready, s"Main did not start; evidence: $evidence")
        for suffix <- 1 to 4 do
          accepted(s"127.0.0.$suffix").discard
          accepted(s"127.0.0.$suffix").discard
          if suffix == 1 then
            val (excess, status) = open("127.0.0.1")
            assertEquals(status, 429)
            excess.close()
            feeds = feeds.filterNot(_ eq excess)
        val (ninth, status) = open("127.0.0.5")
        assertEquals(status, 429)
        ninth.close()
        feeds = feeds.filterNot(_ eq ninth)
        feeds.tail.foreach(_.close())
        feeds = feeds.take(1)
        val releasedUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var released = false
        while !released && System.nanoTime() < releasedUntil do
          val (replacement, status) = open("127.0.0.1")
          if status == 200 then
            replacement.snapshot()
            released = true
          else
            assertEquals(status, 429)
            replacement.close()
            feeds = feeds.filterNot(_ eq replacement)
            Thread.sleep(20)
        assert(released, "disconnect did not release admission")
        snapshot(httpPort) // HTTP/actor authority still progresses after seven simultaneous disconnects
        // Shut down at full admission as well; all eight publisher owners must cancel/reap without starving carriers.
        for suffix <- 2 to 4 do
          accepted(s"127.0.0.$suffix").discard
          accepted(s"127.0.0.$suffix").discard
        child.destroy()
        assert(child.waitFor(10, TimeUnit.SECONDS), s"Main shutdown hung; evidence: $evidence")
        assertEquals(child.exitValue(), 143)
        for port <- List(httpPort, devicePort) do
          val check = new Socket()
          try intercept[java.io.IOException](check.connect(InetSocketAddress("127.0.0.1", port), 500)).discard
          finally check.close()
        passed = true
      finally
        if child.isAlive then
          try
            val dump = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "jstack").toString, child.pid().toString)
              .redirectErrorStream(true)
              .redirectOutput(evidence.resolve("jstack.txt").toFile)
              .start()
            if !dump.waitFor(5, TimeUnit.SECONDS) then
              dump.destroyForcibly().discard
              dump.waitFor(5, TimeUnit.SECONDS).discard
          catch case _: java.io.IOException => () // Missing diagnostics must not skip kill/reap or obscure the original failure.
          finally
            child.destroyForcibly().discard
            assert(child.waitFor(5, TimeUnit.SECONDS), "owned Main was not reaped")
        feeds.foreach(_.close())
        if passed then
          val paths = Files.walk(evidence)
          try paths.iterator().asScala.toList.reverse.foreach(Files.delete(_))
          finally paths.close()
        else System.err.println(s"SSE failure evidence: $evidence")
