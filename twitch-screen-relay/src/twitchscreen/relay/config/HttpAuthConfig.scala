package twitchscreen.relay.config

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import pureconfig.ConfigReader
import scala.util.Try
import ox.computeIntensive

/** Deprecated settings retained only to reject nonempty legacy configuration without disclosing values. */
final case class HttpAuthConfig(
    basicUsername: String = "",
    basicPasswordHash: Option[Sensitive] = None,
    apiToken: Option[Sensitive] = None
):
  validate()

  private def validate(): Unit =
    val obsolete = List(
      "basic-username" -> basicUsername.nonEmpty,
      "basic-password-hash" -> basicPasswordHash.exists(_.value.nonEmpty),
      "api-token" -> apiToken.exists(_.value.nonEmpty)
    ).collect { case (key, true) => s"http.auth.$key" }
    require(
      obsolete.isEmpty,
      s"Obsolete management authentication settings: ${obsolete.mkString(", ")}; remove them and restrict network access"
    )

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
