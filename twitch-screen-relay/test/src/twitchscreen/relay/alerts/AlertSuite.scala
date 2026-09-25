package twitchscreen.relay.alerts

import java.time.Instant
import scala.concurrent.duration.DurationInt
import twitchscreen.relay.bus.{BusEvent, RelayEvent}
import twitchscreen.relay.config.{AlertsConfig, TwitchMode}

class MonitorStateSuite extends munit.FunSuite:
  private val start = Instant.ofEpochSecond(1790309000L)
  private def at(offsetSeconds: Long) = start.plusSeconds(offsetSeconds)
  private val initial = MonitorState.initial(start)

  test("a Twitch link that has only just gone missing does not trip a one-minute rule"):
    assertEquals(initial.check(AlertRule.TwitchDisconnected(1.minute), at(30)), None)

  test("a Twitch link missing for longer than the rule allows trips it"):
    assert(initial.check(AlertRule.TwitchDisconnected(1.minute), at(120)).isDefined)

  test("a Twitch link that came up clears the rule"):
    val up = initial.apply(BusEvent(at(10), RelayEvent.TwitchLinkUp("connected")))
    assertEquals(up.check(AlertRule.TwitchDisconnected(1.minute), at(600)), None)

  test("a device that connects clears the no-devices rule"):
    val connected = initial.withDevices(connected = 1, at(10))
    assertEquals(connected.check(AlertRule.NoDevicesConnected(1.minute), at(600)), None)

  test("no device for longer than the rule allows trips it"):
    val empty = initial.withDevices(connected = 0, at(10))
    assert(empty.check(AlertRule.NoDevicesConnected(1.minute), at(600)).isDefined)

  test("failures below the threshold do not trip the rate rule"):
    val failing = (1 to 3).foldLeft(initial)((state, i) => state.apply(BusEvent(at(i.toLong), RelayEvent.RelayFailure("twitch", "boom"))))
    assertEquals(failing.check(AlertRule.FailureRate(10, 5.minutes), at(60)), None)

  test("failures at the threshold trip the rate rule"):
    val failing = (1 to 10).foldLeft(initial)((state, i) => state.apply(BusEvent(at(i.toLong), RelayEvent.RelayFailure("twitch", "boom"))))
    assert(failing.check(AlertRule.FailureRate(10, 5.minutes), at(60)).isDefined)

  test("failures older than the window stop counting"):
    val failing = (1 to 10).foldLeft(initial)((state, i) => state.apply(BusEvent(at(i.toLong), RelayEvent.RelayFailure("twitch", "boom"))))
    assertEquals(failing.check(AlertRule.FailureRate(10, 5.minutes), at(3600)), None)

class AlertRuleSuite extends munit.FunSuite:
  private val config = AlertsConfig(
    evaluationInterval = 15.seconds,
    bufferSize = 10,
    noDevicesConnectedFor = Some(2.minutes),
    twitchDisconnectedFor = Some(1.minute),
    streamOfflineFor = None,
    errorRateThreshold = 10,
    errorRateWindow = 5.minutes
  )

  test("an unset threshold disables its rule"):
    assert(!AlertRule.from(config, TwitchMode.Live).exists(_.isInstanceOf[AlertRule.StreamOffline]))

  test("the Twitch rules are skipped when no Twitch connection was asked for"):
    assert(!AlertRule.from(config, TwitchMode.Disabled).exists(_.isInstanceOf[AlertRule.TwitchDisconnected]))

  test("the device rule applies whether or not Twitch is configured"):
    assert(AlertRule.from(config, TwitchMode.Disabled).exists(_.isInstanceOf[AlertRule.NoDevicesConnected]))

class AlertStoreSuite extends munit.FunSuite:
  private val at = Instant.ofEpochSecond(1790309000L)
  private val rule = AlertRule.NoDevicesConnected(1.minute)

  test("a rule that stays broken raises one alert, not one per evaluation"):
    val store = AlertStore(10)
    store.raise(rule, "no device", at).discard
    assertEquals(store.raise(rule, "still no device", at.plusSeconds(15)), None)

  test("a resolved rule can raise again when it breaks a second time"):
    val store = AlertStore(10)
    store.raise(rule, "no device", at).discard
    store.resolve(rule.name, at.plusSeconds(30)).discard
    assert(store.raise(rule, "no device again", at.plusSeconds(60)).isDefined)

  test("acknowledging an alert takes it out of the active count"):
    val store = AlertStore(10)
    val raised = store.raise(rule, "no device", at).get
    store.acknowledge(raised.id, at.plusSeconds(5)).discard
    assertEquals(store.activeCount, 0)

  test("acknowledging an unknown alert says so"):
    assertEquals(AlertStore(10).acknowledge(99, at), Left(AcknowledgeFailure.NotFound(99)))

  test("acknowledging a resolved alert is a conflict, not a silent success"):
    val store = AlertStore(10)
    val raised = store.raise(rule, "no device", at).get
    store.resolve(rule.name, at.plusSeconds(5)).discard
    assertEquals(store.acknowledge(raised.id, at.plusSeconds(10)), Left(AcknowledgeFailure.NotActive(raised.id)))

  test("the store keeps only as many alerts as it was sized for"):
    val store = AlertStore(2)
    (1 to 5).foreach: index =>
      store.raise(rule, s"attempt $index", at).discard
      store.resolve(rule.name, at).discard
    assertEquals(store.recent(10, None, activeOnly = false).size, 2)

  extension [T](value: T) private def discard: Unit = ()
