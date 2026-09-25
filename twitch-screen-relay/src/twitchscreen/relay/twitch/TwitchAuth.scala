package twitchscreen.relay.twitch

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential
import com.github.twitch4j.auth.providers.TwitchIdentityProvider
import java.nio.file.Path
import java.security.SecureRandom
import java.time.{Clock, Duration as JDuration, Instant}
import java.util.Base64
import java.util.concurrent.{ConcurrentHashMap, CountDownLatch}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.Actor
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.jdk.DurationConverters.*
import scala.util.control.NonFatal
import sttp.model.Uri
import sttp.model.Uri.UriContext
import twitchscreen.relay.bus.{EventBus, RelayEvent}
import twitchscreen.relay.config.TwitchConfig

/** The broadcaster's user token, obtained on demand instead of configured.
  *
  * [[beginAuthorization]] mints a single-use `state` and returns Twitch's consent URL; [[completeAuthorization]] checks the `state` Twitch
  * hands back — the CSRF guard that stops a third party from binding their own account to this relay — and swaps the code for a token. The
  * token is persisted to [[TokenFile]] and kept fresh by [[maintain]], which the application scope runs once a minute.
  *
  * twitch4j keeps the credential object it was given and reads its access token each time it (re)subscribes, so the relay hands it one
  * [[OAuth2Credential]] for its whole lifetime and rewrites that object's fields on every refresh instead of swapping it for another.
  */
private[twitch] final class TwitchAuth(
    config: TwitchConfig,
    client: TwitchOAuthClient,
    file: TokenFile,
    bus: EventBus,
    clock: Clock,
    initial: Option[UserToken]
)(using Ox):
  import TwitchAuth.*

  private val logger = LoggerFactory.getLogger(getClass)
  private val token = AtomicReference(initial)
  private val rejectedToken = AtomicBoolean(false)
  private val lastFailure = AtomicReference(Option.empty[String])
  private val lastValidated = AtomicReference(Instant.MIN)
  private val pendingStates = ConcurrentHashMap[String, Instant]()
  private val firstToken = CountDownLatch(if initial.isDefined then 0 else 1)
  private val random = SecureRandom()

  private val handle =
    OAuth2Credential(TwitchIdentityProvider.PROVIDER_NAME, initial.map(_.accessToken.value).getOrElse(""))
  initial.foreach(updateHandle)
  private val transitions = Actor.create(CredentialTransitions())

  def current: Option[UserToken] = token.get()

  /** For Helix calls made per request, which take the token as an argument. */
  def accessToken: Option[String] =
    token.get().filter(held => !rejectedToken.get() && held.expiresAt.isAfter(clock.instant())).map(_.accessToken.value)

  def rejectAccessToken(): Unit = rejectedToken.set(true)

  def credential: Option[OAuth2Credential] = accessToken.map(_ => handle)

  /** The scopes asked for that the current token was not granted. */
  def missingScopes: List[String] = token.get().fold(config.oauth.scopes)(granted => config.oauth.scopes.diff(granted.scopes))

  /** Blocks until a token exists, then returns the credential twitch4j should hold on to. Interruptible, so it ends with its scope. */
  def awaitCredential(): OAuth2Credential =
    firstToken.await()
    handle

  /** Starts a consent round trip: the URL to send the browser to. */
  def beginAuthorization(): String =
    val now = clock.instant()
    pendingStates.entrySet().removeIf(entry => entry.getValue.isBefore(now))
    // Bound outstanding authorization requests even if an authenticated operator starts many consent flows.
    while pendingStates.size >= MaxPendingStates do
      pendingStates.entrySet().asScala.minByOption(_.getValue).foreach(oldest => pendingStates.remove(oldest.getKey).discard)
    val state = newState()
    pendingStates.put(state, now.plus(StateLifetime)).discard
    authorizationUrl(config.clientId, config.oauth.redirectUrl, config.oauth.scopes, state).toString

  /** Finishes the round trip Twitch redirected back from. A `state` is accepted once and only before it expires. */
  def completeAuthorization(code: String, state: String): Either[String, UserToken] =
    for
      _ <- consumeState(state)
      issued <- client.exchange(code)
    yield
      transitions.ask(_.install(issued))
      logger.info(s"Twitch authorized by '${issued.login}' (${issued.scopes.mkString(" ")})")
      if !issued.login.equalsIgnoreCase(config.channel) then
        logger.warn(s"The token belongs to '${issued.login}', not the broadcaster '${config.channel}'; subscriber totals will be refused")
      if missingScopes.nonEmpty then logger.warn(s"Twitch did not grant ${missingScopes.mkString(", ")}")
      issued

  /** Forgets the token, revokes it at Twitch and deletes the file. `false` when there was nothing to forget. */
  def signOut(): Boolean =
    transitions.ask(_.remove()) match
      case None => false
      case Some(revoked) =>
        if !client.revoke(revoked) then logger.warn("Twitch did not confirm the revocation; the token will lapse on its own")
        logger.info(s"Signed out of Twitch as '${revoked.login}'")
        true

  /** Refreshes a token that is about to expire, and validates it hourly as Twitch requires of every application. Never throws. */
  def maintain(): Unit =
    try
      val snapshot = token.get()
      snapshot.foreach: held =>
        val now = clock.instant()
        if rejectedToken.get() || !held.expiresAt.isAfter(now.plus(config.oauth.refreshBefore.toJava)) then refresh(snapshot, held)
        else if JDuration.between(lastValidated.get(), now).compareTo(ValidationInterval) >= 0 then
          client.isValid(held) match
            case Some(false) =>
              logger.warn("Twitch no longer accepts the user token; refreshing it")
              rejectedToken.set(true)
              refresh(snapshot, held)
            case Some(true) => lastValidated.set(now)
            case None       => () // Twitch unreachable: ask again next round.
    catch case NonFatal(error) => fail(s"token maintenance failed: ${error.getMessage}")

  /** `snapshot` is the very `Option` read from `token`: `compareAndSet` compares references, so an equal but rebuilt `Some` never matches.
    */
  private def refresh(snapshot: Option[UserToken], held: UserToken): Unit =
    client.refresh(held) match
      case Right(renewed) =>
        // A refresh that loses the race against a new consent or a sign-out must not put the old grant back.
        transitions.ask(_.refreshIfCurrent(snapshot, renewed))
      case Left(reason) =>
        fail(s"$reason; if this persists, authorize again at ${AuthorizePath}")

  /** All accepted transitions include the foreign credential handle and persisted grant. Network exchanges happen before actor calls, so a
    * slow Twitch request cannot block sign-out; an obsolete refresh cannot repopulate the handle or file after sign-out/new consent.
    */
  private final class CredentialTransitions:
    def install(issued: UserToken): Unit =
      token.set(Some(issued))
      afterInstall(issued)

    def refreshIfCurrent(snapshot: Option[UserToken], renewed: UserToken): Unit =
      if token.compareAndSet(snapshot, Some(renewed)) then
        afterInstall(renewed)
        logger.info(s"Refreshed the Twitch user token; valid until ${renewed.expiresAt}")

    def remove(): Option[UserToken] =
      val revoked = token.getAndSet(None)
      handle.setAccessToken("")
      handle.setRefreshToken("")
      revoked.foreach(_ => persist(file.delete()))
      revoked

  private def afterInstall(issued: UserToken): Unit =
    rejectedToken.set(false)
    lastFailure.set(None)
    lastValidated.set(clock.instant())
    updateHandle(issued)
    persist(file.save(issued))
    firstToken.countDown()

  private def updateHandle(issued: UserToken): Unit =
    handle.setAccessToken(issued.accessToken.value)
    handle.setRefreshToken(issued.refreshToken.value)
    handle.setUserId(issued.userId)

  private def consumeState(state: String): Either[String, Unit] =
    Option(pendingStates.remove(state))
      .filter(_.isAfter(clock.instant()))
      .toRight(s"unknown or expired authorization request; start again at $AuthorizePath")
      .map(_ => ())

  /** The token stays usable in memory when the disk is not; the relay just asks for consent again after a restart. */
  private def persist(write: => Unit): Unit =
    try write
    catch case NonFatal(error) => fail(s"could not update ${file.location}: ${error.getMessage}")

  private def fail(reason: String): Unit =
    if lastFailure.getAndSet(Some(reason)) != Some(reason) then
      logger.warn(s"Twitch authorization: $reason")
      bus.publish(RelayEvent.RelayFailure("twitch-oauth", reason))

  private def newState(): String =
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

