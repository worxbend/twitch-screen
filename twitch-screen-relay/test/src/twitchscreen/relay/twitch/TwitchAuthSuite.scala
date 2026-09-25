package twitchscreen.relay.twitch

import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import ox.supervised
import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import sttp.client4.*
import sttp.model.{StatusCode, Uri}
import sttp.tapir.server.stub4.TapirSyncStubInterpreter
import twitchscreen.relay.bus.EventBus
import twitchscreen.relay.config.*

/** The consent flow against a scripted Twitch: the `state` check, persistence, refresh and sign-out, and what the endpoints disclose. */
class TwitchAuthSuite extends munit.FunSuite:
  private val start = Instant.ofEpochSecond(1790309000L)
  private val redirectUrl = "http://localhost:8080/api/v1/twitch/callback"
  private val scopes = List("moderator:read:followers", "channel:read:subscriptions")

  private final class MovableClock(var now: Instant) extends Clock:
    override def getZone: ZoneId = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant = now

  /** Hands out tokens numbered by the order they were issued, so a test can tell a refreshed token from the original. */
  private final class ScriptedTwitch(clock: Clock) extends TwitchOAuthClient:
    var issued = 0
    var refreshFails = false
    val exchangedCodes = mutable.ListBuffer.empty[String]
    val revoked = mutable.ListBuffer.empty[String]

    private def next(login: String, granted: List[String]): UserToken =
      issued += 1
      UserToken(Sensitive(s"access-$issued"), Sensitive(s"refresh-$issued"), clock.instant().plusSeconds(4 * 3600), granted, "1234", login)

    override def exchange(code: String): Either[String, UserToken] =
      exchangedCodes += code
      if code == "bad" then Left("could not exchange the authorization code: 400") else Right(next("somechannel", scopes))

    override def refresh(token: UserToken): Either[String, UserToken] =
      if refreshFails then Left("could not refresh the user token: 400") else Right(next(token.login, token.scopes))

    override def isValid(token: UserToken): Option[Boolean] = Some(true)

    override def revoke(token: UserToken): Boolean =
      revoked += token.accessToken.value
      true

  private def twitchConfig(tokenFile: Path) = TwitchConfig(
    mode = TwitchMode.Live,
    channel = "somechannel",
    clientId = "client-id",
    clientSecret = Sensitive("client-secret"),
    oauth = TwitchOAuthConfig(redirectUrl, scopes, tokenFile.toString, 15.minutes),
    eventSub = EventSubConfig(EventSubTransport.WebSocket, "", Sensitive.Empty),
    pollInterval = 30.seconds,
    simulation = SimulationConfig(10.seconds, 2.seconds)
  )

  private val tempDir = FunFixture[Path](_ => Files.createTempDirectory("twitch-auth"), dir => deleteRecursively(dir))

  private def deleteRecursively(dir: Path): Unit =
    Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))

  private def newAuth(dir: Path, clock: MovableClock, twitch: ScriptedTwitch): TwitchAuth =
    val file = TokenFile(dir.resolve("data").resolve("twitch-token.json"))
    TwitchAuth(twitchConfig(file.location), twitch, file, EventBus(clock, queueCapacity = 16), clock, file.load().toOption.flatten)

  private def stateOf(url: String): String =
    Uri.unsafeParse(url).params.get("state").getOrElse(fail(s"no state in $url"))

  test("the consent URL asks Twitch for a code, for this application, with the configured scopes and redirect"):
    val url = TwitchAuth.authorizationUrl("client-id", redirectUrl, scopes, "xyz")
    assertEquals(url.host, Some("id.twitch.tv"))
    assertEquals(url.path, List("oauth2", "authorize"))
    val params = url.params.toMap
    assertEquals(params("response_type"), "code")
    assertEquals(params("client_id"), "client-id")
    assertEquals(params("redirect_uri"), redirectUrl)
    assertEquals(params("scope"), "moderator:read:followers channel:read:subscriptions")
    assertEquals(params("state"), "xyz")

  tempDir.test("a granted code is exchanged, stored with owner-only permissions and loaded again by the next run"): dir =>
    val clock = MovableClock(start)
    val twitch = ScriptedTwitch(clock)
    val auth = newAuth(dir, clock, twitch)
    assertEquals(auth.current, None)

    val granted = auth.completeAuthorization("good", stateOf(auth.beginAuthorization()))
    assertEquals(granted.map(_.accessToken.value), Right("access-1"))
    assertEquals(auth.accessToken, Some("access-1"))
    assertEquals(auth.missingScopes, Nil)
    assertEquals(auth.awaitCredential().getAccessToken, "access-1")

    val stored = dir.resolve("data").resolve("twitch-token.json")
    assert(Files.exists(stored))
    if stored.getFileSystem.supportedFileAttributeViews.contains("posix") then
      assertEquals(PosixFilePermissions.toString(Files.getPosixFilePermissions(stored)), "rw-------")

    val restarted = newAuth(dir, clock, ScriptedTwitch(clock))
    assertEquals(restarted.current.map(_.login), Some("somechannel"))
    assertEquals(restarted.accessToken, Some("access-1"))

  tempDir.test("a state is accepted once: a replayed callback is refused without asking Twitch"): dir =>
    val clock = MovableClock(start)
    val twitch = ScriptedTwitch(clock)
    val auth = newAuth(dir, clock, twitch)
    val state = stateOf(auth.beginAuthorization())
    assert(auth.completeAuthorization("good", state).isRight)
    assert(auth.completeAuthorization("good", state).isLeft)
    assertEquals(twitch.exchangedCodes.toList, List("good"))

  tempDir.test("a state the relay never issued, or one older than ten minutes, is refused without asking Twitch"): dir =>
    val clock = MovableClock(start)
    val twitch = ScriptedTwitch(clock)
    val auth = newAuth(dir, clock, twitch)
    assert(auth.completeAuthorization("good", "forged").isLeft)
    val state = stateOf(auth.beginAuthorization())
    clock.now = start.plusSeconds(11 * 60)
    assert(auth.completeAuthorization("good", state).isLeft)
    assert(twitch.exchangedCodes.isEmpty)
    assertEquals(auth.current, None)

  tempDir.test("a code Twitch will not exchange leaves the relay unauthorized"): dir =>
    val clock = MovableClock(start)
    val auth = newAuth(dir, clock, ScriptedTwitch(clock))
    assert(auth.completeAuthorization("bad", stateOf(auth.beginAuthorization())).isLeft)
    assertEquals(auth.current, None)

  tempDir.test("a token inside its refresh margin is replaced, keeping the account, and the handle twitch4j holds follows it"): dir =>
    val clock = MovableClock(start)
    val twitch = ScriptedTwitch(clock)
    val auth = newAuth(dir, clock, twitch)
    auth.completeAuthorization("good", stateOf(auth.beginAuthorization())).discard
    val handle = auth.awaitCredential()

    auth.maintain()
    assertEquals(auth.accessToken, Some("access-1"), "four hours out, nothing to do")

    clock.now = start.plusSeconds(4 * 3600 - 10 * 60)
    auth.maintain()
    assertEquals(auth.accessToken, Some("access-2"))
    assertEquals(auth.current.map(_.login), Some("somechannel"))
    assertEquals(handle.getAccessToken, "access-2")
    assertEquals(newAuth(dir, clock, ScriptedTwitch(clock)).accessToken, Some("access-2"), "the refreshed token is what persists")

  tempDir.test("a failed refresh keeps the token it has, so a network blip does not sign the relay out"): dir =>
    val clock = MovableClock(start)
    val twitch = ScriptedTwitch(clock)
    val auth = newAuth(dir, clock, twitch)
    auth.completeAuthorization("good", stateOf(auth.beginAuthorization())).discard
    twitch.refreshFails = true
    clock.now = start.plusSeconds(4 * 3600)
    auth.maintain()
    assertEquals(auth.accessToken, Some("access-1"))

  tempDir.test("signing out revokes the token at Twitch and deletes the file"): dir =>
    val clock = MovableClock(start)
    val twitch = ScriptedTwitch(clock)
    val auth = newAuth(dir, clock, twitch)
    assert(!auth.signOut(), "nothing to sign out of yet")
    auth.completeAuthorization("good", stateOf(auth.beginAuthorization())).discard
    assert(auth.signOut())
    assertEquals(auth.current, None)
    assertEquals(twitch.revoked.toList, List("access-1"))
    assert(!Files.exists(dir.resolve("data").resolve("twitch-token.json")))

  tempDir.test("the endpoints redirect to Twitch, complete the callback and report the grant without disclosing the token"): dir =>
    supervised:
      val clock = MovableClock(start)
      val auth = newAuth(dir, clock, ScriptedTwitch(clock))
      val backend = TapirSyncStubInterpreter().whenServerEndpointsRunLogic(TwitchAuthApi(auth).endpoints).backend()
      val base = uri"http://localhost:8080/api/v1/twitch"

      val redirect = basicRequest.get(uri"$base/authorize").followRedirects(false).send(backend)
      assertEquals(redirect.code, StatusCode.Found)
      val location = redirect.header("Location").getOrElse(fail("no Location header"))
      assert(location.startsWith("https://id.twitch.tv/oauth2/authorize?"), location)

      val denied = basicRequest.get(uri"$base/callback?error=access_denied&error_description=<b>no</b>").send(backend)
      assertEquals(denied.code, StatusCode.BadRequest)
      assert(denied.body.left.exists(_.contains("&lt;b&gt;no&lt;/b&gt;")), s"the description must be escaped: ${denied.body}")

      val completed = basicRequest.get(uri"$base/callback?code=good&state=${stateOf(location)}").send(backend)
      assertEquals(completed.code, StatusCode.Ok)
      assert(completed.body.exists(_.contains("somechannel")), completed.body.toString)

      val status = basicRequest.get(uri"$base/authorization").send(backend).body.getOrElse(fail("no status"))
      assert(status.contains("\"authorized\":true"), status)
      assert(!status.contains("access-1") && !status.contains("refresh-1"), s"the token leaked: $status")

      assertEquals(basicRequest.delete(uri"$base/authorization").send(backend).code, StatusCode.NoContent)
      assertEquals(basicRequest.delete(uri"$base/authorization").send(backend).code, StatusCode.NotFound)

  extension [T](value: T) private def discard: Unit = ()
