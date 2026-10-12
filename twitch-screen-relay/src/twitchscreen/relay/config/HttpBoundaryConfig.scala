package twitchscreen.relay.config

import java.net.URI
import java.util.Locale
import scala.util.Try

/** Exact private HTTP authorities. No DNS lookups, wildcards or implicit proxy trust. */
private[relay] object HttpBoundaryConfig:
  val DefaultHosts: List[String] = List("localhost:8080", "127.0.0.1:8080", "[::1]:8080")
  val DefaultOrigins: List[String] = DefaultHosts.map("http://" + _)

  def authority(value: String, scheme: String = "http"): Option[String] =
    Try(URI(s"$scheme://$value")).toOption
      .filter: uri =>
        value.nonEmpty && value == value.trim && !value.exists(c => c.isControl || c == '*' || c == '\\') &&
          uri.getRawAuthority == value && uri.getHost != null && uri.getRawUserInfo == null &&
          uri.getRawPath.isEmpty && uri.getRawQuery == null && uri.getRawFragment == null &&
          uri.getPort >= -1 && uri.getPort <= 65535 && !value.endsWith(":") && uri.getPort != 0
      .map: uri =>
        val port = if uri.getPort == -1 then (if scheme == "https" then 443 else 80) else uri.getPort
        s"${uri.getHost.toLowerCase(Locale.ROOT)}:$port"

  def origin(value: String): Option[(String, String)] =
    Try(URI(value)).toOption.flatMap: uri =>
      Option(uri.getScheme)
        .map(_.toLowerCase(Locale.ROOT))
        .filter(Set("http", "https"))
        .flatMap: scheme =>
          Option(uri.getRawAuthority).flatMap: raw =>
            Option
              .when(uri.getRawPath.isEmpty && uri.getRawQuery == null && uri.getRawFragment == null)(raw)
              .flatMap(authority(_, scheme))
              .map(scheme -> _)

  /** Literal IPs only: never resolve a configured proxy hostname or trust a subnet/forwarded chain. */
  def ip(value: String): Option[String] =
    Option
      .when(value == value.trim && !value.contains('%') && !value.startsWith("["))(value)
      .flatMap(_ => Option(io.netty.util.NetUtil.createByteArrayFromIpAddressString(value)))
      .map(bytes => java.util.HexFormat.of().formatHex(bytes))

  def validate(hosts: List[String], origins: List[String], publicOrigin: String = "", proxies: List[String] = Nil): Unit =
    require(
      hosts.nonEmpty && hosts.size <= 64 && hosts.forall(authority(_).isDefined),
      "http.allowed-hosts requires 1..64 exact authorities"
    )
    require(
      origins.nonEmpty && origins.size <= 64 && origins.forall(origin(_).isDefined),
      "http.allowed-origins requires 1..64 exact HTTP origins"
    )
    require(proxies.size <= 64 && proxies.forall(ip(_).isDefined), "http.trusted-proxies requires exact literal IP addresses")
    require(publicOrigin.isEmpty == proxies.isEmpty, "http.public-origin and http.trusted-proxies must be configured together")
    if publicOrigin.nonEmpty then
      val parsed = origin(publicOrigin)
      require(parsed.exists(_._1 == "https"), "http.public-origin must be one exact HTTPS origin")
      require(parsed.exists(origins.flatMap(origin).contains), "http.public-origin must be in http.allowed-origins")
      require(
        parsed.exists(p => hosts.flatMap(authority(_, "https")).contains(p._2)),
        "http.public-origin authority must be in http.allowed-hosts"
      )
