package twitchscreen.relay.config

import java.net.URI
import java.util.Locale
import scala.util.Try

private[config] object CallbackUrl:
  def valid(raw: String, path: String, webhook: Boolean): Boolean =
    Try(URI(raw)).toOption.exists: uri =>
      val scheme = Option(uri.getScheme).map(_.toLowerCase(Locale.ROOT))
      val host = Option(uri.getHost).map(_.toLowerCase(Locale.ROOT))
      val transport =
        if webhook then scheme.contains("https") && (uri.getPort == -1 || uri.getPort == 443)
        else scheme.contains("https") || (scheme.contains("http") && host.exists(Set("localhost", "127.0.0.1", "[::1]")))
      transport && host.exists(_.nonEmpty) && uri.getPath == path && uri.getRawQuery == null &&
      uri.getRawFragment == null && uri.getRawUserInfo == null
