// Independent DESK/1 JVM encoder. No relay imports or generated layouts.
// Run: scala run protocol/DeskVectors.scala --server=false -- protocol/desk-v1-vectors.tsv
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Path}

object DeskVectors:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "fixture path required")
    val report = "8 8 8 4 8 8 8 4 4 4 4 4 4 4 1 1 1 1 4 4 4 4" + " 8 4 1 1 2" * 9
    val records = Map(
      "HELLO" -> (1, 92, "4 4 2 2 b32 b16 2 2 8 4 8 8"),
      "WELCOME" -> (32, 60, "4 4 4 2 2 2 2 4 2 2 8 8 8 8"),
      "STATS" -> (34, 60, "4 4 4 4 4 4 4 2 1 1 8 8 4 4 1 b3"),
      "TIME_REQUEST" -> (5, 8, "8"),
      "DEVICE_REPORT" -> (6, 244, report),
      "TIME_RESPONSE" -> (38, 32, "8 8 8 8"),
      "DISPLAY_STATE" -> (39, 32, "8 8 4 4 4 1 1 2"),
      "WEATHER" -> (40, 68, "8 8 4 8 1 1 1 1 -2 1 1 b32"),
      "CPU" -> (41, 68, "8 8 4 8 1 1 2 2 -2 b32"),
      "SYNC_END" -> (42, 40, "8 8 4 4 4 4 4 4"),
      "DESK_EVENT" -> (43, 196, "4 4 4 b4 2 2 1 1 1 1 b48 b96 8 4 4 8 1 b3"),
      "PROGRESS_BARRIER" -> (44, 20, "8 8 4")
    )
    def unhex(value: String): Array[Byte] = java.util.HexFormat.of().parseHex(value)
    val lines = Files.readString(Path.of(args(0))).linesIterator.toVector
    lines.foreach: line =>
      val columns = line.split("\t", -1)
      require(columns.length == 5, "TSV shape")
      val (code, length, widths) = records(columns(1))
      val values = columns(4).split(",", -1)
      val tokens = widths.split(" ")
      require(values.length == tokens.length, "field count")
      val payload = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
      tokens.zip(values).foreach: (token, value) =>
        if token.startsWith("b") then
          val width = token.drop(1).toInt
          val bytes = unhex(value)
          require(bytes.length < width, "text bound")
          payload.put(bytes.padTo(width, 0.toByte))
        else
          val width = token.stripPrefix("-").toInt
          val number = BigInt(value)
          width match
            case 1 => payload.put(number.toByte)
            case 2 => payload.putShort(number.toShort)
            case 4 => payload.putInt(number.toInt)
            case 8 => payload.putLong(number.toLong)
            case _ => throw IllegalArgumentException("width")
      require(payload.position() == length && length <= 248, "payload length")
      val frame = ByteBuffer.allocate(length + 8).order(ByteOrder.LITTLE_ENDIAN)
      frame.put(167.toByte).put(83.toByte).put(3.toByte).put(code.toByte)
      frame.putShort(length.toShort).put(columns(2).toByte)
      val sum = (0 until 7).map(i => (i + 1) * (frame.array()(i) & 255)).sum & 255
      frame.put((255 ^ sum).toByte).put(payload.array())
      require(frame.array().sameElements(unhex(columns(3))), s"${columns(0)}: independent bytes differ")
    require(lines.size == 23, "vector count")
    println(s"Scala independent encoder: ${lines.size} DESK vectors passed")
