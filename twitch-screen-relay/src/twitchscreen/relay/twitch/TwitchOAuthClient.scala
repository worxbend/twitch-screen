package twitchscreen.relay.twitch

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential
import com.github.twitch4j.auth.providers.TwitchIdentityProvider
import java.time.Clock
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.control.NonFatal
import twitchscreen.relay.config.{Sensitive, TwitchConfig}

/** Twitch's OAuth token endpoints, as values. The seam [[TwitchAuth]] is tested through, so no test ever talks to id.twitch.tv. */
private[twitch] trait TwitchOAuthClient:
  /** Swaps the code Twitch handed the callback for a token, and asks Twitch whose it is. */
  def exchange(code: String): Either[TwitchCallFailure, UserToken]

  /** A fresh access token for the same grant. Twitch may rotate the refresh token too, so the whole token is replaced. */
  def refresh(token: UserToken): Either[TwitchCallFailure, UserToken]

  /** `None` when Twitch could not be asked; a network failure is not evidence that the token is bad. */
  def isValid(token: UserToken): Option[Boolean]

  def revoke(token: UserToken): Boolean

private[twitch] object TwitchOAuthClient:
  /** twitch4j's identity provider speaks the token, validate and revoke endpoints. Its authorization URL builder is not used: see
    * [[TwitchAuth.authorizationUrl]].
    */
  def live(config: TwitchConfig, clock: Clock): TwitchOAuthClient = new TwitchOAuthClient:
    private val provider = new TwitchIdentityProvider(config.clientId, config.clientSecret.value, config.oauth.redirectUrl):
      // credentialmanager 0.5.0 otherwise flattens token HTTP errors into RuntimeException text. Preserve status before that boundary.
      httpClient = httpClient.newBuilder().addInterceptor(chain => checkTokenResponse(chain.proceed(chain.request()))).build()

    override def exchange(code: String): Either[TwitchCallFailure, UserToken] =
      TwitchCall.attempt("exchange the authorization code"):
        val issued = provider.getCredentialByCode(code)
        // The token response carries no identity; /oauth2/validate does.
        val described = provider
          .getAdditionalCredentialInformation(issued)
          .toScala
          .getOrElse(throw IllegalStateException("Twitch would not validate the token it had just issued"))
        toUserToken(issued, Option(described.getUserId).getOrElse(""), Option(described.getUserName).getOrElse(""), described)

    override def refresh(token: UserToken): Either[TwitchCallFailure, UserToken] =
      TwitchCall.attempt("refresh the user token"):
        val renewed = provider.refreshCredentialOrThrow(credentialOf(token))
        toUserToken(renewed, token.userId, token.login, renewed, fallbackScopes = token.scopes)

    override def isValid(token: UserToken): Option[Boolean] =
      try provider.isCredentialValid(credentialOf(token)).toScala.map(_.booleanValue)
      catch case NonFatal(_) => None

    override def revoke(token: UserToken): Boolean =
      try provider.revokeCredential(credentialOf(token))
      catch case NonFatal(_) => false

    private def toUserToken(
        issued: OAuth2Credential,
        userId: String,
        login: String,
        scopesFrom: OAuth2Credential,
        fallbackScopes: List[String] = Nil
    ): UserToken =
      // Twitch always sends `expires_in` for user tokens. If it ever did not, an hour is short enough that the refresh loop swaps the
      // token long before a real one would lapse.
      val lifetimeSeconds = Option(issued.getExpiresIn).map(_.longValue).getOrElse(3600L)
      val scopes = Option(scopesFrom.getScopes).map(_.asScala.toList).filter(_.nonEmpty).getOrElse(fallbackScopes)
      UserToken(
        accessToken = Sensitive(issued.getAccessToken),
        refreshToken = Sensitive(Option(issued.getRefreshToken).getOrElse("")),
        expiresAt = clock.instant().plusSeconds(lifetimeSeconds),
        scopes = scopes,
        userId = userId,
        login = login
      )

  private[twitch] def credentialOf(token: UserToken): OAuth2Credential =
    OAuth2Credential(
      TwitchIdentityProvider.PROVIDER_NAME,
      token.accessToken.value,
      token.refreshToken.value,
      token.userId,
      token.login,
      null,
      token.scopes.asJava
    )

  private[twitch] final class TokenResponseFailure(val status: Int)
      extends RuntimeException(s"OAuth token endpoint returned $status", null, false, false)

  private[twitch] def checkTokenResponse(response: okhttp3.Response): okhttp3.Response =
    if response.request().url().encodedPath() == "/oauth2/token" && !response.isSuccessful() then
      val status = response.code()
      response.close()
      throw TokenResponseFailure(status)
    response
