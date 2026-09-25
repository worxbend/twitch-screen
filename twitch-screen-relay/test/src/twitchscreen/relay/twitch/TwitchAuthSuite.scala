package twitchscreen.relay.twitch

import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import ox.{Ox, fork, supervised}
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
  private val scopes = TwitchScopes.Default

  private final class MovableClock(var now: Instant) extends Clock:
    override def getZone: ZoneId = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant = now

  /** Hands out tokens numbered by the order they were issued, so a test can tell a refreshed token from the original. */
  private final class ScriptedTwitch(clock: Clock) extends TwitchOAuthClient:
    var issued = 0
    var refreshFails = false
    var refreshRejected = false
    var refreshCalls = 0
    var beforeRefresh: () => Unit = () => ()
    val exchangedCodes = mutable.ListBuffer.empty[String]
    val revoked = mutable.ListBuffer.empty[String]

    private def next(login: String, granted: List[String]): UserToken =
      issued += 1
      UserToken(Sensitive(s"access-$issued"), Sensitive(s"refresh-$issued"), clock.instant().plusSeconds(4 * 3600), granted, "1234", login)

    override def exchange(code: String): Either[TwitchCallFailure, UserToken] =
      exchangedCodes += code
      if code == "bad" then Left(TwitchCallFailure("exchange the authorization code", "Rejected", Some(400)))
      else if code == "partial" then Right(next("somechannel", scopes.filterNot(_ == TwitchScopes.Followers)))
      else Right(next("somechannel", scopes))

    override def refresh(token: UserToken): Either[TwitchCallFailure, UserToken] =
      beforeRefresh()
      refreshCalls += 1
      if refreshRejected then Left(TwitchCallFailure("refresh the user token", "Rejected", Some(400)))
      else if refreshFails then Left(TwitchCallFailure("refresh the user token", "Unavailable", Some(503)))
      else Right(next(token.login, token.scopes))

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

  private def newAuth(dir: Path, clock: MovableClock, twitch: ScriptedTwitch)(using Ox): TwitchAuth =
    val file = TokenFile(dir.resolve("data").resolve("twitch-token.json"))
    TwitchAuth.create(twitchConfig(file.location), twitch, file, EventBus(clock, queueCapacity = 16), clock, file.load().toOption.flatten)

  private def usableAccess(auth: TwitchAuth): Option[String] = auth.view.usable.map(_.accessToken.value)

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
    // A literal on purpose: it pins the exact wire format Twitch receives, independent of TwitchScopes.
    assertEquals(params("scope"), "moderator:read:followers channel:read:subscriptions")
    assertEquals(params("state"), "xyz")

  tempDir.test("a granted code is exchanged, stored with owner-only permissions and loaded again by the next run"): dir =>
    supervised:
      val clock = MovableClock(start)
      val twitch = ScriptedTwitch(clock)
      val auth = newAuth(dir, clock, twitch)
      assertEquals(auth.view.held, None)

      val granted = auth.completeAuthorization("good", stateOf(auth.beginAuthorization()))
      assertEquals(granted.map(_.accessToken.value), Right("access-1"))
      assertEquals(usableAccess(auth), Some("access-1"))
      assertEquals(auth.view.missingScopes, Nil)

      val stored = dir.resolve("data").resolve("twitch-token.json")
      assert(Files.exists(stored))
      if stored.getFileSystem.supportedFileAttributeViews.contains("posix") then
        assertEquals(PosixFilePermissions.toString(Files.getPosixFilePermissions(stored)), "rw-------")

      val restarted = newAuth(dir, clock, ScriptedTwitch(clock))
      assertEquals(restarted.view.held.map(_.login), Some("somechannel"))
      assertEquals(usableAccess(restarted), Some("access-1"))

  tempDir.test("a state is accepted once: a replayed callback is refused without asking Twitch"): dir =>
    supervised:
      val clock = MovableClock(start)
      val twitch = ScriptedTwitch(clock)
      val auth = newAuth(dir, clock, twitch)
      val state = stateOf(auth.beginAuthorization())
      assert(auth.completeAuthorization("good", state).isRight)
      assert(auth.completeAuthorization("good", state).isLeft)
      assertEquals(twitch.exchangedCodes.toList, List("good"))

  tempDir.test("a state the relay never issued, or one older than ten minutes, is refused without asking Twitch"): dir =>
    supervised:
      val clock = MovableClock(start)
      val twitch = ScriptedTwitch(clock)
      val auth = newAuth(dir, clock, twitch)
      assert(auth.completeAuthorization("good", "forged").isLeft)
      val state = stateOf(auth.beginAuthorization())
      clock.now = start.plusSeconds(11 * 60)
      assert(auth.completeAuthorization("good", state).isLeft)
      assert(twitch.exchangedCodes.isEmpty)
      assertEquals(auth.view.held, None)

  tempDir.test("a code Twitch will not exchange leaves the relay unauthorized"): dir =>
    supervised:
      val clock = MovableClock(start)
      val auth = newAuth(dir, clock, ScriptedTwitch(clock))
      assert(auth.completeAuthorization("bad", stateOf(auth.beginAuthorization())).isLeft)
      assertEquals(auth.view.held, None)

  tempDir.test("a token inside its refresh margin is replaced, keeping the account, and the usable grant follows it"): dir =>
    supervised:
      val clock = MovableClock(start)
      val twitch = ScriptedTwitch(clock)
      val auth = newAuth(dir, clock, twitch)
      auth.completeAuthorization("good", stateOf(auth.beginAuthorization())).discard

      auth.maintain()
      assertEquals(usableAccess(auth), Some("access-1"), "four hours out, nothing to do")

      clock.now = start.plusSeconds(4 * 3600 - 10 * 60)
      auth.maintain()
      assertEquals(usableAccess(auth), Some("access-2"))
      assertEquals(auth.view.held.map(_.login), Some("somechannel"))
      assertEquals(
        newAuth(dir, clock, ScriptedTwitch(clock)).view.usable.map(_.accessToken.value),
        Some("access-2"),
        "the refreshed token is what persists"
      )

  tempDir.test("a failed refresh retains the grant for retry but never supplies an expired access token"): dir =>
    supervised:
      val clock = MovableClock(start)
      val twitch = ScriptedTwitch(clock)
      val auth = newAuth(dir, clock, twitch)
      auth.completeAuthorization("good", stateOf(auth.beginAuthorization())).discard
      twitch.refreshFails = true
      clock.now = start.plusSeconds(4 * 3600)
      auth.maintain()
      assertEquals(usableAccess(auth), None)
      assertEquals(auth.view.held.map(_.accessToken.value), Some("access-1"))

  tempDir.test("signing out revokes the token at Twitch and deletes the file"): dir =>
    supervised:
      val clock = MovableClock(start)
      val twitch = ScriptedTwitch(clock)
      val auth = newAuth(dir, clock, twitch)
      assert(!auth.signOut(), "nothing to sign out of yet")
      auth.completeAuthorization("good", stateOf(auth.beginAuthorization())).discard
      assert(auth.signOut())
      assertEquals(auth.view.usable, None)
      assertEquals(auth.view.held, None)
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

  tempDir.test("a rejected access token is withheld until maintenance refreshes it"): dir =>
    supervised:
      val clock = MovableClock(start)
      val twitch = ScriptedTwitch(clock)
      val auth = newAuth(dir, clock, twitch)
      auth.completeAuthorization("good", stateOf(auth.beginAuthorization())).discard
      auth.rejectAccessToken()
      assertEquals(usableAccess(auth), None)
      auth.maintain()
      assertEquals(usableAccess(auth), Some("access-2"))

  tempDir.test("sign-out racing a refresh cannot restore the usable grant or persisted grant"): dir =>
    supervised:
      val clock = MovableClock(start)
      val twitch = ScriptedTwitch(clock)
      val auth = newAuth(dir, clock, twitch)
      auth.completeAuthorization("good", stateOf(auth.beginAuthorization())).discard
      val entered = java.util.concurrent.CountDownLatch(1)
      val proceed = java.util.concurrent.CountDownLatch(1)
      twitch.beforeRefresh = () =>
        entered.countDown()
        proceed.await()
      clock.now = start.plusSeconds(4 * 3600)
      val refresh = fork(auth.maintain())
      try
        assert(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assert(auth.signOut())
      finally proceed.countDown()
      refresh.join()
      assertEquals(auth.view.held, None)
      assertEquals(auth.view.usable, None)
      assertEquals(twitch.revoked.toList, List("access-1", "access-2"))
      assert(!Files.exists(dir.resolve("data/twitch-token.json")))

  tempDir.test("new consent racing refresh retains the new grant as the usable grant and in the file"): dir =>
    supervised:
      val clock = MovableClock(start)
      val twitch = ScriptedTwitch(clock)
      val auth = newAuth(dir, clock, twitch)
      auth.completeAuthorization("first", stateOf(auth.beginAuthorization())).discard
      val entered = java.util.concurrent.CountDownLatch(1)
      val proceed = java.util.concurrent.CountDownLatch(1)
      twitch.beforeRefresh = () =>
        entered.countDown()
        proceed.await()
      clock.now = start.plusSeconds(4 * 3600)
      val refresh = fork(auth.maintain())
      val expected =
        try
          assert(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
          auth.completeAuthorization("new", stateOf(auth.beginAuthorization())).toOption.get.accessToken.value
        finally proceed.countDown()
      refresh.join()
      assertEquals(usableAccess(auth), Some(expected))
      assertEquals(usableAccess(newAuth(dir, clock, twitch)), Some(expected))

  tempDir.test("a rejected refresh stops retries until a new consent grant arrives"): dir =>
    supervised:
      val clock = MovableClock(start)
      val twitch = ScriptedTwitch(clock)
      val auth = newAuth(dir, clock, twitch)
      auth.completeAuthorization("good", stateOf(auth.beginAuthorization())).discard
      twitch.refreshRejected = true
      auth.rejectAccessToken()
      auth.maintain()
      auth.maintain()
      assertEquals(twitch.refreshCalls, 1)
      assertEquals(usableAccess(auth), None)
      assert(auth.view.held.isDefined)
      twitch.refreshRejected = false
      auth.completeAuthorization("new", stateOf(auth.beginAuthorization())).discard
      assert(usableAccess(auth).isDefined)

  tempDir.test("authorization status exposes an expired or rejected retained grant as unusable"): dir =>
    supervised:
      val clock = MovableClock(start)
      val auth = newAuth(dir, clock, ScriptedTwitch(clock))
      auth.completeAuthorization("good", stateOf(auth.beginAuthorization())).discard
      auth.rejectAccessToken()
      val backend = TapirSyncStubInterpreter().whenServerEndpointsRunLogic(TwitchAuthApi(auth).endpoints).backend()
      val status = basicRequest.get(uri"http://localhost:8080/api/v1/twitch/authorization").send(backend).body.getOrElse(fail("no status"))
      assert(status.contains("\"authorized\":false"), status)
      assert(status.contains("somechannel"), status)

  tempDir.test("a concurrent rejection of the same grant does not discard a successful refresh"): dir =>
    supervised:
      val clock = MovableClock(start)
      val twitch = ScriptedTwitch(clock)
      val auth = newAuth(dir, clock, twitch)
      auth.completeAuthorization("good", stateOf(auth.beginAuthorization())).discard
      twitch.beforeRefresh = () => auth.rejectAccessToken("access-1")
      clock.now = start.plusSeconds(4 * 3600)
      auth.maintain()
      assertEquals(usableAccess(auth), Some("access-2"))
      assertEquals(twitch.revoked.toList, Nil)

  tempDir.test("the callback page reports the scopes missing from the grant it just installed"): dir =>
    supervised:
      val clock = MovableClock(start)
      val auth = newAuth(dir, clock, ScriptedTwitch(clock))
      val backend = TapirSyncStubInterpreter().whenServerEndpointsRunLogic(TwitchAuthApi(auth).endpoints).backend()
      val base = uri"http://localhost:8080/api/v1/twitch"
      val location =
        basicRequest.get(uri"$base/authorize").followRedirects(false).send(backend).header("Location").getOrElse(fail("no Location"))
      val completed = basicRequest.get(uri"$base/callback?code=partial&state=${stateOf(location)}").send(backend)
      assertEquals(completed.code, StatusCode.Ok)
      assert(completed.body.exists(_.contains(s"did not grant ${TwitchScopes.Followers}")), completed.body.toString)

  tempDir.test("missing scopes are computed from the issued grant, not re-read from state"): dir =>
    supervised:
      val clock = MovableClock(start)
      val auth = newAuth(dir, clock, ScriptedTwitch(clock))
      val granted = auth.completeAuthorization("partial", stateOf(auth.beginAuthorization())).getOrElse(fail("not granted"))
      assert(auth.signOut())
      assertEquals(auth.missingScopesOf(granted), List(TwitchScopes.Followers))
      assertEquals(auth.view.missingScopes, scopes, "the state now holds no grant at all")

  tempDir.test("Twitch4J receives an immutable credential snapshot"): dir =>
    supervised:
      val clock = MovableClock(start)
      val auth = newAuth(dir, clock, ScriptedTwitch(clock))
      auth.completeAuthorization("good", stateOf(auth.beginAuthorization())).discard
      val snapshot = TwitchOAuthClient.credentialOf(auth.view.usable.getOrElse(fail("no usable grant")))
      clock.now = start.plusSeconds(4 * 3600 - 10 * 60)
      auth.maintain()
      assertEquals(snapshot.getAccessToken, "access-1")
      assertEquals(usableAccess(auth), Some("access-2"))
