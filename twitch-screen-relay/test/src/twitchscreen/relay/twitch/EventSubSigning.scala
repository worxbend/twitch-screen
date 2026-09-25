package twitchscreen.relay.twitch

import java.nio.charset.StandardCharsets.UTF_8
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Twitch's EventSub webhook signature, computed independently of the implementation so tests can sign real deliveries. */
object EventSubSigning:
  /** `sha256=` + hex HMAC-SHA256 of message id, timestamp and body, keyed by the subscription secret. */
  def sign(secret: String, messageId: String, timestamp: String, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    "sha256=" + mac.doFinal((messageId + timestamp + body).getBytes(UTF_8)).map(byte => f"$byte%02x").mkString

  /** The four `Twitch-Eventsub-Message-*` headers of a correctly signed delivery. */
  def headers(
      secret: String,
      messageId: String,
      timestamp: String,
      body: String,
      messageType: String = "webhook_callback_verification"
  ): List[(String, String)] = List(
    "Twitch-Eventsub-Message-Id" -> messageId,
    "Twitch-Eventsub-Message-Timestamp" -> timestamp,
    "Twitch-Eventsub-Message-Type" -> messageType,
    "Twitch-Eventsub-Message-Signature" -> sign(secret, messageId, timestamp, body)
  )
