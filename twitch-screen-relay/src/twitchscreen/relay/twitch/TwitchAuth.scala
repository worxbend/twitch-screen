package twitchscreen.relay.twitch

import java.nio.file.Path
import java.security.SecureRandom
import java.time.{Clock, Duration as JDuration, Instant}
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import ox.*
import ox.channels.{Actor, ActorRef}
import scala.concurrent.duration.DurationInt
import scala.annotation.tailrec
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
  * Every read goes through [[view]]: one [[AuthorizationView]] built from a single `state.get()`, so the held grant, the usable grant and
  * the missing scopes always describe the same grant. twitch4j never sees this state; each subscription plan hands it an immutable
  * `TwitchOAuthClient.credentialOf(plan.grant)` snapshot (see [[EventSubTransportStrategy]]), and a refresh or rejection resubscribes with
  * a new snapshot rather than mutating a credential twitch4j already holds.
  */
private[twitch] final class TwitchAuth private (
    config: TwitchConfig,
    client: TwitchOAuthClient,
    bus: EventBus,
    clock: Clock,
    state: AtomicReference[TwitchAuth.State],
    transitions: ActorRef[TwitchAuth.CredentialTransitions],
    pending: PendingAuthorizations
):
  import TwitchAuth.*

  private val logger = LoggerFactory.getLogger(getClass)
  private val lastFailure = AtomicReference(Option.empty[String])

  def view: AuthorizationView =
    val held = state.get()
    val usable = held.token.filter(token => !held.rejected && !held.refreshRejected && token.expiresAt.isAfter(clock.instant()))
    AuthorizationView(held.token, usable, held.token.fold(config.oauth.scopes)(missingScopesOf))

  /** The configured scopes `granted` lacks. Pure: it never reads the authorization state, so it describes exactly that grant. */
  def missingScopesOf(granted: UserToken): List[String] = config.oauth.scopes.diff(granted.scopes)

  def rejectAccessToken(): Unit = state.updateAndGet(_.copy(rejected = true)).discard
  def rejectAccessToken(token: String): Unit =
    state.updateAndGet(before => if before.token.exists(_.accessToken.value == token) then before.copy(rejected = true) else before).discard

  /** Starts a consent round trip: the URL to send the browser to. */
  def beginAuthorization(): String =
    authorizationUrl(config.clientId, config.oauth.redirectUrl, config.oauth.scopes, pending.issue()).toString

  /** Finishes the round trip Twitch redirected back from. A `state` is accepted once and only before it expires. */
  def completeAuthorization(code: String, state: String): Either[AuthorizationFailure, UserToken] =
    for
      _ <- pending.consume(state)
      issued <- client.exchange(code).left.map(AuthorizationFailure.Provider.apply)
    yield
      transitions.ask(_.install(issued))
      lastFailure.set(None)
      logger.info(s"Twitch authorized by '${issued.login}' (${issued.scopes.mkString(" ")})")
      if !issued.login.equalsIgnoreCase(config.channel) then
        logger.warn(s"The token belongs to '${issued.login}', not the broadcaster '${config.channel}'; subscriber totals will be refused")
      val missing = missingScopesOf(issued)
      if missing.nonEmpty then logger.warn(s"Twitch did not grant ${missing.mkString(", ")}")
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
      val snapshot = state.get()
      snapshot.token
        .filter(_ => !snapshot.refreshRejected)
        .foreach: held =>
          val now = clock.instant()
          if snapshot.rejected || !held.expiresAt.isAfter(now.plus(config.oauth.refreshBefore.toJava)) then refresh(snapshot, held)
          else if JDuration.between(snapshot.validatedAt, now).compareTo(ValidationInterval) >= 0 then
            client.isValid(held) match
              case Some(false) =>
                val rejected = snapshot.copy(rejected = true)
                if state.compareAndSet(snapshot, rejected) then refresh(rejected, held)
              case Some(true) => state.compareAndSet(snapshot, snapshot.copy(validatedAt = now)).discard
              case None       => ()
    catch case NonFatal(error) => fail(s"token maintenance failed (${error.getClass.getSimpleName})")

  private def refresh(snapshot: State, held: UserToken): Unit =
    client.refresh(held) match
      case Right(renewed) =>
        if transitions.ask(_.refreshIfCurrent(snapshot, renewed)) then lastFailure.set(None)
        else if !client.revoke(renewed) then logger.warn("Could not revoke an obsolete refreshed token")
      case Left(reason) =>
        if reason.rejectedGrant then transitions.ask(_.rejectRefreshIfCurrent(snapshot))
        fail(s"${reason.message}; authorize again at $AuthorizePath")

  private def fail(reason: String): Unit =
    if lastFailure.getAndSet(Some(reason)) != Some(reason) then
      logger.warn(s"Twitch authorization: $reason")
      bus.publish(RelayEvent.RelayFailure("twitch-oauth", reason))

private[twitch] object TwitchAuth:
  private val logger = LoggerFactory.getLogger(getClass)

  val AuthorizePath = "/api/v1/twitch/authorize"

  private final case class State(
      token: Option[UserToken],
      rejected: Boolean = false,
      refreshRejected: Boolean = false,
      validatedAt: Instant = Instant.MIN
  )

  def create(config: TwitchConfig, client: TwitchOAuthClient, file: TokenFile, bus: EventBus, clock: Clock, initial: Option[UserToken])(
      using Ox
  ): TwitchAuth =
    val state = AtomicReference(State(initial))
    // Ox's default mailbox holds 16 pending operations; when it is full, ask blocks the HTTP or maintenance caller (backpressure, not
    // dropping). See docs/reference/architecture.md "Actor mailboxes".
    val transitions = Actor.create(CredentialTransitions(state, file, clock, bus))
    new TwitchAuth(config, client, bus, clock, state, transitions, PendingAuthorizations(clock, SecureRandom()))

  private final class CredentialTransitions(state: AtomicReference[State], file: TokenFile, clock: Clock, bus: EventBus):
    def install(issued: UserToken): Unit =
      state.set(State(Some(issued), validatedAt = clock.instant()))
      persist(file.save(issued))

    def refreshIfCurrent(snapshot: State, renewed: UserToken): Boolean =
      if replaceGrant(snapshot.token, State(Some(renewed), validatedAt = clock.instant())) then
        persist(file.save(renewed))
        logger.info(s"Refreshed the Twitch user token; valid until ${renewed.expiresAt}")
        true
      else false

    def rejectRefreshIfCurrent(snapshot: State): Unit =
      state
        .updateAndGet: before =>
          if before.token eq snapshot.token then before.copy(rejected = true, refreshRejected = true) else before
        .discard

    @tailrec private def replaceGrant(expected: Option[UserToken], replacement: State): Boolean =
      val before = state.get()
      if !(before.token eq expected) then false
      else if state.compareAndSet(before, replacement) then true
      else replaceGrant(expected, replacement)

    def remove(): Option[UserToken] =
      val revoked = state.getAndSet(State(None)).token
      revoked.foreach(_ => persist(file.delete()))
      revoked

    private def persist(write: => Unit): Unit =
      try write
      catch
        case NonFatal(error) =>
          val reason = s"could not update token file (${error.getClass.getSimpleName})"
          logger.warn(reason)
          bus.publish(RelayEvent.RelayFailure("twitch-oauth", reason))

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
    val auth = create(config, client, file, bus, clock, initial)
    forkDiscard:
      forever:
        auth.maintain()
        sleep(MaintenanceInterval)
    auth
