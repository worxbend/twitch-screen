package twitchscreen.relay

import com.softwaremill.macwire.wireList
import twitchscreen.relay.activity.ActivityApi
import twitchscreen.relay.alerts.AlertsApi
import twitchscreen.relay.config.ConfigApi
import twitchscreen.relay.device.{AdminApi, AdminPanelApi, ClockApi, CpuApi, DeviceApi, DisplayApi, NotificationApi, WeatherApi}
import twitchscreen.relay.health.{HealthApi, StatusApi}
import twitchscreen.relay.http.ServerEndpoints
import twitchscreen.relay.observability.LogsApi
import twitchscreen.relay.stats.StatsApi
import twitchscreen.relay.twitch.{MonitoringApi, TwitchOAuthOwner, TwitchSource}

/** Every group of HTTP endpoints the relay serves.
  *
  * `wireList` collects the constructor parameters that are [[ServerEndpoints]], so adding an API means adding a parameter — there is no
  * second list to forget to update, and a new API cannot be silently left unmounted.
  */
private[relay] final class Apis(
    healthApi: HealthApi,
    statusApi: StatusApi,
    deviceApi: DeviceApi,
    notificationApi: NotificationApi,
    statsApi: StatsApi,
    clockApi: ClockApi,
    cpuApi: CpuApi,
    weatherApi: WeatherApi,
    displayApi: DisplayApi,
    monitoringApi: MonitoringApi,
    adminApi: AdminApi,
    adminPanelApi: AdminPanelApi,
    activityApi: ActivityApi,
    alertsApi: AlertsApi,
    logsApi: LogsApi,
    configApi: ConfigApi,
    twitchOAuthOwner: TwitchOAuthOwner,
    twitchSource: TwitchSource
):
  val all: List[ServerEndpoints] = wireList[ServerEndpoints]
