package twitchscreen.relay.twitch

private[twitch] enum HealthComponent(val label: String):
  case Startup extends HealthComponent("startup")
  case Authorization extends HealthComponent("authorization")
  case Chat extends HealthComponent("chat")
  case Streams extends HealthComponent("streams")
  case Followers extends HealthComponent("followers")
  case Subscribers extends HealthComponent("subscribers")
  case Poll extends HealthComponent("poll")
  case SubscriptionMaintenance extends HealthComponent("subscription-maintenance")
  case EventSubConnection extends HealthComponent("eventsub-connection")
  case EventSubOnline extends HealthComponent("eventsub-stream.online")
  case EventSubOffline extends HealthComponent("eventsub-stream.offline")
  case EventSubUpdate extends HealthComponent("eventsub-channel.update")
  case EventSubFollow extends HealthComponent("eventsub-channel.follow")

private[twitch] object HealthComponent:
  def subscription(kind: String): Option[HealthComponent] = kind match
    case "stream.online"  => Some(EventSubOnline)
    case "stream.offline" => Some(EventSubOffline)
    case "channel.update" => Some(EventSubUpdate)
    case "channel.follow" => Some(EventSubFollow)
    case _                => None
