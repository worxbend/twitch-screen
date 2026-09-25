package twitchscreen.relay.config

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import pureconfig.ConfigReader
import scala.util.Try
import ox.tap

/** Empty fields disable a method; at least one complete method is required at server startup. */
final case class HttpAuthConfig(
    basicUsername: String = "",
    basicPasswordHash: Sensitive = Sensitive.Empty,
    apiToken: Sensitive = Sensitive.Empty
):
  def validate(): Unit =
    require(basicPasswordHash.value.isEmpty || basicPasswordHash.isSet, "http.auth.basic-password-hash cannot be whitespace")
    require(apiToken.value.isEmpty || apiToken.isSet, "http.auth.api-token cannot be whitespace")
    require(basicUsername.isBlank == !basicPasswordHash.isSet, "http.auth requires both Basic username and password hash")
    require(basicUsername.isBlank || !basicUsername.contains(':'), "http.auth.basic-username cannot contain ':'")
    require(!basicPasswordHash.isSet || PasswordVerifier.valid(basicPasswordHash.value), "http.auth.basic-password-hash has invalid format")
    require(!apiToken.isSet || apiToken.value.getBytes(UTF_8).length >= 32, "http.auth.api-token must contain at least 32 bytes")
    require(basicPasswordHash.isSet || apiToken.isSet, "http.auth requires Basic credentials or an API token")

object HttpAuthConfig:
  given ConfigReader[HttpAuthConfig] = ValidatedConfigReader(ConfigReader.derived[HttpAuthConfig].map(_.tap(_.validate())))

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
      try MessageDigest.isEqual(expected, SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded)
      finally spec.clearPassword()
