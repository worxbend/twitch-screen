package twitchscreen.relay.http

import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}

/** The jsoniter configuration shared by the management API's DTOs.
  *
  * Parameterless enums are written as plain strings so the JSON matches the `Schema.derivedEnumeration` shown in the OpenAPI document.
  * `inline` because `JsonCodecMaker.make` needs its configuration as a constant expression.
  */
private[relay] object ApiJson:
  /** Enum values keep their Scala case names, matching a `defaultStringBased` schema. */
  inline def config: CodecMakerConfig =
    CodecMakerConfig
      .withDiscriminatorFieldName(None)
      .withSkipUnexpectedFields(true)
      // jsoniter omits empty collections by default, which would make `GET /devices` return `{}` and leave a
      // non-Scala client reading an absent field rather than an empty list.
      .withTransientEmpty(false)

  /** The notification DTOs, which speak the device protocol's vocabulary: lower-cased kinds, and `type` for the field Scala has to call
    * `kind`.
    */
  inline def deviceVocabulary: CodecMakerConfig =
    CodecMakerConfig
      .withDiscriminatorFieldName(None)
      .withAdtLeafClassNameMapper(name => JsonCodecMaker.simpleClassName(name).toLowerCase)
      .withSkipUnexpectedFields(true)
      .withTransientEmpty(false)
      .withFieldNameMapper:
        case "kind" => "type"
        case other  => other
