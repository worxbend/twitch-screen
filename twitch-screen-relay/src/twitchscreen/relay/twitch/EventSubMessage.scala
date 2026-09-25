package twitchscreen.relay.twitch

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}

/** Just enough of an EventSub webhook payload to route it.
  *
  * Twitch sends one envelope shape for every subscription type, with a differently shaped `event` object inside. Rather than model every
  * variant, this flattens the handful of fields the relay actually reads; unknown fields are skipped, so a new subscription type cannot
  * break parsing.
  */
private[twitch] final case class EventSubEnvelope(
    subscription: EventSubSubscriptionRef,
    event: Option[EventSubPayload],
    challenge: Option[String]
)

private[twitch] final case class EventSubSubscriptionRef(kind: String, status: Option[String])

private[twitch] final case class EventSubPayload(
    userName: Option[String] = None,
    broadcasterUserName: Option[String] = None,
    title: Option[String] = None,
    categoryName: Option[String] = None,
    startedAt: Option[String] = None
)

private[twitch] object EventSubEnvelope:
  given JsonValueCodec[EventSubEnvelope] = JsonCodecMaker.make(
    CodecMakerConfig
      .withFieldNameMapper:
        case "kind" => "type" // `type` is a Scala keyword
        case other  => JsonCodecMaker.enforce_snake_case(other)
      .withSkipUnexpectedFields(true)
  )
