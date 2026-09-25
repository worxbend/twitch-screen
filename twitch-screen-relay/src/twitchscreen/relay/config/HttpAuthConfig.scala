package twitchscreen.relay.config

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import pureconfig.ConfigReader
import scala.util.Try
import ox.computeIntensive

/** An absent secret (`None`; an empty string in HOCON) disables its method. Construction rejects an invalid credential set, a present but
  * whitespace-only secret, or a set without a complete method, so every instance is usable as-is: the HOCON reader reports that rejection
  * as a `CannotConvert` at `http.auth`.
  */
final case class HttpAuthConfig(
    basicUsername: String = "",
    basicPasswordHash: Option[Sensitive] = None,
    apiToken: Option[Sensitive] = None
):
  validate()

  private def validate(): Unit =
    require(
      basicUsername.isEmpty || (!basicUsername.isBlank && basicUsername == basicUsername.trim && !basicUsername
        .exists(c => c.isControl || c == ':')),
      "http.auth.basic-username must be nonblank without surrounding whitespace, controls or colon"
    )
    require(
      apiToken.forall(_.value.matches("[A-Za-z0-9._~+/-]+=*")),
      "http.auth.api-token must use the ASCII Bearer token alphabet"
    )
    require(basicPasswordHash.forall(_.isSet), "http.auth.basic-password-hash cannot be whitespace")
    require(apiToken.forall(_.isSet), "http.auth.api-token cannot be whitespace")
    require(basicUsername.isBlank == basicPasswordHash.isEmpty, "http.auth requires both Basic username and password hash")
    require(basicUsername.isBlank || !basicUsername.contains(':'), "http.auth.basic-username cannot contain ':'")
    require(basicPasswordHash.forall(hash => PasswordVerifier.valid(hash.value)), "http.auth.basic-password-hash has invalid format")
    require(apiToken.forall(_.value.getBytes(UTF_8).length >= 32), "http.auth.api-token must contain at least 32 bytes")
    require(basicPasswordHash.isDefined || apiToken.isDefined, "http.auth requires Basic credentials or an API token")

object HttpAuthConfig:
  private given ConfigReader[Option[Sensitive]] = Sensitive.optionalReader
  given ConfigReader[HttpAuthConfig] = ValidatedConfigReader.derivedValidated[HttpAuthConfig]

/** PBKDF2-SHA256 verifier: pbkdf2-sha256$600000$base64(salt)$base64(32-byte key). No password is retained. */
private[relay] object PasswordVerifier:
  private val decoder = Base64.getDecoder

  private def parts(encoded: String): Option[(Array[Byte], Array[Byte])] =
    encoded.split("\\$", -1).toList match
      case "pbkdf2-sha256" :: "600000" :: salt :: key :: Nil =>
        Try((decoder.decode(salt), decoder.decode(key))).toOption.filter((s, k) => s.length >= 16 && s.length <= 64 && k.length == 32)
      case _ => None

  def valid(encoded: String): Boolean = parts(encoded).isDefined

  def verify(password: String, encoded: String): Boolean =
    parts(encoded).exists: (salt, expected) =>
      val spec = PBEKeySpec(password.toCharArray, salt, 600000, 256)
      try
        computeIntensive(
          MessageDigest.isEqual(expected, SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded)
        )
      finally spec.clearPassword()