private[twitch] object TwitchAuth:
  private val logger = LoggerFactory.getLogger(getClass)

  val AuthorizePath = "/api/v1/twitch/authorize"

  private val StateLifetime = JDuration.ofMinutes(10)
  private val MaxPendingStates = 16

  /** Twitch requires an application to validate its user tokens at least hourly. */
  private val ValidationInterval = JDuration.ofHours(1)
  private val MaintenanceInterval = 1.minute

  private val AuthorizeEndpoint = uri"https://id.twitch.tv/oauth2/authorize"

  /** Built here rather than by twitch4j, whose builder joins scopes with `+` and then percent-encodes it; Twitch wants a space. */
  def authorizationUrl(clientId: String, redirectUrl: String, scopes: List[String], state: String): Uri =
    AuthorizeEndpoint.addParams(
      "response_type" -> "code",
      "client_id" -> clientId,
      "redirect_uri" -> redirectUrl,
      "scope" -> scopes.mkString(" "),
      "state" -> state
    )

  /** Loads any token a previous run left behind and keeps it fresh for as long as the enclosing scope lives. */
  def start(config: TwitchConfig, client: TwitchOAuthClient, bus: EventBus, clock: Clock)(using Ox): TwitchAuth =
    val file = TokenFile(Path.of(config.oauth.tokenFile))
    val initial = file.load() match
      case Right(loaded) => loaded
      case Left(reason) =>
        logger.warn(s"Ignoring the stored Twitch token: $reason")
        None
    initial match
      case Some(held) => logger.info(s"Loaded the Twitch user token of '${held.login}' from ${file.location}")
      case None       => logger.info(s"No Twitch user token yet; open $AuthorizePath in a browser to grant one")
    val auth = TwitchAuth(config, client, file, bus, clock, initial)
    forkDiscard:
      forever:
        auth.maintain()
        sleep(MaintenanceInterval)
    auth
