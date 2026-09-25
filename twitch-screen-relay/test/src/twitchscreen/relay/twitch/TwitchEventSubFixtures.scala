package twitchscreen.relay.twitch

import com.github.philippheuer.events4j.core.EventManager
import com.github.philippheuer.events4j.simple.SimpleEventHandler
import com.github.twitch4j.common.util.TypeConvert
import com.github.twitch4j.eventsub.events.{ChannelFollowEvent, ChannelUpdateV2Event, StreamOfflineEvent, StreamOnlineEvent}

/** Builds twitch4j EventSub events the way its WebSocket client does, by decoding Twitch's JSON, since the classes have no setters. */
private[twitch] object TwitchEventSubFixtures:
  def channelUpdate(broadcaster: Option[String], title: Option[String], category: Option[String]): ChannelUpdateV2Event =
    decode[ChannelUpdateV2Event]("broadcaster_user_name" -> broadcaster, "title" -> title, "category_name" -> category)

  def follow(user: Option[String]): ChannelFollowEvent = decode[ChannelFollowEvent]("user_name" -> user)

  def streamOnline(startedAt: Option[String]): StreamOnlineEvent = decode[StreamOnlineEvent]("started_at" -> startedAt)

  def streamOffline(): StreamOfflineEvent = decode[StreamOfflineEvent]()

  /** A synchronous event manager, so a published event has reached every handler when `publish` returns. */
  def eventManager(): EventManager =
    val events = EventManager()
    events.registerEventHandler(SimpleEventHandler())
    events.setDefaultEventHandler(classOf[SimpleEventHandler])
    events

  /** An absent field is written as an explicit JSON `null`, which is how Twitch reports an omitted value. */
  private def decode[E](fields: (String, Option[String])*)(using tag: scala.reflect.ClassTag[E]): E =
    val json = fields
      .map((name, value) => s"\"$name\":${value.fold("null")(v => "\"" + v + "\"")}")
      .mkString("{", ",", "}")
    TypeConvert.jsonToObject(json, tag.runtimeClass.asInstanceOf[Class[E]])
