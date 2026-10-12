package twitchscreen.relay.twitch

/** Explicit synthetic provider identities for existing transport fixtures. Production never infers identity from these fields. */
private[relay] object WebhookIdentityFixtures:
  def bindSource(source: TwitchSource, broadcaster: String): Unit = source.webhookOwner.foreach(bind(_, broadcaster))
  def bind(api: EventSubWebhookApi, broadcaster: String = "123"): Unit =
    api.bindBroadcaster(broadcaster)
    List("stream.online", "stream.offline", "channel.update", "channel.follow").foreach(api.bindSubscription(_, "synthetic-subscription"))

  def body(text: String, broadcaster: String = "123", login: String = "somechannel"): String = text
    .replace(
      "\"subscription\":{",
      s"\"subscription\":{\"id\":\"synthetic-subscription\",\"condition\":{\"broadcaster_user_id\":\"$broadcaster\"},"
    )
    .replace("\"event\":{", s"\"event\":{\"broadcaster_user_id\":\"$broadcaster\",\"broadcaster_user_login\":\"$login\",")
    .replace(",}", "}")
