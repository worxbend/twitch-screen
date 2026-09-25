package twitchscreen.relay.protocol

import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}

/** The single jsoniter configuration behind both directions of protocol v2.
  *
  * It reproduces the shape documented in `twitch-screen-firmware/docs/PROTOCOL.md`: the frame kind travels in an `op` field holding the
  * lower-cased case name, and every other field is snake_case. The two exceptions below cannot be derived from the Scala name — `type` is a
  * reserved word, and `uptime_s` abbreviates its unit.
  */
private[protocol] object WireJson:
  inline def config: CodecMakerConfig = CodecMakerConfig
    .withDiscriminatorFieldName(Some("op"))
    .withAdtLeafClassNameMapper(name => JsonCodecMaker.simpleClassName(name).toLowerCase)
    .withFieldNameMapper:
      case "kind"   => "type"
      case "uptime" => "uptime_s"
      case other    => JsonCodecMaker.enforce_snake_case(other)
    // A newer firmware may send fields this build does not know; ignoring them keeps old relays working.
    .withSkipUnexpectedFields(true)
