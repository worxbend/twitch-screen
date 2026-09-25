package twitchscreen.relay.device

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString, writeToString}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import java.time.Instant
import sttp.tapir.{Schema, SchemaType}
import twitchscreen.relay.protocol.DeviceId

/** K-037 / KIMI-D23: `Device_OUT` embeds [[LinkTraffic]] whole (Preserve Whole Object), so a new counter touches only `LinkCounters.scala`,
  * and the management JSON nests every counter under `"traffic"`.
  *
  * These tests serialize through the same `given JsonValueCodec`s that `DeviceApi`'s `jsonBody` outputs use, so they pin the wire shape. A
  * refactor that flattens a counter back onto the device object, or renames the nested object, fails here.
  */
class DeviceApiJsonSuite extends munit.FunSuite:
  import DeviceApiJsonSuite.*

  private val instant = Instant.ofEpochSecond(1790309000L)

  /** Every Long field has a distinct non-zero value, so a mis-mapped field shows up. */
  private val fixtureTraffic = LinkTraffic(
    framesSent = 11,
    framesDropped = 12,
    framesReceived = 13,
    framesSkipped = 14,
    framesUnknownType = 15,
    framesWrongDirection = 16,
    framesShortPayload = 17,
    framesInvalidField = 18,
    framesOversizeSkipped = 19,
    resyncEvents = 20,
    bytesSent = 21,
    bytesReceived = 22,
    ackedSeq = 23,
    lastSeenAt = instant
  )

  private val device = Device_OUT(
    connection = ConnectionId(7),
    device = DeviceId("desk-1").fold(error => fail(error), identity),
    remoteAddress = "192.0.2.10:5000",
    connectedAt = instant,
    protocolVersion = 3,
    baselineSeq = 5,
    traffic = fixtureTraffic
  )

  /** Splits the device JSON into the `"traffic":{...}` object and everything else. LinkTraffic has no nested objects, and `lastSeenAt` is
    * an ISO string without braces, so the first `}` after the opening brace closes it.
    */
  private def splitTraffic(json: String): (String, String) =
    val marker = "\"traffic\":{"
    val start = json.indexOf(marker)
    assert(start >= 0, clue(json))
    val end = json.indexOf('}', start + marker.length)
    assert(end > start, clue(json))
    (json.substring(start, end + 1), json.substring(0, start) + json.substring(end + 1))

  test("K-037: a device's counters are rendered under a nested traffic object"):
    val json = writeToString(device)
    val probe = readFromString[DeviceProbe](json)
    assertEquals(probe.traffic, Some(TrafficProbe(11, 12, 23, 21, 22, 20, instant)), clue(json))
    assertEquals(probe.framesSent, None, clue(json))
    assertEquals(probe.ackedSeq, None, clue(json))
    assertEquals(probe.bytesSent, None, clue(json))
    assertEquals(probe.lastSeenAt, None, clue(json))

  test("K-037: no LinkTraffic field is flattened onto the device object"):
    val json = writeToString(device)
    val (traffic, rest) = splitTraffic(json)
    fixtureTraffic.productElementNames.foreach: name =>
      assert(traffic.contains(s"\"$name\":"), clue(s"$name missing from $traffic"))
      assert(!rest.contains(s"\"$name\":"), clue(s"$name flattened into $rest"))
    List("connection", "device", "remoteAddress", "connectedAt", "protocolVersion", "baselineSeq").foreach: name =>
      assert(rest.contains(s"\"$name\":"), clue(s"$name missing from $rest"))

  test("K-037: the device list nests traffic per device too"):
    val json = writeToString(Devices_OUT(List(device)))
    assert(json.startsWith("{\"devices\":[{"), clue(json))
    assert(json.contains("\"traffic\":{\"framesSent\":11,"), clue(json))
    assertEquals(json.split("\"framesSent\":", -1).length - 1, 1, clue(json))

  test("K-037: the OpenAPI schema nests traffic as well"):
    val fields = summon[Schema[Device_OUT]].schemaType match
      case product: SchemaType.SProduct[?] => product.fields.map(_.name.name)
      case other                           => fail(s"Device_OUT schema is not a product: $other")
    assert(fields.contains("traffic"), clue(fields))
    assert(!fields.contains("framesSent"), clue(fields))

object DeviceApiJsonSuite:
  final case class TrafficProbe(
      framesSent: Long,
      framesDropped: Long,
      ackedSeq: Long,
      bytesSent: Long,
      bytesReceived: Long,
      resyncEvents: Long,
      lastSeenAt: Instant
  )

  final case class DeviceProbe(
      traffic: Option[TrafficProbe],
      framesSent: Option[Long],
      ackedSeq: Option[Long],
      bytesSent: Option[Long],
      lastSeenAt: Option[String]
  )

  given JsonValueCodec[DeviceProbe] = JsonCodecMaker.make
