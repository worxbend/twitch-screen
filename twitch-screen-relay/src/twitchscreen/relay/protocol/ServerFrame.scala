package twitchscreen.relay.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import java.time.Instant

/** A frame the relay pushes to a device, in its on-the-wire shape: primitives only, so the JSON codec is derived from exactly what protocol
  * v2 specifies and the domain types stay free to change. Build these through the companion's constructors, which perform the
  * domain-to-wire conversion in one place.
  */
private[relay] enum ServerFrame:
  case Welcome(proto: Int, latestSeq: Long, serverTime: Long)
  case Notify(seq: Long, id: String, kind: String, title: String, body: String, timestamp: Long, ttlMs: Long)
  case Stats(live: Boolean, viewers: Int, followers: Int, subs: Int, uptime: Long, chatRate: Int)
  case Ping(t: Long)
  case Pong(t: Long)

private[relay] object ServerFrame:
  given JsonValueCodec[ServerFrame] = JsonCodecMaker.make(WireJson.config)

  def welcome(protocolVersion: Int, latestSeq: SeqNo, now: Instant): ServerFrame =
    Welcome(protocolVersion, latestSeq.value, now.getEpochSecond)

  def notify(notification: Notification): ServerFrame =
    Notify(
      seq = notification.seq.value,
      id = notification.id.value,
      kind = notification.kind.wire,
      title = notification.title,
      body = notification.body,
      timestamp = notification.at.getEpochSecond,
      ttlMs = notification.ttl.toMillis
    )

  def stats(stats: StreamStats): ServerFrame =
    Stats(
      live = stats.state.isLive,
      viewers = stats.viewers.value,
      followers = stats.followers.value,
      subs = stats.subscribers.value,
      uptime = stats.uptime.toSeconds,
      chatRate = stats.chatRate.value
    )

  def ping(now: Instant): ServerFrame = Ping(now.getEpochSecond)

  def pong(clientTimestamp: Long): ServerFrame = Pong(clientTimestamp)
